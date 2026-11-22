package com.commerceos.inventory;

import com.commerceos.identity.access.AccessGuard;
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
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 수동 재고 조정(M3 I5, docs/13 §6, DLG-043). 내부 원장 거점만(물류사 재고는 임의 조정 금지).
 * 원가 영향(|수량 변화| × 기준 원가)이 사업자 승인 기준 이상이거나 원가가 없으면 승인 요청으로 만들고, 승인 전까지 재고는 바뀌지 않는다.
 * 승인자는 재고 조정 권한 + 승인 권한이 있어야 하며 자기 요청은 승인할 수 없다(공통 승인 모듈).
 */
@Service
public class AdjustmentService implements ApprovalHandler {
    public static final String REQUEST_TYPE = "INVENTORY_ADJUSTMENT";
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final InventoryLedger ledger;
    private final InventoryService inventory;
    private final NodeService nodes;
    private final CountLocks locks;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final BigDecimal defaultThreshold;

    public AdjustmentService(JdbcClient jdbc, AccessGuard guard, InventoryLedger ledger, InventoryService inventory, NodeService nodes, CountLocks locks,
                             @Lazy ApprovalService approvals, AuditService audit,
                             @Value("${commerce.inventory.adjustment-approval-threshold:100000}") BigDecimal defaultThreshold) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.ledger = ledger;
        this.inventory = inventory;
        this.nodes = nodes;
        this.locks = locks;
        this.approvals = approvals;
        this.audit = audit;
        this.defaultThreshold = defaultThreshold;
    }

    public record Adjust(UUID businessEntityId, UUID nodeId, UUID skuId, UUID stockId, String locationCode, String lotCode, String qualityStatus, String mode,
                         Integer quantity, String reason) {
    }

    public record AdjustResult(String status, UUID approvalId, UUID stockId, int before, int after, int delta, BigDecimal unitCost, BigDecimal costImpact,
                               BigDecimal approvalThreshold, String approvalReasonKo) {
    }

    public record PolicyView(UUID businessEntityId, BigDecimal approvalThreshold, boolean configured, Instant updatedAt, int version) {
    }

    public record PolicyPut(BigDecimal approvalThreshold) {
    }

    // ---------------------------------------------------------------- 승인 기준

    public PolicyView policy(UUID be) {
        guard.requirePermission(Perm.INVENTORY_READ);
        guard.checkRead("BUSINESS_ENTITY", be, be, null);
        return policyOf(guard.actor().workspaceId(), be);
    }

    /** 승인 기준 변경은 승인 권한자만(조정 담당자가 기준을 올려 승인을 피하지 못하게). */
    @Transactional
    public PolicyView putPolicy(UUID be, Integer expectedVersion, PolicyPut p) {
        guard.requirePermission(Perm.APPROVAL_DECIDE);
        CurrentActor a = guard.actor();
        guard.checkWrite("BUSINESS_ENTITY", be, be, null);
        if (p.approvalThreshold() == null || p.approvalThreshold().signum() < 0 || p.approvalThreshold().stripTrailingZeros().scale() > 0) {
            throw Errors.validation("approval_threshold", "승인 기준은 0 이상의 원 단위 금액입니다.");
        }
        PolicyView before = policyOf(a.workspaceId(), be);
        if (before.configured() && (expectedVersion == null || expectedVersion != before.version())) {
            throw Errors.versionConflict(expectedVersion == null ? 0 : expectedVersion, before.version());
        }
        jdbc.sql("""
                insert into inventory_adjustment_policy (workspace_id, business_entity_id, approval_threshold, updated_by) values (:ws, :b, :t, :by)
                on conflict (business_entity_id) do update set approval_threshold = excluded.approval_threshold, updated_by = excluded.updated_by, updated_at = now(),
                    version = inventory_adjustment_policy.version + 1
                """).param("ws", a.workspaceId()).param("b", be).param("t", p.approvalThreshold()).param("by", a.memberId()).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "INVENTORY_ADJUSTMENT_POLICY_UPDATED", "BUSINESS_ENTITY", be).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).before(Map.of("approval_threshold", before.approvalThreshold().toPlainString()))
                .after(Map.of("approval_threshold", p.approvalThreshold().toPlainString())).build());
        return policyOf(a.workspaceId(), be);
    }

    private PolicyView policyOf(UUID ws, UUID be) {
        return jdbc.sql("select approval_threshold, updated_at, version from inventory_adjustment_policy where workspace_id = :ws and business_entity_id = :b")
                .param("ws", ws).param("b", be).query((rs, i) -> new PolicyView(be, rs.getBigDecimal("approval_threshold"), true, Db.instant(rs, "updated_at"),
                        rs.getInt("version"))).optional().orElse(new PolicyView(be, defaultThreshold, false, null, 0));
    }

    // ---------------------------------------------------------------- 조정

    @Transactional
    public AdjustResult adjust(Adjust r) {
        guard.requirePermission(Perm.INVENTORY_ADJUST);
        CurrentActor a = guard.actor();
        Target t = resolve(a, r);
        if (r.quantity() == null || r.quantity() < 0 || r.quantity() > 10_000_000) {
            throw Errors.validation("quantity", "수량은 0 이상의 정수입니다.");
        }
        String mode = r.mode() == null ? "" : r.mode();
        int delta = switch (mode) {
            case "INCREASE" -> r.quantity();
            case "DECREASE" -> -r.quantity();
            case "ABSOLUTE" -> r.quantity() - t.current();
            default -> throw Errors.validation("mode", "조정 방식은 INCREASE(증가)·DECREASE(감소)·ABSOLUTE(절대수량)입니다.");
        };
        if (delta == 0) {
            throw Errors.validation("quantity", "현재 수량과 같아 조정할 차이가 없습니다.");
        }
        if (t.current() + delta < 0) {
            throw Errors.unprocessable("STOCK_INSUFFICIENT", "버킷 수량보다 많이 줄일 수 없습니다.", Map.of("on_hand", t.current(), "delta", delta));
        }
        String reason = InventoryService.trimOrNull(r.reason());
        InventoryService.requireReason(reason, true);
        locks.requireUnlocked(a.workspaceId(), t.bucket().ownerId(), t.bucket().nodeId(), t.bucket().skuId(), t.bucket().locationId(), "조정");
        Optional<BigDecimal> unit = inventory.unitCost(a.workspaceId(), t.bucket().ownerId(), t.bucket().skuId());
        BigDecimal cost = unit.map(u -> u.multiply(BigDecimal.valueOf(Math.abs(delta)))).orElse(null);
        BigDecimal threshold = policyOf(a.workspaceId(), t.bucket().ownerId()).approvalThreshold();
        boolean needsApproval = cost == null || cost.compareTo(threshold) >= 0;
        // 확보 침범은 요청 단계에서도 미리 거부(승인 후 실패를 줄임)
        if ("SELLABLE".equals(t.bucket().quality()) && delta < 0 && t.position().sellableOnHand() + delta < t.position().committed()) {
            throw Errors.conflict("STOCK_COMMITTED", "확보(예약·할당·피킹)된 수량은 줄일 수 없습니다.",
                    Map.of("sellable_on_hand", t.position().sellableOnHand(), "committed", t.position().committed()));
        }
        if (needsApproval) {
            Map<String, Object> payload = payload(t.bucket(), t.current(), delta, reason);
            Map<String, Object> impact = new LinkedHashMap<>();
            impact.put("sku_code", t.skuCode());
            impact.put("before", t.current());
            impact.put("after", t.current() + delta);
            impact.put("delta", delta);
            impact.put("unit_cost", unit.map(BigDecimal::toPlainString).orElse(null));
            impact.put("cost_impact", cost == null ? null : cost.toPlainString());
            impact.put("approval_threshold", threshold.toPlainString());
            ApprovalService.Request req = approvals.create(new ApprovalService.NewRequest(REQUEST_TYPE, "INVENTORY_BUCKET", targetKey(t.bucket()),
                    t.bucket().ownerId(), payload, impact, reason));
            return new AdjustResult("PENDING_APPROVAL", req.id(), t.stockId(), t.current(), t.current(), delta, unit.orElse(null), cost, threshold,
                    cost == null ? "기준 원가가 없어 원가 영향을 판단할 수 없으므로 승인이 필요합니다." : "원가 영향이 승인 기준 이상이라 승인이 필요합니다.");
        }
        Position after = ledger.changeBucket(a.workspaceId(), t.position(), t.bucket(), delta, "ADJUSTMENT", "MANUAL_ADJUSTMENT", null, reason, a.memberId());
        audit.record(AuditEntry.builder(a.workspaceId(), "INVENTORY_ADJUSTED", "INVENTORY_POSITION", after.id()).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(t.bucket().ownerId(), null).reason(reason).after(Map.of("delta", delta, "before", t.current(), "cost_impact", String.valueOf(cost))).build());
        if ("SELLABLE".equals(t.bucket().quality())) {
            inventory.availabilityChanged(a.workspaceId(), after);
        }
        UUID stockId = ledger.findBucket(a.workspaceId(), t.bucket()).map(Stock::id).orElse(t.stockId());
        return new AdjustResult("APPLIED", null, stockId, t.current(), t.current() + delta, delta, unit.orElse(null), cost, threshold, null);
    }

    // ---------------------------------------------------------------- 승인 처리기

    @Override
    public String requestType() {
        return REQUEST_TYPE;
    }

    /** 요청 이후 버킷 수량이 바뀌었으면 승인하지 않는다(요청 자동 취소). */
    @Override
    public boolean isCurrent(ApprovalService.Request r) {
        UUID ws = guard.actor().workspaceId();
        Bucket b = bucket(r.payload());
        ledger.lockPosition(ws, b.ownerId(), b.skuId(), b.nodeId());
        int current = ledger.findBucket(ws, b).map(Stock::onHand).orElse(0);
        return current == r.payload().get("expected_on_hand").asInt();
    }

    @Override
    public void apply(ApprovalService.Request r) {
        CurrentActor a = guard.actor();
        if (!a.has(Perm.INVENTORY_ADJUST)) {
            throw Errors.unprocessable("APPROVER_NEEDS_INVENTORY_ADJUST", "재고 조정 승인은 재고 조정 권한이 있는 승인자만 할 수 있습니다.");
        }
        Bucket b = bucket(r.payload());
        int delta = r.payload().get("delta").asInt();
        locks.requireUnlocked(a.workspaceId(), b.ownerId(), b.nodeId(), b.skuId(), b.locationId(), "조정");
        Position p = ledger.lockPosition(a.workspaceId(), b.ownerId(), b.skuId(), b.nodeId());
        Position after = ledger.changeBucket(a.workspaceId(), p, b, delta, "ADJUSTMENT", "APPROVAL_REQUEST", r.id().toString(),
                r.payload().get("reason").asString(), r.requestedBy());
        audit.record(AuditEntry.builder(a.workspaceId(), "INVENTORY_ADJUSTED", "INVENTORY_POSITION", after.id()).actor(ActorType.APPROVER, a.memberId(), a.displayName())
                .scope(b.ownerId(), null).reason(r.payload().get("reason").asString())
                .after(Map.of("delta", delta, "approval_id", r.id().toString())).build());
        if ("SELLABLE".equals(b.quality())) {
            inventory.availabilityChanged(a.workspaceId(), after);
        }
    }

    // ---------------------------------------------------------------- 내부

    record Target(Bucket bucket, Position position, int current, UUID stockId, String skuCode) {
    }

    private Target resolve(CurrentActor a, Adjust r) {
        Bucket key;
        if (r.stockId() != null) {
            Map<String, Object> s = jdbc.sql("select * from inventory_stock where workspace_id = :ws and id = :id").param("ws", a.workspaceId()).param("id", r.stockId())
                    .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("INVENTORY_STOCK"));
            key = new Bucket((UUID) s.get("owner_business_entity_id"), (UUID) s.get("sku_id"), (UUID) s.get("node_id"), (UUID) s.get("location_id"),
                    (UUID) s.get("lot_id"), (String) s.get("quality_status"), (String) s.get("hold_reason"));
        } else {
            if (r.businessEntityId() == null || r.nodeId() == null || r.skuId() == null) {
                throw Errors.validation("stock_id", "조정할 재고 버킷 또는 사업자·거점·SKU 를 지정하세요.");
            }
            UUID loc = null;
            String code = InventoryService.trimOrNull(r.locationCode());
            if (code != null) {
                loc = jdbc.sql("select id from node_location where workspace_id = :ws and node_id = :n and code = :c").param("ws", a.workspaceId())
                        .param("n", r.nodeId()).param("c", code).query(UUID.class).optional().orElseThrow(() -> Errors.validation("location_code", "거점에 없는 로케이션입니다."));
            }
            UUID lot = null;
            String lotCode = InventoryService.trimOrNull(r.lotCode());
            if (lotCode != null) {
                lot = jdbc.sql("select id from inventory_lot where sku_id = :s and lot_code = :c").param("s", r.skuId()).param("c", lotCode).query(UUID.class)
                        .optional().orElseThrow(() -> Errors.validation("lot_code", "등록되지 않은 LOT 입니다."));
            }
            String q = r.qualityStatus() == null ? "SELLABLE" : r.qualityStatus();
            if (!InventoryService.QUALITIES.contains(q)) {
                throw Errors.validation("quality_status", "품질 상태 값이 올바르지 않습니다.");
            }
            key = new Bucket(r.businessEntityId(), r.skuId(), r.nodeId(), loc, lot, q, null);
        }
        guard.checkWrite("BUSINESS_ENTITY", key.ownerId(), key.ownerId(), null);
        InventoryService.SkuInfo sku = inventory.skuInfo(key.skuId());
        if (sku.lotManaged() && key.lotId() == null) {
            throw Errors.validation("lot_code", "LOT 관리 SKU 는 LOT 를 지정해 조정하세요.");
        }
        NodeService.NodeView node = inventory.requireUsableNode(key.nodeId(), key.ownerId());
        if ("EXTERNAL".equals(node.inventorySource())) {
            throw Errors.unprocessable("NODE_INVENTORY_EXTERNAL", "물류사 재고 기준 거점의 수량은 임의로 조정할 수 없습니다. 물류사 재고 조정 후 외부 재고 동기화로 반영하세요.");
        }
        Position p = ledger.lockPosition(a.workspaceId(), key.ownerId(), key.skuId(), key.nodeId());
        Optional<Stock> s = ledger.findBucket(a.workspaceId(), key);
        return new Target(key, p, s.map(Stock::onHand).orElse(0), s.map(Stock::id).orElse(null), sku.code());
    }

    private static String targetKey(Bucket b) {
        return String.join(":", b.ownerId().toString(), b.skuId().toString(), b.nodeId().toString(), String.valueOf(b.locationId()), String.valueOf(b.lotId()),
                b.quality(), String.valueOf(b.holdReason()));
    }

    private static Map<String, Object> payload(Bucket b, int current, int delta, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("business_entity_id", b.ownerId().toString());
        m.put("sku_id", b.skuId().toString());
        m.put("node_id", b.nodeId().toString());
        m.put("location_id", b.locationId() == null ? null : b.locationId().toString());
        m.put("lot_id", b.lotId() == null ? null : b.lotId().toString());
        m.put("quality_status", b.quality());
        m.put("hold_reason", b.holdReason());
        m.put("expected_on_hand", current);
        m.put("delta", delta);
        m.put("reason", reason);
        return m;
    }

    private static Bucket bucket(JsonNode p) {
        return new Bucket(UUID.fromString(p.get("business_entity_id").asString()), UUID.fromString(p.get("sku_id").asString()),
                UUID.fromString(p.get("node_id").asString()), uuidOrNull(p.get("location_id")), uuidOrNull(p.get("lot_id")), p.get("quality_status").asString(),
                p.get("hold_reason") == null || p.get("hold_reason").isNull() ? null : p.get("hold_reason").asString());
    }

    private static UUID uuidOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : UUID.fromString(n.asString());
    }
}
