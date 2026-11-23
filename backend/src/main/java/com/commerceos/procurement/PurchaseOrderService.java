package com.commerceos.procurement;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.inventory.InventoryService;
import com.commerceos.operations.WorkItemService;
import com.commerceos.operations.approval.ApprovalHandler;
import com.commerceos.operations.approval.ApprovalService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발주(FR-FIN-01, docs/04 §8, DLG-191~195).
 * DRAFT → APPROVAL_PENDING(승인 요청) → PLACED → SUPPLIER_CONFIRMED → PARTIALLY_RECEIVED → RECEIVED, 그리고 CANCELLED(입고 전)·CLOSED(부분 입고 후 잔량 종료).
 * <ul>
 *   <li>MOQ·발주단위 미충족은 사유가 있어야만 허용. 금액은 공급가 기준, 과세면 부가세 ⌊공급가/10⌋.</li>
 *   <li>승인: 요청자 ≠ 승인자, 승인자도 발주 관리 권한. 승인 전후로 내용이 바뀌면 자동 취소(버전).</li>
 *   <li>입고: 정상 수량은 재고 원장 입고(실매입 단가 → 이동평균·입고 단가 보존), 불량은 받지 않고 공급처 반송 업무. 발주 수량을 넘는 입고는 거부.</li>
 *   <li>입고예정 = 열린 발주(PLACED·SUPPLIER_CONFIRMED·PARTIALLY_RECEIVED)의 남은 수량. 판매가능 재고로 세지 않는다.</li>
 * </ul>
 */
@Service
public class PurchaseOrderService implements ApprovalHandler {
    public static final String REQUEST_TYPE = "PURCHASE_ORDER";
    static final Set<String> OPEN = Set.of("PLACED", "SUPPLIER_CONFIRMED", "PARTIALLY_RECEIVED");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final SupplierService suppliers;
    private final InventoryService inventory;
    private final ApprovalService approvals;
    private final WorkItemService workItems;
    private final AuditService audit;

    public PurchaseOrderService(JdbcClient jdbc, AccessGuard guard, SupplierService suppliers, InventoryService inventory, @Lazy ApprovalService approvals,
                                WorkItemService workItems, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.suppliers = suppliers;
        this.inventory = inventory;
        this.approvals = approvals;
        this.workItems = workItems;
        this.audit = audit;
    }

    public record LineInput(UUID skuId, Integer quantity, BigDecimal unitCost, String moqOverrideReason) {
    }

    public record PoInput(UUID supplierId, UUID nodeId, LocalDate expectedOn, String memo, List<LineInput> lines) {
    }

    public record LineView(UUID id, int lineNo, UUID skuId, String skuCode, String skuName, int quantity, BigDecimal unitCost, String taxType, BigDecimal supplyAmount,
                           BigDecimal vatAmount, int receivedQty, int rejectedQty, int remainingQty, String moqOverrideReason) {
    }

    public record ReceiptLineView(UUID poLineId, String skuCode, int goodQty, int rejectedQty, BigDecimal unitCost, BigDecimal poUnitCost, String lotCode,
                                  LocalDate expiresOn, UUID inventoryTxnId, String rejectReason) {
    }

    public record ReceiptView(UUID id, String receiptNo, java.time.Instant receivedAt, String note, String createdByName, List<ReceiptLineView> lines) {
    }

    public record PoView(UUID id, String poNo, UUID businessEntityId, String businessEntityName, UUID supplierId, String supplierName, UUID nodeId, String nodeName,
                         String status, LocalDate expectedOn, String supplierRef, String memo, String closeReason, UUID approvalId, BigDecimal supplyTotal,
                         BigDecimal vatTotal, int orderedQty, int receivedQty, int rejectedQty, BigDecimal priceVariance, boolean costVisible, List<LineView> lines,
                         List<ReceiptView> receipts, java.time.Instant createdAt, java.time.Instant placedAt, int version) {
    }

    public record ReceiveLine(UUID poLineId, Integer goodQty, Integer rejectedQty, BigDecimal unitCost, String lotCode, LocalDate manufacturedOn, LocalDate expiresOn,
                              String locationCode, String rejectReason) {
    }

    public record ReceiveInput(List<ReceiveLine> lines, String note) {
    }

