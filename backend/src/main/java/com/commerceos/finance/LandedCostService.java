package com.commerceos.finance;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.inventory.InventoryService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 입고 부대비용(landed cost, M5 T33, docs/05 §3, DLG-169·170).
 * 운임·관세·검사 등 취득부대비용을 입고분(재고 원장 입고 행)에 수량·금액·직접 지정으로 배부한다(원 미만 잔액은 가장 큰 행, DLG-030).
 * SKU 별로 아직 보유 중인 수량 비율만큼 이동평균 원가에 더하고(취득원가), 이미 출고·소진된 수량분은 매출원가로 바로 기록한다.
 * 같은 청구 근거번호는 한 번만이며, 비용 원장에 같은 근거번호로 이미 비용 처리된 청구는 받지 않는다(반대도 마찬가지 — 출고비 등으로 중복 차감 금지).
 */
@Service
public class LandedCostService {
    static final Set<String> TYPES = Set.of("FREIGHT", "DUTY", "CUSTOMS", "INSPECTION", "OTHER");
    static final Set<String> METHODS = Set.of("QUANTITY", "AMOUNT", "MANUAL");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final InventoryService inventory;
    private final Ledger ledger;
    private final AuditService audit;

    public LandedCostService(JdbcClient jdbc, AccessGuard guard, InventoryService inventory, Ledger ledger, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.inventory = inventory;
        this.ledger = ledger;
        this.audit = audit;
    }

    public record Target(UUID receiptTxnId, BigDecimal amount) {
    }

    public record NewLandedCost(UUID businessEntityId, String costType, BigDecimal supplyAmount, BigDecimal vatAmount, String method, String sourceRef, String memo,
                                List<Target> receipts) {
    }

    public record LineView(UUID receiptTxnId, UUID skuId, String skuCode, int quantity, BigDecimal basisValue, BigDecimal allocated, BigDecimal capitalized,
                           BigDecimal expensed, int onHandAtAllocation) {
    }

    public record LandedCostView(UUID id, UUID businessEntityId, String businessEntityName, String costType, BigDecimal supplyAmount, BigDecimal vatAmount, String method,
                                 String sourceRef, String memo, BigDecimal capitalized, BigDecimal expensed, List<LineView> lines, java.time.Instant createdAt) {
    }

