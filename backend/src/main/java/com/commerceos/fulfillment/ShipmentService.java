package com.commerceos.fulfillment;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.inventory.ReservationService;
import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.outbox.OutboxPublisher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출고 확정·송장·배송 단계(M4 O5, docs/04 §1·§2·§4·§9).
 * <ul>
 *   <li>출고 확정은 출고지시 단위(전부). 송장(박스)은 1~N 개. 확보는 PICKING → CONSUMED 로 넘기고, 실물 출고 시각을 차감 시각으로 남긴다(DLG-130).</li>
 *   <li>배송 단계는 송장 축. 송장 발급 = LABEL_CREATED 일 뿐 배송완료가 아니다. 늦게 온 이벤트는 기록만 하고 상태를 되돌리지 않는다(T10).</li>
 *   <li>판매채널 발송처리 상태는 송장마다 따로 둔다(전송은 integration 작업).</li>
 * </ul>
 */
@Service
public class ShipmentService {
    static final List<String> PROGRESS = List.of("LABEL_CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED");
    static final Set<String> STATUSES = Set.of("LABEL_CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "EXCEPTION");
    private static final Pattern TRACKING = Pattern.compile("^[A-Z0-9]{4,40}$");
    private static final List<String> FO_PROGRESS = List.of("ACCEPTED", "PICKING", "PACKING", "SHIPPED");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final ReservationService reservations;
    private final FulfillmentService fulfillment;
    private final WorkItemService workItems;
    private final OutboxPublisher outbox;
    private final AuditService audit;
    private final Clock clock;
    private final WmsPickPlans picks;
    private final com.commerceos.inventory.MaterialService materials;

    public ShipmentService(JdbcClient jdbc, AccessGuard guard, ReservationService reservations, FulfillmentService fulfillment, WorkItemService workItems,
                           OutboxPublisher outbox, AuditService audit, Clock clock, WmsPickPlans picks, com.commerceos.inventory.MaterialService materials) {
        this.materials = materials;
        this.jdbc = jdbc;
        this.guard = guard;
        this.reservations = reservations;
        this.fulfillment = fulfillment;
        this.workItems = workItems;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
        this.picks = picks;
    }

    public record Item(UUID skuId, Integer quantity) {
    }

    public record Parcel(String carrierCode, String trackingNo, List<Item> items) {
    }

    public record Ship(Instant shippedAt, List<Parcel> parcels) {
    }

    public record TrackingInput(String status, Instant occurredAt, String description, String location) {
    }

    public record ManualDone(String note) {
    }

    public record ItemView(UUID orderItemId, UUID skuId, String skuCode, int quantity) {
    }

    public record EventView(String status, Instant occurredAt, String description, String location, String source, boolean applied, Instant receivedAt) {
    }

    public record ShipmentView(UUID id, String shipmentNo, UUID fulfillmentOrderId, String foNo, UUID orderId, String orderNo, UUID businessEntityId,
                               String businessEntityName, UUID channelAccountId, UUID nodeId, String nodeName, int parcelNo, String carrierCode, String carrierName,
                               String trackingNo, String status, Instant statusAt, Instant shippedAt, Instant deliveredAt, String source,
                               String channelDispatchStatus, String channelDispatchMode, int channelDispatchAttempts, String channelDispatchRef,
                               String channelDispatchError, Instant channelDispatchedAt, Instant createdAt, List<ItemView> items, List<EventView> events,
                               int version) {
    }

    public record Carrier(String code, String nameKo) {
    }

    /** 직접 기록 결과. IGNORED_STALE = 이력에는 남겼지만 현재 단계보다 이르거나 역행이라 상태는 그대로. */
    public record TrackingResult(String result, ShipmentView shipment) {
    }

    // ---------------------------------------------------------------- 출고 확정

    /** 사용자 출고 확정·송장 입력(자체 WMS·수동 전달 거점). */
    @Transactional
    public FulfillmentService.FoView ship(UUID foId, Ship cmd) {
        fulfillment.writable(foId);
        applyShip(foId, cmd, "MANUAL");
        return fulfillment.get(foId);
    }

    /**
     * 출고 확정 반영(화면·파일·물류사 연동 공통). @return SHIPPED · DUPLICATE(같은 송장으로 이미 확정)
     * 이미 다른 송장으로 확정됐거나 취소된 출고지시면 409 — 호출자가 검토 업무로 넘긴다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String applyShip(UUID foId, Ship cmd, String source) {
        CurrentActor a = guard.actor();
        UUID ws = a.workspaceId();
        Map<String, Object> f = fulfillment.lock(ws, foId);
        String foNo = (String) f.get("fo_no");
        List<Parcel> parcels = normalize(cmd);
        Instant shippedAt = cmd.shippedAt();
        Set<String> requested = new LinkedHashSet<>();
        parcels.forEach(p -> requested.add(p.carrierCode() + ":" + p.trackingNo()));
        String st = (String) f.get("status");
        if ("SHIPPED".equals(st)) {
            Set<String> existing = new LinkedHashSet<>(jdbc.sql("select carrier_code || ':' || tracking_key from shipment where fo_id = :f order by parcel_no")
                    .param("f", foId).query(String.class).list());
            if (existing.equals(requested)) {
                return "DUPLICATE";
            }
            throw Errors.conflict("FULFILLMENT_ALREADY_SHIPPED", "이미 다른 송장으로 출고 확정된 출고지시입니다.", Map.of("fo_no", foNo, "tracking", existing));
        }
        if ("CANCELLED".equals(st)) {
            throw Errors.conflict("FULFILLMENT_CANCELLED", "취소된 출고지시입니다. 실물이 나갔다면 반품으로 회수하세요.", Map.of("fo_no", foNo));
        }
        if (shippedAt.isBefore(instant(f.get("created_at")).minus(Duration.ofHours(1)))) {
            throw Errors.validation("shipped_at", "출고 시각이 출고 배정보다 이릅니다.");
        }
        List<Map<String, Object>> lines = jdbc.sql("select l.*, s.sku_code from fulfillment_order_line l join sku s on s.id = l.sku_id where l.fo_id = :f order by l.line_no")
                .param("f", foId).query().listOfRows();
        // 박스별 품목: 하나라도 적었으면 합이 출고지시와 같아야 한다. 아무도 안 적었으면 첫 박스에 전부.
        Map<UUID, Integer> skuTotals = new LinkedHashMap<>();
        lines.forEach(l -> skuTotals.merge((UUID) l.get("sku_id"), ((Number) l.get("quantity")).intValue(), Integer::sum));
        boolean itemized = parcels.stream().anyMatch(p -> p.items() != null && !p.items().isEmpty());
        if (itemized) {
            Map<UUID, Integer> given = new LinkedHashMap<>();
            parcels.forEach(p -> (p.items() == null ? List.<Item>of() : p.items()).forEach(i -> given.merge(i.skuId(), i.quantity(), Integer::sum)));
            if (!given.equals(skuTotals)) {
                throw Errors.unprocessable("PARCEL_ITEMS_MISMATCH", "박스별 품목 수량의 합이 출고지시 수량과 다릅니다(출고지시 단위로 전부 출고).",
                        Map.of("expected", stringKeys(skuTotals), "given", stringKeys(given)));
            }
        }
        // 접수 기록 없이 바로 출고된 건(수동 전달·자체 처리)은 출고 시각으로 접수를 남긴다(외부 재고 해석, DLG-097)
        if (Set.of("ALLOCATED", "REQUESTED", "FAILED").contains(st)) {
            String kind = jdbc.sql("select p.kind from fulfillment_node n join logistics_provider p on p.code = n.logistics_provider_code where n.id = :n")
                    .param("n", f.get("node_id")).query(String.class).single();
            String mode = "SELF_WMS".equals(kind) ? "INTERNAL" : f.get("dispatch_mode") == null ? "MANUAL" : (String) f.get("dispatch_mode");
            fulfillment.markAccepted(foId, mode, (String) f.get("external_ref"), shippedAt, (UUID) f.get("connection_id"));
        }
        // 자체 WMS 웨이브 출고지시는 스캔 검수·포장을 마친 것만 출고 확정, 피킹한 버킷 그대로 차감(DLG-149)
        UUID waveId = (UUID) f.get("wave_id");
        Map<UUID, Map<UUID, Integer>> picked = waveId == null ? Map.of() : picks.pickPlan(foId);
        if (waveId != null && !"PACKING".equals(st) && !"CANCEL_REQUESTED".equals(st)) {
            throw Errors.conflict("WMS_NOT_PACKED", "자체 WMS 웨이브 출고지시는 피킹·검수 포장을 마친 뒤 출고 확정합니다.", Map.of("status", st));
        }
        // 확보: ALLOCATED → PICKING → CONSUMED(실물 출고 시각). 같은 수량을 다시 차감하지 않는다.
        Set<UUID> reservationIds = new LinkedHashSet<>();
        lines.forEach(l -> {
            if (l.get("reservation_id") != null) {
                reservationIds.add((UUID) l.get("reservation_id"));
            }
        });
        for (UUID r : reservationIds) {
            String state = jdbc.sql("select state from inventory_reservation where id = :id").param("id", r).query(String.class).single();
            ReservationService.Transition t = new ReservationService.Transition("출고 확정 " + foNo, null);
            switch (state) {
                case "ALLOCATED" -> {
                    reservations.pick(r, t);
                    reservations.consumePlanned(r, t, shippedAt, picked.get(r));
                }
                case "PICKING" -> reservations.consumePlanned(r, t, shippedAt, picked.get(r));
                case "CONSUMED" -> {
                }
                default -> throw Errors.conflict("RESERVATION_NOT_ACTIVE", "출고지시의 재고 확보가 이미 해제되었습니다. 재고 확인 후 처리하세요.",
                        Map.of("reservation_id", r.toString(), "state", state));
            }
        }
        // 포장 부자재: 거점 포장 프로필의 유효 포장 BOM × 박스 수 차감(DLG-246~249). 확보 수량과 별개, 부족해도 출고는 진행하고 업무로
        materials.consumeForShipment(ws, foId, (UUID) f.get("business_entity_id"), (UUID) f.get("node_id"), parcels.size(), shippedAt, foNo, a.memberId());
        Map<String, Object> order = jdbc.sql("select order_no, source_type from sales_order where id = :o").param("o", f.get("order_id")).query().listOfRows().get(0);
        String channelStatus = "MANUAL".equals(order.get("source_type")) ? "NOT_REQUIRED" : "PENDING";
        Map<Integer, Integer> lineLeft = new LinkedHashMap<>();
        lines.forEach(l -> lineLeft.put(((Number) l.get("line_no")).intValue(), ((Number) l.get("quantity")).intValue()));
        List<UUID> shipmentIds = new ArrayList<>();
        String base = foNo.replaceFirst("^F", "S");
        for (int i = 0; i < parcels.size(); i++) {
            Parcel p = parcels.get(i);
            UUID id = Ids.newId();
            shipmentIds.add(id);
            jdbc.sql("""
                    insert into shipment (id, workspace_id, shipment_no, fo_id, order_id, business_entity_id, channel_account_id, node_id, parcel_no, carrier_code,
                                          tracking_no, tracking_key, status, status_at, shipped_at, source, channel_dispatch_status, created_by)
                    values (:id, :ws, :no, :f, :o, :be, :ca, :n, :pn, :c, :t, :k, 'LABEL_CREATED', :at, :at, :src, :cs, cast(:by as uuid))
                    """).param("id", id).param("ws", ws).param("no", base + "-" + (i + 1)).param("f", foId).param("o", f.get("order_id"))
                    .param("be", f.get("business_entity_id")).param("ca", f.get("channel_account_id")).param("n", f.get("node_id")).param("pn", i + 1)
                    .param("c", p.carrierCode()).param("t", p.trackingNo()).param("k", p.trackingNo()).param("at", Db.ts(shippedAt)).param("src", source)
                    .param("cs", channelStatus).param("by", a.memberId()).update();
            insertEvent(ws, id, "LABEL_CREATED", shippedAt, "출고 확정·송장 등록", null, null, source, true, "INITIAL", a.memberId());
            List<Item> items = itemized ? (p.items() == null ? List.of() : p.items())
                    : i == 0 ? skuTotals.entrySet().stream().map(e -> new Item(e.getKey(), e.getValue())).toList() : List.of();
            Map<String, Integer> merged = new LinkedHashMap<>();
            for (Item it : items) {
                int need = it.quantity();
                for (Map<String, Object> l : lines) {
                    int ln = ((Number) l.get("line_no")).intValue();
                    if (need == 0 || !it.skuId().equals(l.get("sku_id")) || lineLeft.get(ln) == 0) {
                        continue;
                    }
                    int take = Math.min(need, lineLeft.get(ln));
                    lineLeft.put(ln, lineLeft.get(ln) - take);
                    need -= take;
                    merged.merge(l.get("order_item_id") + "|" + l.get("sku_id"), take, Integer::sum);
                }
            }
            for (var e : merged.entrySet()) {
                String[] k = e.getKey().split("\\|");
                jdbc.sql("insert into shipment_item (workspace_id, shipment_id, order_item_id, sku_id, quantity) values (:ws, :s, :i, :k, :q)")
                        .param("ws", ws).param("s", id).param("i", UUID.fromString(k[0])).param("k", UUID.fromString(k[1])).param("q", e.getValue()).update();
            }
        }
        jdbc.sql("""
                update fulfillment_order set status = 'SHIPPED', shipped_at = :at, ship_source = :src, version = version + 1, updated_at = now() where id = :id
                """).param("at", Db.ts(shippedAt)).param("src", source).param("id", foId).update();
        fulfillment.foEvent(a, foId, "SHIPPED", st, "SHIPPED", shippedAt, Map.of("parcels", parcels.size(), "source", source));
        picks.closeIfDone(waveId);
        UUID orderId = (UUID) f.get("order_id");
        refreshOrderShipping(a, orderId);
        fulfillment.orderEvent(a, orderId, "FULFILLMENT_SHIPPED", null, foNo, Map.of("tracking", String.join(", ", requested)));
        workItems.resolveByKey(ws, "fulfillment:" + foId + ":TRACKING");
        workItems.resolveByKey(ws, "fulfillment:" + foId + ":DISPATCH");
        workItems.resolveByKey(ws, "fulfillment:" + foId + ":SHIP_APPLY");
        if ("CANCEL_REQUESTED".equals(st)) {
            workItems.resolveByKey(ws, "fulfillment:" + foId + ":CANCEL");
            workItems.raise(new WorkItemService.Raise(ws, (UUID) f.get("business_entity_id"), (UUID) f.get("channel_account_id"), null,
                    "FULFILLMENT_SHIPPED_DURING_CANCEL", "HIGH", "취소 요청 중 출고됨: " + foNo,
                    "출고 취소를 요청했지만 물류사에서 이미 출고되었습니다. 출고 사실은 그대로 반영했습니다.",
                    "고객 안내 후 반품(회수)으로 처리하세요.", "fulfillment:" + foId + ":SHIPPED_DURING_CANCEL", Map.of("fulfillment_order_id", foId.toString())));
        }
        outbox.publish(ws, (UUID) f.get("business_entity_id"), "FULFILLMENT_ORDER", foId, "ShipmentRecorded",
                Map.of("fulfillment_order_id", foId.toString(), "shipment_ids", shipmentIds.stream().map(UUID::toString).toList()));
        audit.record(AuditEntry.builder(ws, "SHIPMENT_RECORDED", "FULFILLMENT_ORDER", foId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) f.get("business_entity_id"), (UUID) f.get("channel_account_id"))
                .after(Map.of("fo_no", foNo, "shipped_at", shippedAt.toString(), "tracking", List.copyOf(requested), "source", source)).build());
        return "SHIPPED";
    }

    /** 물류사 출고 진행(피킹·포장). 역행·중복은 무시. 피킹 시작은 확보를 PICKING 으로 넘긴다. @return APPLIED · IGNORED */
    @Transactional(propagation = Propagation.MANDATORY)
    public String applyProgress(UUID foId, String status, Instant at) {
        CurrentActor a = guard.actor();
        Map<String, Object> f = fulfillment.lock(a.workspaceId(), foId);
        String st = (String) f.get("status");
        if (!"PICKING".equals(status) && !"PACKING".equals(status)) {
            return "IGNORED";
        }
        if (Set.of("ALLOCATED", "REQUESTED", "FAILED").contains(st)) {
            fulfillment.markAccepted(foId, f.get("dispatch_mode") == null ? "MANUAL" : (String) f.get("dispatch_mode"), (String) f.get("external_ref"), at,
                    (UUID) f.get("connection_id"));
            st = "ACCEPTED";
        }
        if (!FO_PROGRESS.contains(st) || FO_PROGRESS.indexOf(status) <= FO_PROGRESS.indexOf(st)) {
            return "IGNORED";
        }
        for (UUID r : jdbc.sql("select distinct l.reservation_id from fulfillment_order_line l join inventory_reservation r on r.id = l.reservation_id where l.fo_id = :f and r.state = 'ALLOCATED'")
                .param("f", foId).query(UUID.class).list()) {
            reservations.pick(r, new ReservationService.Transition("물류사 피킹 시작 " + f.get("fo_no"), null));
        }
        jdbc.sql("update fulfillment_order set status = :s, version = version + 1, updated_at = now() where id = :id").param("s", status).param("id", foId).update();
        fulfillment.foEvent(a, foId, status, st, status, at, Map.of());
        return "APPLIED";
    }

    // ---------------------------------------------------------------- 배송 단계

    /**
     * 배송 이벤트 반영. 같은 이벤트(dedupe_key)는 한 번만, 현재 상태 시각보다 이른 이벤트·진행 역행은 기록만 한다(T10).
     * 예외(EXCEPTION)는 배송완료 전까지 걸리고, 이후 정상 이벤트가 오면 풀린다. 배송완료는 최종.
     * @return APPLIED · IGNORED_STALE · DUPLICATE
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String applyTracking(UUID shipmentId, String status, Instant occurredAt, String description, String location, String code, String source,
                                String dedupeKey) {
        CurrentActor a = guard.actor();
        UUID ws = a.workspaceId();
        if (status == null || !STATUSES.contains(status) || occurredAt == null) {
            throw Errors.validation("status", "배송 단계와 발생 시각을 확인하세요.");
        }
        Map<String, Object> s = jdbc.sql("select * from shipment where workspace_id = :ws and id = :id for update").param("ws", ws).param("id", shipmentId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SHIPMENT"));
        String cur = (String) s.get("status");
        Instant curAt = instant(s.get("status_at"));
        boolean newer = curAt == null || !occurredAt.isBefore(curAt);
        boolean apply;
        if ("DELIVERED".equals(cur)) {
            apply = false;
        } else if ("EXCEPTION".equals(status)) {
            apply = newer;
        } else if ("EXCEPTION".equals(cur)) {
            apply = newer;
        } else {
            apply = newer && PROGRESS.indexOf(status) >= PROGRESS.indexOf(cur) && !status.equals(cur);
        }
        if (!insertEvent(ws, shipmentId, status, occurredAt, description, location, code, source, apply, dedupeKey, a.memberId())) {
            return "DUPLICATE";
        }
        if (!apply) {
            return "IGNORED_STALE";
        }
        jdbc.sql("""
                update shipment set status = :st, status_at = :at, delivered_at = case when :st = 'DELIVERED' then :at else delivered_at end,
                       version = version + 1, updated_at = now() where id = :id
                """).param("st", status).param("at", Db.ts(occurredAt)).param("id", shipmentId).update();
        String key = "shipment:" + shipmentId + ":EXCEPTION";
        if ("EXCEPTION".equals(status)) {
            workItems.raise(new WorkItemService.Raise(ws, (UUID) s.get("business_entity_id"), (UUID) s.get("channel_account_id"), null, "SHIPMENT_EXCEPTION", "HIGH",
                    "배송 예외: " + s.get("carrier_code") + " " + s.get("tracking_no"), description == null ? "택배사가 배송 예외를 알렸습니다." : description,
                    "택배사·고객에게 확인하고 필요하면 재발송·반품으로 처리하세요.", key, Map.of("shipment_id", shipmentId.toString())));
        } else {
            workItems.resolveByKey(ws, key);
        }
        if ("DELIVERED".equals(status)) {
            refreshOrderShipping(a, (UUID) s.get("order_id"));
        }
        return "APPLIED";
    }

    /** 화면에서 배송 단계 직접 기록(택배사 연동 없음 · 고객 확인 등). */
    @Transactional
    public TrackingResult addTracking(UUID shipmentId, TrackingInput in) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        ShipmentView v = get(shipmentId);
        guard.checkWrite("SHIPMENT", shipmentId, v.businessEntityId(), v.channelAccountId());
        guard.checkNode(AccessKind.WRITE, "SHIPMENT", shipmentId, v.nodeId());
        if (in == null || in.status() == null || !STATUSES.contains(in.status()) || "LABEL_CREATED".equals(in.status())) {
            throw Errors.validation("status", "배송 단계를 선택하세요.");
        }
        Instant at = in.occurredAt() == null ? clock.instant() : in.occurredAt();
        if (at.isAfter(clock.instant().plus(Duration.ofMinutes(5)))) {
            throw Errors.validation("occurred_at", "발생 시각이 미래입니다.");
        }
        String desc = trim(in.description(), 500);
        String result = applyTracking(shipmentId, in.status(), at, desc, trim(in.location(), 200), null, "MANUAL", "MANUAL:" + in.status() + ":" + at);
        CurrentActor a = guard.actor();
        audit.record(AuditEntry.builder(a.workspaceId(), "SHIPMENT_TRACKING_RECORDED", "SHIPMENT", shipmentId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(v.businessEntityId(), v.channelAccountId()).after(Map.of("status", in.status(), "occurred_at", at.toString(), "result", result)).build());
        return new TrackingResult(result, get(shipmentId));
    }

    // ---------------------------------------------------------------- 판매채널 발송처리

    @Transactional(propagation = Propagation.MANDATORY)
    public void markChannelDispatch(UUID shipmentId, String status, String mode, String ref, String error) {
        CurrentActor a = guard.actor();
        Map<String, Object> s = jdbc.sql("select * from shipment where workspace_id = :ws and id = :id for update").param("ws", a.workspaceId()).param("id", shipmentId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SHIPMENT"));
        jdbc.sql("""
                update shipment set channel_dispatch_status = :st, channel_dispatch_mode = coalesce(cast(:m as text), channel_dispatch_mode),
                       channel_dispatch_attempts = channel_dispatch_attempts + case when :m = 'API' then 1 else 0 end,
                       channel_dispatch_ref = coalesce(cast(:ref as text), channel_dispatch_ref), channel_dispatch_error = cast(:err as text),
                       channel_dispatched_at = case when :st in ('SENT', 'MANUAL_DONE') then now() else channel_dispatched_at end,
                       version = version + 1, updated_at = now() where id = :id
                """).param("st", status).param("m", mode).param("ref", ref).param("err", error).param("id", shipmentId).update();
        String key = "shipment:" + shipmentId + ":CHANNEL";
        if ("MANUAL_REQUIRED".equals(status) || "FAILED".equals(status)) {
            String orderNo = jdbc.sql("select order_no from sales_order where id = :o").param("o", s.get("order_id")).query(String.class).single();
            workItems.raise(new WorkItemService.Raise(a.workspaceId(), (UUID) s.get("business_entity_id"), (UUID) s.get("channel_account_id"), null,
                    "MANUAL_REQUIRED".equals(status) ? "CHANNEL_DISPATCH_MANUAL" : "CHANNEL_DISPATCH_FAILED", "MANUAL_REQUIRED".equals(status) ? "MEDIUM" : "HIGH",
                    ("MANUAL_REQUIRED".equals(status) ? "판매채널 발송처리 필요: " : "판매채널 송장 전송 실패: ") + orderNo + " · " + s.get("tracking_no"),
                    error, "MANUAL_REQUIRED".equals(status) ? "판매채널 판매자센터에서 발송처리(송장 입력)한 뒤 배송 화면에서 '채널 발송처리 완료'를 기록하세요."
                            : "연동 상태를 확인하고 배송 화면에서 다시 전송하거나, 판매자센터에서 직접 발송처리하세요.", key,
                    Map.of("shipment_id", shipmentId.toString(), "order_id", s.get("order_id").toString())));
        } else if ("SENT".equals(status) || "MANUAL_DONE".equals(status) || "NOT_REQUIRED".equals(status)) {
            workItems.resolveByKey(a.workspaceId(), key);
        }
    }

    @Transactional
    public ShipmentView retryChannelDispatch(UUID shipmentId) {
        ShipmentView v = writableShipment(shipmentId);
        if (!Set.of("FAILED", "MANUAL_REQUIRED").contains(v.channelDispatchStatus())) {
            throw Errors.conflict("CHANNEL_DISPATCH_STATE_INVALID", "다시 전송할 수 없는 발송처리 상태입니다.", Map.of("status", v.channelDispatchStatus()));
        }
        CurrentActor a = guard.actor();
        jdbc.sql("update shipment set channel_dispatch_status = 'PENDING', channel_dispatch_error = null, version = version + 1, updated_at = now() where id = :id")
                .param("id", shipmentId).update();
        workItems.resolveByKey(a.workspaceId(), "shipment:" + shipmentId + ":CHANNEL");
        outbox.publish(a.workspaceId(), v.businessEntityId(), "SHIPMENT", shipmentId, "ShipmentChannelDispatchRequested", Map.of("shipment_id", shipmentId.toString()));
        return get(shipmentId);
    }

    @Transactional
    public ShipmentView manualChannelDone(UUID shipmentId, ManualDone in) {
        ShipmentView v = writableShipment(shipmentId);
        if ("SENT".equals(v.channelDispatchStatus()) || "MANUAL_DONE".equals(v.channelDispatchStatus())) {
            return v;
        }
        if ("NOT_REQUIRED".equals(v.channelDispatchStatus())) {
            throw Errors.conflict("CHANNEL_DISPATCH_STATE_INVALID", "발송처리가 필요 없는 송장입니다.");
        }
        CurrentActor a = guard.actor();
        String note = trim(in == null ? null : in.note(), 500);
        markChannelDispatch(shipmentId, "MANUAL_DONE", "MANUAL", null, null);
        audit.record(AuditEntry.builder(a.workspaceId(), "CHANNEL_DISPATCH_MANUAL_DONE", "SHIPMENT", shipmentId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(v.businessEntityId(), v.channelAccountId()).reason(note).build());
        return get(shipmentId);
    }

    // ---------------------------------------------------------------- 주문 배송 축

    /** 주문상품 출고 수량(구성품 기준 완전 출고 단위)과 주문 배송 상태를 다시 계산한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshOrderShipping(CurrentActor a, UUID orderId) {
        List<Map<String, Object>> rows = jdbc.sql("""
                select i.id, i.shipped_qty, i.original_qty - i.canceled_qty as active,
                       coalesce((select min(floor(coalesce((select sum(l.quantity) from fulfillment_order_line l join fulfillment_order f on f.id = l.fo_id
                                                            where f.status = 'SHIPPED' and l.order_item_id = c.order_item_id and l.sku_id = c.component_sku_id), 0)
                                                  / c.quantity_per_unit))
                                   from order_item_component c where c.order_item_id = i.id), -1)::int as units
                  from order_item i where i.order_id = :o
                """).param("o", orderId).query().listOfRows();
        int activeItems = 0;
        int fullyShipped = 0;
        int anyShipped = 0;
        for (Map<String, Object> r : rows) {
            int active = ((Number) r.get("active")).intValue();
            int units = ((Number) r.get("units")).intValue();
            int cur = ((Number) r.get("shipped_qty")).intValue();
            if (units < 0) {
                continue; // 재고 미관리(구성 없음) 상품은 출고 대상이 아니다
            }
            int next = Math.min(active, units);
            if (next > cur) {
                jdbc.sql("update order_item set shipped_qty = :q, version = version + 1, updated_at = now() where id = :id").param("q", next).param("id", r.get("id")).update();
                fulfillment.orderEvent(a, orderId, "ITEM_SHIPPED", String.valueOf(cur), String.valueOf(next), Map.of("order_item_id", r.get("id").toString()));
            }
            if (active > 0) {
                activeItems++;
                if (Math.max(cur, next) >= active) {
                    fullyShipped++;
                }
                if (Math.max(cur, next) > 0) {
                    anyShipped++;
                }
            }
        }
        String shipping = activeItems > 0 && fullyShipped == activeItems ? "SHIPPED" : anyShipped > 0 ? "PARTIALLY_SHIPPED" : "NONE";
        Instant delivered = null;
        if ("SHIPPED".equals(shipping)) {
            Map<String, Object> d = jdbc.sql("""
                    select count(*) filter (where s.status <> 'DELIVERED') as open, max(s.delivered_at) as last from shipment s join fulfillment_order f on f.id = s.fo_id
                     where s.order_id = :o and f.status = 'SHIPPED'
                    """).param("o", orderId).query().listOfRows().get(0);
            if (((Number) d.get("open")).intValue() == 0 && d.get("last") != null) {
                shipping = "DELIVERED";
                delivered = instant(d.get("last"));
            }
        }
        String prev = jdbc.sql("select shipping_status from sales_order where id = :o").param("o", orderId).query(String.class).single();
        if (!shipping.equals(prev)) {
            jdbc.sql("update sales_order set shipping_status = :s, delivered_at = :d, version = version + 1, updated_at = now() where id = :o")
                    .param("s", shipping).param("d", Db.ts(delivered)).param("o", orderId).update();
            fulfillment.orderEvent(a, orderId, "SHIPPING_STATUS_CHANGED", prev, shipping, Map.of());
        }
    }

    // ---------------------------------------------------------------- 조회

    public List<Carrier> carriers() {
        return jdbc.sql("select code, name_ko from carrier where active order by sort_order").query((rs, i) -> new Carrier(rs.getString("code"), rs.getString("name_ko"))).list();
    }

    public List<ShipmentView> list(List<String> businessEntityIds, String status, String channelDispatchStatus, UUID orderId, UUID foId, String q) {
        guard.requirePermission(Perm.ORDER_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        Set<UUID> nodes = guard.nodeFilter(AccessKind.READ, null);
        if (bes.isEmpty() || cas.isEmpty() || nodes.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT + " where s.workspace_id = :ws and s.business_entity_id in (:bes) and s.channel_account_id in (:cas) and s.node_id in (:nodes)");
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "bes", bes, "cas", cas, "nodes", nodes));
        if (status != null && !status.isBlank()) {
            sql.append(" and s.status = :st");
            p.put("st", status);
        }
        if (channelDispatchStatus != null && !channelDispatchStatus.isBlank()) {
            sql.append(" and s.channel_dispatch_status = :cds");
            p.put("cds", channelDispatchStatus);
        }
        if (orderId != null) {
            sql.append(" and s.order_id = :o");
            p.put("o", orderId);
        }
        if (foId != null) {
            sql.append(" and s.fo_id = :f");
            p.put("f", foId);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" and (s.tracking_key like :q or so.order_no like :q2 or f.fo_no like :q2)");
            p.put("q", "%" + trackingKey(q) + "%");
            p.put("q2", "%" + q.trim().toUpperCase(Locale.ROOT) + "%");
        }
        sql.append(" order by s.created_at desc, s.parcel_no limit 300");
        return jdbc.sql(sql.toString()).params(p).query((rs, i) -> view(rs)).list().stream().map(this::withParts).toList();
    }

    public ShipmentView get(UUID id) {
        guard.requirePermission(Perm.ORDER_READ);
        ShipmentView v = jdbc.sql(SELECT + " where s.workspace_id = :ws and s.id = :id").param("ws", guard.actor().workspaceId()).param("id", id)
                .query((rs, i) -> view(rs)).optional().orElseThrow(() -> Errors.notFound("SHIPMENT"));
        guard.checkRead("SHIPMENT", id, v.businessEntityId(), v.channelAccountId());
        guard.checkNode(AccessKind.READ, "SHIPMENT", id, v.nodeId());
        return withParts(v);
    }

    // ---------------------------------------------------------------- 내부

    private ShipmentView writableShipment(UUID id) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        ShipmentView v = get(id);
        guard.checkWrite("SHIPMENT", id, v.businessEntityId(), v.channelAccountId());
        guard.checkNode(AccessKind.WRITE, "SHIPMENT", id, v.nodeId());
        return v;
    }

    private List<Parcel> normalize(Ship cmd) {
        if (cmd == null || cmd.shippedAt() == null) {
            throw Errors.validation("shipped_at", "출고 시각을 입력하세요.");
        }
        if (cmd.shippedAt().isAfter(clock.instant().plus(Duration.ofMinutes(5)))) {
            throw Errors.validation("shipped_at", "출고 시각이 미래입니다.");
        }
        if (cmd.parcels() == null || cmd.parcels().isEmpty() || cmd.parcels().size() > 20) {
            throw Errors.validation("parcels", "송장을 1~20개 입력하세요.");
        }
        List<Parcel> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Parcel p : cmd.parcels()) {
            String carrier = p == null || p.carrierCode() == null ? null : p.carrierCode().trim().toUpperCase(Locale.ROOT);
            boolean known = carrier != null && jdbc.sql("select count(*) from carrier where code = :c and active").param("c", carrier).query(Integer.class).single() > 0;
            if (!known) {
                throw Errors.validation("carrier_code", "택배사를 선택하세요.");
            }
            String key = trackingKey(p.trackingNo());
            if (key == null || !TRACKING.matcher(key).matches()) {
                throw Errors.validation("tracking_no", "송장번호는 숫자·영문 4~40자입니다.");
            }
            if (!seen.add(carrier + ":" + key)) {
                throw Errors.validation("tracking_no", "같은 송장번호를 두 번 입력했습니다.");
            }
            List<Item> items = p.items() == null ? null : p.items().stream().peek(i -> {
                if (i == null || i.skuId() == null || i.quantity() == null || i.quantity() < 1) {
                    throw Errors.validation("items", "박스 품목의 SKU·수량을 확인하세요.");
                }
            }).toList();
            out.add(new Parcel(carrier, key, items));
        }
        return out;
    }

    static String trackingKey(String raw) {
        if (raw == null) {
            return null;
        }
        String k = raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        return k.isEmpty() ? null : k;
    }

    private boolean insertEvent(UUID ws, UUID shipmentId, String status, Instant at, String description, String location, String code, String source,
                                boolean applied, String dedupeKey, UUID member) {
        return jdbc.sql("""
                insert into shipment_event (id, workspace_id, shipment_id, status, occurred_at, description, location, external_code, source, applied, dedupe_key,
                                            actor_member_id)
                values (:id, :ws, :s, :st, :at, cast(:d as text), cast(:l as text), cast(:c as text), :src, :ap, :k, cast(:m as uuid))
                on conflict (shipment_id, dedupe_key) do nothing
                """).param("id", Ids.newId()).param("ws", ws).param("s", shipmentId).param("st", status).param("at", Db.ts(at)).param("d", description)
                .param("l", location).param("c", code).param("src", source).param("ap", applied).param("k", dedupeKey).param("m", member).update() > 0;
    }

    static Instant instant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (v instanceof java.time.OffsetDateTime o) {
            return o.toInstant();
        }
        return (Instant) v;
    }

    private static Map<String, Integer> stringKeys(Map<UUID, Integer> m) {
        Map<String, Integer> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k.toString(), v));
        return out;
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static final String SELECT = """
            select s.*, f.fo_no, so.order_no, be.display_name as be_name, n.name as node_name, c.name_ko as carrier_name from shipment s
              join fulfillment_order f on f.id = s.fo_id join sales_order so on so.id = s.order_id join business_entity be on be.id = s.business_entity_id
              join fulfillment_node n on n.id = s.node_id join carrier c on c.code = s.carrier_code
            """;

    private static ShipmentView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ShipmentView(Db.uuid(rs, "id"), rs.getString("shipment_no"), Db.uuid(rs, "fo_id"), rs.getString("fo_no"), Db.uuid(rs, "order_id"),
                rs.getString("order_no"), Db.uuid(rs, "business_entity_id"), rs.getString("be_name"), Db.uuid(rs, "channel_account_id"), Db.uuid(rs, "node_id"),
                rs.getString("node_name"), rs.getInt("parcel_no"), rs.getString("carrier_code"), rs.getString("carrier_name"), rs.getString("tracking_no"),
                rs.getString("status"), Db.instant(rs, "status_at"), Db.instant(rs, "shipped_at"), Db.instant(rs, "delivered_at"), rs.getString("source"),
                rs.getString("channel_dispatch_status"), rs.getString("channel_dispatch_mode"), rs.getInt("channel_dispatch_attempts"),
                rs.getString("channel_dispatch_ref"), rs.getString("channel_dispatch_error"), Db.instant(rs, "channel_dispatched_at"), Db.instant(rs, "created_at"),
                List.of(), List.of(), rs.getInt("version"));
    }

    private ShipmentView withParts(ShipmentView v) {
        List<ItemView> items = jdbc.sql("select i.*, k.sku_code from shipment_item i join sku k on k.id = i.sku_id where i.shipment_id = :s order by k.sku_code")
                .param("s", v.id()).query((rs, i) -> new ItemView(Db.uuid(rs, "order_item_id"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"), rs.getInt("quantity")))
                .list();
        List<EventView> events = jdbc.sql("select * from shipment_event where shipment_id = :s order by occurred_at desc, received_at desc limit 100").param("s", v.id())
                .query((rs, i) -> new EventView(rs.getString("status"), Db.instant(rs, "occurred_at"), rs.getString("description"), rs.getString("location"),
                        rs.getString("source"), rs.getBoolean("applied"), Db.instant(rs, "received_at"))).list();
        return new ShipmentView(v.id(), v.shipmentNo(), v.fulfillmentOrderId(), v.foNo(), v.orderId(), v.orderNo(), v.businessEntityId(), v.businessEntityName(),
                v.channelAccountId(), v.nodeId(), v.nodeName(), v.parcelNo(), v.carrierCode(), v.carrierName(), v.trackingNo(), v.status(), v.statusAt(),
                v.shippedAt(), v.deliveredAt(), v.source(), v.channelDispatchStatus(), v.channelDispatchMode(), v.channelDispatchAttempts(), v.channelDispatchRef(),
                v.channelDispatchError(), v.channelDispatchedAt(), v.createdAt(), items, events, v.version());
    }
}
