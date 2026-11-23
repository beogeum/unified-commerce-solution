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
import com.commerceos.platform.outbox.OutboxPublisher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사업자 간 재고 소유권 이전(M5 T35, docs/04 §사업자간 소유권 이전, DLG-034·173~175).
 * 거점 이동이 아니라 내부 매출·매입 거래: 요청 → 승인(요청자 ≠ 승인자, 승인자는 원가 관리 권한) → 같은 실물 위치에서 소유 사업자만 바뀐다.
 * 재고 원장: 보내는 사업자 OWNERSHIP_TRANSFER_OUT, 받는 사업자 OWNERSHIP_TRANSFER_IN(이전 단가로 이동평균 반영).
 * 재무(outbox): 보내는 사업자에 내부 매출·내부 매출원가(intercompany, 상대 사업자), 운영그룹 합계에서 제거(DLG-033). 세금계산서 발행 대상 표시(발행은 외부).
 */
@Service
public class IntercompanyTransferService implements ApprovalHandler {
    public static final String REQUEST_TYPE = "INTERCOMPANY_TRANSFER";
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final InventoryLedger ledger;
    private final InventoryService inventory;
    private final CountLocks locks;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final OutboxPublisher outbox;

    public IntercompanyTransferService(JdbcClient jdbc, AccessGuard guard, InventoryLedger ledger, InventoryService inventory, CountLocks locks,
                                       @Lazy ApprovalService approvals, AuditService audit, OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.ledger = ledger;
        this.inventory = inventory;
        this.locks = locks;
        this.approvals = approvals;
        this.audit = audit;
        this.outbox = outbox;
    }

    public record Request(UUID stockId, UUID toBusinessEntityId, Integer quantity, BigDecimal unitPrice, String taxType, String reason) {
    }

    public record TransferView(UUID id, UUID fromBusinessEntityId, String fromName, UUID toBusinessEntityId, String toName, UUID skuId, String skuCode, UUID nodeId,
                               String nodeCode, int quantity, BigDecimal unitPrice, BigDecimal supplyAmount, BigDecimal vatAmount, String taxType, BigDecimal unitCost,
                               String status, UUID approvalId, String reason, boolean taxInvoiceRequired, java.time.Instant createdAt, java.time.Instant completedAt) {
    }

