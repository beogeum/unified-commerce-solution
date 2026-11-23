package com.commerceos.insight;

import com.commerceos.finance.UnitCosts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 규칙 기반 이상 탐지(docs/14 §5 우선순위 1·2, DLG-179·180). 운영그룹 전체를 계산하고 조회 때 사용자 범위로 거른다.
 * 원인은 비용 항목별 비중 변화(%p)로 분해한 '후보'이며 인과로 단정하지 않는다. 예상 비용·원가 미등록 등 확정되지 않은 근거는 미확인으로 표시한다.
 */
@Component
public class InsightEngine {
    static final Set<String> NET_SALES = Set.of("SALES", "SELLER_DISCOUNT", "SALES_RETURN", "EXCHANGE_PRICE_DIFFERENCE");
    static final Set<String> OTHER_REVENUE = Set.of("SHIPPING_FEE", "CUSTOMER_RETURN_FEE");
    private final JdbcClient jdbc;
    private final UnitCosts costs;
    private final BigDecimal marginDropPp;
    private final BigDecimal minNetSales;
    private final int windowDays;
    private final int coverDays;
    private final int slowDays;
    private final int expiryDays;
    private final int settlementMissingDays;
    private final int undepositedGraceDays;

    public InsightEngine(JdbcClient jdbc, UnitCosts costs, @Value("${commerce.insight.margin-drop-pp:3}") BigDecimal marginDropPp,
                         @Value("${commerce.insight.min-net-sales:50000}") BigDecimal minNetSales, @Value("${commerce.insight.window-days:30}") int windowDays,
                         @Value("${commerce.insight.cover-days:7}") int coverDays, @Value("${commerce.insight.slow-days:90}") int slowDays,
                         @Value("${commerce.insight.expiry-days:60}") int expiryDays, @Value("${commerce.insight.settlement-missing-days:30}") int settlementMissingDays,
                         @Value("${commerce.insight.undeposited-grace-days:7}") int undepositedGraceDays) {
        this.jdbc = jdbc;
        this.costs = costs;
        this.marginDropPp = marginDropPp;
        this.minNetSales = minNetSales;
        this.windowDays = windowDays;
        this.coverDays = coverDays;
        this.slowDays = slowDays;
        this.expiryDays = expiryDays;
        this.settlementMissingDays = settlementMissingDays;
        this.undepositedGraceDays = undepositedGraceDays;
    }

    public record Detection(String rule, String dedupeKey, UUID businessEntityId, UUID channelAccountId, UUID skuId, String severity, String title, String summary,
                            Map<String, Object> metric, List<Map<String, Object>> causes, Map<String, Object> evidence, List<String> unconfirmed) {
    }

    public List<Detection> detect(UUID ws, LocalDate today) {
        List<Detection> out = new ArrayList<>();
        out.addAll(marginDrop(ws, today));
        out.addAll(stock(ws, today));
        out.addAll(expiry(ws, today));
        out.addAll(settlement(ws, today));
        return out;
    }

    // ---------------------------------------------------------------- 1. 공헌이익률 급락(판매계정)

