package com.commerceos.inventory;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.inventory.InventoryLedger.Bucket;
import com.commerceos.inventory.InventoryLedger.Position;
import com.commerceos.inventory.InventoryLedger.Stock;
import com.commerceos.inventory.InventoryLedger.Txn;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.outbox.OutboxPublisher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.PageQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 버킷·집계·원장(M3 I2). 재고 소유는 사업자, 위치는 거점·로케이션·LOT·품질로 구분한다.
 * 판매 가능 재고(ATP) = 판매가능 실물 − 확보수량(RESERVED+ALLOCATED+PICKING). 사업자 전체 ATP 는 안전재고를 뺀다.
 */
@Service
public class InventoryService {
    static final Set<String> QUALITIES = Set.of("SELLABLE", "PENDING_INSPECTION", "REPACK", "DEFECTIVE", "DAMAGED", "EXPIRED", "DISPOSAL_PENDING", "UNDETERMINED");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final InventoryLedger ledger;
    private final NodeService nodes;

    public InventoryService(JdbcClient jdbc, AccessGuard guard, AuditService audit, OutboxPublisher outbox, InventoryLedger ledger, NodeService nodes) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.outbox = outbox;
        this.ledger = ledger;
        this.nodes = nodes;
    }

    public record Receipt(UUID businessEntityId, UUID skuId, UUID nodeId, Integer quantity, String qualityStatus, String holdReason, String lotCode,
                          LocalDate manufacturedOn, LocalDate expiresOn, String locationCode, String referenceType, String referenceId, String reason) {
    }

    public record QualityChange(UUID stockId, Integer quantity, String toQualityStatus, String toHoldReason, String reason) {
    }

    public record StockView(UUID id, UUID businessEntityId, String businessEntityName, UUID skuId, String skuCode, String skuName, UUID nodeId, String nodeCode,
                            String locationCode, String lotCode, LocalDate expiresOn, String qualityStatus, String holdReason, int onHand, Instant updatedAt) {
    }

    public record PositionView(UUID id, UUID businessEntityId, String businessEntityName, UUID skuId, String skuCode, String skuName, UUID nodeId, String nodeCode,
                               String nodeName, String inventorySource, int sellableOnHand, int committed, int reserved, int allocated, int picking, int atp,
                               int nonSellableOnHand, Instant updatedAt) {
    }

    public record TxnView(UUID id, Instant occurredAt, String txnType, UUID businessEntityId, String businessEntityName, UUID skuId, String skuCode, UUID nodeId,
                          String nodeCode, UUID stockId, String qualityStatus, int onHandDelta, int sellableDelta, int committedDelta, UUID reservationId,
                          String reservationStateFrom, String reservationStateTo, String referenceType, String referenceId, String reason, String actorName) {
    }

    public record NodeAvailability(UUID nodeId, String nodeCode, String nodeName, int sellableOnHand, int committed, int atp) {
    }

    public record Availability(UUID businessEntityId, UUID skuId, String skuCode, int safetyStock, int sellableOnHand, int committed, int atp,
                               List<NodeAvailability> nodes) {
    }

    public record Filter(List<String> businessEntityIds, UUID nodeId, UUID skuId, String q, String qualityStatus, String txnType, Boolean nonZero) {
    }

    public record Mismatch(String check, UUID positionId, UUID businessEntityId, UUID skuId, UUID nodeId, long expected, long actual, String messageKo) {
    }

    // ---------------------------------------------------------------- 입고

    @Transactional
    public StockView receive(Receipt r) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        if (r.businessEntityId() == null || r.skuId() == null || r.nodeId() == null) {
            throw Errors.validation("business_entity_id", "소유 사업자·SKU·거점을 모두 지정하세요.");
        }
        guard.checkWrite("BUSINESS_ENTITY", r.businessEntityId(), r.businessEntityId(), null);
        int qty = positive(r.quantity());
        String quality = r.qualityStatus() == null ? "SELLABLE" : r.qualityStatus();
        String hold = normalizeHold(quality, r.holdReason());
        var sku = skuInfo(r.skuId());
        NodeService.NodeView node = requireUsableNode(r.nodeId(), r.businessEntityId());
        if ("EXTERNAL".equals(node.inventorySource())) {
            throw Errors.unprocessable("NODE_INVENTORY_EXTERNAL", "이 거점은 물류사 재고가 기준입니다. 수동 입고 대신 물류사 재고 동기화로 반영하세요.");
        }
        UUID lotId = resolveLot(a.workspaceId(), sku, r.lotCode(), r.manufacturedOn(), r.expiresOn());
        UUID locationId = resolveLocation(a.workspaceId(), node, r.locationCode());
        requireReason(r.reason(), false);

        Position p = ledger.lockPosition(a.workspaceId(), r.businessEntityId(), r.skuId(), r.nodeId());
        UUID stockId = ledger.addToBucket(a.workspaceId(), new Bucket(r.businessEntityId(), r.skuId(), r.nodeId(), locationId, lotId, quality, hold), qty);
        int sellable = "SELLABLE".equals(quality) ? qty : 0;
        ledger.applyToPosition(p, sellable, 0);
        ledger.record(a.workspaceId(), new Txn("RECEIPT", p, stockId, quality, qty, sellable, 0, null, null, null,
                trimOrNull(r.referenceType()), trimOrNull(r.referenceId()), trimOrNull(r.reason()), a.memberId()));
        if (sellable > 0) {
            availabilityChanged(a.workspaceId(), p);
        }
        return stock(stockId);
    }

    // ---------------------------------------------------------------- 품질 변경(보류·검수·불량 등)

    @Transactional
    public List<StockView> changeQuality(QualityChange c) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        if (c.stockId() == null) {
            throw Errors.validation("stock_id", "재고 버킷을 지정하세요.");
        }
        int qty = positive(c.quantity());
        requireReason(c.reason(), true);
        if (c.toQualityStatus() == null || !QUALITIES.contains(c.toQualityStatus())) {
            throw Errors.validation("to_quality_status", "변경할 품질 상태를 선택하세요.");
        }
        String toHold = normalizeHold(c.toQualityStatus(), c.toHoldReason());
        // 잠금 순서: position → stock
        Map<String, Object> head = jdbc.sql("select owner_business_entity_id, sku_id, node_id from inventory_stock where workspace_id = :ws and id = :id")
                .param("ws", a.workspaceId()).param("id", c.stockId()).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("INVENTORY_STOCK"));
        UUID owner = (UUID) head.get("owner_business_entity_id");
        guard.checkWrite("INVENTORY_STOCK", c.stockId(), owner, null);
        Position p = ledger.lockPosition(a.workspaceId(), owner, (UUID) head.get("sku_id"), (UUID) head.get("node_id"));
        Stock s = ledger.lockStock(a.workspaceId(), c.stockId());
        NodeService.NodeView node = nodes.find(s.bucket().nodeId()).orElseThrow();
        if ("EXTERNAL".equals(node.inventorySource())) {
            throw Errors.unprocessable("NODE_INVENTORY_EXTERNAL", "물류사 재고가 기준인 거점은 물류사 동기화로만 품질이 바뀝니다.");
        }
        if (qty > s.onHand()) {
            throw Errors.unprocessable("STOCK_INSUFFICIENT", "버킷 수량보다 많이 옮길 수 없습니다.", Map.of("on_hand", s.onHand(), "requested", qty));
        }
        Bucket from = s.bucket();
        if (from.quality().equals(c.toQualityStatus()) && java.util.Objects.equals(from.holdReason(), toHold)) {
            throw Errors.validation("to_quality_status", "현재와 같은 품질 상태입니다.");
        }
        boolean fromSellable = "SELLABLE".equals(from.quality());
        boolean toSellable = "SELLABLE".equals(c.toQualityStatus());
        int sellableDelta = (toSellable ? qty : 0) - (fromSellable ? qty : 0);
        if (sellableDelta < 0 && p.sellableOnHand() + sellableDelta < p.committed()) {
            throw Errors.conflict("STOCK_COMMITTED", "확보(예약·할당·피킹)된 수량은 판매 불가로 돌릴 수 없습니다. 먼저 확보를 해제하거나 다른 거점으로 배정하세요.",
                    Map.of("sellable_on_hand", p.sellableOnHand(), "committed", p.committed(), "movable", Math.max(0, p.sellableOnHand() - p.committed())));
        }
        ledger.removeFromBucket(s.id(), qty);
        UUID toId = ledger.addToBucket(a.workspaceId(), new Bucket(from.ownerId(), from.skuId(), from.nodeId(), from.locationId(), from.lotId(),
                c.toQualityStatus(), toHold), qty);
        ledger.applyToPosition(p, sellableDelta, 0);
        String reason = c.reason().trim();
        ledger.record(a.workspaceId(), new Txn("QUALITY_CHANGE", p, s.id(), from.quality(), -qty, fromSellable ? -qty : 0, 0, null, null, null,
                "INVENTORY_STOCK", toId.toString(), reason, a.memberId()));
        ledger.record(a.workspaceId(), new Txn("QUALITY_CHANGE", p, toId, c.toQualityStatus(), qty, toSellable ? qty : 0, 0, null, null, null,
                "INVENTORY_STOCK", s.id().toString(), reason, a.memberId()));
        audit.record(AuditEntry.builder(a.workspaceId(), "INVENTORY_QUALITY_CHANGED", "INVENTORY_STOCK", s.id())
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(owner, null).reason(reason)
                .before(Map.of("quality_status", from.quality(), "hold_reason", String.valueOf(from.holdReason())))
                .after(Map.of("quality_status", c.toQualityStatus(), "hold_reason", String.valueOf(toHold), "quantity", qty, "to_stock_id", toId.toString())).build());
        if (sellableDelta != 0) {
            availabilityChanged(a.workspaceId(), p);
        }
        return List.of(stock(s.id()), stock(toId));
    }

    // ---------------------------------------------------------------- 조회

    public List<PositionView> positions(Filter f) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, f.businessEntityIds());
        if (bes.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                select p.*, be.display_name as be_name, s.sku_code, coalesce(s.name, pr.name) as sku_name, n.code as node_code, n.name as node_name,
                       n.inventory_source,
                       (select coalesce(sum(st.on_hand), 0) from inventory_stock st where st.owner_business_entity_id = p.owner_business_entity_id
                           and st.sku_id = p.sku_id and st.node_id = p.node_id and st.quality_status <> 'SELLABLE') as non_sellable,
                       (select coalesce(sum(quantity) filter (where state = 'RESERVED'), 0) from inventory_reservation r where r.position_id = p.id) as q_reserved,
                       (select coalesce(sum(quantity) filter (where state = 'ALLOCATED'), 0) from inventory_reservation r where r.position_id = p.id) as q_allocated,
                       (select coalesce(sum(quantity) filter (where state = 'PICKING'), 0) from inventory_reservation r where r.position_id = p.id) as q_picking
                  from inventory_position p join business_entity be on be.id = p.owner_business_entity_id
                  join sku s on s.id = p.sku_id join product pr on pr.id = s.product_id join fulfillment_node n on n.id = p.node_id
                 where p.workspace_id = :ws and p.owner_business_entity_id in (:bes)
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ws", ws());
        params.put("bes", bes);
        common(sql, params, f, "p");
        if (Boolean.TRUE.equals(f.nonZero())) {
            sql.append(" and (p.sellable_on_hand > 0 or p.committed > 0 or exists (select 1 from inventory_stock st where st.owner_business_entity_id = p.owner_business_entity_id and st.sku_id = p.sku_id and st.node_id = p.node_id and st.on_hand > 0))");
        }
        sql.append(" order by s.sku_code, n.code, be.display_name limit 500");
        return jdbc.sql(sql.toString()).params(params).query((rs, i) -> new PositionView(Db.uuid(rs, "id"), Db.uuid(rs, "owner_business_entity_id"),
                rs.getString("be_name"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"), rs.getString("sku_name"), Db.uuid(rs, "node_id"), rs.getString("node_code"),
                rs.getString("node_name"), rs.getString("inventory_source"), rs.getInt("sellable_on_hand"), rs.getInt("committed"), rs.getInt("q_reserved"),
                rs.getInt("q_allocated"), rs.getInt("q_picking"), rs.getInt("sellable_on_hand") - rs.getInt("committed"), rs.getInt("non_sellable"),
                Db.instant(rs, "updated_at"))).list();
    }

    public List<StockView> stocks(Filter f) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, f.businessEntityIds());
        if (bes.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(STOCK_SELECT + " where st.workspace_id = :ws and st.owner_business_entity_id in (:bes) and st.on_hand > 0");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ws", ws());
        params.put("bes", bes);
        common(sql, params, f, "st");
        if (f.qualityStatus() != null && !f.qualityStatus().isBlank()) {
            sql.append(" and st.quality_status = :qs");
            params.put("qs", f.qualityStatus());
        }
        sql.append(" order by s.sku_code, n.code, l.expires_on nulls last, st.quality_status limit 500");
        return jdbc.sql(sql.toString()).params(params).query((rs, i) -> stockView(rs)).list();
    }

    public ApiResponse<List<TxnView>> transactions(Filter f, PageQuery page) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, f.businessEntityIds());
        if (bes.isEmpty()) {
            return ApiResponse.page(List.of(), null);
        }
        StringBuilder sql = new StringBuilder("""
                select t.*, be.display_name as be_name, s.sku_code, n.code as node_code, u.display_name as actor_name
                  from inventory_transaction t join business_entity be on be.id = t.owner_business_entity_id join sku s on s.id = t.sku_id
                  join product pr on pr.id = s.product_id join fulfillment_node n on n.id = t.node_id left join workspace_member m on m.workspace_id = t.workspace_id and m.id = t.actor_member_id
                  left join user_account u on u.id = m.user_id
                 where t.workspace_id = :ws and t.owner_business_entity_id in (:bes)
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ws", ws());
        params.put("bes", bes);
        common(sql, params, f, "t");
        if (f.txnType() != null && !f.txnType().isBlank()) {
            sql.append(" and t.txn_type = :tt");
            params.put("tt", f.txnType());
        }
        if (page.after() != null) {
            sql.append(" and t.id < :after");
            params.put("after", page.after());
        }
        sql.append(" order by t.id desc limit ").append(page.fetchSize());
        List<TxnView> rows = jdbc.sql(sql.toString()).params(params).query((rs, i) -> new TxnView(Db.uuid(rs, "id"), Db.instant(rs, "occurred_at"),
                rs.getString("txn_type"), Db.uuid(rs, "owner_business_entity_id"), rs.getString("be_name"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"),
                Db.uuid(rs, "node_id"), rs.getString("node_code"), Db.uuid(rs, "stock_id"), rs.getString("quality_status"), rs.getInt("on_hand_delta"),
                rs.getInt("sellable_delta"), rs.getInt("committed_delta"), Db.uuid(rs, "reservation_id"), rs.getString("reservation_state_from"),
                rs.getString("reservation_state_to"), rs.getString("reference_type"), rs.getString("reference_id"), rs.getString("reason"),
                rs.getString("actor_name"))).list();
        return page.toResponse(rows, TxnView::id);
    }

    /** 사업자·SKU 의 거점별·전체 ATP. 전체 ATP 는 사업자 SKU 정책의 안전재고를 뺀 값(음수면 0). */
    public Availability availability(UUID businessEntityId, UUID skuId) {
        guard.requirePermission(Perm.INVENTORY_READ);
        guard.checkRead("BUSINESS_ENTITY", businessEntityId, businessEntityId, null);
        var sku = skuInfo(skuId);
        return availabilityOf(ws(), businessEntityId, skuId, sku.code());
    }

    Availability availabilityOf(UUID ws, UUID businessEntityId, UUID skuId, String skuCode) {
        int safety = safetyStock(ws, businessEntityId, skuId);
        List<NodeAvailability> per = jdbc.sql("""
                select p.node_id, n.code, n.name, p.sellable_on_hand, p.committed from inventory_position p join fulfillment_node n on n.id = p.node_id
                 where p.workspace_id = :ws and p.owner_business_entity_id = :o and p.sku_id = :s order by n.code
                """).param("ws", ws).param("o", businessEntityId).param("s", skuId).query((rs, i) -> new NodeAvailability(Db.uuid(rs, "node_id"),
                rs.getString("code"), rs.getString("name"), rs.getInt("sellable_on_hand"), rs.getInt("committed"),
                rs.getInt("sellable_on_hand") - rs.getInt("committed"))).list();
        int sellable = per.stream().mapToInt(NodeAvailability::sellableOnHand).sum();
        int committed = per.stream().mapToInt(NodeAvailability::committed).sum();
        return new Availability(businessEntityId, skuId, skuCode, safety, sellable, committed, Math.max(0, sellable - committed - safety), per);
    }

    int safetyStock(UUID ws, UUID businessEntityId, UUID skuId) {
        return jdbc.sql("select safety_stock from business_sku_policy where workspace_id = :ws and business_entity_id = :b and sku_id = :s")
                .param("ws", ws).param("b", businessEntityId).param("s", skuId).query(Integer.class).optional().orElse(0);
    }

    /**
     * 재고 검증(I6 검증센터의 기초). 집계 = 버킷 합, 확보 = 활성 예약 합, 버킷 = 원장 합. 불일치 0건이 정상.
     * 사용자가 볼 수 있는 사업자만 검사한다.
     */
    public List<Mismatch> verify() {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, null);
        if (bes.isEmpty()) {
            return List.of();
        }
        List<Mismatch> out = new ArrayList<>();
        out.addAll(jdbc.sql("""
                select p.id, p.owner_business_entity_id, p.sku_id, p.node_id, p.sellable_on_hand,
                       coalesce((select sum(on_hand) from inventory_stock s where s.owner_business_entity_id = p.owner_business_entity_id and s.sku_id = p.sku_id
                                 and s.node_id = p.node_id and s.quality_status = 'SELLABLE'), 0) as bucket_sum
                  from inventory_position p where p.workspace_id = :ws and p.owner_business_entity_id in (:bes)
                """).param("ws", ws()).param("bes", bes).query((rs, i) -> mismatch(rs, "POSITION_VS_BUCKETS", rs.getLong("sellable_on_hand"), rs.getLong("bucket_sum"),
                "판매가능 집계와 판매가능 버킷 합계가 다릅니다.")).list().stream().filter(m -> m.expected() != m.actual()).toList());
        out.addAll(jdbc.sql("""
                select p.id, p.owner_business_entity_id, p.sku_id, p.node_id, p.committed,
                       coalesce((select sum(quantity) from inventory_reservation r where r.position_id = p.id and r.state in ('RESERVED','ALLOCATED','PICKING')), 0) as active_sum
                  from inventory_position p where p.workspace_id = :ws and p.owner_business_entity_id in (:bes)
                """).param("ws", ws()).param("bes", bes).query((rs, i) -> mismatch(rs, "COMMITTED_VS_RESERVATIONS", rs.getLong("committed"), rs.getLong("active_sum"),
                "확보수량과 활성 예약 합계가 다릅니다.")).list().stream().filter(m -> m.expected() != m.actual()).toList());
        out.addAll(jdbc.sql("""
                select p.id, p.owner_business_entity_id, p.sku_id, p.node_id,
                       coalesce((select sum(on_hand) from inventory_stock s where s.owner_business_entity_id = p.owner_business_entity_id and s.sku_id = p.sku_id
                                 and s.node_id = p.node_id), 0) as bucket_sum,
                       coalesce((select sum(on_hand_delta) from inventory_transaction t where t.position_id = p.id), 0) as ledger_sum
                  from inventory_position p where p.workspace_id = :ws and p.owner_business_entity_id in (:bes)
                """).param("ws", ws()).param("bes", bes).query((rs, i) -> mismatch(rs, "BUCKETS_VS_LEDGER", rs.getLong("ledger_sum"), rs.getLong("bucket_sum"),
                "실물 버킷 합계와 원장 누적이 다릅니다.")).list().stream().filter(m -> m.expected() != m.actual()).toList());
        out.addAll(jdbc.sql("""
                select p.id, p.owner_business_entity_id, p.sku_id, p.node_id, p.committed,
                       coalesce((select sum(committed_delta) from inventory_transaction t where t.position_id = p.id), 0) as ledger_sum
                  from inventory_position p where p.workspace_id = :ws and p.owner_business_entity_id in (:bes)
                """).param("ws", ws()).param("bes", bes).query((rs, i) -> mismatch(rs, "COMMITTED_VS_LEDGER", rs.getLong("ledger_sum"), rs.getLong("committed"),
                "확보수량과 원장 누적이 다릅니다.")).list().stream().filter(m -> m.expected() != m.actual()).toList());
        return out;
    }

    // ---------------------------------------------------------------- 내부

    record SkuInfo(UUID id, String code, boolean lotManaged, boolean expiryManaged) {
    }

    SkuInfo skuInfo(UUID skuId) {
        Map<String, Object> s = jdbc.sql("select id, sku_code, inventory_managed, lot_managed, expiry_managed, status from sku where workspace_id = :ws and id = :id")
                .param("ws", ws()).param("id", skuId).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SKU"));
        if (!(Boolean) s.get("inventory_managed")) {
            throw Errors.unprocessable("SKU_NOT_INVENTORY_MANAGED", "재고관리 대상이 아닌 SKU 입니다(가상묶음은 구성부품 재고로 계산).");
        }
        if ("ARCHIVED".equals(s.get("status"))) {
            throw Errors.unprocessable("SKU_ARCHIVED", "보관 처리된 SKU 입니다.");
        }
        return new SkuInfo(skuId, (String) s.get("sku_code"), (Boolean) s.get("lot_managed"), (Boolean) s.get("expiry_managed"));
    }

    NodeService.NodeView requireUsableNode(UUID nodeId, UUID ownerId) {
        NodeService.NodeView node = nodes.find(nodeId).orElseThrow(() -> Errors.notFound("FULFILLMENT_NODE"));
        if (!"ACTIVE".equals(node.status())) {
            throw Errors.unprocessable("NODE_NOT_ACTIVE", "운영 중인 거점이 아닙니다.");
        }
        if (!ownerAllowedOnNode(node, ownerId)) {
            throw Errors.unprocessable("OWNER_NOT_ON_NODE", "이 사업자는 해당 거점에 화주계정이 연결되어 있지 않습니다(다른 사업자 재고와 섞을 수 없음).");
        }
        return node;
    }

    /** 화면 표시용 shippers 는 조회 범위로 걸러지므로, 판단은 DB 에서 직접 한다. */
    boolean ownerAllowedOnNode(NodeService.NodeView node, UUID ownerId) {
        if ("SELF_WMS".equals(node.providerKind())) {
            return true;
        }
        return jdbc.sql("""
                select count(*) from fulfillment_node_shipper l join shipper_account s on s.id = l.shipper_account_id
                 where l.node_id = :n and l.business_entity_id = :b and s.status = 'ACTIVE'
                """).param("n", node.id()).param("b", ownerId).query(Integer.class).single() > 0;
    }

    private UUID resolveLot(UUID ws, SkuInfo sku, String lotCode, LocalDate mfg, LocalDate exp) {
        String code = trimOrNull(lotCode);
        if (sku.lotManaged() && code == null) {
            throw Errors.validation("lot_code", "LOT 관리 SKU 는 LOT 번호가 필요합니다.");
        }
        if (sku.expiryManaged() && exp == null) {
            throw Errors.validation("expires_on", "유통기한 관리 SKU 는 유통기한이 필요합니다.");
        }
        if (code == null) {
            if (exp != null || mfg != null) {
                throw Errors.validation("lot_code", "제조일·유통기한은 LOT 와 함께 입력하세요.");
            }
            return null;
        }
        if (!sku.lotManaged()) {
            throw Errors.validation("lot_code", "LOT 관리 대상이 아닌 SKU 입니다.");
        }
        if (!code.matches("^[A-Za-z0-9][A-Za-z0-9_.-]{0,39}$")) {
            throw Errors.validation("lot_code", "LOT 번호는 영문·숫자·점·하이픈·밑줄 40자 이내입니다.");
        }
        if (exp != null && mfg != null && exp.isBefore(mfg)) {
            throw Errors.validation("expires_on", "유통기한이 제조일보다 빠를 수 없습니다.");
        }
        jdbc.sql("insert into inventory_lot (id, workspace_id, sku_id, lot_code, manufactured_on, expires_on) values (:id, :ws, :s, :c, :m, :e) on conflict (sku_id, lot_code) do nothing")
                .param("id", Ids.newId()).param("ws", ws).param("s", sku.id()).param("c", code).param("m", mfg).param("e", exp).update();
        Map<String, Object> lot = jdbc.sql("select id, manufactured_on, expires_on from inventory_lot where sku_id = :s and lot_code = :c").param("s", sku.id()).param("c", code)
                .query().listOfRows().get(0);
        LocalDate storedExp = lot.get("expires_on") == null ? null : ((java.sql.Date) lot.get("expires_on")).toLocalDate();
        if (exp != null && storedExp != null && !exp.equals(storedExp)) {
            throw Errors.unprocessable("LOT_EXPIRY_MISMATCH", "같은 LOT 에 등록된 유통기한과 다릅니다.", Map.of("lot_code", code, "registered_expires_on", storedExp.toString()));
        }
        return (UUID) lot.get("id");
    }

    private UUID resolveLocation(UUID ws, NodeService.NodeView node, String locationCode) {
        String code = trimOrNull(locationCode);
        if (code == null) {
            return null;
        }
        return jdbc.sql("select id from node_location where workspace_id = :ws and node_id = :n and code = :c and status = 'ACTIVE'").param("ws", ws)
                .param("n", node.id()).param("c", code).query(UUID.class).optional()
                .orElseThrow(() -> Errors.validation("location_code", "거점에 등록된 운영 중 로케이션이 아닙니다."));
    }

    private static String normalizeHold(String quality, String hold) {
        if (!QUALITIES.contains(quality)) {
            throw Errors.validation("quality_status", "품질 상태 값이 올바르지 않습니다.");
        }
        String h = trimOrNull(hold);
        if (h == null) {
            return null;
        }
        if ("SELLABLE".equals(quality)) {
            throw Errors.validation("hold_reason", "판매가능 재고에는 보류 사유를 둘 수 없습니다.");
        }
        h = h.toUpperCase();
        if (!h.matches("^[A-Z][A-Z0-9_]{1,40}$")) {
            throw Errors.validation("hold_reason", "보류 사유 코드는 영문 대문자·숫자·밑줄입니다.");
        }
        return h;
    }

    static int positive(Integer q) {
        if (q == null || q < 1 || q > 10_000_000) {
            throw Errors.validation("quantity", "수량은 1 이상의 정수여야 합니다.");
        }
        return q;
    }

    static void requireReason(String reason, boolean required) {
        if (required && (reason == null || reason.isBlank())) {
            throw Errors.validation("reason", "사유를 입력하세요.");
        }
        if (reason != null && reason.length() > 500) {
            throw Errors.validation("reason", "사유는 500자 이내입니다.");
        }
    }

    static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    void availabilityChanged(UUID ws, Position p) {
        outbox.publish(ws, p.ownerId(), "INVENTORY_POSITION", p.id(), "InventoryAvailabilityChanged",
                Map.of("business_entity_id", p.ownerId().toString(), "sku_id", p.skuId().toString(), "node_id", p.nodeId().toString()));
    }

    private static final String STOCK_SELECT = """
            select st.*, be.display_name as be_name, s.sku_code, coalesce(s.name, pr.name) as sku_name, n.code as node_code, loc.code as location_code,
                   l.lot_code, l.expires_on
              from inventory_stock st join business_entity be on be.id = st.owner_business_entity_id join sku s on s.id = st.sku_id
              join product pr on pr.id = s.product_id join fulfillment_node n on n.id = st.node_id
              left join node_location loc on loc.id = st.location_id left join inventory_lot l on l.id = st.lot_id
            """;

    private StockView stock(UUID id) {
        return jdbc.sql(STOCK_SELECT + " where st.workspace_id = :ws and st.id = :id").param("ws", ws()).param("id", id).query((rs, i) -> stockView(rs)).single();
    }

    private StockView stockView(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Date exp = rs.getDate("expires_on");
        return new StockView(Db.uuid(rs, "id"), Db.uuid(rs, "owner_business_entity_id"), rs.getString("be_name"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"),
                rs.getString("sku_name"), Db.uuid(rs, "node_id"), rs.getString("node_code"), rs.getString("location_code"), rs.getString("lot_code"),
                exp == null ? null : exp.toLocalDate(), rs.getString("quality_status"), rs.getString("hold_reason"), rs.getInt("on_hand"), Db.instant(rs, "updated_at"));
    }

    private static void common(StringBuilder sql, Map<String, Object> params, Filter f, String alias) {
        if (f.nodeId() != null) {
            sql.append(" and ").append(alias).append(".node_id = :node");
            params.put("node", f.nodeId());
        }
        if (f.skuId() != null) {
            sql.append(" and ").append(alias).append(".sku_id = :sku");
            params.put("sku", f.skuId());
        }
        if (f.q() != null && !f.q().isBlank()) {
            sql.append(" and (s.sku_code ilike :q or coalesce(s.name, pr.name) ilike :q or s.barcode = :qe)");
            params.put("q", "%" + f.q().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            params.put("qe", f.q().trim());
        }
    }

    private static Mismatch mismatch(java.sql.ResultSet rs, String check, long expected, long actual, String msg) throws java.sql.SQLException {
        return new Mismatch(check, Db.uuid(rs, "id"), Db.uuid(rs, "owner_business_entity_id"), Db.uuid(rs, "sku_id"), Db.uuid(rs, "node_id"), expected, actual, msg);
    }

    private UUID ws() {
        return guard.actor().workspaceId();
    }
}
