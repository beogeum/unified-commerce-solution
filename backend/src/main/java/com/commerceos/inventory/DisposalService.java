package com.commerceos.inventory;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.inventory.InventoryLedger.Bucket;
import com.commerceos.inventory.InventoryLedger.Position;
import com.commerceos.inventory.InventoryLedger.Stock;
import com.commerceos.operations.approval.ApprovalHandler;
import com.commerceos.operations.approval.ApprovalService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 폐기(M4 O6, FR-INV-05, docs/13 §5, DLG-145). 비가용 버킷(폐기대기·불량·파손·만료)만, 항상 승인(요청자 ≠ 승인자, 승인자는 폐기 권한).
 * 승인 시 원장 DISPOSAL 로 실물 −Q. 장부가치·평가손실 반영은 재무(M5 N5)가 이 원장을 기준으로 한 번만 인식한다(이중 손실 금지).
 * 물류사 재고 기준 거점은 물류사 폐기 후 동기화로 반영하므로 여기서 폐기하지 않는다.
 */
@Service
public class DisposalService implements ApprovalHandler {
    public static final String REQUEST_TYPE = "INVENTORY_DISPOSAL";
    static final Set<String> DISPOSABLE = Set.of("DISPOSAL_PENDING", "DEFECTIVE", "DAMAGED", "EXPIRED");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final InventoryLedger ledger;
    private final InventoryService inventory;
    private final CountLocks locks;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final com.commerceos.platform.outbox.OutboxPublisher outbox;

    public DisposalService(JdbcClient jdbc, AccessGuard guard, InventoryLedger ledger, InventoryService inventory, CountLocks locks, @Lazy ApprovalService approvals,
                           AuditService audit, com.commerceos.platform.outbox.OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.ledger = ledger;
        this.inventory = inventory;
        this.locks = locks;
        this.approvals = approvals;
        this.audit = audit;
        this.outbox = outbox;
    }

    public record Request(UUID stockId, Integer quantity, String reason, String evidenceRef) {
    }

    public record Requested(UUID approvalId, String skuCode, String qualityStatus, int quantity, BigDecimal unitCost, BigDecimal bookValue) {
    }

    @Transactional
    public Requested request(Request r) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        if (r == null || r.stockId() == null) {
            throw com.commerceos.shared.error.Errors.validation("stock_id", "폐기할 재고 버킷을 지정하세요.");
        }
        int qty = r.quantity() == null ? 0 : r.quantity();
        String reason = InventoryService.trimOrNull(r.reason());
        if (qty < 1) {
            throw com.commerceos.shared.error.Errors.validation("quantity", "폐기 수량은 1 이상입니다.");
        }
        if (reason == null || reason.length() > 500) {
            throw com.commerceos.shared.error.Errors.validation("reason", "폐기 사유를 500자 이내로 입력하세요.");
        }
        Map<String, Object> s = jdbc.sql("select * from inventory_stock where workspace_id = :ws and id = :id").param("ws", a.workspaceId()).param("id", r.stockId())
                .query().listOfRows().stream().findFirst().orElseThrow(() -> com.commerceos.shared.error.Errors.notFound("INVENTORY_STOCK"));
        Bucket b = new Bucket((UUID) s.get("owner_business_entity_id"), (UUID) s.get("sku_id"), (UUID) s.get("node_id"), (UUID) s.get("location_id"),
                (UUID) s.get("lot_id"), (String) s.get("quality_status"), (String) s.get("hold_reason"));
        guard.checkWrite("INVENTORY_STOCK", r.stockId(), b.ownerId(), null);
        guard.checkNode(AccessKind.WRITE, "INVENTORY_STOCK", r.stockId(), b.nodeId());
        if (!DISPOSABLE.contains(b.quality())) {
            throw com.commerceos.shared.error.Errors.unprocessable("STOCK_NOT_DISPOSABLE",
                    "폐기대기·불량·파손·만료 재고만 폐기할 수 있습니다. 먼저 품질 상태를 바꾸세요.", Map.of("quality_status", b.quality()));
        }
        NodeService.NodeView node = inventory.requireUsableNode(b.nodeId(), b.ownerId());
        if ("EXTERNAL".equals(node.inventorySource())) {
            throw com.commerceos.shared.error.Errors.unprocessable("NODE_INVENTORY_EXTERNAL", "물류사 재고 기준 거점은 물류사에서 폐기한 뒤 외부 재고 동기화로 반영합니다.");
        }
        int onHand = ((Number) s.get("on_hand")).intValue();
        if (qty > onHand) {
            throw com.commerceos.shared.error.Errors.unprocessable("STOCK_INSUFFICIENT", "버킷 수량보다 많이 폐기할 수 없습니다.", Map.of("on_hand", onHand));
        }
        InventoryService.SkuInfo sku = inventory.skuInfo(b.skuId());
        Optional<BigDecimal> unit = inventory.unitCost(a.workspaceId(), b.ownerId(), b.skuId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stock_id", r.stockId().toString());
        payload.put("business_entity_id", b.ownerId().toString());
        payload.put("sku_id", b.skuId().toString());
        payload.put("node_id", b.nodeId().toString());
        payload.put("location_id", b.locationId() == null ? null : b.locationId().toString());
        payload.put("lot_id", b.lotId() == null ? null : b.lotId().toString());
        payload.put("quality_status", b.quality());
        payload.put("hold_reason", b.holdReason());
        payload.put("expected_on_hand", onHand);
        payload.put("quantity", qty);
        payload.put("reason", reason);
        payload.put("evidence_ref", InventoryService.trimOrNull(r.evidenceRef()));
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("sku_code", sku.code());
        impact.put("quality_status", b.quality());
        impact.put("before", onHand);
        impact.put("after", onHand - qty);
        impact.put("unit_cost", unit.map(BigDecimal::toPlainString).orElse(null));
        impact.put("book_value", unit.map(u -> u.multiply(BigDecimal.valueOf(qty)).toPlainString()).orElse(null));
        impact.put("valuation_note", "재고평가손실을 이미 인식한 수량은 폐기 시 다시 손실로 잡지 않습니다(재무 원장에서 대사).");
        ApprovalService.Request req = approvals.create(new ApprovalService.NewRequest(REQUEST_TYPE, "INVENTORY_STOCK", r.stockId().toString(), b.ownerId(), payload,
                impact, reason));
        return new Requested(req.id(), sku.code(), b.quality(), qty, unit.orElse(null), unit.map(u -> u.multiply(BigDecimal.valueOf(qty))).orElse(null));
    }

