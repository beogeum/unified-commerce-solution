package com.commerceos.procurement;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.inventory.InventoryService;
import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단계별 입고(docs/04 §8: 입고예정 → 센터 도착 → 검수 → 적치 → 완료|차이, 발주 1:N, DLG-220~225).
 * <ul>
 *   <li>입고예정 등록은 발주 관리 권한, 도착·검수·적치는 재고 운영 권한 + 거점 범위.</li>
 *   <li>도착 수량은 실물 확인 기록(재고 원장 미반영). 검수 확정 때 정상 수량만 원장 입고(품질 '검수 대기', 보류 PUTAWAY_PENDING → 판매불가),
 *       불량은 반송 업무, 발주 잔량을 넘는 초과 도착은 받지 않는 수량(over)으로 반송 업무에 포함.</li>
 *   <li>적치 확정에서 판매가능으로 바꾸고 지정 로케이션으로 옮긴다(부분 적치 가능). 수량 차이(도착 ≠ 예정, 초과 도착)가 있으면 DISCREPANCY 로 끝내고 차이 업무를 만든다.</li>
 *   <li>모든 상태 전환은 inbound_shipment_event(append-only)와 감사로그에 남는다. 동시 처리는 발주 행 → 입고 건 행 순서로 잠근다.</li>
 * </ul>
 */
@Service
public class InboundShipmentService {
    static final String PUTAWAY_HOLD = "PUTAWAY_PENDING";
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final PurchaseOrderService orders;
    private final InventoryService inventory;
    private final WorkItemService workItems;
    private final AuditService audit;
    private final Json json;

    public InboundShipmentService(JdbcClient jdbc, AccessGuard guard, PurchaseOrderService orders, InventoryService inventory, WorkItemService workItems,
                                  AuditService audit, Json json) {
        this.json = json;
        this.jdbc = jdbc;
        this.guard = guard;
        this.orders = orders;
        this.inventory = inventory;
        this.workItems = workItems;
        this.audit = audit;
    }

    public record PlanLine(UUID poLineId, Integer quantity) {
    }

    public record PlanInput(LocalDate expectedOn, String carrier, String trackingNo, String note, List<PlanLine> lines) {
    }

    public record ArriveLine(UUID lineId, Integer arrivedQty) {
    }

    public record ArriveInput(List<ArriveLine> lines, String note) {
    }

    public record InspectLine(UUID lineId, Integer goodQty, Integer rejectedQty, Integer overQty, BigDecimal unitCost, String lotCode, LocalDate manufacturedOn,
                              LocalDate expiresOn, String rejectReason) {
    }

    public record InspectInput(List<InspectLine> lines, String note) {
    }

    public record PutawayLine(UUID lineId, Integer quantity, String locationCode) {
    }

    public record PutawayInput(List<PutawayLine> lines) {
    }

    public record LineView(UUID id, UUID poLineId, UUID skuId, String skuCode, String skuName, int expectedQty, Integer arrivedQty, Integer goodQty, Integer rejectedQty,
                           Integer overQty, String rejectReason, int putawayQty, int putawayRemaining, Integer difference) {
    }

    public record EventView(String fromStatus, String toStatus, String actorName, Instant occurredAt, String detail) {
    }

    public record ShipmentView(UUID id, String shipmentNo, UUID poId, String poNo, UUID businessEntityId, String businessEntityName, String supplierName, UUID nodeId,
                               String nodeName, String status, LocalDate expectedOn, String carrier, String trackingNo, String note, String receiptNo,
                               boolean hasDiscrepancy, Instant arrivedAt, Instant inspectedAt, Instant completedAt, String cancelReason, int expectedQty,
                               int arrivedQty, int goodQty, int putawayQty, List<LineView> lines, List<EventView> events, Instant createdAt, int version) {
    }

    // ---------------------------------------------------------------- 입고예정 등록

