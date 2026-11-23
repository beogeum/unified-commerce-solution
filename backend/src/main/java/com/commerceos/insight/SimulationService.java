package com.commerceos.insight;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.error.Errors;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 손익 시뮬레이션(FR-OPS-04·05, docs/14 §5-3, DLG-207~209). 읽기 전용 추정 — 아무것도 바꾸지 않는다.
 * 기준 기간의 원장(사업자·판매계정·SKU 범위)을 출발점으로 가격·수요 탄력성·수수료율·무료배송·배송 단가·사은품 변경을 적용한 공헌이익을 계산한다.
 * <ul>
 *   <li>판매량 연동 비용(상품 원가·사은품·배송·피킹·포장·부자재)은 판매량에 비례, 매출 연동 비용(판매·결제 수수료)은 순매출에 비례, 나머지(광고·보관 등)는 고정으로 가정.</li>
 *   <li>판매량 변화 = 탄력성 × 가격 변화율 + 추가 수요 가정. 손익분기 판매량(지금 공헌이익을 유지하는 판매량 변화)을 함께 낸다.</li>
 *   <li>결과는 가정과 함께 '검토 업무'로 남길 수 있다(사람 판단, 자동 실행 없음).</li>
 * </ul>
 */
@Service
public class SimulationService {
    static final Set<String> NET_SALES = Set.of("SALES", "SELLER_DISCOUNT", "SALES_RETURN", "EXCHANGE_PRICE_DIFFERENCE");
    static final Set<String> OTHER_REVENUE = Set.of("SHIPPING_FEE", "CUSTOMER_RETURN_FEE");
    static final Set<String> VOLUME_COSTS = Set.of("PRODUCT_COGS", "GWP", "OUTBOUND_SHIPPING", "THIRD_PARTY_PICK", "PACK", "MATERIAL");
    static final Set<String> SALES_COSTS = Set.of("SELLING_FEE", "PAYMENT_FEE");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final WorkItemService workItems;
    private final AuditService audit;

    public SimulationService(JdbcClient jdbc, AccessGuard guard, WorkItemService workItems, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.workItems = workItems;
        this.audit = audit;
    }

    /** 가정값. 비율은 % 단위(예: 가격 −10 = 10% 인하). */
    public record Scenario(UUID businessEntityId, UUID channelAccountId, UUID skuId, LocalDate from, LocalDate to, BigDecimal priceChangePct, BigDecimal elasticity,
                           BigDecimal extraDemandPct, BigDecimal commissionRatePp, Boolean freeShipping, BigDecimal shippingCostChangePct, Boolean stopGifts,
                           BigDecimal giftCostChangePct, String note) {
    }

    public record Side(BigDecimal netSales, BigDecimal otherRevenue, Map<String, BigDecimal> costs, BigDecimal totalCost, BigDecimal contribution,
                       BigDecimal marginPct, BigDecimal units) {
    }

    public record Result(Scenario scenario, LocalDate from, LocalDate to, Side base, Side simulated, BigDecimal contributionDelta, BigDecimal volumeFactor,
                         BigDecimal breakevenVolumeChangePct, List<String> assumptions, List<String> unconfirmed) {
    }

