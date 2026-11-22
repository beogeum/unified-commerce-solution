package com.commerceos.inventory;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.inventory.InventoryLedger.Bucket;
import com.commerceos.inventory.InventoryLedger.Position;
import com.commerceos.inventory.InventoryLedger.Stock;
import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * 거점 간 재고 이동(M3 I5). 같은 소유 사업자 안에서만(DLG-034: 사업자 간 이전은 내부 매출·매입 거래).
 * 출발: 판매 가능(확보되지 않은) 실물에서 유통기한 빠른 LOT 부터 빼고 TRANSFER_OUT. 이동 중 수량은 이동 기록에 남는다.
 * 도착: 받은 수량만 TRANSFER_IN, 부족분은 사라진 것으로 처리하지 않고 이동 차이 업무로 남긴다.
 * 물류사 재고 기준 거점 도착분은 물류사 자료(외부 재고 동기화)로 반영되므로 내부 버킷을 더하지 않는다.
 */
@Service
public class TransferService {
    private static final DateTimeFormatter NO = DateTimeFormatter.ofPattern("yyMM").withZone(ZoneId.of("Asia/Seoul"));
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final InventoryLedger ledger;
    private final InventoryService inventory;
    private final CountLocks locks;
    private final WorkItemService workItems;
    private final AuditService audit;

    public TransferService(JdbcClient jdbc, AccessGuard guard, InventoryLedger ledger, InventoryService inventory, CountLocks locks, WorkItemService workItems,
                           AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.ledger = ledger;
        this.inventory = inventory;
        this.locks = locks;
        this.workItems = workItems;
        this.audit = audit;
    }

    public record Line(UUID skuId, Integer quantity) {
    }

    public record Create(UUID businessEntityId, UUID fromNodeId, UUID toNodeId, List<Line> lines, String note) {
    }

    public record ReceiveLine(Integer lineNo, Integer receivedQty, String qualityStatus) {
    }

    public record Receive(List<ReceiveLine> lines, String locationCode, String note) {
    }

    public record LineView(int lineNo, UUID skuId, String skuCode, String lotCode, int quantity, Integer receivedQty, String receivedQuality) {
    }

    public record TransferView(UUID id, String transferNo, UUID businessEntityId, String businessEntityName, UUID fromNodeId, String fromNodeName,
                               UUID toNodeId, String toNodeName, String toInventorySource, String status, String note, Instant shippedAt, Instant receivedAt,
                               String requestedByName, int totalQuantity, int inTransitQuantity, List<LineView> lines, int version) {
    }