    @Transactional
    public ShipmentView plan(UUID poId, PlanInput in) {
        guard.requirePermission(Perm.PURCHASE_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> po = orders.lock(a.workspaceId(), poId);
        UUID be = (UUID) po.get("business_entity_id");
        UUID node = (UUID) po.get("node_id");
        guard.checkWrite("PURCHASE_ORDER", poId, be, null);
        if (!PurchaseOrderService.OPEN.contains(po.get("status"))) {
            throw Errors.conflict("PO_INVALID_STATE", "발주 완료·공급처 확인·부분 입고 상태의 발주만 입고예정을 등록할 수 있습니다.", Map.of("status", po.get("status")));
        }
        if (in == null || in.lines() == null || in.lines().isEmpty()) {
            throw Errors.validation("lines", "입고예정 품목을 입력하세요.");
        }
        UUID id = Ids.newId();
        String no = nextNo(a.workspaceId());
        jdbc.sql("""
                insert into inbound_shipment (id, workspace_id, shipment_no, po_id, business_entity_id, node_id, expected_on, carrier, tracking_no, note, created_by)
                values (:id, :ws, :no, :po, :be, :n, cast(:e as date), cast(:c as text), cast(:t as text), cast(:note as text), :by)
                """).param("id", id).param("ws", a.workspaceId()).param("no", no).param("po", poId).param("be", be).param("n", node)
                .param("e", in.expectedOn() == null ? po.get("expected_on") : in.expectedOn()).param("c", SupplierService.trim(in.carrier(), 100, "carrier"))
                .param("t", SupplierService.trim(in.trackingNo(), 100, "tracking_no")).param("note", SupplierService.trim(in.note(), 500, "note")).param("by", a.memberId())
                .update();
        Set<UUID> seen = new HashSet<>();
        int total = 0;
        for (PlanLine l : in.lines()) {
            if (l == null || l.poLineId() == null || !seen.add(l.poLineId())) {
                throw Errors.validation("lines", "발주 품목이 비었거나 중복입니다.");
            }
            Map<String, Object> pl = jdbc.sql("select * from purchase_order_line where po_id = :po and id = :id for update").param("po", poId).param("id", l.poLineId())
                    .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.validation("lines", "이 발주의 품목이 아닙니다."));
            int qty = l.quantity() == null ? 0 : l.quantity();
            if (qty < 1) {
                throw Errors.validation("quantity", "입고예정 수량은 1 이상입니다.");
            }
            int remaining = ((Number) pl.get("quantity")).intValue() - ((Number) pl.get("received_qty")).intValue() - ((Number) pl.get("rejected_qty")).intValue();
            int planned = jdbc.sql("""
                    select coalesce(sum(l.expected_qty), 0) from inbound_shipment_line l join inbound_shipment s on s.id = l.shipment_id
                     where l.po_line_id = :pl and s.status in ('EXPECTED', 'ARRIVED', 'INSPECTING')
                    """).param("pl", l.poLineId()).query(Integer.class).single();
            if (qty > remaining - planned) {
                throw Errors.unprocessable("INBOUND_OVER_PLAN", "발주 잔량(진행 중인 다른 입고예정 제외)보다 많이 등록할 수 없습니다.",
                        Map.of("remaining", remaining, "planned_elsewhere", planned, "requested", qty));
            }
            jdbc.sql("""
                    insert into inbound_shipment_line (id, workspace_id, shipment_id, po_line_id, sku_id, expected_qty) values (:id, :ws, :s, :pl, :sku, :q)
                    """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("s", id).param("pl", l.poLineId()).param("sku", pl.get("sku_id")).param("q", qty)
                    .update();
            total += qty;
        }
        event(a, id, null, "EXPECTED", Map.of("lines", in.lines().size(), "expected_qty", total));
        audit.record(AuditEntry.builder(a.workspaceId(), "INBOUND_SHIPMENT_PLANNED", "INBOUND_SHIPMENT", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).after(Map.of("shipment_no", no, "po_no", po.get("po_no"), "expected_qty", total)).build());
        return get(id);
    }

    // ---------------------------------------------------------------- 센터 도착

    @Transactional
    public ShipmentView arrive(UUID id, Integer version, ArriveInput in) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        Map<String, Object> s = lockForWork(a, id, version);
        requireStatus(s, Set.of("EXPECTED"), "입고예정 상태에서만 도착을 등록할 수 있습니다.");
        if (in == null || in.lines() == null || in.lines().isEmpty()) {
            throw Errors.validation("lines", "도착 수량을 입력하세요.");
        }
        Map<UUID, Map<String, Object>> lines = lines(id);
        Map<UUID, Integer> given = new HashMap<>();
        for (ArriveLine l : in.lines()) {
            if (l == null || l.lineId() == null || !lines.containsKey(l.lineId()) || given.containsKey(l.lineId())) {
                throw Errors.validation("lines", "이 입고 건의 품목이 아니거나 중복입니다.");
            }
            if (l.arrivedQty() == null || l.arrivedQty() < 0) {
                throw Errors.validation("arrived_qty", "도착 수량은 0 이상입니다.");
            }
            given.put(l.lineId(), l.arrivedQty());
        }
        if (given.size() != lines.size()) {
            throw Errors.validation("lines", "모든 품목의 도착 수량을 입력하세요(안 온 품목은 0).");
        }
        int total = given.values().stream().mapToInt(Integer::intValue).sum();
        if (total < 1) {
            throw Errors.validation("lines", "도착한 수량이 없습니다. 입고 건을 취소하거나 도착 후 다시 등록하세요.");
        }
        given.forEach((lineId, q) -> jdbc.sql("update inbound_shipment_line set arrived_qty = :q where id = :id").param("q", q).param("id", lineId).update());
        String note = SupplierService.trim(in.note(), 500, "note");
        jdbc.sql("""
                update inbound_shipment set status = 'ARRIVED', arrived_at = now(), note = coalesce(cast(:n as text), note), updated_at = now(), version = version + 1
                 where id = :id
                """).param("n", note).param("id", id).update();
        int expected = lines.values().stream().mapToInt(r -> ((Number) r.get("expected_qty")).intValue()).sum();
        event(a, id, "EXPECTED", "ARRIVED", Map.of("arrived_qty", total, "expected_qty", expected));
        audit(a, s, "INBOUND_SHIPMENT_ARRIVED", Map.of("arrived_qty", total, "expected_qty", expected));
        return get(id);
    }