    List<Detection> marginDrop(UUID ws, LocalDate today) {
        LocalDate curFrom = today.minusDays(windowDays - 1L);
        LocalDate prevFrom = curFrom.minusDays(windowDays);
        Map<String, Window[]> acc = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.sql("""
                select e.business_entity_id as be, e.channel_account_id as ca, case when e.period_date >= :cf then 1 else 0 end as cur, e.category, e.basis,
                       sum(e.supply_amount) as amt, count(*) filter (where e.category = 'PRODUCT_COGS' and e.unit_cost is null and e.quantity > 0) as missing
                  from financial_entry e where e.workspace_id = :ws and e.channel_account_id is not null and not e.intercompany and e.entry_class <> 'MEMO'
                   and e.period_date between :pf and :t group by 1, 2, 3, 4, 5
                """).param("ws", ws).param("cf", curFrom).param("pf", prevFrom).param("t", today).query().listOfRows()) {
            String k = r.get("be") + "|" + r.get("ca");
            Window[] w = acc.computeIfAbsent(k, x -> new Window[]{new Window(), new Window()});
            w[((Number) r.get("cur")).intValue()].add((String) r.get("category"), (String) r.get("basis"), (BigDecimal) r.get("amt"), ((Number) r.get("missing")).intValue());
        }
        List<Detection> out = new ArrayList<>();
        for (var e : acc.entrySet()) {
            Window prev = e.getValue()[0];
            Window cur = e.getValue()[1];
            if (prev.netSales.compareTo(minNetSales) < 0 || cur.netSales.compareTo(minNetSales) < 0) {
                continue;
            }
            BigDecimal mPrev = prev.marginPct();
            BigDecimal mCur = cur.marginPct();
            BigDecimal drop = mPrev.subtract(mCur);
            if (drop.compareTo(marginDropPp) < 0) {
                continue;
            }
            String[] k = e.getKey().split("\\|");
            UUID be = UUID.fromString(k[0]);
            UUID ca = UUID.fromString(k[1]);
            String caName = jdbc.sql("select be.display_name || ' · ' || ca.display_name from channel_account ca join business_entity be on be.id = ca.business_entity_id where ca.id = :id")
                    .param("id", ca).query(String.class).single();
            // 원인 분해: 비용 항목별 순매출 대비 비중 변화(%p), 기타 수익 비중 감소
            List<Map<String, Object>> causes = new ArrayList<>();
            Set<String> cats = new java.util.TreeSet<>(cur.costs.keySet());
            cats.addAll(prev.costs.keySet());
            for (String c : cats) {
                BigDecimal d = ratio(cur.costs.getOrDefault(c, BigDecimal.ZERO), cur.netSales).subtract(ratio(prev.costs.getOrDefault(c, BigDecimal.ZERO), prev.netSales));
                if (d.compareTo(new BigDecimal("0.1")) >= 0) {
                    causes.add(cause(c, d, cur.costs.getOrDefault(c, BigDecimal.ZERO), prev.costs.getOrDefault(c, BigDecimal.ZERO)));
                }
            }
            BigDecimal otherDrop = ratio(prev.other, prev.netSales).subtract(ratio(cur.other, cur.netSales));
            if (otherDrop.compareTo(new BigDecimal("0.1")) >= 0) {
                causes.add(cause("OTHER_REVENUE_DOWN", otherDrop, cur.other, prev.other));
            }
            causes.sort(Comparator.comparing((Map<String, Object> m) -> (BigDecimal) m.get("delta_pp")).reversed());
            List<Map<String, Object>> top = causes.size() > 3 ? causes.subList(0, 3) : causes;
            List<String> unconfirmed = new ArrayList<>();
            if (cur.estimated.signum() != 0) {
                unconfirmed.add("최근 기간 비용에 예상 비용 " + cur.estimated.toPlainString() + "원 포함(실제 청구·정산 전)");
            }
            if (cur.missing + prev.missing > 0) {
                unconfirmed.add("원가 미등록으로 매출원가 0 인 줄 " + (cur.missing + prev.missing) + "건");
            }
            Map<String, Object> metric = new LinkedHashMap<>();
            metric.put("current_from", curFrom.toString());
            metric.put("current_to", today.toString());
            metric.put("previous_from", prevFrom.toString());
            metric.put("previous_to", curFrom.minusDays(1).toString());
            metric.put("current_margin_pct", mCur);
            metric.put("previous_margin_pct", mPrev);
            metric.put("delta_pp", drop.negate());
            metric.put("current_net_sales", cur.netSales);
            metric.put("previous_net_sales", prev.netSales);
            metric.put("current_contribution", cur.contribution());
            metric.put("previous_contribution", prev.contribution());
            String causeText = top.isEmpty() ? "비용 비중 변화로 설명되지 않음(매출 구성 변화 가능)" : String.join(" / ", top.stream().map(m -> m.get("label") + " +"
                    + ((BigDecimal) m.get("delta_pp")).toPlainString() + "%p").toList());
            out.add(new Detection("MARGIN_DROP", "MARGIN_DROP:" + be + ":" + ca, be, ca, null, drop.compareTo(BigDecimal.TEN) >= 0 ? "HIGH" : "MEDIUM",
                    caName + " 공헌이익률 " + drop.negate().toPlainString() + "%p", "직전 " + windowDays + "일 " + mPrev.toPlainString() + "% → 최근 " + windowDays + "일 "
                    + mCur.toPlainString() + "%. 원인 후보: " + causeText + ".", metric, top,
                    Map.of("profit_filter", Map.of("channel_account_id", ca.toString(), "from", curFrom.toString(), "to", today.toString()),
                            "certainty", "규칙 기반 분해(비용 비중 변화). 인과로 단정하지 않습니다."), unconfirmed));
        }
        return out;
    }

    static final Map<String, String> COST_KO = Map.ofEntries(Map.entry("PRODUCT_COGS", "상품 원가"), Map.entry("SELLING_FEE", "판매수수료"),
            Map.entry("PAYMENT_FEE", "결제수수료"), Map.entry("ADVERTISING", "광고비"), Map.entry("OUTBOUND_SHIPPING", "출고 배송비"), Map.entry("THIRD_PARTY_PICK", "3PL 피킹비"),
            Map.entry("PACK", "포장비"), Map.entry("STORAGE", "보관비"), Map.entry("INBOUND_SERVICE", "입고 작업비"), Map.entry("MATERIAL", "부자재"), Map.entry("GWP", "사은품"),
            Map.entry("RETURN_SHIPPING", "반품 회수비"), Map.entry("RETURN_INSPECTION", "반품 검수비"), Map.entry("REPACK", "재포장비"), Map.entry("EXCHANGE_SHIPPING", "교환 배송비"),
            Map.entry("INVENTORY_IMPAIRMENT", "재고 손실"), Map.entry("DISPOSAL_SERVICE", "폐기 처리비"), Map.entry("CHANNEL_PENALTY", "채널 패널티"), Map.entry("OTHER", "기타 비용"),
            Map.entry("OTHER_REVENUE_DOWN", "배송비·반품비 수익 감소"));

    static Map<String, Object> cause(String code, BigDecimal deltaPp, BigDecimal cur, BigDecimal prev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("label", COST_KO.getOrDefault(code, code));
        m.put("delta_pp", deltaPp);
        m.put("current_amount", cur);
        m.put("previous_amount", prev);
        return m;
    }

    static BigDecimal ratio(BigDecimal amt, BigDecimal base) {
        return base.signum() <= 0 ? BigDecimal.ZERO : amt.multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP);
    }

    static final class Window {
        BigDecimal netSales = BigDecimal.ZERO;
        BigDecimal other = BigDecimal.ZERO;
        BigDecimal estimated = BigDecimal.ZERO;
        int missing;
        final Map<String, BigDecimal> costs = new TreeMap<>();

        void add(String category, String basis, BigDecimal amt, int miss) {
            if (NET_SALES.contains(category)) {
                netSales = netSales.add(amt);
            } else if (OTHER_REVENUE.contains(category)) {
                other = other.add(amt);
            } else if (COST_KO.containsKey(category)) {
                costs.merge(category, amt, BigDecimal::add);
                if ("ESTIMATED".equals(basis)) {
                    estimated = estimated.add(amt);
                }
            }
            missing += miss;
        }

        BigDecimal contribution() {
            return netSales.add(other).subtract(costs.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        }

        BigDecimal marginPct() {
            return ratio(contribution(), netSales);
        }
    }

    // ---------------------------------------------------------------- 2·3. 품절 위험·장기 재고(사업자 × SKU)

    List<Detection> stock(UUID ws, LocalDate today) {
        List<Detection> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("""
                with pos as (select owner_business_entity_id as be, sku_id, sum(sellable_on_hand - committed - restricted_hold) as atp
                               from inventory_position where workspace_id = :ws group by 1, 2),
                     st as (select owner_business_entity_id as be, sku_id, sum(on_hand) as on_hand from inventory_stock where workspace_id = :ws group by 1, 2),
                     tx as (select owner_business_entity_id as be, sku_id,
                                   coalesce(sum(-on_hand_delta) filter (where txn_type = 'CONSUME' and occurred_at >= now() - interval '28 days'), 0) as sold28,
                                   max(occurred_at) filter (where txn_type = 'CONSUME') as last_sale,
                                   min(occurred_at) filter (where on_hand_delta > 0) as first_in
                              from inventory_transaction where workspace_id = :ws group by 1, 2)
                select coalesce(pos.be, st.be) as be, coalesce(pos.sku_id, st.sku_id) as sku_id, greatest(coalesce(pos.atp, 0), 0) as atp, coalesce(st.on_hand, 0) as on_hand,
                       coalesce(tx.sold28, 0) as sold28, tx.last_sale, tx.first_in, k.sku_code, be.display_name as be_name
                  from pos full join st on st.be = pos.be and st.sku_id = pos.sku_id
                  left join tx on tx.be = coalesce(pos.be, st.be) and tx.sku_id = coalesce(pos.sku_id, st.sku_id)
                  join sku k on k.id = coalesce(pos.sku_id, st.sku_id) join business_entity be on be.id = coalesce(pos.be, st.be)
                """).param("ws", ws).query().listOfRows()) {
            UUID be = (UUID) r.get("be");
            UUID sku = (UUID) r.get("sku_id");
            int atp = ((Number) r.get("atp")).intValue();
            int onHand = ((Number) r.get("on_hand")).intValue();
            int sold = ((Number) r.get("sold28")).intValue();
            String name = r.get("be_name") + " · " + r.get("sku_code");
            if (sold > 0) {
                BigDecimal days = BigDecimal.valueOf(atp).multiply(BigDecimal.valueOf(28)).divide(BigDecimal.valueOf(sold), 1, RoundingMode.HALF_UP);
                if (days.compareTo(BigDecimal.valueOf(coverDays)) < 0) {
                    Map<String, Object> metric = new LinkedHashMap<>();
                    metric.put("atp", atp);
                    metric.put("sold_28d", sold);
                    metric.put("avg_daily_sales", BigDecimal.valueOf(sold).divide(BigDecimal.valueOf(28), 2, RoundingMode.HALF_UP));
                    metric.put("days_of_cover", days);
                    metric.put("threshold_days", coverDays);
                    out.add(new Detection("STOCKOUT_RISK", "STOCKOUT_RISK:" + be + ":" + sku, be, null, sku, atp == 0 || days.compareTo(BigDecimal.valueOf(3)) < 0 ? "HIGH"
                            : "MEDIUM", name + " 품절 위험(재고일수 " + days.toPlainString() + "일)", "판매가능 " + atp + "개, 최근 28일 일평균 " + metric.get("avg_daily_sales")
                            + "개 판매. 기준 " + coverDays + "일 미만입니다.", metric, List.of(Map.of("code", "DEMAND_VS_ATP", "label", "판매 속도 대비 가용재고 부족",
                            "certainty", "최근 28일 판매가 계속된다는 가정")), Map.of("inventory_filter", Map.of("business_entity_id", be.toString(), "sku_id", sku.toString()),
                            "suggestion", "입고 또는 다른 거점의 재고 이동을 검토하세요(발주 제안은 발주·매입 기능 이후)."), List.of()));
                }
            }
            java.time.Instant cut = java.time.Instant.now().minus(java.time.Duration.ofDays(slowDays));
            java.time.Instant last = r.get("last_sale") == null ? null : ((java.sql.Timestamp) r.get("last_sale")).toInstant();
            java.time.Instant first = r.get("first_in") == null ? null : ((java.sql.Timestamp) r.get("first_in")).toInstant();
            if (onHand > 0 && first != null && first.isBefore(cut) && (last == null || last.isBefore(cut))) {
                BigDecimal unit = costs.current(be, sku).orElse(null);
                BigDecimal book = unit == null ? null : unit.multiply(BigDecimal.valueOf(onHand)).setScale(0, RoundingMode.HALF_UP);
                Map<String, Object> metric = new LinkedHashMap<>();
                metric.put("on_hand", onHand);
                metric.put("days_without_sale", slowDays);
                metric.put("last_sale_at", last == null ? null : last.toString());
                metric.put("book_value", book);
                out.add(new Detection("SLOW_MOVING", "SLOW_MOVING:" + be + ":" + sku, be, null, sku, book != null && book.compareTo(BigDecimal.valueOf(1_000_000)) >= 0 ? "MEDIUM"
                        : "LOW", name + " " + slowDays + "일 이상 미판매 재고", "보유 " + onHand + "개" + (book == null ? "" : ", 장부가 약 " + book.toPlainString() + "원")
                        + ". " + slowDays + "일 동안 출고가 없습니다.", metric, List.of(), Map.of("inventory_filter", Map.of("business_entity_id", be.toString(),
                        "sku_id", sku.toString()), "suggestion", "프로모션·채널 재배치·평가손실 검토(실행은 담당자 판단)."),
                        book == null ? List.of("원가 미등록으로 장부가 계산불가") : List.of()));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 4. 유통기한 임박(사업자 × SKU × LOT)

    List<Detection> expiry(UUID ws, LocalDate today) {
        List<Detection> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("""
                select s.owner_business_entity_id as be, s.sku_id, l.id as lot_id, l.lot_code, l.expires_on, sum(s.on_hand) as qty, k.sku_code, be.display_name as be_name
                  from inventory_stock s join inventory_lot l on l.id = s.lot_id join sku k on k.id = s.sku_id join business_entity be on be.id = s.owner_business_entity_id
                 where s.workspace_id = :ws and s.quality_status = 'SELLABLE' and s.on_hand > 0 and l.expires_on is not null and l.expires_on <= :lim
                 group by 1, 2, 3, 4, 5, 7, 8
                """).param("ws", ws).param("lim", today.plusDays(expiryDays)).query().listOfRows()) {
            LocalDate exp = ((java.sql.Date) r.get("expires_on")).toLocalDate();
            long left = java.time.temporal.ChronoUnit.DAYS.between(today, exp);
            UUID be = (UUID) r.get("be");
            UUID sku = (UUID) r.get("sku_id");
            Map<String, Object> metric = new LinkedHashMap<>();
            metric.put("lot_code", r.get("lot_code"));
            metric.put("expires_on", exp.toString());
            metric.put("days_left", left);
            metric.put("sellable_qty", ((Number) r.get("qty")).intValue());
            out.add(new Detection("EXPIRY_RISK", "EXPIRY_RISK:" + be + ":" + sku + ":" + r.get("lot_id"), be, null, sku, left <= 14 ? "HIGH" : "MEDIUM",
                    r.get("be_name") + " · " + r.get("sku_code") + " LOT " + r.get("lot_code") + (left < 0 ? " 유통기한 경과" : " 유통기한 " + left + "일 남음"),
                    "판매가능 " + metric.get("sellable_qty") + "개, 유통기한 " + exp + ".", metric, List.of(),
                    Map.of("inventory_filter", Map.of("business_entity_id", be.toString(), "sku_id", sku.toString()),
                            "suggestion", left < 0 ? "판매 중지(품질 상태 만료로 변경) 후 폐기·반품 검토" : "선출고(FEFO) 확인, 할인·채널 배치 검토"), List.of()));
        }
        return out;
    }

    // ---------------------------------------------------------------- 5·6. 정산 누락 의심·미입금(판매계정)

    List<Detection> settlement(UUID ws, LocalDate today) {
        List<Detection> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("""
                select so.business_entity_id as be, so.channel_account_id as ca, count(distinct so.id) as orders,
                       coalesce(sum(oi.line_amount - oi.seller_discount), 0) as amount, min(sh.first_ship) as oldest
                  from sales_order so join order_item oi on oi.order_id = so.id and oi.item_kind = 'ORIGINAL'
                  join (select order_id, min(shipped_at) as first_ship from shipment where workspace_id = :ws group by order_id) sh on sh.order_id = so.id
                 where so.workspace_id = :ws and so.source_type = 'ORDER' and sh.first_ship < now() - make_interval(days => :d) and sh.first_ship >= now() - interval '180 days'
                   and exists (select 1 from settlement_run r where r.channel_account_id = so.channel_account_id)
                   and not exists (select 1 from settlement_line l where l.order_id = so.id)
                 group by 1, 2
                """).param("ws", ws).param("d", settlementMissingDays).query().listOfRows()) {
            UUID be = (UUID) r.get("be");
            UUID ca = (UUID) r.get("ca");
            int orders = ((Number) r.get("orders")).intValue();
            String caName = jdbc.sql("select display_name from channel_account where id = :id").param("id", ca).query(String.class).single();
            Map<String, Object> metric = new LinkedHashMap<>();
            metric.put("orders", orders);
            metric.put("order_amount", r.get("amount"));
            metric.put("days_after_shipment", settlementMissingDays);
            out.add(new Detection("SETTLEMENT_MISSING", "SETTLEMENT_MISSING:" + ca, be, ca, null, "MEDIUM", caName + " 정산 누락 의심 주문 " + orders + "건",
                    "출고 후 " + settlementMissingDays + "일이 지났지만 가져온 정산에 없는 주문이 " + orders + "건(주문금액 " + ((BigDecimal) r.get("amount")).toPlainString()
                            + "원) 있습니다.", metric, List.of(Map.of("code", "SETTLEMENT_NOT_IMPORTED", "label", "정산 파일 미반영 또는 채널 정산 지연", "certainty",
                    "가져온 정산 원본 기준. 채널 정산 주기에 따라 정상일 수 있음")), Map.of("settlement_filter", Map.of("channel_account_id", ca.toString())),
                    List.of("채널 정산 API 미연동 — 가져온 파일 기준")));
        }
        for (Map<String, Object> r : jdbc.sql("""
                select x.business_entity_id as be, x.channel_account_id as ca, count(*) as runs, sum(x.rem) as amount, min(x.payout_date) as oldest
                  from (select r.business_entity_id, r.channel_account_id, r.payout_date,
                               (select coalesce(sum(l.amount), 0) from settlement_line l where l.run_id = r.id)
                             - (select coalesce(sum(m.amount), 0) from deposit_match m where m.run_id = r.id and m.cancelled_at is null) as rem
                          from settlement_run r where r.workspace_id = :ws and r.payout_date < :d) x
                 where x.rem > 0 group by 1, 2
                """).param("ws", ws).param("d", today.minusDays(undepositedGraceDays)).query().listOfRows()) {
            UUID be = (UUID) r.get("be");
            UUID ca = (UUID) r.get("ca");
            String caName = jdbc.sql("select display_name from channel_account where id = :id").param("id", ca).query(String.class).single();
            Map<String, Object> metric = new LinkedHashMap<>();
            metric.put("runs", ((Number) r.get("runs")).intValue());
            metric.put("undeposited_amount", r.get("amount"));
            metric.put("oldest_payout_date", String.valueOf(r.get("oldest")));
            metric.put("grace_days", undepositedGraceDays);
            out.add(new Detection("UNDEPOSITED", "UNDEPOSITED:" + ca, be, ca, null, "MEDIUM", caName + " 지급예정일 지난 미입금 정산 " + metric.get("runs") + "건",
                    "지급예정일 + " + undepositedGraceDays + "일이 지났지만 입금이 연결되지 않은 금액 " + ((BigDecimal) r.get("amount")).toPlainString() + "원.", metric,
                    List.of(Map.of("code", "DEPOSIT_NOT_MATCHED", "label", "입금 미등록·미연결 또는 채널 지급 지연", "certainty", "등록된 입금 기준")),
                    Map.of("settlement_filter", Map.of("channel_account_id", ca.toString())), List.of("은행 입금 자동 수집 미지원 — 등록된 입금 기준")));
        }
        return out;
    }
}