    public Result simulate(Scenario s) {
        guard.requirePermission(Perm.PROFIT_READ);
        if (s == null || s.businessEntityId() == null) {
            throw Errors.validation("business_entity_id", "시뮬레이션할 사업자를 선택하세요.");
        }
        guard.checkRead("BUSINESS_ENTITY", s.businessEntityId(), s.businessEntityId(), s.channelAccountId());
        LocalDate to = s.to() == null ? LocalDate.now(AnalyticsService.KST) : s.to();
        LocalDate from = s.from() == null ? to.minusDays(29) : s.from();
        if (from.isAfter(to) || from.plusDays(400).isBefore(to)) {
            throw Errors.validation("from", "기준 기간을 확인하세요(400일 이내).");
        }
        BigDecimal p = pct(s.priceChangePct(), -90, 300, "price_change_pct");
        BigDecimal el = s.elasticity() == null ? BigDecimal.ZERO : s.elasticity();
        if (el.compareTo(BigDecimal.valueOf(-10)) < 0 || el.compareTo(BigDecimal.TEN) > 0) {
            throw Errors.validation("elasticity", "수요 탄력성은 −10 ~ 10 사이로 입력하세요(보통 음수).");
        }
        BigDecimal extra = pct(s.extraDemandPct(), -100, 500, "extra_demand_pct");
        BigDecimal commPp = pct(s.commissionRatePp(), -50, 50, "commission_rate_pp");
        BigDecimal shipPct = pct(s.shippingCostChangePct(), -100, 300, "shipping_cost_change_pct");
        BigDecimal giftPct = pct(s.giftCostChangePct(), -100, 500, "gift_cost_change_pct");

        Map<String, Object> p0 = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "be", s.businessEntityId(), "f", from, "t", to));
        String where = " where workspace_id = :ws and business_entity_id = :be and period_date between :f and :t and entry_class <> 'MEMO' and not intercompany"
                + " and category not like 'FIXED%'"; // 고정비(추정 영업이익용)는 공헌이익 시뮬레이션 밖
        if (s.channelAccountId() != null) {
            where += " and channel_account_id = :ca";
            p0.put("ca", s.channelAccountId());
        }
        if (s.skuId() != null) {
            where += " and sku_id = :sku";
            p0.put("sku", s.skuId());
        }
        Map<String, BigDecimal> cat = new TreeMap<>();
        BigDecimal estimated = BigDecimal.ZERO;
        int missing = 0;
        for (Map<String, Object> r : jdbc.sql("select category, basis, sum(supply_amount) as a, count(*) filter (where category = 'PRODUCT_COGS' and unit_cost is null and quantity > 0) as m"
                + " from financial_entry" + where + " group by category, basis").params(p0).query().listOfRows()) {
            cat.merge((String) r.get("category"), (BigDecimal) r.get("a"), BigDecimal::add);
            if ("ESTIMATED".equals(r.get("basis")) && !NET_SALES.contains(r.get("category")) && !OTHER_REVENUE.contains(r.get("category"))) {
                estimated = estimated.add((BigDecimal) r.get("a"));
            }
            missing += ((Number) r.get("m")).intValue();
        }
        BigDecimal units = jdbc.sql("select coalesce(sum(quantity), 0) from financial_entry" + where + " and category = 'SALES'").params(p0).query(BigDecimal.class).single();
        Side base = side(cat, units);
        if (base.netSales().signum() <= 0) {
            throw Errors.unprocessable("SIMULATION_NO_SALES", "기준 기간에 순매출이 없어 시뮬레이션할 수 없습니다. 기간·범위를 바꿔 보세요.");
        }
        // 판매량 계수 v = 1 + 탄력성 × 가격변화 + 추가 수요(무료배송 효과 등), 0 미만은 0
        BigDecimal priceF = BigDecimal.ONE.add(p.movePointLeft(2));
        BigDecimal v = BigDecimal.ONE.add(el.multiply(p).movePointLeft(2)).add(extra.movePointLeft(2)).max(BigDecimal.ZERO);
        Map<String, BigDecimal> sim = new TreeMap<>();
        BigDecimal simNet = base.netSales().multiply(priceF).multiply(v);
        for (var e : cat.entrySet()) {
            String c = e.getKey();
            BigDecimal a = e.getValue();
            if (NET_SALES.contains(c) || OTHER_REVENUE.contains(c)) {
                continue;
            }
            BigDecimal x;
            if (SALES_COSTS.contains(c)) {
                x = a.multiply(priceF).multiply(v);
            } else if (VOLUME_COSTS.contains(c)) {
                x = a.multiply(v);
                if ("OUTBOUND_SHIPPING".equals(c)) {
                    x = x.multiply(BigDecimal.ONE.add(shipPct.movePointLeft(2)));
                }
                if ("GWP".equals(c)) {
                    x = Boolean.TRUE.equals(s.stopGifts()) ? BigDecimal.ZERO : x.multiply(BigDecimal.ONE.add(giftPct.movePointLeft(2)));
                }
            } else {
                x = a; // 고정으로 가정
            }
            sim.put(c, x);
        }
        if (commPp.signum() != 0) {
            sim.merge("SELLING_FEE", simNet.multiply(commPp).movePointLeft(2), BigDecimal::add);
        }
        BigDecimal simOther = BigDecimal.ZERO;
        for (String c : OTHER_REVENUE) {
            BigDecimal a = cat.getOrDefault(c, BigDecimal.ZERO);
            simOther = simOther.add("SHIPPING_FEE".equals(c) && Boolean.TRUE.equals(s.freeShipping()) ? BigDecimal.ZERO : a.multiply(v));
        }
        Side simulated = sideFrom(simNet, simOther, sim, units.multiply(v));
        // 손익분기: 새 가격·비용 구조에서 지금 공헌이익을 유지하는 판매량 계수 v* (기여 = A·v − F)
        BigDecimal fixed = sim.entrySet().stream().filter(e -> !SALES_COSTS.contains(e.getKey()) && !VOLUME_COSTS.contains(e.getKey())).map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal perV = v.signum() == 0 ? null : simulated.contribution().add(fixed).divide(v, 8, RoundingMode.HALF_UP);
        BigDecimal breakeven = perV == null || perV.signum() <= 0 ? null
                : base.contribution().add(fixed).divide(perV, 8, RoundingMode.HALF_UP).subtract(BigDecimal.ONE).movePointRight(2).setScale(1, RoundingMode.HALF_UP);
        List<String> assumptions = new ArrayList<>();
        assumptions.add("기준: " + from + " ~ " + to + " 관리 손익 원장(공급가, 내부거래 제외)" + (s.skuId() != null ? ", SKU 범위" : "") + (s.channelAccountId() != null ? ", 판매계정 범위" : ""));
        assumptions.add("판매량 변화 = 탄력성 " + el.stripTrailingZeros().toPlainString() + " × 가격 " + p.stripTrailingZeros().toPlainString() + "% + 추가 수요 "
                + extra.stripTrailingZeros().toPlainString() + "% → 판매량 ×" + v.setScale(3, RoundingMode.HALF_UP).toPlainString());
        assumptions.add("상품 원가·사은품·배송·피킹·포장·부자재는 판매량에, 판매·결제 수수료는 순매출에 비례. 광고·보관 등 나머지는 고정.");
        if (Boolean.TRUE.equals(s.freeShipping())) {
            assumptions.add("무료배송: 고객 부담 배송비 수익 0(배송비 비용은 그대로 발생).");
        }
        assumptions.add("인과 효과가 아닌 단순 추정입니다. 실행 전 실험·통제 근거를 확인하세요.");
        List<String> unconfirmed = new ArrayList<>();
        if (estimated.signum() != 0) {
            unconfirmed.add("기준 비용에 예상 비용 " + estimated.setScale(0, RoundingMode.HALF_UP).toPlainString() + "원 포함");
        }
        if (missing > 0) {
            unconfirmed.add("원가 미등록 매출원가 0 줄 " + missing + "건");
        }
        return new Result(s, from, to, base, simulated, simulated.contribution().subtract(base.contribution()), v.setScale(4, RoundingMode.HALF_UP), breakeven, assumptions,
                unconfirmed);
    }

    /** 결과를 가정과 함께 검토 업무로 남긴다(실행은 사람이). */
    @Transactional
    public Map<String, Object> saveAsWorkItem(Scenario s) {
        guard.requirePermission(Perm.WORK_ITEM_UPDATE);
        CurrentActor a = guard.actor();
        Result r = simulate(s);
        String title = "시뮬레이션 검토: 공헌이익 " + (r.contributionDelta().signum() >= 0 ? "+" : "") + r.contributionDelta().setScale(0, RoundingMode.HALF_UP).toPlainString() + "원";
        UUID id = workItems.raise(new WorkItemService.Raise(a.workspaceId(), s.businessEntityId(), s.channelAccountId(), null, "SIMULATION_REVIEW", "LOW", title,
                String.join(" / ", r.assumptions()), s.note() == null ? "가정을 검토하고 실행 여부를 결정하세요(자동 실행 없음)." : s.note(),
                "simulation:" + UUID.randomUUID(), Map.of("base_contribution", r.base().contribution().toPlainString(),
                        "simulated_contribution", r.simulated().contribution().toPlainString(), "volume_factor", r.volumeFactor().toPlainString())));
        audit.record(AuditEntry.builder(a.workspaceId(), "SIMULATION_SAVED", "WORK_ITEM", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(s.businessEntityId(), s.channelAccountId()).after(Map.of("delta", r.contributionDelta().toPlainString())).build());
        return Map.of("work_item_id", id.toString(), "result", r);
    }

    private static Side side(Map<String, BigDecimal> cat, BigDecimal units) {
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal other = BigDecimal.ZERO;
        Map<String, BigDecimal> costs = new TreeMap<>();
        for (var e : cat.entrySet()) {
            if (NET_SALES.contains(e.getKey())) {
                net = net.add(e.getValue());
            } else if (OTHER_REVENUE.contains(e.getKey())) {
                other = other.add(e.getValue());
            } else {
                costs.put(e.getKey(), e.getValue());
            }
        }
        return sideFrom(net, other, costs, units);
    }

    private static Side sideFrom(BigDecimal net, BigDecimal other, Map<String, BigDecimal> costs, BigDecimal units) {
        Map<String, BigDecimal> rounded = new TreeMap<>();
        costs.forEach((k, v) -> rounded.put(k, v.setScale(0, RoundingMode.HALF_UP)));
        BigDecimal n = net.setScale(0, RoundingMode.HALF_UP);
        BigDecimal o = other.setScale(0, RoundingMode.HALF_UP);
        BigDecimal total = rounded.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal contribution = n.add(o).subtract(total);
        BigDecimal margin = n.signum() > 0 ? contribution.multiply(BigDecimal.valueOf(100)).divide(n, 2, RoundingMode.HALF_UP) : null;
        return new Side(n, o, rounded, total, contribution, margin, units.setScale(1, RoundingMode.HALF_UP));
    }

    private static BigDecimal pct(BigDecimal v, int min, int max, String field) {
        BigDecimal x = v == null ? BigDecimal.ZERO : v;
        if (x.compareTo(BigDecimal.valueOf(min)) < 0 || x.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw Errors.validation(field, "값은 " + min + " ~ " + max + " 사이로 입력하세요.");
        }
        return x;
    }
}