    public record InboundRow(UUID businessEntityId, UUID skuId, String skuCode, UUID nodeId, int inboundQty, LocalDate earliestExpectedOn) {
    }

    // ---------------------------------------------------------------- 작성·수정

    @Transactional
    public PoView create(PoInput in) {
        guard.requirePermission(Perm.PURCHASE_MANAGE);
        CurrentActor a = guard.actor();
        if (in == null || in.supplierId() == null || in.nodeId() == null) {
            throw Errors.validation("supplier_id", "공급처와 입고 거점을 선택하세요.");
        }
        Map<String, Object> s = suppliers.row(a.workspaceId(), in.supplierId());
        UUID be = (UUID) s.get("business_entity_id");
        guard.checkWrite("SUPPLIER", in.supplierId(), be, null);
        if (!"ACTIVE".equals(s.get("status"))) {
            throw Errors.unprocessable("SUPPLIER_INACTIVE", "사용 중지된 공급처에는 발주할 수 없습니다.");
        }
        requireNode(be, in.nodeId());
        UUID id = Ids.newId();
        String no = nextNo(a.workspaceId(), "PURCHASE_ORDER", "PO");
        jdbc.sql("""
                insert into purchase_order (id, workspace_id, po_no, business_entity_id, supplier_id, node_id, status, expected_on, memo, created_by)
                values (:id, :ws, :no, :be, :s, :n, 'DRAFT', cast(:e as date), cast(:m as text), :by)
                """).param("id", id).param("ws", a.workspaceId()).param("no", no).param("be", be).param("s", in.supplierId()).param("n", in.nodeId())
                .param("e", in.expectedOn()).param("m", SupplierService.trim(in.memo(), 500, "memo")).param("by", a.memberId()).update();
        writeLines(a.workspaceId(), id, in.supplierId(), in.lines());
        audit.record(AuditEntry.builder(a.workspaceId(), "PURCHASE_ORDER_CREATED", "PURCHASE_ORDER", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).after(Map.of("po_no", no, "lines", in.lines() == null ? 0 : in.lines().size())).build());
        return get(id);
    }

    @Transactional
    public PoView update(UUID id, PoInput in, Integer version) {
        guard.requirePermission(Perm.PURCHASE_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> po = lock(a.workspaceId(), id);
        UUID be = (UUID) po.get("business_entity_id");
        guard.checkWrite("PURCHASE_ORDER", id, be, null);
        requireVersion(po, version);
        if (!"DRAFT".equals(po.get("status"))) {
            throw Errors.conflict("PO_NOT_DRAFT", "작성 중인 발주만 고칠 수 있습니다.", Map.of("status", po.get("status")));
        }
        UUID node = in == null || in.nodeId() == null ? (UUID) po.get("node_id") : in.nodeId();
        requireNode(be, node);
        jdbc.sql("update purchase_order set node_id = :n, expected_on = cast(:e as date), memo = cast(:m as text), updated_at = now(), version = version + 1 where id = :id")
                .param("n", node).param("e", in == null || in.expectedOn() == null ? po.get("expected_on") : in.expectedOn())
                .param("m", in == null || in.memo() == null ? po.get("memo") : SupplierService.trim(in.memo(), 500, "memo")).param("id", id).update();
        if (in != null && in.lines() != null) {
            jdbc.sql("delete from purchase_order_line where po_id = :id").param("id", id).update();
            writeLines(a.workspaceId(), id, (UUID) po.get("supplier_id"), in.lines());
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "PURCHASE_ORDER_UPDATED", "PURCHASE_ORDER", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).after(Map.of("lines", in == null || in.lines() == null ? "unchanged" : in.lines().size())).build());
        return get(id);
    }