    @Transactional
    public ShipmentView startInspection(UUID id, Integer version) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        Map<String, Object> s = lockForWork(a, id, version);
        requireStatus(s, Set.of("ARRIVED"), "센터 도착 상태에서만 검수를 시작할 수 있습니다.");
        jdbc.sql("update inbound_shipment set status = 'INSPECTING', updated_at = now(), version = version + 1 where id = :id").param("id", id).update();
        event(a, id, "ARRIVED", "INSPECTING", Map.of());
        audit(a, s, "INBOUND_SHIPMENT_INSPECTION_STARTED", Map.of());
        return get(id);
    }

    // ---------------------------------------------------------------- 검수 확정

    @Transactional
    public ShipmentView inspect(UUID id, Integer version, InspectInput in) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        UUID poId = jdbc.sql("select po_id from inbound_shipment where workspace_id = :ws and id = :id").param("ws", a.workspaceId()).param("id", id).query(UUID.class)
                .optional().orElseThrow(() -> Errors.notFound("INBOUND_SHIPMENT"));
        Map<String, Object> po = orders.lock(a.workspaceId(), poId);
        Map<String, Object> s = lockForWork(a, id, version);
        requireStatus(s, Set.of("INSPECTING"), "검수 중 상태에서만 검수 결과를 확정할 수 있습니다.");
        if (!PurchaseOrderService.OPEN.contains(po.get("status"))) {
            throw Errors.conflict("PO_INVALID_STATE", "발주가 입고 가능한 상태가 아닙니다.", Map.of("status", po.get("status")));
        }
        if (in == null || in.lines() == null) {
            throw Errors.validation("lines", "검수 결과를 입력하세요.");
        }
        Map<UUID, Map<String, Object>> lines = lines(id);
        Map<UUID, InspectLine> given = new LinkedHashMap<>();
        for (InspectLine l : in.lines()) {
            if (l == null || l.lineId() == null || !lines.containsKey(l.lineId()) || given.containsKey(l.lineId())) {
                throw Errors.validation("lines", "이 입고 건의 품목이 아니거나 중복입니다.");
            }
            given.put(l.lineId(), l);
        }
        List<PurchaseOrderService.ReceiveLine> receive = new ArrayList<>();
        Map<UUID, UUID> poLineToLine = new HashMap<>();
        boolean discrepancy = false;
        int overTotal = 0;
        int goodTotal = 0;
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Object>> e : lines.entrySet()) {
            Map<String, Object> row = e.getValue();
            int arrived = ((Number) row.get("arrived_qty")).intValue();
            int expected = ((Number) row.get("expected_qty")).intValue();
            InspectLine l = given.get(e.getKey());
            if (l == null && arrived > 0) {
                throw Errors.validation("lines", "도착 수량이 있는 모든 품목의 검수 결과를 입력하세요.");
            }
            int good = l == null || l.goodQty() == null ? 0 : l.goodQty();
            int rej = l == null || l.rejectedQty() == null ? 0 : l.rejectedQty();
            int over = l == null || l.overQty() == null ? 0 : l.overQty();
            if (good < 0 || rej < 0 || over < 0 || good + rej + over != arrived) {
                throw Errors.validation("lines", "정상 + 불량 + 초과(미인수) 수량이 도착 수량(" + arrived + ")과 같아야 합니다.");
            }
            String reason = l == null ? null : SupplierService.trim(l.rejectReason(), 300, "reject_reason");
            if ((rej > 0 || over > 0) && reason == null) {
                throw Errors.validation("reject_reason", "불량·초과(미인수) 수량이 있으면 사유를 입력하세요.");
            }
            jdbc.sql("update inbound_shipment_line set good_qty = :g, rejected_qty = :r, over_qty = :o, reject_reason = cast(:rr as text) where id = :id")
                    .param("g", good).param("r", rej).param("o", over).param("rr", reason).param("id", e.getKey()).update();
            UUID poLine = (UUID) row.get("po_line_id");
            if (good + rej > 0) {
                receive.add(new PurchaseOrderService.ReceiveLine(poLine, good, rej, l.unitCost(), l.lotCode(), l.manufacturedOn(), l.expiresOn(), null, reason));
                poLineToLine.put(poLine, e.getKey());
            }
            if (arrived != expected || over > 0) {
                discrepancy = true;
                diffs.add(row.get("sku_code") + " 예정 " + expected + " / 도착 " + arrived + (over > 0 ? " / 초과 " + over : ""));
            }
            overTotal += over;
            goodTotal += good;
        }
        String receiptNo = null;
        UUID receiptId = null;
        if (!receive.isEmpty()) {
            // 같은 발주 행 잠금·잔량 검사·실매입 단가·LOT·불량 반송 업무는 즉시 입고와 같은 경로(DLG-194). 정상 수량은 적치 전까지 판매불가.
            PurchaseOrderService.Posted posted = orders.postReceipt(a, po, receive, "입고 건 " + s.get("shipment_no") + " 검수", "PENDING_INSPECTION", PUTAWAY_HOLD);
            receiptNo = posted.receiptNo();
            receiptId = posted.receiptId();
            posted.stockByPoLine().forEach((poLine, stock) -> jdbc.sql("update inbound_shipment_line set staging_stock_id = :st where id = :id").param("st", stock)
                    .param("id", poLineToLine.get(poLine)).update());
        }
        String next = goodTotal > 0 ? "PUTAWAY" : discrepancy ? "DISCREPANCY" : "COMPLETED";
        jdbc.sql("""
                update inbound_shipment set status = :st, receipt_id = cast(:r as uuid), has_discrepancy = :d, inspected_at = now(),
                       completed_at = case when :st in ('COMPLETED', 'DISCREPANCY') then now() end, updated_at = now(), version = version + 1 where id = :id
                """).param("st", next).param("r", receiptId).param("d", discrepancy).param("id", id).update();
        UUID be = (UUID) s.get("business_entity_id");
        if (overTotal > 0) {
            workItems.raise(new WorkItemService.Raise(a.workspaceId(), be, null, null, "PURCHASE_RETURN_TO_SUPPLIER", "MEDIUM",
                    "공급처 반송(초과 도착): " + po.get("po_no") + " " + s.get("shipment_no"), "발주 잔량을 넘어 도착한 " + overTotal + "개를 인수하지 않았습니다(재고 미반영).",
                    "공급처에 회수를 요청하거나, 필요하면 별도 발주로 처리하세요.", "inbound-over:" + id,
                    Map.of("purchase_order_id", poId.toString(), "inbound_shipment_id", id.toString())));
        }
        if (discrepancy) {
            raiseDiscrepancy(a, s, po, diffs);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("receipt_no", String.valueOf(receiptNo));
        detail.put("good_qty", goodTotal);
        detail.put("over_qty", overTotal);
        detail.put("discrepancy", discrepancy);
        event(a, id, "INSPECTING", next, detail);
        audit(a, s, "INBOUND_SHIPMENT_INSPECTED", detail);
        return get(id);
    }

    // ---------------------------------------------------------------- 적치

    @Transactional
    public ShipmentView putaway(UUID id, Integer version, PutawayInput in) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        Map<String, Object> s = lockForWork(a, id, version);
        requireStatus(s, Set.of("PUTAWAY"), "적치 대기 상태에서만 적치를 확정할 수 있습니다.");
        if (in == null || in.lines() == null || in.lines().isEmpty()) {
            throw Errors.validation("lines", "적치할 품목과 수량을 입력하세요.");
        }
        Map<UUID, Map<String, Object>> lines = lines(id);
        Set<UUID> seen = new HashSet<>();
        int total = 0;
        for (PutawayLine l : in.lines()) {
            if (l == null || l.lineId() == null || !lines.containsKey(l.lineId()) || !seen.add(l.lineId())) {
                throw Errors.validation("lines", "이 입고 건의 품목이 아니거나 중복입니다.");
            }
            Map<String, Object> row = lines.get(l.lineId());
            int good = row.get("good_qty") == null ? 0 : ((Number) row.get("good_qty")).intValue();
            int done = ((Number) row.get("putaway_qty")).intValue();
            int qty = l.quantity() == null ? good - done : l.quantity();
            if (qty < 1 || qty > good - done) {
                throw Errors.validation("quantity", "적치 수량은 1 이상, 남은 적치 수량(" + (good - done) + ") 이하입니다.");
            }
            UUID staging = (UUID) row.get("staging_stock_id");
            String reason = "입고 적치 " + s.get("shipment_no");
            // 판매가능 전환(원장 QUALITY_CHANGE −/+) 후 지정 로케이션으로 이동(원장 LOCATION_MOVE −/+). 실사 잠금·확보 수량 검사는 재고 서비스가 맡는다.
            List<InventoryService.StockView> changed = inventory.changeQuality(new InventoryService.QualityChange(staging, qty, "SELLABLE", null, reason));
            InventoryService.StockView sellable = changed.get(1);
            String loc = l.locationCode() == null || l.locationCode().isBlank() ? null : l.locationCode().trim();
            if (loc != null && !loc.equals(sellable.locationCode())) {
                inventory.moveLocation(new InventoryService.LocationMove(sellable.id(), qty, loc, reason));
            }
            jdbc.sql("update inbound_shipment_line set putaway_qty = putaway_qty + :q where id = :id").param("q", qty).param("id", l.lineId()).update();
            total += qty;
        }
        int left = jdbc.sql("select coalesce(sum(coalesce(good_qty, 0) - putaway_qty), 0) from inbound_shipment_line where shipment_id = :id").param("id", id)
                .query(Integer.class).single();
        String next = left > 0 ? "PUTAWAY" : (Boolean) s.get("has_discrepancy") ? "DISCREPANCY" : "COMPLETED";
        jdbc.sql("""
                update inbound_shipment set status = :st, completed_at = case when :st = 'PUTAWAY' then null else now() end, updated_at = now(), version = version + 1
                 where id = :id
                """).param("st", next).param("id", id).update();
        Map<String, Object> detail = Map.of("putaway_qty", total, "remaining", left);
        event(a, id, "PUTAWAY", next, detail);
        audit(a, s, "INBOUND_SHIPMENT_PUTAWAY", detail);
        return get(id);
    }

    // ---------------------------------------------------------------- 취소

    @Transactional
    public ShipmentView cancel(UUID id, Integer version, String reason) {
        guard.requirePermission(Perm.PURCHASE_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> s = lockForWork(a, id, version);
        requireStatus(s, Set.of("EXPECTED", "ARRIVED", "INSPECTING"), "검수 확정 전 입고 건만 취소할 수 있습니다(확정 후에는 재고 조정·반품으로).");
        String why = SupplierService.trim(reason, 500, "reason");
        if (why == null) {
            throw Errors.validation("reason", "취소 사유를 입력하세요.");
        }
        jdbc.sql("update inbound_shipment set status = 'CANCELLED', cancel_reason = :r, updated_at = now(), version = version + 1 where id = :id").param("r", why)
                .param("id", id).update();
        event(a, id, (String) s.get("status"), "CANCELLED", Map.of("reason", why));
        audit(a, s, "INBOUND_SHIPMENT_CANCELLED", Map.of("reason", why));
        return get(id);
    }

    // ---------------------------------------------------------------- 조회

    public List<ShipmentView> list(List<String> businessEntityIds, String status, UUID poId) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        Set<UUID> nodes = guard.nodeFilter(AccessKind.READ, null);
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "bes", bes, "nodes", nodes.isEmpty() ? Set.of(new UUID(0, 0)) : nodes));
        String sql = "select id from inbound_shipment where workspace_id = :ws and business_entity_id in (:bes) and node_id in (:nodes)";
        if (status != null && !status.isBlank()) {
            if ("OPEN".equals(status)) {
                sql += " and status in ('EXPECTED', 'ARRIVED', 'INSPECTING', 'PUTAWAY')";
            } else {
                sql += " and status = :st";
                p.put("st", status);
            }
        }
        if (poId != null) {
            sql += " and po_id = :po";
            p.put("po", poId);
        }
        return jdbc.sql(sql + " order by coalesce(expected_on, created_at::date), created_at desc limit 300").params(p).query(UUID.class).list().stream()
                .map(i -> view(i, false)).toList();
    }

    public ShipmentView get(UUID id) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Map<String, Object> s = jdbc.sql("select business_entity_id, node_id from inbound_shipment where workspace_id = :ws and id = :id")
                .param("ws", guard.actor().workspaceId()).param("id", id).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("INBOUND_SHIPMENT"));
        guard.checkRead("INBOUND_SHIPMENT", id, (UUID) s.get("business_entity_id"), null);
        guard.checkNode(AccessKind.READ, "INBOUND_SHIPMENT", id, (UUID) s.get("node_id"));
        return view(id, true);
    }

    ShipmentView view(UUID id, boolean detail) {
        Map<String, Object> s = jdbc.sql("""
                select s.*, o.po_no, be.display_name as be_name, sp.name as supplier_name, n.name as node_name, r.receipt_no from inbound_shipment s
                  join purchase_order o on o.id = s.po_id join business_entity be on be.id = s.business_entity_id join supplier sp on sp.id = o.supplier_id
                  join fulfillment_node n on n.id = s.node_id left join po_receipt r on r.id = s.receipt_id
                 where s.id = :id
                """).param("id", id).query().listOfRows().get(0);
        List<LineView> lines = jdbc.sql("""
                select l.*, k.sku_code, k.name as sku_name from inbound_shipment_line l join sku k on k.id = l.sku_id join purchase_order_line pl on pl.id = l.po_line_id
                 where l.shipment_id = :id order by pl.line_no
                """).param("id", id).query((rs, i) -> {
            Integer arrived = (Integer) rs.getObject("arrived_qty");
            Integer good = (Integer) rs.getObject("good_qty");
            int put = rs.getInt("putaway_qty");
            return new LineView(Db.uuid(rs, "id"), Db.uuid(rs, "po_line_id"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"), rs.getString("sku_name"),
                    rs.getInt("expected_qty"), arrived, good, (Integer) rs.getObject("rejected_qty"), (Integer) rs.getObject("over_qty"), rs.getString("reject_reason"), put,
                    good == null ? 0 : good - put, arrived == null ? null : arrived - rs.getInt("expected_qty"));
        }).list();
        List<EventView> events = !detail ? List.of() : jdbc.sql("""
                select e.*, (select u.display_name from workspace_member m join user_account u on u.id = m.user_id where m.id = e.actor_member_id) as by_name
                  from inbound_shipment_event e where e.shipment_id = :id order by e.occurred_at, e.id
                """).param("id", id).query((rs, i) -> new EventView(rs.getString("from_status"), rs.getString("to_status"), rs.getString("by_name"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("detail"))).list();
        int expected = 0;
        int arrived = 0;
        int good = 0;
        int put = 0;
        for (LineView l : lines) {
            expected += l.expectedQty();
            arrived += l.arrivedQty() == null ? 0 : l.arrivedQty();
            good += l.goodQty() == null ? 0 : l.goodQty();
            put += l.putawayQty();
        }
        return new ShipmentView(id, (String) s.get("shipment_no"), (UUID) s.get("po_id"), (String) s.get("po_no"), (UUID) s.get("business_entity_id"),
                (String) s.get("be_name"), (String) s.get("supplier_name"), (UUID) s.get("node_id"), (String) s.get("node_name"), (String) s.get("status"),
                s.get("expected_on") == null ? null : ((java.sql.Date) s.get("expected_on")).toLocalDate(), (String) s.get("carrier"), (String) s.get("tracking_no"),
                (String) s.get("note"), (String) s.get("receipt_no"), (Boolean) s.get("has_discrepancy"), ts(s.get("arrived_at")), ts(s.get("inspected_at")),
                ts(s.get("completed_at")), (String) s.get("cancel_reason"), expected, arrived, good, put, lines, events, ts(s.get("created_at")),
                ((Number) s.get("version")).intValue());
    }

    // ---------------------------------------------------------------- 내부

    private Map<String, Object> lockForWork(CurrentActor a, UUID id, Integer version) {
        Map<String, Object> s = jdbc.sql("select * from inbound_shipment where workspace_id = :ws and id = :id for update").param("ws", a.workspaceId()).param("id", id)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("INBOUND_SHIPMENT"));
        UUID be = (UUID) s.get("business_entity_id");
        guard.checkWrite("INBOUND_SHIPMENT", id, be, null);
        guard.checkNode(AccessKind.WRITE, "INBOUND_SHIPMENT", id, (UUID) s.get("node_id"));
        if (version != null && version != ((Number) s.get("version")).intValue()) {
            throw Errors.conflict("VERSION_CONFLICT", "다른 사용자가 먼저 바꿨습니다. 새로 고친 뒤 다시 시도하세요.");
        }
        return s;
    }

    private static void requireStatus(Map<String, Object> s, Set<String> allowed, String message) {
        if (!allowed.contains(s.get("status"))) {
            throw Errors.conflict("INBOUND_INVALID_STATE", message, Map.of("status", s.get("status")));
        }
    }

    private Map<UUID, Map<String, Object>> lines(UUID shipmentId) {
        Map<UUID, Map<String, Object>> out = new LinkedHashMap<>();
        jdbc.sql("""
                select l.*, k.sku_code from inbound_shipment_line l join sku k on k.id = l.sku_id join purchase_order_line pl on pl.id = l.po_line_id
                 where l.shipment_id = :id order by pl.line_no
                """).param("id", shipmentId).query().listOfRows().forEach(r -> out.put((UUID) r.get("id"), r));
        return out;
    }

    private void raiseDiscrepancy(CurrentActor a, Map<String, Object> s, Map<String, Object> po, List<String> diffs) {
        workItems.raise(new WorkItemService.Raise(a.workspaceId(), (UUID) s.get("business_entity_id"), null, null, "INBOUND_DISCREPANCY", "MEDIUM",
                "입고 수량 차이: " + po.get("po_no") + " " + s.get("shipment_no"), String.join("; ", diffs),
                "공급처와 미도착·초과 수량을 확인하고, 남은 발주 잔량은 추가 입고예정 등록 또는 잔량 종료로 정리하세요.", "inbound-diff:" + s.get("id"),
                Map.of("purchase_order_id", po.get("id").toString(), "inbound_shipment_id", s.get("id").toString())));
    }

    private void event(CurrentActor a, UUID id, String from, String to, Map<String, Object> detail) {
        jdbc.sql("""
                insert into inbound_shipment_event (id, workspace_id, shipment_id, from_status, to_status, actor_member_id, detail)
                values (:id, :ws, :s, cast(:f as text), :t, :by, cast(:d as jsonb))
                """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("s", id).param("f", from).param("t", to).param("by", a.memberId())
                .param("d", json.write(detail)).update();
    }

    private void audit(CurrentActor a, Map<String, Object> s, String action, Map<String, Object> after) {
        Map<String, Object> m = new LinkedHashMap<>(after);
        m.put("shipment_no", s.get("shipment_no"));
        audit.record(AuditEntry.builder(a.workspaceId(), action, "INBOUND_SHIPMENT", (UUID) s.get("id")).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) s.get("business_entity_id"), null).before(Map.of("status", s.get("status"))).after(m).build());
    }

    private String nextNo(UUID ws) {
        LocalDate d = LocalDate.now(ZoneId.of("Asia/Seoul"));
        int n = jdbc.sql("""
                insert into claim_number_counter (workspace_id, kind, day, last_value) values (:ws, 'INBOUND_SHIPMENT', :d, 1)
                on conflict (workspace_id, kind, day) do update set last_value = claim_number_counter.last_value + 1 returning last_value
                """).param("ws", ws).param("d", d).query(Integer.class).single();
        return "IB-" + d.toString().replace("-", "") + "-" + String.format("%04d", n);
    }

    private static Instant ts(Object o) {
        return o == null ? null : ((java.sql.Timestamp) o).toInstant();
    }
}