    @Transactional
    public LandedCostView create(NewLandedCost in) {
        guard.requirePermission(Perm.COST_MANAGE);
        CurrentActor a = guard.actor();
        if (in == null || in.businessEntityId() == null) {
            throw Errors.validation("business_entity_id", "사업자를 선택하세요.");
        }
        UUID be = in.businessEntityId();
        guard.checkWrite("BUSINESS_ENTITY", be, be, null);
        if (in.costType() == null || !TYPES.contains(in.costType())) {
            throw Errors.validation("cost_type", "부대비용 종류를 선택하세요(운임·관세·통관·검사·기타).");
        }
        String method = in.method() == null ? "QUANTITY" : in.method();
        if (!METHODS.contains(method)) {
            throw Errors.validation("method", "배부 기준은 수량·금액·직접 지정 중 하나입니다.");
        }
        if (in.supplyAmount() == null || in.supplyAmount().signum() <= 0 || in.supplyAmount().stripTrailingZeros().scale() > 0) {
            throw Errors.validation("supply_amount", "공급가(부가세 제외)를 원 단위 양수로 입력하세요.");
        }
        BigDecimal vat = in.vatAmount() == null ? BigDecimal.ZERO : in.vatAmount();
        if (vat.signum() < 0 || vat.stripTrailingZeros().scale() > 0) {
            throw Errors.validation("vat_amount", "부가세를 원 단위로 입력하세요.");
        }
        String ref = in.sourceRef() == null ? null : in.sourceRef().trim();
        if (ref == null || ref.isEmpty() || ref.length() > 200) {
            throw Errors.validation("source_ref", "청구서·신고 번호 등 근거번호를 입력하세요(같은 청구 중복 방지).");
        }
        if (jdbc.sql("select count(*) from landed_cost where business_entity_id = :be and source_ref = :r").param("be", be).param("r", ref).query(Integer.class).single() > 0) {
            throw Errors.conflict("LANDED_COST_DUPLICATE", "같은 근거번호의 입고 부대비용이 이미 반영되어 있습니다.");
        }
        if (jdbc.sql("""
                select count(*) from financial_entry where business_entity_id = :be and source_ref = :r and entry_class = 'COST' and source_type = 'MANUAL_COST'
                """).param("be", be).param("r", ref).query(Integer.class).single() > 0) {
            throw Errors.conflict("COST_ALREADY_EXPENSED", "같은 근거번호의 청구가 이미 비용으로 기록되어 있습니다. 취득원가로 다시 반영하면 이중 차감됩니다.");
        }
        List<Target> targets = in.receipts() == null ? List.of() : in.receipts();
        if (targets.isEmpty() || targets.size() > 200) {
            throw Errors.validation("receipts", "배부할 입고 내역을 1~200건 선택하세요.");
        }
        // 입고 행 확인(같은 사업자, 입고, 수량 > 0)
        Set<UUID> seen = new LinkedHashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Target t : targets) {
            if (t == null || t.receiptTxnId() == null || !seen.add(t.receiptTxnId())) {
                throw Errors.validation("receipts", "입고 내역이 비었거나 중복되었습니다.");
            }
            Map<String, Object> r = jdbc.sql("""
                    select t.id, t.sku_id, t.on_hand_delta, t.node_id, k.sku_code, c.unit_cost from inventory_transaction t join sku k on k.id = t.sku_id
                      left join inventory_receipt_cost c on c.txn_id = t.id
                     where t.workspace_id = :ws and t.id = :id and t.owner_business_entity_id = :be and t.txn_type = 'RECEIPT' and t.on_hand_delta > 0
                    """).param("ws", a.workspaceId()).param("id", t.receiptTxnId()).param("be", be).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> Errors.validation("receipts", "이 사업자의 입고 내역이 아닙니다."));
            guard.checkNode(AccessKind.WRITE, "INVENTORY_TRANSACTION", t.receiptTxnId(), (UUID) r.get("node_id"));
            BigDecimal basis = switch (method) {
                case "QUANTITY" -> BigDecimal.valueOf(((Number) r.get("on_hand_delta")).intValue());
                case "AMOUNT" -> {
                    if (r.get("unit_cost") == null) {
                        throw Errors.unprocessable("RECEIPT_COST_MISSING", "금액 기준 배부에는 입고 단가가 있어야 합니다. 단가 없는 입고(" + r.get("sku_code") + ")가 있습니다.");
                    }
                    yield ((BigDecimal) r.get("unit_cost")).multiply(BigDecimal.valueOf(((Number) r.get("on_hand_delta")).intValue()));
                }
                default -> {
                    if (t.amount() == null || t.amount().signum() < 0 || t.amount().stripTrailingZeros().scale() > 0) {
                        throw Errors.validation("receipts", "직접 지정은 입고마다 원 단위 금액을 입력하세요.");
                    }
                    yield t.amount();
                }
            };
            Map<String, Object> row = new LinkedHashMap<>(r);
            row.put("basis", basis);
            rows.add(row);
        }
        BigDecimal total = in.supplyAmount();
        Map<UUID, BigDecimal> alloc = allocate(rows, total, "MANUAL".equals(method));
        // SKU 별 보유 비율로 취득원가·매출원가 분리
        Map<UUID, int[]> qtyBySku = new LinkedHashMap<>();
        Map<UUID, BigDecimal> amtBySku = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            UUID sku = (UUID) r.get("sku_id");
            qtyBySku.computeIfAbsent(sku, k -> new int[1])[0] += ((Number) r.get("on_hand_delta")).intValue();
            amtBySku.merge(sku, alloc.get((UUID) r.get("id")), BigDecimal::add);
        }
        UUID id = Ids.newId();
        BigDecimal capTotal = BigDecimal.ZERO;
        BigDecimal expTotal = BigDecimal.ZERO;
        Map<UUID, BigDecimal> capBySku = new LinkedHashMap<>();
        Map<UUID, Integer> onHandBySku = new LinkedHashMap<>();
        for (var e : amtBySku.entrySet()) {
            UUID sku = e.getKey();
            int received = qtyBySku.get(sku)[0];
            int onHand = jdbc.sql("select coalesce(sum(on_hand), 0) from inventory_stock where owner_business_entity_id = :b and sku_id = :s").param("b", be).param("s", sku)
                    .query(Integer.class).single();
            onHandBySku.put(sku, onHand);
            int held = Math.max(0, Math.min(received, onHand));
            BigDecimal cap = e.getValue().multiply(BigDecimal.valueOf(held)).divide(BigDecimal.valueOf(received), 0, RoundingMode.HALF_UP);
            BigDecimal exp = e.getValue().subtract(cap);
            capBySku.put(sku, cap);
            if (cap.signum() > 0) {
                inventory.capitalize(a.workspaceId(), be, sku, cap);
            }
            if (exp.signum() != 0) {
                ledger.post(new Ledger.Entry(a.workspaceId(), be, null, null, null, null, null, null, sku, null, "COST", "PRODUCT_COGS", "ACTUAL", exp, BigDecimal.ZERO,
                        "OUT_OF_SCOPE", null, null, Ledger.period(null), "LANDED_COST", ref, "LANDED:" + id + ":" + sku, null, null,
                        "입고 부대비용 중 이미 출고·소진된 수량분(" + (received - held) + "/" + received + ")", a.memberId()));
            }
            capTotal = capTotal.add(cap);
            expTotal = expTotal.add(exp);
        }
        jdbc.sql("""
                insert into landed_cost (id, workspace_id, business_entity_id, cost_type, supply_amount, vat_amount, method, source_ref, memo, capitalized_amount,
                                         expensed_amount, created_by)
                values (:id, :ws, :be, :t, :s, :v, :m, :r, cast(:memo as text), :cap, :exp, :by)
                """).param("id", id).param("ws", a.workspaceId()).param("be", be).param("t", in.costType()).param("s", total).param("v", vat).param("m", method)
                .param("r", ref).param("memo", in.memo() == null || in.memo().isBlank() ? null : in.memo().trim()).param("cap", capTotal).param("exp", expTotal)
                .param("by", a.memberId()).update();
        // 줄별 기록(SKU 단위 분리를 줄 비중으로 다시 나눔 — 합계 일치)
        Map<UUID, BigDecimal> capLeft = new LinkedHashMap<>(capBySku);
        Map<UUID, Integer> linesLeft = new LinkedHashMap<>();
        rows.forEach(r -> linesLeft.merge((UUID) r.get("sku_id"), 1, Integer::sum));
        for (Map<String, Object> r : rows) {
            UUID sku = (UUID) r.get("sku_id");
            UUID txn = (UUID) r.get("id");
            BigDecimal lineAlloc = alloc.get(txn);
            int left = linesLeft.merge(sku, -1, Integer::sum);
            BigDecimal skuAmt = amtBySku.get(sku);
            BigDecimal lineCap = left == 0 ? capLeft.get(sku) : skuAmt.signum() == 0 ? BigDecimal.ZERO
                    : capBySku.get(sku).multiply(lineAlloc).divide(skuAmt, 0, RoundingMode.HALF_UP);
            capLeft.merge(sku, lineCap.negate(), BigDecimal::add);
            jdbc.sql("""
                    insert into landed_cost_line (id, workspace_id, landed_cost_id, receipt_txn_id, sku_id, quantity, basis_value, allocated_amount, capitalized_amount,
                                                  expensed_amount, on_hand_at_allocation)
                    values (:id, :ws, :lc, :t, :s, :q, :b, :a, :c, :e, :oh)
                    """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("lc", id).param("t", txn).param("s", sku)
                    .param("q", ((Number) r.get("on_hand_delta")).intValue()).param("b", r.get("basis")).param("a", lineAlloc).param("c", lineCap)
                    .param("e", lineAlloc.subtract(lineCap)).param("oh", onHandBySku.get(sku)).update();
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "LANDED_COST_ALLOCATED", "LANDED_COST", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).after(Map.of("source_ref", ref, "supply", total.toPlainString(), "method", method, "capitalized", capTotal.toPlainString(),
                        "expensed", expTotal.toPlainString())).build());
        return get(id);
    }

    /** 기준 비중으로 배부, 원 미만 잔액은 기준이 가장 큰 행(DLG-030). 직접 지정은 합계가 정확히 같아야 한다. */
    static Map<UUID, BigDecimal> allocate(List<Map<String, Object>> rows, BigDecimal total, boolean manual) {
        Map<UUID, BigDecimal> out = new LinkedHashMap<>();
        BigDecimal base = rows.stream().map(r -> (BigDecimal) r.get("basis")).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (manual) {
            if (base.compareTo(total) != 0) {
                throw Errors.unprocessable("ALLOCATION_TOTAL_MISMATCH", "직접 지정한 금액 합계가 부대비용 공급가와 같아야 합니다.",
                        Map.of("total", total.toPlainString(), "assigned", base.toPlainString()));
            }
            rows.forEach(r -> out.put((UUID) r.get("id"), (BigDecimal) r.get("basis")));
            return out;
        }
        if (base.signum() <= 0) {
            throw Errors.unprocessable("ALLOCATION_BASE_ZERO", "배부 기준 합계가 0 입니다. 다른 기준을 선택하세요.");
        }
        BigDecimal used = BigDecimal.ZERO;
        UUID largest = null;
        BigDecimal largestBase = null;
        for (Map<String, Object> r : rows) {
            BigDecimal b = (BigDecimal) r.get("basis");
            BigDecimal share = total.multiply(b).divide(base, 0, RoundingMode.HALF_UP);
            out.put((UUID) r.get("id"), share);
            used = used.add(share);
            if (largestBase == null || b.compareTo(largestBase) > 0) {
                largestBase = b;
                largest = (UUID) r.get("id");
            }
        }
        out.merge(largest, total.subtract(used), BigDecimal::add);
        return out;
    }

    public List<LandedCostView> list(List<String> businessEntityIds) {
        guard.requirePermission(Perm.COST_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select id from landed_cost where workspace_id = :ws and business_entity_id in (:bes) order by created_at desc limit 200")
                .param("ws", guard.actor().workspaceId()).param("bes", bes).query(UUID.class).list().stream().map(this::view).toList();
    }

    public LandedCostView get(UUID id) {
        guard.requirePermission(Perm.COST_READ);
        UUID be = jdbc.sql("select business_entity_id from landed_cost where workspace_id = :ws and id = :id").param("ws", guard.actor().workspaceId()).param("id", id)
                .query(UUID.class).optional().orElseThrow(() -> Errors.notFound("LANDED_COST"));
        guard.checkRead("LANDED_COST", id, be, null);
        return view(id);
    }

    private LandedCostView view(UUID id) {
        Map<String, Object> h = jdbc.sql("select l.*, be.display_name as be_name from landed_cost l join business_entity be on be.id = l.business_entity_id where l.id = :id")
                .param("id", id).query().listOfRows().get(0);
        List<LineView> lines = jdbc.sql("""
                select l.*, k.sku_code from landed_cost_line l join sku k on k.id = l.sku_id where l.landed_cost_id = :id order by k.sku_code, l.receipt_txn_id
                """).param("id", id).query((rs, i) -> new LineView(Db.uuid(rs, "receipt_txn_id"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"), rs.getInt("quantity"),
                rs.getBigDecimal("basis_value"), rs.getBigDecimal("allocated_amount"), rs.getBigDecimal("capitalized_amount"), rs.getBigDecimal("expensed_amount"),
                rs.getInt("on_hand_at_allocation"))).list();
        return new LandedCostView(id, (UUID) h.get("business_entity_id"), (String) h.get("be_name"), (String) h.get("cost_type"), (BigDecimal) h.get("supply_amount"),
                (BigDecimal) h.get("vat_amount"), (String) h.get("method"), (String) h.get("source_ref"), (String) h.get("memo"), (BigDecimal) h.get("capitalized_amount"),
                (BigDecimal) h.get("expensed_amount"), lines, PostingService.instant(h.get("created_at")));
    }
}