    private void writeLines(UUID ws, UUID poId, UUID supplierId, List<LineInput> lines) {
        if (lines == null || lines.isEmpty() || lines.size() > 200) {
            throw Errors.validation("lines", "발주 품목을 1~200개 입력하세요.");
        }
        Set<UUID> seen = new HashSet<>();
        int no = 0;
        List<Map<String, Object>> problems = new ArrayList<>();
        for (LineInput l : lines) {
            no++;
            if (l == null || l.skuId() == null || !seen.add(l.skuId())) {
                throw Errors.validation("lines", no + "번째 품목의 SKU 가 비었거나 중복입니다.");
            }
            int q = l.quantity() == null ? 0 : l.quantity();
            if (q < 1) {
                throw Errors.validation("lines", no + "번째 품목의 수량은 1 이상입니다.");
            }
            Map<String, Object> ss = jdbc.sql("select * from supplier_sku where supplier_id = :s and sku_id = :k and status = 'ACTIVE'").param("s", supplierId)
                    .param("k", l.skuId()).query().listOfRows().stream().findFirst().orElse(null);
            if (ss == null) {
                throw Errors.unprocessable("SUPPLIER_SKU_MISSING", no + "번째 품목은 이 공급처의 공급 SKU 로 등록되어 있지 않습니다. 공급처 SKU 를 먼저 등록하세요.");
            }
            int moq = ((Number) ss.get("moq")).intValue();
            int mult = ((Number) ss.get("order_multiple")).intValue();
            String override = SupplierService.trim(l.moqOverrideReason(), 300, "moq_override_reason");
            if ((q < moq || q % mult != 0) && override == null) {
                problems.add(Map.of("line", no, "quantity", q, "moq", moq, "order_multiple", mult));
            }
            BigDecimal unit = l.unitCost() == null ? (BigDecimal) ss.get("unit_cost") : l.unitCost();
            if (unit.signum() < 0 || unit.stripTrailingZeros().scale() > 0) {
                throw Errors.validation("lines", no + "번째 품목의 단가를 원 단위로 입력하세요.");
            }
            String tax = (String) ss.get("tax_type");
            BigDecimal supply = unit.multiply(BigDecimal.valueOf(q));
            BigDecimal vat = "TAXABLE".equals(tax) ? supply.divide(BigDecimal.TEN, 0, RoundingMode.DOWN) : BigDecimal.ZERO;
            jdbc.sql("""
                    insert into purchase_order_line (id, workspace_id, po_id, line_no, sku_id, supplier_sku_id, quantity, unit_cost, tax_type, supply_amount, vat_amount,
                                                     moq_override_reason)
                    values (:id, :ws, :po, :no, :k, :ss, :q, :u, :t, :s, :v, cast(:o as text))
                    """).param("id", Ids.newId()).param("ws", ws).param("po", poId).param("no", no).param("k", l.skuId()).param("ss", ss.get("id")).param("q", q)
                    .param("u", unit).param("t", tax).param("s", supply).param("v", vat).param("o", override).update();
        }
        if (!problems.isEmpty()) {
            throw Errors.unprocessable("MOQ_NOT_MET", "최소 발주수량 또는 발주단위를 맞추지 않은 품목이 있습니다. 수량을 고치거나 예외 사유를 입력하세요.",
                    Map.of("lines", problems));
        }
    }

    // ---------------------------------------------------------------- 승인

