package com.commerceos.finance;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비용 확정·재고 평가·관리 공헌이익(M5 N2·N5·N6, docs/05 §5~7, DLG-037·153~160).
 * <ul>
 *   <li>실제 비용이 범위(출고지시·주문상품·클레임)를 가지고 오면 그 범위의 남은 예상 비용을 반대 거래로 상계하고 실제만 남긴다(합산 금지, T26). 차이가 기준(기본 100원) 이상이면 검토 업무.</li>
 *   <li>평가손실은 품질 상태만으로 자동 처리하지 않고 재무 담당이 버킷 단위로 기록(장부가 0). 이후 폐기는 남은 장부가만 손실(T17).</li>
 *   <li>공헌이익 = 순매출(공급가) + 배송비·반품비 수익 − 변동비. 이익률은 이익 합계 ÷ 순매출 합계(T36).</li>
 * </ul>
 */
@Service
public class FinanceService {
    static final Set<String> NET_SALES = Set.of("SALES", "SELLER_DISCOUNT", "SALES_RETURN", "EXCHANGE_PRICE_DIFFERENCE");
    static final Set<String> OTHER_REVENUE = Set.of("SHIPPING_FEE", "CUSTOMER_RETURN_FEE");
    static final Set<String> COSTS = Set.of("PRODUCT_COGS", "SELLING_FEE", "PAYMENT_FEE", "ADVERTISING", "OUTBOUND_SHIPPING", "THIRD_PARTY_PICK", "PACK", "STORAGE",
            "INBOUND_SERVICE", "MATERIAL", "GWP", "RETURN_SHIPPING", "RETURN_INSPECTION", "REPACK", "EXCHANGE_SHIPPING", "INVENTORY_IMPAIRMENT", "DISPOSAL_SERVICE",
            "CHANNEL_PENALTY", "OTHER");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final Ledger ledger;
    private final UnitCosts costs;
    private final PostingService posting;
    private final WorkItemService workItems;
    private final AuditService audit;
    private final BigDecimal varianceThreshold;
    private final GroupElimination elimination;

    public FinanceService(JdbcClient jdbc, AccessGuard guard, Ledger ledger, UnitCosts costs, PostingService posting, WorkItemService workItems, AuditService audit,
                          @Value("${commerce.finance.variance-review-threshold:100}") BigDecimal varianceThreshold, GroupElimination elimination) {
        this.elimination = elimination;
        this.jdbc = jdbc;
        this.guard = guard;
        this.ledger = ledger;
        this.costs = costs;
        this.posting = posting;
        this.workItems = workItems;
        this.audit = audit;
        this.varianceThreshold = varianceThreshold;
    }

    public record Scope(UUID fulfillmentOrderId, UUID orderItemId, UUID claimId) {
    }

    public record NewCost(UUID businessEntityId, String category, String basis, BigDecimal supplyAmount, BigDecimal vatAmount, LocalDate periodDate,
                          UUID channelAccountId, UUID nodeId, UUID skuId, UUID orderId, Scope scope, String sourceRef, String memo) {
    }

    public record CostResult(UUID entryId, List<UUID> replacedEstimateIds, BigDecimal estimated, BigDecimal actual, BigDecimal variance, boolean reviewRequired) {
    }

    public record Impair(UUID stockId, Integer quantity, String reason) {
    }

    public record ImpairResult(UUID stockId, int quantity, BigDecimal unitCost, BigDecimal loss) {
    }

    public record ProfitRow(String key, String label, BigDecimal netSales, BigDecimal otherRevenue, Map<String, BigDecimal> costs, BigDecimal totalCost,
                            BigDecimal contribution, BigDecimal marginPct, BigDecimal estimatedCost, int missingCostLines) {
    }

    public record Profit(LocalDate from, LocalDate to, String groupBy, List<ProfitRow> rows, ProfitRow total, String basisNote, GroupElimination.Elimination elimination) {
    }

    public record EntryView(UUID id, UUID businessEntityId, String businessEntityName, UUID channelAccountId, UUID orderId, String orderNo, UUID orderItemId,
                            UUID claimId, UUID fulfillmentOrderId, UUID skuId, String skuCode, String entryClass, String category, String basis, BigDecimal supplyAmount,
                            BigDecimal vatAmount, String taxType, Integer quantity, BigDecimal unitCost, LocalDate periodDate, String sourceType, String sourceRef,
                            UUID reversesId, String scopeKey, String memo, java.time.Instant createdAt) {
    }