    @Override
    public String requestType() {
        return REQUEST_TYPE;
    }

    @Override
    public boolean isCurrent(ApprovalService.Request r) {
        UUID ws = guard.actor().workspaceId();
        UUID stockId = UUID.fromString(r.payload().get("stock_id").asString());
        Integer onHand = jdbc.sql("select on_hand from inventory_stock where workspace_id = :ws and id = :id").param("ws", ws).param("id", stockId).query(Integer.class)
                .optional().orElse(0);
        return onHand == r.payload().get("expected_on_hand").asInt();
    }

    @Override
    public void apply(ApprovalService.Request r) {
        CurrentActor a = guard.actor();
        if (!a.has(Perm.INVENTORY_DISPOSE)) {
            throw com.commerceos.shared.error.Errors.unprocessable("APPROVER_NEEDS_INVENTORY_DISPOSE", "재고 폐기 승인은 재고 폐기 권한이 있는 승인자만 할 수 있습니다.");
        }
        UUID stockId = UUID.fromString(r.payload().get("stock_id").asString());
        int qty = r.payload().get("quantity").asInt();
        Map<String, Object> head = jdbc.sql("select owner_business_entity_id, sku_id, node_id from inventory_stock where workspace_id = :ws and id = :id")
                .param("ws", a.workspaceId()).param("id", stockId).query().listOfRows().get(0);
        UUID owner = (UUID) head.get("owner_business_entity_id");
        guard.checkNode(AccessKind.WRITE, "INVENTORY_STOCK", stockId, (UUID) head.get("node_id"));
        Position p = ledger.lockPosition(a.workspaceId(), owner, (UUID) head.get("sku_id"), (UUID) head.get("node_id"));
        Stock s = ledger.lockStock(a.workspaceId(), stockId);
        locks.requireUnlocked(a.workspaceId(), owner, s.bucket().nodeId(), s.bucket().skuId(), s.bucket().locationId(), "폐기");
        String reason = r.payload().get("reason").asString();
        ledger.changeBucket(a.workspaceId(), p, s.bucket(), -qty, "DISPOSAL", "APPROVAL_REQUEST", r.id().toString(), reason, r.requestedBy());
        // 재무(M5): 장부가 기준 폐기손실(이미 평가손실·장부가 0 인 수량은 다시 잡지 않음)
        outbox.publish(a.workspaceId(), owner, "INVENTORY_STOCK", stockId, "InventoryDisposed", Map.of("approval_id", r.id().toString(), "stock_id", stockId.toString(),
                "quantity", qty));
        audit.record(AuditEntry.builder(a.workspaceId(), "INVENTORY_DISPOSED", "INVENTORY_STOCK", stockId).actor(ActorType.APPROVER, a.memberId(), a.displayName())
                .scope(owner, null).reason(reason).after(Map.of("quantity", qty, "approval_id", r.id().toString(), "quality_status", s.bucket().quality())).build());
    }
}