    @Transactional
    public PoView submit(UUID id, Integer version) {
        guard.requirePermission(Perm.PURCHASE_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> po = lock(a.workspaceId(), id);
        UUID be = (UUID) po.get("business_entity_id");
        guard.checkWrite("PURCHASE_ORDER", id, be, null);
        requireVersion(po, version);
        if (!"DRAFT".equals(po.get("status"))) {
            throw Errors.conflict("PO_NOT_DRAFT", "작성 중인 발주만 승인 요청할 수 있습니다.");
        }
        PoView v = get(id);
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("supplier", v.supplierName());
        impact.put("node", v.nodeName());
        impact.put("lines", v.lines().size());
        impact.put("quantity", v.orderedQty());
        impact.put("supply_total", v.supplyTotal().toPlainString());
        impact.put("vat_total", v.vatTotal().toPlainString());
        impact.put("moq_overrides", v.lines().stream().filter(l -> l.moqOverrideReason() != null).count());
        int nextVersion = ((Number) po.get("version")).intValue() + 1;
        ApprovalService.Request req = approvals.create(new ApprovalService.NewRequest(REQUEST_TYPE, "PURCHASE_ORDER", id.toString(), be,
                Map.of("po_id", id.toString(), "expected_version", nextVersion), impact, "발주 " + v.poNo() + " 승인 요청"));
        jdbc.sql("update purchase_order set status = 'APPROVAL_PENDING', approval_id = :ap, updated_at = now(), version = version + 1 where id = :id").param("ap", req.id())
                .param("id", id).update();
        return get(id);
    }

    @Override
    public String requestType() {
        return REQUEST_TYPE;
    }

    @Override
    public boolean isCurrent(ApprovalService.Request r) {
        UUID id = UUID.fromString(r.payload().get("po_id").asString());
        return jdbc.sql("select count(*) from purchase_order where id = :id and status = 'APPROVAL_PENDING' and version = :v").param("id", id)
                .param("v", r.payload().get("expected_version").asInt()).query(Integer.class).single() == 1;
    }

    @Override
    public void apply(ApprovalService.Request r) {
        CurrentActor a = guard.actor();
        if (!a.has(Perm.PURCHASE_MANAGE)) {
            throw Errors.unprocessable("APPROVER_NEEDS_PURCHASE_MANAGE", "발주 승인은 발주 관리 권한이 있는 승인자만 할 수 있습니다.");
        }
        UUID id = UUID.fromString(r.payload().get("po_id").asString());
        jdbc.sql("update purchase_order set status = 'PLACED', placed_at = now(), updated_at = now(), version = version + 1 where id = :id").param("id", id).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "PURCHASE_ORDER_PLACED", "PURCHASE_ORDER", id).actor(ActorType.APPROVER, a.memberId(), a.displayName())
                .scope(r.businessEntityId(), null).after(Map.of("approval_id", r.id().toString())).build());
    }

    // ---------------------------------------------------------------- 공급처 확인·취소·종료

    @Transactional
    public PoView supplierConfirm(UUID id, LocalDate expectedOn, String supplierRef) {
        guard.requirePermission(Perm.PURCHASE_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> po = lock(a.workspaceId(), id);
        guard.checkWrite("PURCHASE_ORDER", id, (UUID) po.get("business_entity_id"), null);
        if (!Set.of("PLACED", "SUPPLIER_CONFIRMED").contains(po.get("status"))) {
            throw Errors.conflict("PO_INVALID_STATE", "발주 완료 상태에서만 공급처 확인을 기록할 수 있습니다.", Map.of("status", po.get("status")));
        }
        jdbc.sql("""
                update purchase_order set status = 'SUPPLIER_CONFIRMED', expected_on = cast(:e as date), supplier_ref = cast(:r as text), updated_at = now(),
                       version = version + 1 where id = :id
                """).param("e", expectedOn == null ? po.get("expected_on") : expectedOn).param("r", SupplierService.trim(supplierRef, 100, "supplier_ref"))
                .param("id", id).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "PURCHASE_ORDER_SUPPLIER_CONFIRMED", "PURCHASE_ORDER", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) po.get("business_entity_id"), null).after(Map.of("expected_on", String.valueOf(expectedOn))).build());
        return get(id);
    }

    @Transactional
    public PoView cancel(UUID id, String reason) {
        guard.requirePermission(Perm.PURCHASE_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> po = lock(a.workspaceId(), id);
        UUID be = (UUID) po.get("business_entity_id");
        guard.checkWrite("PURCHASE_ORDER", id, be, null);
        String st = (String) po.get("status");
        if (!Set.of("DRAFT", "APPROVAL_PENDING", "PLACED", "SUPPLIER_CONFIRMED").contains(st)) {
            throw Errors.conflict("PO_INVALID_STATE", "입고가 시작된 발주는 취소할 수 없습니다. 잔량 종료를 사용하세요.", Map.of("status", st));
        }
        String why = SupplierService.trim(reason, 500, "reason");
        if (why == null && !"DRAFT".equals(st)) {
            throw Errors.validation("reason", "취소 사유를 입력하세요.");
        }
        if ("APPROVAL_PENDING".equals(st) && po.get("approval_id") != null) {
            jdbc.sql("update approval_request set status = 'CANCELLED', decided_at = now(), decision_note = '발주 취소', version = version + 1 where id = :id and status = 'PENDING'")
                    .param("id", po.get("approval_id")).update();
        }
        jdbc.sql("update purchase_order set status = 'CANCELLED', close_reason = cast(:r as text), updated_at = now(), version = version + 1 where id = :id")
                .param("r", why).param("id", id).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "PURCHASE_ORDER_CANCELLED", "PURCHASE_ORDER", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).reason(why).before(Map.of("status", st)).build());
        return get(id);
    }

    @Transactional
    public PoView closeShort(UUID id, String reason) {
        guard.requirePermission(Perm.PURCHASE_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> po = lock(a.workspaceId(), id);
        UUID be = (UUID) po.get("business_entity_id");
        guard.checkWrite("PURCHASE_ORDER", id, be, null);
        if (!"PARTIALLY_RECEIVED".equals(po.get("status"))) {
            throw Errors.conflict("PO_INVALID_STATE", "부분 입고된 발주만 잔량 종료할 수 있습니다(입고 전이면 취소).", Map.of("status", po.get("status")));
        }
        String why = SupplierService.trim(reason, 500, "reason");
        if (why == null) {
            throw Errors.validation("reason", "잔량 종료 사유를 입력하세요.");
        }
        jdbc.sql("update purchase_order set status = 'CLOSED', close_reason = :r, updated_at = now(), version = version + 1 where id = :id").param("r", why).param("id", id)
                .update();
        audit.record(AuditEntry.builder(a.workspaceId(), "PURCHASE_ORDER_CLOSED", "PURCHASE_ORDER", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).reason(why).build());
        return get(id);
    }

    // ---------------------------------------------------------------- 입고

    @Transactional
    public PoView receive(UUID id, ReceiveInput in) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        Map<String, Object> po = lock(a.workspaceId(), id);
        UUID be = (UUID) po.get("business_entity_id");
        UUID node = (UUID) po.get("node_id");
        guard.checkWrite("PURCHASE_ORDER", id, be, null);
        guard.checkNode(AccessKind.WRITE, "PURCHASE_ORDER", id, node);
        if (!OPEN.contains(po.get("status"))) {
            throw Errors.conflict("PO_INVALID_STATE", "발주 완료·공급처 확인·부분 입고 상태에서만 입고할 수 있습니다.", Map.of("status", po.get("status")));
        }
        if (in == null || in.lines() == null || in.lines().isEmpty()) {
            throw Errors.validation("lines", "입고할 품목을 입력하세요.");
        }
        UUID receiptId = Ids.newId();
        String no = nextNo(a.workspaceId(), "PO_RECEIPT", "GR");
        jdbc.sql("insert into po_receipt (id, workspace_id, receipt_no, po_id, note, created_by) values (:id, :ws, :no, :po, cast(:n as text), :by)").param("id", receiptId)
                .param("ws", a.workspaceId()).param("no", no).param("po", id).param("n", SupplierService.trim(in.note(), 500, "note")).param("by", a.memberId()).update();
        int rejectedTotal = 0;
        Set<UUID> seen = new HashSet<>();
        for (ReceiveLine l : in.lines()) {
            if (l == null || l.poLineId() == null || !seen.add(l.poLineId())) {
                throw Errors.validation("lines", "발주 품목이 비었거나 중복입니다.");
            }
            Map<String, Object> pl = jdbc.sql("select * from purchase_order_line where po_id = :po and id = :id for update").param("po", id).param("id", l.poLineId())
                    .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.validation("lines", "이 발주의 품목이 아닙니다."));
            int good = l.goodQty() == null ? 0 : l.goodQty();
            int rej = l.rejectedQty() == null ? 0 : l.rejectedQty();
            if (good < 0 || rej < 0 || good + rej < 1) {
                throw Errors.validation("lines", "정상·불량 수량을 확인하세요.");
            }
            int remaining = ((Number) pl.get("quantity")).intValue() - ((Number) pl.get("received_qty")).intValue() - ((Number) pl.get("rejected_qty")).intValue();
            if (good + rej > remaining) {
                throw Errors.unprocessable("PO_OVER_RECEIPT", "발주 잔량보다 많이 입고할 수 없습니다. 초과분은 별도 발주·입고로 처리하세요.",
                        Map.of("remaining", remaining, "requested", good + rej));
            }
            String rejectReason = SupplierService.trim(l.rejectReason(), 300, "reject_reason");
            if (rej > 0 && rejectReason == null) {
                throw Errors.validation("reject_reason", "불량(반송) 수량이 있으면 사유를 입력하세요.");
            }
            BigDecimal poUnit = (BigDecimal) pl.get("unit_cost");
            BigDecimal unit = l.unitCost() == null ? poUnit : l.unitCost();
            if (unit.signum() < 0 || unit.stripTrailingZeros().scale() > 0) {
                throw Errors.validation("unit_cost", "실매입 단가를 원 단위로 입력하세요.");
            }
            UUID lineId = Ids.newId();
            UUID txn = null;
            if (good > 0) {
                inventory.receive(new InventoryService.Receipt(be, (UUID) pl.get("sku_id"), node, good, "SELLABLE", null, l.lotCode(), l.manufacturedOn(), l.expiresOn(),
                        l.locationCode(), "PO_RECEIPT", lineId.toString(), "발주 입고 " + po.get("po_no"), unit));
                txn = jdbc.sql("select id from inventory_transaction where workspace_id = :ws and reference_type = 'PO_RECEIPT' and reference_id = :r and txn_type = 'RECEIPT'")
                        .param("ws", a.workspaceId()).param("r", lineId.toString()).query(UUID.class).single();
            }
            jdbc.sql("""
                    insert into po_receipt_line (id, workspace_id, receipt_id, po_line_id, good_qty, rejected_qty, unit_cost, po_unit_cost, lot_code, expires_on,
                                                 inventory_txn_id, reject_reason)
                    values (:id, :ws, :r, :pl, :g, :rj, :u, :pu, cast(:lot as text), cast(:exp as date), cast(:t as uuid), cast(:rr as text))
                    """).param("id", lineId).param("ws", a.workspaceId()).param("r", receiptId).param("pl", l.poLineId()).param("g", good).param("rj", rej).param("u", unit)
                    .param("pu", poUnit).param("lot", l.lotCode()).param("exp", l.expiresOn()).param("t", txn).param("rr", rejectReason).update();
            jdbc.sql("update purchase_order_line set received_qty = received_qty + :g, rejected_qty = rejected_qty + :rj where id = :id").param("g", good).param("rj", rej)
                    .param("id", l.poLineId()).update();
            rejectedTotal += rej;
        }
        int open = jdbc.sql("select coalesce(sum(quantity - received_qty - rejected_qty), 0) from purchase_order_line where po_id = :po").param("po", id)
                .query(Integer.class).single();
        jdbc.sql("update purchase_order set status = :s, updated_at = now(), version = version + 1 where id = :id").param("s", open == 0 ? "RECEIVED" : "PARTIALLY_RECEIVED")
                .param("id", id).update();
        if (rejectedTotal > 0) {
            workItems.raise(new WorkItemService.Raise(a.workspaceId(), be, null, null, "PURCHASE_RETURN_TO_SUPPLIER", "MEDIUM", "공급처 반송: " + po.get("po_no") + " " + no,
                    "입고 검수에서 불량 " + rejectedTotal + "개를 받지 않았습니다(재고 미반영).", "공급처에 반송·재발송 또는 대금 차감을 요청하세요.",
                    "po-reject:" + receiptId, Map.of("purchase_order_id", id.toString(), "receipt_id", receiptId.toString())));
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "PURCHASE_ORDER_RECEIVED", "PURCHASE_ORDER", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).after(Map.of("receipt_no", no, "lines", in.lines().size(), "rejected", rejectedTotal, "open_qty", open)).build());
        return get(id);
    }

    // ---------------------------------------------------------------- 조회

    public List<PoView> list(List<String> businessEntityIds, String status, UUID supplierId) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "bes", bes));
        String sql = "select id from purchase_order where workspace_id = :ws and business_entity_id in (:bes)";
        if (status != null && !status.isBlank()) {
            sql += " and status = :st";
            p.put("st", status);
        }
        if (supplierId != null) {
            sql += " and supplier_id = :s";
            p.put("s", supplierId);
        }
        return jdbc.sql(sql + " order by created_at desc limit 200").params(p).query(UUID.class).list().stream().map(i -> view(i, false)).toList();
    }

    public PoView get(UUID id) {
        guard.requirePermission(Perm.INVENTORY_READ);
        UUID be = jdbc.sql("select business_entity_id from purchase_order where workspace_id = :ws and id = :id").param("ws", guard.actor().workspaceId()).param("id", id)
                .query(UUID.class).optional().orElseThrow(() -> Errors.notFound("PURCHASE_ORDER"));
        guard.checkRead("PURCHASE_ORDER", id, be, null);
        return view(id, true);
    }

    /** 입고예정(열린 발주의 남은 수량). 판매가능 재고가 아니다. */
    public List<InboundRow> inbound(List<String> businessEntityIds, UUID skuId) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "bes", bes));
        String extra = "";
        if (skuId != null) {
            extra = " and l.sku_id = :sku";
            p.put("sku", skuId);
        }
        return jdbc.sql("""
                select o.business_entity_id, l.sku_id, max(k.sku_code) as sku_code, o.node_id, sum(l.quantity - l.received_qty - l.rejected_qty) as q,
                       min(o.expected_on) as eta
                  from purchase_order o join purchase_order_line l on l.po_id = o.id join sku k on k.id = l.sku_id
                 where o.workspace_id = :ws and o.business_entity_id in (:bes) and o.status in ('PLACED', 'SUPPLIER_CONFIRMED', 'PARTIALLY_RECEIVED')""" + extra + """

                 group by o.business_entity_id, l.sku_id, o.node_id having sum(l.quantity - l.received_qty - l.rejected_qty) > 0
                """).params(p).query((rs, i) -> new InboundRow(Db.uuid(rs, "business_entity_id"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"), Db.uuid(rs, "node_id"), rs.getInt("q"),
                rs.getObject("eta", LocalDate.class))).list();
    }

    /** 발주 입고분의 재고 원장 입고 행(입고 부대비용 배부 대상, T33 연결). */
    public List<UUID> receiptTxnIds(UUID ws, UUID poId) {
        return jdbc.sql("""
                select rl.inventory_txn_id from po_receipt_line rl join po_receipt r on r.id = rl.receipt_id
                 where r.workspace_id = :ws and r.po_id = :po and rl.inventory_txn_id is not null order by r.received_at
                """).param("ws", ws).param("po", poId).query(UUID.class).list();
    }

    PoView view(UUID id, boolean detail) {
        Map<String, Object> o = jdbc.sql("""
                select o.*, be.display_name as be_name, s.name as supplier_name, n.name as node_name from purchase_order o
                  join business_entity be on be.id = o.business_entity_id join supplier s on s.id = o.supplier_id join fulfillment_node n on n.id = o.node_id
                 where o.id = :id
                """).param("id", id).query().listOfRows().get(0);
        boolean cost = guard.actor().has(Perm.COST_READ) || guard.actor().has(Perm.PURCHASE_MANAGE);
        List<LineView> lines = jdbc.sql("""
                select l.*, k.sku_code, k.name as sku_name from purchase_order_line l join sku k on k.id = l.sku_id where l.po_id = :id order by l.line_no
                """).param("id", id).query((rs, i) -> new LineView(Db.uuid(rs, "id"), rs.getInt("line_no"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"),
                rs.getString("sku_name"), rs.getInt("quantity"), cost ? rs.getBigDecimal("unit_cost") : null, rs.getString("tax_type"),
                cost ? rs.getBigDecimal("supply_amount") : null, cost ? rs.getBigDecimal("vat_amount") : null, rs.getInt("received_qty"), rs.getInt("rejected_qty"),
                rs.getInt("quantity") - rs.getInt("received_qty") - rs.getInt("rejected_qty"), rs.getString("moq_override_reason"))).list();
        BigDecimal supply = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        int ordered = 0;
        int received = 0;
        int rejected = 0;
        for (LineView l : lines) {
            if (cost) {
                supply = supply.add(l.supplyAmount());
                vat = vat.add(l.vatAmount());
            }
            ordered += l.quantity();
            received += l.receivedQty();
            rejected += l.rejectedQty();
        }
        BigDecimal variance = jdbc.sql("""
                select coalesce(sum((rl.unit_cost - rl.po_unit_cost) * rl.good_qty), 0) from po_receipt_line rl join po_receipt r on r.id = rl.receipt_id where r.po_id = :id
                """).param("id", id).query(BigDecimal.class).single();
        List<ReceiptView> receipts = !detail ? List.of() : jdbc.sql("""
                select r.*, (select u.display_name from workspace_member m join user_account u on u.id = m.user_id where m.id = r.created_by) as by_name
                  from po_receipt r where r.po_id = :id order by r.received_at
                """).param("id", id).query((rs, i) -> new ReceiptView(Db.uuid(rs, "id"), rs.getString("receipt_no"), rs.getTimestamp("received_at").toInstant(),
                rs.getString("note"), rs.getString("by_name"), List.of())).list().stream().map(r -> new ReceiptView(r.id(), r.receiptNo(), r.receivedAt(), r.note(),
                r.createdByName(), jdbc.sql("""
                        select rl.*, k.sku_code from po_receipt_line rl join purchase_order_line l on l.id = rl.po_line_id join sku k on k.id = l.sku_id
                         where rl.receipt_id = :r order by l.line_no
                        """).param("r", r.id()).query((rs, i) -> new ReceiptLineView(Db.uuid(rs, "po_line_id"), rs.getString("sku_code"), rs.getInt("good_qty"),
                        rs.getInt("rejected_qty"), cost ? rs.getBigDecimal("unit_cost") : null, cost ? rs.getBigDecimal("po_unit_cost") : null, rs.getString("lot_code"),
                        rs.getObject("expires_on", LocalDate.class), Db.uuid(rs, "inventory_txn_id"), rs.getString("reject_reason"))).list())).toList();
        return new PoView(id, (String) o.get("po_no"), (UUID) o.get("business_entity_id"), (String) o.get("be_name"), (UUID) o.get("supplier_id"),
                (String) o.get("supplier_name"), (UUID) o.get("node_id"), (String) o.get("node_name"), (String) o.get("status"),
                o.get("expected_on") == null ? null : ((java.sql.Date) o.get("expected_on")).toLocalDate(), (String) o.get("supplier_ref"), (String) o.get("memo"),
                (String) o.get("close_reason"), (UUID) o.get("approval_id"), cost ? supply : null, cost ? vat : null, ordered, received, rejected,
                cost ? variance : null, cost, lines, receipts, ((java.sql.Timestamp) o.get("created_at")).toInstant(),
                o.get("placed_at") == null ? null : ((java.sql.Timestamp) o.get("placed_at")).toInstant(), ((Number) o.get("version")).intValue());
    }

    // ---------------------------------------------------------------- 내부

    private Map<String, Object> lock(UUID ws, UUID id) {
        return jdbc.sql("select * from purchase_order where workspace_id = :ws and id = :id for update").param("ws", ws).param("id", id).query().listOfRows().stream()
                .findFirst().orElseThrow(() -> Errors.notFound("PURCHASE_ORDER"));
    }

    private static void requireVersion(Map<String, Object> po, Integer version) {
        if (version != null && version != ((Number) po.get("version")).intValue()) {
            throw Errors.conflict("VERSION_CONFLICT", "다른 사용자가 먼저 바꿨습니다. 새로 고친 뒤 다시 시도하세요.");
        }
    }

    private void requireNode(UUID be, UUID node) {
        if (node == null) {
            throw Errors.validation("node_id", "입고 거점을 선택하세요.");
        }
        Map<String, Object> n = jdbc.sql("select status, inventory_source from fulfillment_node where workspace_id = :ws and id = :id").param("ws", guard.actor().workspaceId())
                .param("id", node).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("FULFILLMENT_NODE"));
        if (!"ACTIVE".equals(n.get("status"))) {
            throw Errors.unprocessable("NODE_NOT_ACTIVE", "운영 중인 거점이 아닙니다.");
        }
        if ("EXTERNAL".equals(n.get("inventory_source"))) {
            throw Errors.unprocessable("NODE_INVENTORY_EXTERNAL", "물류사 재고 기준 거점은 물류사 입고 후 재고 동기화로 반영합니다. 자체 원장 거점을 입고 거점으로 선택하세요.");
        }
        guard.checkNode(AccessKind.WRITE, "FULFILLMENT_NODE", node, node);
    }

    private String nextNo(UUID ws, String kind, String prefix) {
        LocalDate d = LocalDate.now(ZoneId.of("Asia/Seoul"));
        int n = jdbc.sql("""
                insert into claim_number_counter (workspace_id, kind, day, last_value) values (:ws, :k, :d, 1)
                on conflict (workspace_id, kind, day) do update set last_value = claim_number_counter.last_value + 1 returning last_value
                """).param("ws", ws).param("k", kind).param("d", d).query(Integer.class).single();
        return prefix + "-" + d.toString().replace("-", "") + "-" + String.format("%04d", n);
    }
}