    @Transactional
    public TransferView request(Request r) {
        guard.requirePermission(Perm.INVENTORY_ADJUST);
        CurrentActor a = guard.actor();
        if (r == null || r.stockId() == null || r.toBusinessEntityId() == null) {
            throw Errors.validation("stock_id", "보낼 재고 버킷과 받는 사업자를 지정하세요.");
        }
        int qty = r.quantity() == null ? 0 : r.quantity();
        if (qty < 1) {
            throw Errors.validation("quantity", "이전 수량은 1 이상입니다.");
        }
        if (r.unitPrice() == null || r.unitPrice().signum() < 0 || r.unitPrice().stripTrailingZeros().scale() > 0) {
            throw Errors.validation("unit_price", "이전 단가(공급가, 원 단위)를 입력하세요.");
        }
        String taxType = r.taxType() == null ? "TAXABLE" : r.taxType();
        if (!Set.of("TAXABLE", "TAX_FREE", "ZERO_RATED").contains(taxType)) {
            throw Errors.validation("tax_type", "과세 유형을 확인하세요.");
        }
        String reason = InventoryService.trimOrNull(r.reason());
        if (reason == null || reason.length() > 500) {
            throw Errors.validation("reason", "이전 사유를 500자 이내로 입력하세요.");
        }
        Map<String, Object> s = jdbc.sql("select * from inventory_stock where workspace_id = :ws and id = :id").param("ws", a.workspaceId()).param("id", r.stockId())
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("INVENTORY_STOCK"));
        UUID from = (UUID) s.get("owner_business_entity_id");
        UUID to = r.toBusinessEntityId();
        if (from.equals(to)) {
            throw Errors.validation("to_business_entity_id", "같은 사업자 안의 이동은 거점 이동으로 처리하세요.");
        }
        guard.checkWrite("INVENTORY_STOCK", r.stockId(), from, null);
        guard.checkWrite("BUSINESS_ENTITY", to, to, null);
        UUID nodeId = (UUID) s.get("node_id");
        guard.checkNode(AccessKind.WRITE, "INVENTORY_STOCK", r.stockId(), nodeId);
        if (jdbc.sql("select count(*) from business_entity where workspace_id = :ws and id = :id").param("ws", a.workspaceId()).param("id", to).query(Integer.class)
                .single() == 0) {
            throw Errors.notFound("BUSINESS_ENTITY");
        }
        if (!"SELLABLE".equals(s.get("quality_status"))) {
            throw Errors.unprocessable("STOCK_NOT_SELLABLE", "판매가능 재고만 다른 사업자로 이전할 수 있습니다.");
        }
        NodeService.NodeView node = inventory.requireUsableNode(nodeId, from);
        inventory.requireUsableNode(nodeId, to);
        if ("EXTERNAL".equals(node.inventorySource())) {
            throw Errors.unprocessable("NODE_INVENTORY_EXTERNAL", "물류사 재고 기준 거점은 물류사에서 화주(소유자) 변경 후 동기화로 반영합니다.");
        }
        int onHand = ((Number) s.get("on_hand")).intValue();
        if (qty > onHand) {
            throw Errors.unprocessable("STOCK_INSUFFICIENT", "버킷 수량보다 많이 이전할 수 없습니다.", Map.of("on_hand", onHand));
        }
        UUID sku = (UUID) s.get("sku_id");
        BigDecimal supply = r.unitPrice().multiply(BigDecimal.valueOf(qty));
        BigDecimal vat = "TAXABLE".equals(taxType) ? supply.divide(BigDecimal.TEN, 0, RoundingMode.DOWN) : BigDecimal.ZERO;
        Optional<BigDecimal> unitCost = inventory.unitCost(a.workspaceId(), from, sku);
        UUID id = Ids.newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transfer_id", id.toString());
        payload.put("expected_on_hand", onHand);
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("sku_code", inventory.skuInfo(sku).code());
        impact.put("quantity", qty);
        impact.put("from_on_hand", onHand + " → " + (onHand - qty));
        impact.put("internal_sales_supply", supply.toPlainString());
        impact.put("vat", vat.toPlainString());
        impact.put("book_cost", unitCost.map(u -> u.multiply(BigDecimal.valueOf(qty)).setScale(0, RoundingMode.HALF_UP).toPlainString()).orElse(null));
        impact.put("note", "실물은 같은 거점·로케이션에 그대로 있고 소유 사업자만 바뀝니다. 보내는 사업자에 내부 매출·매출원가, 받는 사업자 재고는 이전 단가로 평가합니다. "
                + "운영그룹 합계에서는 내부거래를 제거합니다. 세금계산서는 외부 회계 프로그램에서 발행하세요.");
        ApprovalService.Request req = approvals.create(new ApprovalService.NewRequest(REQUEST_TYPE, "INTERCOMPANY_TRANSFER", id.toString(), from, payload, impact, reason));
        jdbc.sql("""
                insert into intercompany_transfer (id, workspace_id, from_business_entity_id, to_business_entity_id, sku_id, node_id, from_stock_id, quantity, unit_price,
                                                   supply_amount, vat_amount, tax_type, status, approval_id, reason, created_by)
                values (:id, :ws, :f, :t, :s, :n, :st, :q, :p, :sup, :vat, :tt, 'PENDING', :ap, :r, :by)
                """).param("id", id).param("ws", a.workspaceId()).param("f", from).param("t", to).param("s", sku).param("n", nodeId).param("st", r.stockId())
                .param("q", qty).param("p", r.unitPrice()).param("sup", supply).param("vat", vat).param("tt", taxType).param("ap", req.id()).param("r", reason)
                .param("by", a.memberId()).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "INTERCOMPANY_TRANSFER_REQUESTED", "INTERCOMPANY_TRANSFER", id).actor(ActorType.USER, a.memberId(),
                a.displayName()).scope(from, null).reason(reason).after(Map.of("to", to.toString(), "quantity", qty, "supply", supply.toPlainString())).build());
        return get(id);
    }

    @Override
    public String requestType() {
        return REQUEST_TYPE;
    }

    @Override
    public boolean isCurrent(ApprovalService.Request r) {
        UUID id = UUID.fromString(r.payload().get("transfer_id").asString());
        Map<String, Object> t = jdbc.sql("select t.status, s.on_hand from intercompany_transfer t join inventory_stock s on s.id = t.from_stock_id where t.id = :id")
                .param("id", id).query().listOfRows().get(0);
        return "PENDING".equals(t.get("status")) && ((Number) t.get("on_hand")).intValue() == r.payload().get("expected_on_hand").asInt();
    }

    @Override
    public void apply(ApprovalService.Request r) {
        CurrentActor a = guard.actor();
        if (!a.has(Perm.COST_MANAGE)) {
            throw Errors.unprocessable("APPROVER_NEEDS_COST_MANAGE", "사업자 간 재고 이전 승인은 원가 관리 권한이 있는 승인자만 할 수 있습니다(내부 매출·원가 확정).");
        }
        UUID id = UUID.fromString(r.payload().get("transfer_id").asString());
        Map<String, Object> t = jdbc.sql("select * from intercompany_transfer where id = :id for update").param("id", id).query().listOfRows().get(0);
        UUID from = (UUID) t.get("from_business_entity_id");
        UUID to = (UUID) t.get("to_business_entity_id");
        UUID sku = (UUID) t.get("sku_id");
        UUID node = (UUID) t.get("node_id");
        int qty = ((Number) t.get("quantity")).intValue();
        guard.checkWrite("BUSINESS_ENTITY", from, from, null);
        guard.checkWrite("BUSINESS_ENTITY", to, to, null);
        // 집계 잠금은 id 순(교착 방지): 먼저 두 집계 행을 만들고 id 순으로 잠근다
        ledger.ensurePositions(a.workspaceId(), from, List.of(sku), node);
        ledger.ensurePositions(a.workspaceId(), to, List.of(sku), node);
        List<UUID> ids = jdbc.sql("select id from inventory_position where workspace_id = :ws and sku_id = :s and node_id = :n and owner_business_entity_id in (:o) order by id")
                .param("ws", a.workspaceId()).param("s", sku).param("n", node).param("o", List.of(from, to)).query(UUID.class).list();
        Position pFrom = null;
        Position pTo = null;
        for (UUID pid : ids) {
            Position p = ledger.lockPositionById(a.workspaceId(), pid);
            if (p.ownerId().equals(from)) {
                pFrom = p;
            } else {
                pTo = p;
            }
        }
        Stock s = ledger.lockStock(a.workspaceId(), (UUID) t.get("from_stock_id"));
        locks.requireUnlocked(a.workspaceId(), from, node, sku, s.bucket().locationId(), "사업자 간 이전");
        locks.requireUnlocked(a.workspaceId(), to, node, sku, s.bucket().locationId(), "사업자 간 이전");
        if (pFrom.sellableOnHand() - pFrom.committed() - pFrom.restrictedHold() < qty) {
            throw Errors.conflict("STOCK_COMMITTED", "확보(예약·할당)되었거나 판매 제한된 수량은 이전할 수 없습니다. 먼저 확보를 해제하세요.",
                    Map.of("atp", pFrom.sellableOnHand() - pFrom.committed() - pFrom.restrictedHold(), "requested", qty));
        }
        BigDecimal unitCost = inventory.unitCost(a.workspaceId(), from, sku).orElse(null);
        String ref = id.toString();
        String reason = (String) t.get("reason");
        Position afterFrom = ledger.changeBucket(a.workspaceId(), pFrom, s.bucket(), -qty, "OWNERSHIP_TRANSFER_OUT", "INTERCOMPANY_TRANSFER", ref, reason, r.requestedBy());
        Bucket target = new Bucket(to, sku, node, s.bucket().locationId(), s.bucket().lotId(), "SELLABLE", null);
        Position afterTo = ledger.changeBucket(a.workspaceId(), pTo, target, qty, "OWNERSHIP_TRANSFER_IN", "INTERCOMPANY_TRANSFER", ref, reason, r.requestedBy());
        UUID toStock = ledger.findBucket(a.workspaceId(), target).map(Stock::id).orElse(null);
        inventory.updateMovingCostForTransfer(a.workspaceId(), to, sku, qty, new BigDecimal(t.get("unit_price").toString()));
        jdbc.sql("""
                update intercompany_transfer set status = 'COMPLETED', unit_cost = cast(:uc as numeric), to_stock_id = :ts, completed_at = now(), version = version + 1
                 where id = :id
                """).param("uc", unitCost).param("ts", toStock).param("id", id).update();
        inventory.availabilityChanged(a.workspaceId(), afterFrom);
        inventory.availabilityChanged(a.workspaceId(), afterTo);
        outbox.publish(a.workspaceId(), from, "INTERCOMPANY_TRANSFER", id, "IntercompanyTransferCompleted", Map.of("transfer_id", id.toString()));
        audit.record(AuditEntry.builder(a.workspaceId(), "INTERCOMPANY_TRANSFER_COMPLETED", "INTERCOMPANY_TRANSFER", id).actor(ActorType.APPROVER, a.memberId(),
                a.displayName()).scope(from, null).reason(reason).after(Map.of("to", to.toString(), "quantity", qty, "approval_id", r.id().toString())).build());
    }

    public List<TransferView> list(List<String> businessEntityIds) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " where t.workspace_id = :ws and (t.from_business_entity_id in (:bes) or t.to_business_entity_id in (:bes)) order by t.created_at desc limit 200")
                .param("ws", guard.actor().workspaceId()).param("bes", bes).query(this::map).list();
    }

    public TransferView get(UUID id) {
        guard.requirePermission(Perm.INVENTORY_READ);
        TransferView v = jdbc.sql(SELECT + " where t.workspace_id = :ws and t.id = :id").param("ws", guard.actor().workspaceId()).param("id", id).query(this::map)
                .optional().orElseThrow(() -> Errors.notFound("INTERCOMPANY_TRANSFER"));
        Set<UUID> readable = guard.businessEntityFilter(AccessKind.READ, null);
        if (!readable.contains(v.fromBusinessEntityId()) && !readable.contains(v.toBusinessEntityId())) {
            throw Errors.notFound("INTERCOMPANY_TRANSFER");
        }
        return v;
    }

    static final String SELECT = """
            select t.*, fb.display_name as from_name, tb.display_name as to_name, k.sku_code, n.code as node_code,
                   case when t.status = 'PENDING' and ar.status = 'REJECTED' then 'REJECTED' when t.status = 'PENDING' and ar.status = 'CANCELLED' then 'CANCELLED'
                        else t.status end as effective_status
              from intercompany_transfer t left join approval_request ar on ar.id = t.approval_id
              join business_entity fb on fb.id = t.from_business_entity_id join business_entity tb on tb.id = t.to_business_entity_id
              join sku k on k.id = t.sku_id join fulfillment_node n on n.id = t.node_id
            """;

    private TransferView map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new TransferView(Db.uuid(rs, "id"), Db.uuid(rs, "from_business_entity_id"), rs.getString("from_name"), Db.uuid(rs, "to_business_entity_id"),
                rs.getString("to_name"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"), Db.uuid(rs, "node_id"), rs.getString("node_code"), rs.getInt("quantity"),
                rs.getBigDecimal("unit_price"), rs.getBigDecimal("supply_amount"), rs.getBigDecimal("vat_amount"), rs.getString("tax_type"), rs.getBigDecimal("unit_cost"),
                rs.getString("effective_status"), Db.uuid(rs, "approval_id"), rs.getString("reason"), true, rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    }
}