    @Transactional
    public TransferView create(Create c) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        if (c.businessEntityId() == null || c.fromNodeId() == null || c.toNodeId() == null) {
            throw Errors.validation("from_node_id", "소유 사업자와 출발·도착 거점을 지정하세요.");
        }
        if (c.fromNodeId().equals(c.toNodeId())) {
            throw Errors.validation("to_node_id", "출발과 도착 거점이 같습니다.");
        }
        guard.checkWrite("BUSINESS_ENTITY", c.businessEntityId(), c.businessEntityId(), null);
        NodeService.NodeView from = inventory.requireUsableNode(c.fromNodeId(), c.businessEntityId());
        inventory.requireUsableNode(c.toNodeId(), c.businessEntityId());
        if ("EXTERNAL".equals(from.inventorySource())) {
            throw Errors.unprocessable("TRANSFER_FROM_EXTERNAL", "물류사 재고 기준 거점에서 내보내는 이동은 물류사 출고요청으로 처리합니다(주문·출고 모듈).");
        }
        if (c.lines() == null || c.lines().isEmpty() || c.lines().size() > 200) {
            throw Errors.validation("lines", "이동할 SKU 를 1~200줄 입력하세요.");
        }
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        for (Line l : c.lines()) {
            if (l.skuId() == null || wanted.put(l.skuId(), InventoryService.positive(l.quantity())) != null) {
                throw Errors.validation("lines.sku_id", "SKU 를 한 줄에 하나씩 지정하세요.");
            }
            inventory.skuInfo(l.skuId());
        }
        String note = InventoryService.trimOrNull(c.note());
        InventoryService.requireReason(note, false);
        List<Position> locked = ledger.lockPositions(a.workspaceId(), c.businessEntityId(), wanted.keySet()).stream()
                .filter(p -> p.nodeId().equals(c.fromNodeId())).toList();
        Map<UUID, Position> bySku = new LinkedHashMap<>();
        locked.forEach(p -> bySku.put(p.skuId(), p));
        List<Map<String, Object>> shortages = new ArrayList<>();
        for (var e : wanted.entrySet()) {
            Position p = bySku.get(e.getKey());
            int free = p == null ? 0 : Math.max(0, p.atp() - locks.lockedSellable(a.workspaceId(), p));
            if (free < e.getValue()) {
                shortages.add(Map.of("sku_id", e.getKey().toString(), "requested", e.getValue(), "available", free));
            }
        }
        if (!shortages.isEmpty()) {
            throw Errors.conflict("INSUFFICIENT_AVAILABLE_STOCK", "확보되지 않은 판매 가능 재고가 부족해 이동하지 못했습니다(실사 중 재고 제외).", Map.of("lines", shortages));
        }
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:k))").param("k", "transfer_no:" + a.workspaceId()).query().listOfRows();
        String prefix = "T-" + NO.format(Instant.now()) + "-";
        int seq = jdbc.sql("select count(*) + 1 from inventory_transfer where workspace_id = :ws and transfer_no like :p").param("ws", a.workspaceId())
                .param("p", prefix + "%").query(Integer.class).single();
        String transferNo = prefix + String.format("%03d", seq);
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into inventory_transfer (id, workspace_id, transfer_no, business_entity_id, from_node_id, to_node_id, note, requested_by)
                values (:id, :ws, :no, :b, :f, :t, cast(:note as text), :by)
                """).param("id", id).param("ws", a.workspaceId()).param("no", transferNo).param("b", c.businessEntityId()).param("f", c.fromNodeId())
                .param("t", c.toNodeId()).param("note", note).param("by", a.memberId()).update();
        int lineNo = 1;
        for (var e : wanted.entrySet()) {
            Position p = bySku.get(e.getKey());
            int remaining = e.getValue();
            for (Stock s : locks.unlocked(a.workspaceId(), p, ledger.lockSellableBucketsFefo(p))) {
                if (remaining == 0) {
                    break;
                }
                int take = Math.min(remaining, s.onHand());
                p = ledger.changeBucket(a.workspaceId(), p, s.bucket(), -take, "TRANSFER_OUT", "INVENTORY_TRANSFER", transferNo, note, a.memberId());
                jdbc.sql("insert into inventory_transfer_line (workspace_id, transfer_id, line_no, sku_id, lot_id, quantity) values (:ws, :t, :no, :s, cast(:lot as uuid), :q)")
                        .param("ws", a.workspaceId()).param("t", id).param("no", lineNo++).param("s", e.getKey()).param("lot", s.bucket().lotId()).param("q", take).update();
                remaining -= take;
            }
            if (remaining > 0) {
                throw Errors.conflict("STOCK_BUCKET_MISMATCH", "판매가능 버킷과 집계가 어긋나 이동하지 못했습니다. 재고 검증을 확인하세요.");
            }
            inventory.availabilityChanged(a.workspaceId(), p);
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "INVENTORY_TRANSFER_SHIPPED", "INVENTORY_TRANSFER", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(c.businessEntityId(), null).after(Map.of("transfer_no", transferNo, "from", c.fromNodeId().toString(), "to", c.toNodeId().toString(),
                        "quantity", wanted.values().stream().mapToInt(Integer::intValue).sum())).build());
        return get(id);
    }

    @Transactional
    public TransferView receive(UUID id, Receive r) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        Map<String, Object> t = jdbc.sql("select * from inventory_transfer where workspace_id = :ws and id = :id for update").param("ws", a.workspaceId())
                .param("id", id).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("INVENTORY_TRANSFER"));
        UUID be = (UUID) t.get("business_entity_id");
        guard.checkWrite("INVENTORY_TRANSFER", id, be, null);
        if (!"IN_TRANSIT".equals(t.get("status"))) {
            throw Errors.conflict("TRANSFER_NOT_IN_TRANSIT", "이동 중인 건이 아닙니다.", Map.of("status", t.get("status")));
        }
        UUID to = (UUID) t.get("to_node_id");
        NodeService.NodeView node = inventory.requireUsableNode(to, be);
        boolean external = "EXTERNAL".equals(node.inventorySource());
        List<Map<String, Object>> lines = jdbc.sql("select * from inventory_transfer_line where transfer_id = :t order by line_no").param("t", id).query().listOfRows();
        Map<Integer, ReceiveLine> byNo = new LinkedHashMap<>();
        for (ReceiveLine l : r == null || r.lines() == null ? List.<ReceiveLine>of() : r.lines()) {
            byNo.put(l.lineNo(), l);
        }
        if (byNo.size() != lines.size()) {
            throw Errors.validation("lines", "모든 이동 줄의 받은 수량을 입력하세요(못 받은 줄은 0).");
        }
        UUID locationId = null;
        String code = r.locationCode() == null ? null : InventoryService.trimOrNull(r.locationCode());
        if (code != null && !external) {
            locationId = jdbc.sql("select id from node_location where workspace_id = :ws and node_id = :n and code = :c and status = 'ACTIVE'").param("ws", a.workspaceId())
                    .param("n", to).param("c", code).query(UUID.class).optional().orElseThrow(() -> Errors.validation("location_code", "도착 거점에 없는 로케이션입니다."));
        }
        String transferNo = (String) t.get("transfer_no");
        Set<UUID> skus = new LinkedHashSet<>();
        lines.forEach(l -> skus.add((UUID) l.get("sku_id")));
        ledger.ensurePositions(a.workspaceId(), be, skus, to);
        Map<UUID, Position> positions = new LinkedHashMap<>();
        ledger.lockPositions(a.workspaceId(), be, skus).stream().filter(p -> p.nodeId().equals(to)).forEach(p -> positions.put(p.skuId(), p));
        int shortTotal = 0;
        for (Map<String, Object> l : lines) {
            int no = ((Number) l.get("line_no")).intValue();
            ReceiveLine rl = byNo.get(no);
            int qty = ((Number) l.get("quantity")).intValue();
            if (rl == null || rl.receivedQty() == null || rl.receivedQty() < 0 || rl.receivedQty() > qty) {
                throw Errors.validation("lines.received_qty", no + "번 줄: 받은 수량은 0 이상 보낸 수량(" + qty + ") 이하입니다. 초과분은 입고로 따로 등록하세요.");
            }
            String quality = rl.qualityStatus() == null ? "SELLABLE" : rl.qualityStatus();
            if (!InventoryService.QUALITIES.contains(quality)) {
                throw Errors.validation("lines.quality_status", "품질 상태 값이 올바르지 않습니다.");
            }
            UUID sku = (UUID) l.get("sku_id");
            if (!external && rl.receivedQty() > 0) {
                locks.requireUnlocked(a.workspaceId(), be, to, sku, locationId, "이동 입고");
                Position p = ledger.changeBucket(a.workspaceId(), positions.get(sku), new Bucket(be, sku, to, locationId, (UUID) l.get("lot_id"), quality, null),
                        rl.receivedQty(), "TRANSFER_IN", "INVENTORY_TRANSFER", transferNo, InventoryService.trimOrNull(r.note()), a.memberId());
                positions.put(sku, p);
                if ("SELLABLE".equals(quality)) {
                    inventory.availabilityChanged(a.workspaceId(), p);
                }
            }
            jdbc.sql("update inventory_transfer_line set received_qty = :q, received_quality = :ql where transfer_id = :t and line_no = :no")
                    .param("q", rl.receivedQty()).param("ql", quality).param("t", id).param("no", no).update();
            shortTotal += qty - rl.receivedQty();
        }
        String status = shortTotal > 0 ? "RECEIVED_WITH_DISCREPANCY" : "RECEIVED";
        jdbc.sql("update inventory_transfer set status = :st, received_by = :by, received_at = now(), version = version + 1 where id = :id")
                .param("st", status).param("by", a.memberId()).param("id", id).update();
        if (shortTotal > 0) {
            workItems.raise(new WorkItemService.Raise(a.workspaceId(), be, null, null, "INVENTORY_TRANSFER_DISCREPANCY", "HIGH",
                    "재고 이동 수량 차이 " + shortTotal + "개 · " + transferNo, "보낸 수량보다 적게 받았습니다. 부족분은 출발 거점에서 이미 빠졌고 도착 거점에는 들어가지 않았습니다.",
                    "운송·출발 거점 출고 기록을 확인하고, 분실이 확정되면 승인 절차를 거쳐 조정하세요.", "inventory_transfer:" + id + ":DISCREPANCY",
                    Map.of("transfer_id", id.toString(), "transfer_no", transferNo, "short_quantity", shortTotal)));
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "INVENTORY_TRANSFER_RECEIVED", "INVENTORY_TRANSFER", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).after(Map.of("status", status, "short_quantity", shortTotal, "external_destination", external)).build());
        return get(id);
    }

    public List<TransferView> list(List<String> businessEntityIds, String status) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        boolean filter = status != null && !status.isBlank();
        var q = jdbc.sql(SELECT + " where t.workspace_id = :ws and t.business_entity_id in (:bes)" + (filter ? " and t.status = :st" : "") + " order by t.id desc limit 200")
                .param("ws", guard.actor().workspaceId()).param("bes", bes);
        if (filter) {
            q = q.param("st", status);
        }
        return q.query((rs, i) -> view(rs)).list().stream().map(this::withLines).toList();
    }

    public TransferView get(UUID id) {
        guard.requirePermission(Perm.INVENTORY_READ);
        TransferView v = jdbc.sql(SELECT + " where t.workspace_id = :ws and t.id = :id").param("ws", guard.actor().workspaceId()).param("id", id)
                .query((rs, i) -> view(rs)).optional().orElseThrow(() -> Errors.notFound("INVENTORY_TRANSFER"));
        guard.checkRead("INVENTORY_TRANSFER", id, v.businessEntityId(), null);
        return withLines(v);
    }

    private TransferView withLines(TransferView v) {
        List<LineView> lines = jdbc.sql("""
                select l.*, s.sku_code, lot.lot_code from inventory_transfer_line l join sku s on s.id = l.sku_id left join inventory_lot lot on lot.id = l.lot_id
                 where l.transfer_id = :t order by l.line_no
                """).param("t", v.id()).query((rs, i) -> new LineView(rs.getInt("line_no"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"), rs.getString("lot_code"),
                rs.getInt("quantity"), (Integer) rs.getObject("received_qty"), rs.getString("received_quality"))).list();
        int total = lines.stream().mapToInt(LineView::quantity).sum();
        int transit = "IN_TRANSIT".equals(v.status()) ? total : 0;
        return new TransferView(v.id(), v.transferNo(), v.businessEntityId(), v.businessEntityName(), v.fromNodeId(), v.fromNodeName(), v.toNodeId(), v.toNodeName(),
                v.toInventorySource(), v.status(), v.note(), v.shippedAt(), v.receivedAt(), v.requestedByName(), total, transit, lines, v.version());
    }

    private static final String SELECT = """
            select t.*, be.display_name as be_name, f.name as from_name, d.name as to_name, d.inventory_source as to_source, u.display_name as requested_by_name
              from inventory_transfer t join business_entity be on be.id = t.business_entity_id join fulfillment_node f on f.id = t.from_node_id
              join fulfillment_node d on d.id = t.to_node_id
              left join workspace_member m on m.workspace_id = t.workspace_id and m.id = t.requested_by left join user_account u on u.id = m.user_id
            """;

    private static TransferView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TransferView(Db.uuid(rs, "id"), rs.getString("transfer_no"), Db.uuid(rs, "business_entity_id"), rs.getString("be_name"),
                Db.uuid(rs, "from_node_id"), rs.getString("from_name"), Db.uuid(rs, "to_node_id"), rs.getString("to_name"), rs.getString("to_source"),
                rs.getString("status"), rs.getString("note"), Db.instant(rs, "shipped_at"), Db.instant(rs, "received_at"), rs.getString("requested_by_name"), 0, 0,
                List.of(), rs.getInt("version"));
    }
}