    // ---------------------------------------------------------------- 비용 기록(예상 → 실제)

    @Transactional
    public CostResult recordCost(NewCost c, String idempotencyKey) {
        guard.requirePermission(Perm.COST_MANAGE);
        CurrentActor a = guard.actor();
        if (c == null || c.businessEntityId() == null) {
            throw Errors.validation("business_entity_id", "비용을 귀속할 사업자를 선택하세요.");
        }
        guard.checkWrite("BUSINESS_ENTITY", c.businessEntityId(), c.businessEntityId(), c.channelAccountId());
        if (c.category() == null || !COSTS.contains(c.category()) || "PRODUCT_COGS".equals(c.category()) || "INVENTORY_IMPAIRMENT".equals(c.category())) {
            throw Errors.validation("category", "비용 항목을 선택하세요(매출원가·평가손실은 출고·재고 평가에서 자동 기록).");
        }
        String basis = c.basis() == null ? "ACTUAL" : c.basis();
        if (!Set.of("ESTIMATED", "ACTUAL").contains(basis)) {
            throw Errors.validation("basis", "예상 또는 실제를 선택하세요.");
        }
        if (c.supplyAmount() == null || c.supplyAmount().stripTrailingZeros().scale() > 0) {
            throw Errors.validation("supply_amount", "공급가(부가세 제외)를 원 단위로 입력하세요.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw Errors.idempotencyKeyRequired();
        }
        String key = "COST:" + idempotencyKey.trim();
        UUID existing = jdbc.sql("select id from financial_entry where workspace_id = :ws and source_key = :k").param("ws", a.workspaceId()).param("k", key).query(UUID.class)
                .optional().orElse(null);
        if (existing != null) {
            return new CostResult(existing, List.of(), null, null, null, false);
        }
        if (c.sourceRef() != null && !c.sourceRef().isBlank() && jdbc.sql("select count(*) from landed_cost where business_entity_id = :be and source_ref = :r")
                .param("be", c.businessEntityId()).param("r", c.sourceRef().trim()).query(Integer.class).single() > 0) {
            throw Errors.conflict("LANDED_COST_CAPITALIZED", "같은 근거번호의 청구가 입고 부대비용으로 이미 취득원가에 반영되어 있습니다. 비용으로 다시 기록하면 이중 차감됩니다.");
        }
        String scopeKey = scopeKey(c.category(), c.scope());
        UUID orderId = c.orderId();
        UUID foId = c.scope() == null ? null : c.scope().fulfillmentOrderId();
        UUID itemId = c.scope() == null ? null : c.scope().orderItemId();
        UUID claimId = c.scope() == null ? null : c.scope().claimId();
        if (foId != null) {
            Map<String, Object> f = jdbc.sql("select order_id, business_entity_id from fulfillment_order where workspace_id = :ws and id = :id").param("ws", a.workspaceId())
                    .param("id", foId).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.validation("scope", "출고지시를 찾을 수 없습니다."));
            requireSameBe(c.businessEntityId(), (UUID) f.get("business_entity_id"));
            orderId = (UUID) f.get("order_id");
        }
        if (itemId != null) {
            Map<String, Object> i = jdbc.sql("select i.order_id, o.business_entity_id from order_item i join sales_order o on o.id = i.order_id where i.workspace_id = :ws and i.id = :id")
                    .param("ws", a.workspaceId()).param("id", itemId).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> Errors.validation("scope", "주문상품을 찾을 수 없습니다."));
            requireSameBe(c.businessEntityId(), (UUID) i.get("business_entity_id"));
            orderId = (UUID) i.get("order_id");
        }
        LocalDate period = c.periodDate() == null ? Ledger.period(null) : c.periodDate();
        Applied ap = applyActual(new ActualCost(a.workspaceId(), c.businessEntityId(), c.channelAccountId(), orderId, itemId, claimId, foId, c.skuId(), c.nodeId(),
                c.category(), basis, c.supplyAmount(), c.vatAmount() == null ? BigDecimal.ZERO : c.vatAmount(), period, "MANUAL_COST", c.sourceRef(), key, scopeKey,
                ClaimTrim.trim(c.memo()), a.memberId()));
        UUID entryId = ap.entryId();
        List<UUID> replaced = ap.replaced();
        BigDecimal estimated = ap.estimated();
        BigDecimal variance = replaced.isEmpty() ? null : c.supplyAmount().subtract(estimated);
        boolean review = variance != null && variance.abs().compareTo(varianceThreshold) >= 0;
        if (review) {
            workItems.raise(new WorkItemService.Raise(a.workspaceId(), c.businessEntityId(), c.channelAccountId(), null, "COST_VARIANCE_REVIEW", "MEDIUM",
                    "예상·실제 비용 차이 " + variance.toPlainString() + "원", "예상 " + estimated.toPlainString() + "원 → 실제 " + c.supplyAmount().toPlainString()
                            + "원. 손익에는 실제만 반영했습니다.", "계약요율·청구 내역을 확인하세요.", "cost-variance:" + entryId,
                    Map.of("entry_id", entryId.toString(), "variance", variance.toPlainString())));
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "COST_RECORDED", "FINANCIAL_ENTRY", entryId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(c.businessEntityId(), c.channelAccountId()).after(Map.of("category", c.category(), "basis", basis, "supply", c.supplyAmount().toPlainString(),
                        "replaced", replaced.size(), "variance", String.valueOf(variance))).build());
        return new CostResult(entryId, replaced, replaced.isEmpty() ? null : estimated, c.supplyAmount(), variance, review);
    }

    /** 실제 비용 기록 입력(수동·정산 공통). */
    public record ActualCost(UUID workspaceId, UUID businessEntityId, UUID channelAccountId, UUID orderId, UUID orderItemId, UUID claimId, UUID foId, UUID skuId,
                             UUID nodeId, String category, String basis, BigDecimal supply, BigDecimal vat, LocalDate period, String sourceType, String sourceRef,
                             String sourceKey, String scopeKey, String memo, UUID createdBy) {
    }

    public record Applied(UUID entryId, List<UUID> replaced, BigDecimal estimated) {
    }

    /**
     * 실제 비용 기록. 범위 키가 있으면 그 범위의 남은 예상(상계되지 않은 행)을 반대 거래로 상계한 뒤 실제를 기록한다(합산 금지, DLG-158).
     * 같은 source_key 는 한 번만.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Applied applyActual(ActualCost c) {
        UUID existing = jdbc.sql("select id from financial_entry where workspace_id = :ws and source_key = :k").param("ws", c.workspaceId()).param("k", c.sourceKey())
                .query(UUID.class).optional().orElse(null);
        if (existing != null) {
            return new Applied(existing, List.of(), BigDecimal.ZERO);
        }
        List<UUID> replaced = new ArrayList<>();
        BigDecimal estimated = BigDecimal.ZERO;
        if ("ACTUAL".equals(c.basis()) && c.scopeKey() != null) {
            for (Map<String, Object> e : jdbc.sql("""
                    select e.* from financial_entry e where e.workspace_id = :ws and e.scope_key = :sk and e.basis = 'ESTIMATED' and e.reverses_id is null
                       and not exists (select 1 from financial_entry r where r.reverses_id = e.id)
                    """).param("ws", c.workspaceId()).param("sk", c.scopeKey()).query().listOfRows()) {
                BigDecimal amt = (BigDecimal) e.get("supply_amount");
                estimated = estimated.add(amt);
                UUID id = (UUID) e.get("id");
                ledger.post(new Ledger.Entry(c.workspaceId(), (UUID) e.get("business_entity_id"), (UUID) e.get("channel_account_id"), (UUID) e.get("order_id"),
                        (UUID) e.get("order_item_id"), (UUID) e.get("claim_id"), (UUID) e.get("fo_id"), null, (UUID) e.get("sku_id"), (UUID) e.get("node_id"), "COST",
                        c.category(), "ESTIMATED", amt.negate(), ((BigDecimal) e.get("vat_amount")).negate(), (String) e.get("tax_type"),
                        e.get("quantity") == null ? null : -((Number) e.get("quantity")).intValue(), null, c.period(), "COST_REPLACEMENT", c.sourceRef(), "REV:" + id, id,
                        c.scopeKey(), "실제 비용으로 대체(예상 상계)", c.createdBy()));
                replaced.add(id);
            }
        }
        ledger.post(new Ledger.Entry(c.workspaceId(), c.businessEntityId(), c.channelAccountId(), c.orderId(), c.orderItemId(), c.claimId(), c.foId(), null, c.skuId(),
                c.nodeId(), "COST", c.category(), c.basis(), c.supply(), c.vat(), "TAXABLE", null, null, c.period(), c.sourceType(), c.sourceRef(), c.sourceKey(), null,
                c.scopeKey(), c.memo(), c.createdBy()));
        UUID id = jdbc.sql("select id from financial_entry where workspace_id = :ws and source_key = :k").param("ws", c.workspaceId()).param("k", c.sourceKey())
                .query(UUID.class).single();
        return new Applied(id, replaced, estimated);
    }

    // ---------------------------------------------------------------- 재고 평가손실

    @Transactional
    public ImpairResult impair(Impair in) {
        guard.requirePermission(Perm.COST_MANAGE);
        CurrentActor a = guard.actor();
        if (in == null || in.stockId() == null || in.quantity() == null || in.quantity() < 1) {
            throw Errors.validation("quantity", "평가할 재고 버킷과 수량을 입력하세요.");
        }
        String reason = ClaimTrim.trim(in.reason());
        if (reason == null) {
            throw Errors.validation("reason", "평가 근거를 입력하세요.");
        }
        Map<String, Object> s = jdbc.sql("select * from inventory_stock where workspace_id = :ws and id = :id for update").param("ws", a.workspaceId()).param("id", in.stockId())
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("INVENTORY_STOCK"));
        UUID be = (UUID) s.get("owner_business_entity_id");
        guard.checkWrite("INVENTORY_STOCK", in.stockId(), be, null);
        if ("SELLABLE".equals(s.get("quality_status"))) {
            throw Errors.unprocessable("STOCK_SELLABLE", "판매가능 재고는 평가손실 대상이 아닙니다. 먼저 품질 상태를 바꾸세요.");
        }
        int onHand = ((Number) s.get("on_hand")).intValue();
        int pool = posting.impairedPool(in.stockId());
        if (in.quantity() > onHand - pool) {
            throw Errors.unprocessable("ALREADY_IMPAIRED", "이미 장부가가 0 인 수량을 빼면 평가할 수 있는 수량을 넘습니다.", Map.of("on_hand", onHand, "already_zero_book", pool));
        }
        UUID sku = (UUID) s.get("sku_id");
        BigDecimal unit = costs.current(be, sku).orElseThrow(() -> Errors.unprocessable("COST_MISSING", "원가가 등록되지 않아 평가할 수 없습니다."));
        BigDecimal loss = Ledger.won(unit.multiply(BigDecimal.valueOf(in.quantity())));
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into inventory_valuation_entry (id, workspace_id, business_entity_id, sku_id, node_id, stock_id, entry_type, quantity, unit_cost, loss_amount, reason,
                                                       source_key, created_by)
                values (:id, :ws, :be, :s, :n, :st, 'IMPAIRMENT', :q, :u, :l, :r, :k, :by)
                """).param("id", id).param("ws", a.workspaceId()).param("be", be).param("s", sku).param("n", s.get("node_id")).param("st", in.stockId())
                .param("q", in.quantity()).param("u", unit).param("l", loss).param("r", reason).param("k", "IMP:" + id).param("by", a.memberId()).update();
        ledger.post(new Ledger.Entry(a.workspaceId(), be, null, null, null, null, null, null, sku, (UUID) s.get("node_id"), "COST", "INVENTORY_IMPAIRMENT", "ACTUAL", loss,
                BigDecimal.ZERO, "OUT_OF_SCOPE", in.quantity(), unit, Ledger.period(null), "INVENTORY_VALUATION", id.toString(), "IMP:" + id, null, null, reason,
                a.memberId()));
        audit.record(AuditEntry.builder(a.workspaceId(), "INVENTORY_IMPAIRED", "INVENTORY_STOCK", in.stockId()).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).reason(reason).after(Map.of("quantity", in.quantity(), "loss", loss.toPlainString())).build());
        return new ImpairResult(in.stockId(), in.quantity(), unit, loss);
    }

    // ---------------------------------------------------------------- 손익

    public Profit profit(List<String> businessEntityIds, UUID channelAccountId, LocalDate from, LocalDate to, String groupBy) {
        guard.requirePermission(Perm.PROFIT_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        String g = groupBy == null ? "business_entity" : groupBy;
        String keyExpr = switch (g) {
            case "business_entity" -> "e.business_entity_id::text";
            case "channel_account" -> "coalesce(e.channel_account_id::text, '-')";
            case "sku" -> "coalesce(e.sku_id::text, '-')";
            case "order" -> "coalesce(e.order_id::text, '-')";
            case "month" -> "to_char(e.period_date, 'YYYY-MM')";
            default -> throw Errors.validation("group_by", "묶음 기준은 business_entity·channel_account·sku·order·month 중 하나입니다.");
        };
        String labelExpr = switch (g) {
            case "business_entity" -> "max(be.display_name)";
            case "channel_account" -> "coalesce(max(ca.display_name), '판매계정 없음(공통)')";
            case "sku" -> "coalesce(max(k.sku_code), 'SKU 없음(공통)')";
            case "order" -> "coalesce(max(so.order_no), '주문 없음(공통)')";
            default -> "to_char(min(e.period_date), 'YYYY-MM')";
        };
        LocalDate f = from == null ? LocalDate.now(Ledger.KST).withDayOfMonth(1) : from;
        LocalDate t = to == null ? LocalDate.now(Ledger.KST) : to;
        if (bes.isEmpty()) {
            return new Profit(f, t, g, List.of(), emptyRow(), BASIS_NOTE, null);
        }
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "bes", bes, "f", f, "t", t));
        StringBuilder where = new StringBuilder(" where e.workspace_id = :ws and e.business_entity_id in (:bes) and e.period_date between :f and :t and e.entry_class <> 'MEMO'");
        // 판매계정 범위: 판매계정이 있는 행은 볼 수 있는 계정만, 공통(판매계정 없음) 행은 사업자 범위로
        where.append(" and (e.channel_account_id is null or e.channel_account_id in (:cas))");
        p.put("cas", cas.isEmpty() ? Set.of(UUID.randomUUID()) : cas);
        if (channelAccountId != null) {
            where.append(" and e.channel_account_id = :ca");
            p.put("ca", channelAccountId);
        }
        List<Map<String, Object>> agg = jdbc.sql("select " + keyExpr + " as k, " + labelExpr + " as label, e.category, e.basis, sum(e.supply_amount) as amt, "
                        + "count(*) filter (where e.category = 'PRODUCT_COGS' and e.unit_cost is null and e.quantity > 0) as missing"
                        + " from financial_entry e join business_entity be on be.id = e.business_entity_id left join channel_account ca on ca.id = e.channel_account_id"
                        + " left join sku k on k.id = e.sku_id left join sales_order so on so.id = e.order_id" + where + " group by 1, e.category, e.basis order by 1")
                .params(p).query().listOfRows();
        Map<String, ProfitAcc> rows = new LinkedHashMap<>();
        ProfitAcc total = new ProfitAcc("TOTAL", "합계");
        for (Map<String, Object> r : agg) {
            String k = (String) r.get("k");
            ProfitAcc acc = rows.computeIfAbsent(k, x -> new ProfitAcc(k, (String) r.get("label")));
            for (ProfitAcc target : List.of(acc, total)) {
                target.add((String) r.get("category"), (String) r.get("basis"), (BigDecimal) r.get("amt"), ((Number) r.get("missing")).intValue());
            }
        }
        // 운영그룹 합계: 범위 안 사업자 간 내부거래 제거(사업자별 행은 그대로, DLG-033). 판매계정 필터가 있으면 내부거래 행이 범위 밖이므로 적용하지 않는다.
        GroupElimination.Elimination el = channelAccountId != null ? null : elimination.compute(guard.actor().workspaceId(), bes, f, t);
        if (el != null) {
            total.add("SALES", "ACTUAL", el.internalSales().negate(), 0);
            total.add("PRODUCT_COGS", "ACTUAL", el.cogsAdjustment(), 0);
        }
        return new Profit(f, t, g, rows.values().stream().map(ProfitAcc::row).toList(), total.row(), BASIS_NOTE, el);
    }

    static final String BASIS_NOTE = "관리 손익(부가세 제외 공급가 기준)이며 법정 재무제표·세무 신고와 다릅니다. 매출은 출고 확정 시, 반품은 회수 검수 완료 시 반영합니다. "
            + "예상 비용은 실제 청구·정산이 오면 대체됩니다.";

    private static ProfitRow emptyRow() {
        return new ProfitAcc("TOTAL", "합계").row();
    }

    private static final class ProfitAcc {
        final String key;
        final String label;
        BigDecimal netSales = BigDecimal.ZERO;
        BigDecimal other = BigDecimal.ZERO;
        BigDecimal estimated = BigDecimal.ZERO;
        int missing;
        final Map<String, BigDecimal> costs = new java.util.TreeMap<>();

        ProfitAcc(String key, String label) {
            this.key = key;
            this.label = label;
        }

        void add(String category, String basis, BigDecimal amt, int miss) {
            if (NET_SALES.contains(category)) {
                netSales = netSales.add(amt);
            } else if (OTHER_REVENUE.contains(category)) {
                other = other.add(amt);
            } else if (COSTS.contains(category)) {
                costs.merge(category, amt, BigDecimal::add);
                if ("ESTIMATED".equals(basis)) {
                    estimated = estimated.add(amt);
                }
            }
            missing += miss;
        }

        ProfitRow row() {
            BigDecimal totalCost = costs.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal contribution = netSales.add(other).subtract(totalCost);
            BigDecimal margin = netSales.signum() > 0 ? contribution.multiply(BigDecimal.valueOf(100)).divide(netSales, 2, RoundingMode.HALF_UP) : null;
            return new ProfitRow(key, label, netSales, other, Map.copyOf(costs), totalCost, contribution, margin, estimated, missing);
        }
    }

    public List<EntryView> entries(List<String> businessEntityIds, UUID orderId, UUID claimId, String category, LocalDate from, LocalDate to, Integer limit) {
        guard.requirePermission(Perm.COST_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        if (bes.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                select e.*, be.display_name as be_name, so.order_no, k.sku_code from financial_entry e join business_entity be on be.id = e.business_entity_id
                  left join sales_order so on so.id = e.order_id left join sku k on k.id = e.sku_id
                 where e.workspace_id = :ws and e.business_entity_id in (:bes) and (e.channel_account_id is null or e.channel_account_id in (:cas))
                """);
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "bes", bes, "cas", cas.isEmpty() ? Set.of(UUID.randomUUID()) : cas));
        if (orderId != null) {
            sql.append(" and e.order_id = :o");
            p.put("o", orderId);
        }
        if (claimId != null) {
            sql.append(" and e.claim_id = :c");
            p.put("c", claimId);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" and e.category = :cat");
            p.put("cat", category);
        }
        if (from != null) {
            sql.append(" and e.period_date >= :f");
            p.put("f", from);
        }
        if (to != null) {
            sql.append(" and e.period_date <= :t");
            p.put("t", to);
        }
        sql.append(" order by e.created_at desc, e.id limit :lim");
        p.put("lim", limit == null ? 300 : Math.max(1, Math.min(1000, limit)));
        return jdbc.sql(sql.toString()).params(p).query((rs, i) -> new EntryView(Db.uuid(rs, "id"), Db.uuid(rs, "business_entity_id"), rs.getString("be_name"),
                Db.uuid(rs, "channel_account_id"), Db.uuid(rs, "order_id"), rs.getString("order_no"), Db.uuid(rs, "order_item_id"), Db.uuid(rs, "claim_id"),
                Db.uuid(rs, "fo_id"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"), rs.getString("entry_class"), rs.getString("category"), rs.getString("basis"),
                rs.getBigDecimal("supply_amount"), rs.getBigDecimal("vat_amount"), rs.getString("tax_type"), (Integer) rs.getObject("quantity"), rs.getBigDecimal("unit_cost"),
                rs.getObject("period_date", LocalDate.class), rs.getString("source_type"), rs.getString("source_ref"), Db.uuid(rs, "reverses_id"), rs.getString("scope_key"),
                rs.getString("memo"), Db.instant(rs, "created_at"))).list();
    }

    // ---------------------------------------------------------------- 내부

    private static String scopeKey(String category, Scope s) {
        if (s == null) {
            return null;
        }
        if (s.fulfillmentOrderId() != null) {
            return category + ":FO:" + s.fulfillmentOrderId();
        }
        if (s.orderItemId() != null) {
            return category + ":ITEM:" + s.orderItemId();
        }
        if (s.claimId() != null) {
            return category + ":CLAIM:" + s.claimId();
        }
        return null;
    }

    private static void requireSameBe(UUID given, UUID actual) {
        if (!given.equals(actual)) {
            throw Errors.validation("business_entity_id", "비용 범위(출고·주문)의 사업자와 다릅니다.");
        }
    }

    /** 짧은 텍스트 정리. */
    static final class ClaimTrim {
        static String trim(String s) {
            if (s == null || s.isBlank()) {
                return null;
            }
            String t = s.trim();
            return t.length() > 500 ? t.substring(0, 500) : t;
        }
    }
}
