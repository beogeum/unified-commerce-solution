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
import com.commerceos.shared.json.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 출고 배정(M4 O4, docs/04 §1·§4). 확정된 출고 구성을 한 거점에서 모두 채울 수 있는 첫 거점(배정 우선순위 → 코드 순)을 고르고,
 * 출고지시 1건 = 확보 수요 1건으로 원자적 확보(전부 또는 실패, I3) 후 ALLOCATED 로 결합한다(CHG-009).
 * 재고 소유 = 주문 사업자(교차 소유 출고 금지). v1 은 거점 분할 출고를 하지 않는다(DLG-123) — 채울 거점이 없으면 배정 실패 + 업무함.
 */
@Service
public class FulfillmentService {
    static final Set<String> ACTIVE = Set.of("ALLOCATED", "REQUESTED", "ACCEPTED", "PICKING", "PACKING", "CANCEL_REQUESTED");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final ReservationService reservations;
    private final WorkItemService workItems;
    private final OutboxPublisher outbox;
    private final AuditService audit;
    private final Json json;
    private final WmsPickPlans picks;

    public FulfillmentService(JdbcClient jdbc, AccessGuard guard, ReservationService reservations, WorkItemService workItems, OutboxPublisher outbox,
                              AuditService audit, Json json, WmsPickPlans picks) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.reservations = reservations;
        this.workItems = workItems;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
        this.picks = picks;
    }

    public record AllocationResult(String outcome, UUID fulfillmentOrderId, String nodeName, List<Map<String, Object>> shortages) {
    }

    public record LineView(int lineNo, UUID orderItemId, UUID skuId, String skuCode, int quantity, UUID reservationId, String reservationState) {
    }

    public record EventView(String eventType, String fromStatus, String toStatus, Instant externalAt, JsonNode detail, String actorType, Instant occurredAt) {
    }

    public record FoView(UUID id, String foNo, UUID orderId, String orderNo, UUID businessEntityId, String businessEntityName, UUID channelAccountId, UUID nodeId,
                         String nodeName, String providerKind, String status, String dispatchKey, String dispatchMode, String externalRef, int attempts,
                         String lastErrorCode, String lastErrorMessage, Instant requestedAt, Instant acceptedAt, Instant createdAt, List<LineView> lines,
                         List<EventView> events, int version) {
    }

    public record Cancel(String reason, Boolean pickReturnConfirmed) {
    }

    public record ManualAccept(String externalRef, Instant acceptedAt) {
    }

    // ---------------------------------------------------------------- 배정

    /** 사용자 실행(출고 배정 버튼). 자동 배정은 시스템 자동 처리 주체로 같은 메서드를 부른다. */
    @Transactional
    public AllocationResult allocate(UUID orderId) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        Map<String, Object> o = jdbc.sql("select * from sales_order where workspace_id = :ws and id = :id for update").param("ws", a.workspaceId()).param("id", orderId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SALES_ORDER"));
        UUID be = (UUID) o.get("business_entity_id");
        guard.checkWrite("SALES_ORDER", orderId, be, (UUID) o.get("channel_account_id"));
        if ("PLATFORM_FULFILLMENT".equals(o.get("source_type"))) {
            jdbc.sql("update sales_order set allocation_status = 'NOT_REQUIRED' where id = :id").param("id", orderId).update();
            return new AllocationResult("NOT_REQUIRED", null, null, List.of());
        }
        if (Boolean.TRUE.equals(o.get("on_hold"))) {
            throw Errors.conflict("ORDER_ON_HOLD", "보류 중인 주문은 출고 배정할 수 없습니다. 보류를 먼저 해결하세요.");
        }
        if (!Set.of("CONFIRMED", "PROCESSING").contains((String) o.get("status")) || !"FIXED".equals(o.get("composition_status"))) {
            throw Errors.conflict("ORDER_NOT_READY", "결제 확인·출고 구성 확정 전 주문입니다.", Map.of("status", o.get("status"), "composition", o.get("composition_status")));
        }
        // 남은 필요 수량(주문상품 × 구성 − 진행 중 출고지시에 이미 담긴 수량)
        List<Map<String, Object>> need = jdbc.sql("""
                select c.order_item_id, c.component_sku_id as sku_id, s.sku_code,
                       (i.original_qty - i.canceled_qty) * c.quantity_per_unit
                         - coalesce((select sum(l.quantity) from fulfillment_order_line l join fulfillment_order f on f.id = l.fo_id
                                      where l.order_item_id = c.order_item_id and l.sku_id = c.component_sku_id and f.status <> 'CANCELLED'), 0) as remaining
                  from order_item_component c join order_item i on i.id = c.order_item_id join sku s on s.id = c.component_sku_id
                 where c.order_id = :o order by i.line_no, s.sku_code
                """).param("o", orderId).query().listOfRows().stream().filter(r -> ((Number) r.get("remaining")).intValue() > 0).toList();
        if (need.isEmpty()) {
            return new AllocationResult("NOTHING_TO_ALLOCATE", null, null, List.of());
        }
        Map<UUID, Integer> bySku = new LinkedHashMap<>();
        need.forEach(r -> bySku.merge((UUID) r.get("sku_id"), ((Number) r.get("remaining")).intValue(), Integer::sum));
        List<Map<String, Object>> nodes = jdbc.sql("""
                select n.id, n.name, n.code from fulfillment_node n join logistics_provider p on p.code = n.logistics_provider_code
                 where n.workspace_id = :ws and n.status = 'ACTIVE'
                   and (p.kind = 'SELF_WMS' or exists (select 1 from fulfillment_node_shipper l join shipper_account sa on sa.id = l.shipper_account_id
                                                      where l.node_id = n.id and l.business_entity_id = :be and sa.status = 'ACTIVE'))
                 order by n.routing_priority, n.code
                """).param("ws", a.workspaceId()).param("be", be).query().listOfRows().stream()
                .filter(n -> guard.scope().allowsNode(AccessKind.WRITE, (UUID) n.get("id"))).toList();
        UUID foId = Ids.newId();
        String chosenName = null;
        UUID chosen = null;
        List<ReservationService.ReservationView> reserved = null;
        for (Map<String, Object> n : nodes) {
            UUID node = (UUID) n.get("id");
            if (!quickFits(be, node, bySku)) {
                continue;
            }
            var attempt = reservations.tryReserve(new ReservationService.Reserve(be, node, "FULFILLMENT_ORDER", foId.toString(),
                    bySku.entrySet().stream().map(e -> new ReservationService.Line(e.getKey(), e.getValue())).toList(), null));
            if (attempt.isPresent()) {
                reserved = attempt.get();
                chosen = node;
                chosenName = (String) n.get("name");
                break;
            }
        }
        String orderNo = (String) o.get("order_no");
        if (chosen == null) {
            List<Map<String, Object>> shortages = new ArrayList<>();
            for (var e : bySku.entrySet()) {
                int total = jdbc.sql("""
                        select coalesce(sum(greatest(sellable_on_hand - committed - restricted_hold, 0)), 0) from inventory_position
                         where workspace_id = :ws and owner_business_entity_id = :b and sku_id = :s
                        """).param("ws", a.workspaceId()).param("b", be).param("s", e.getKey()).query(Integer.class).single();
                String code = jdbc.sql("select sku_code from sku where id = :s").param("s", e.getKey()).query(String.class).single();
                shortages.add(Map.of("sku_id", e.getKey().toString(), "sku_code", code, "required", e.getValue(), "available_all_nodes", total));
            }
            jdbc.sql("update sales_order set allocation_status = 'FAILED', version = version + 1, updated_at = now() where id = :id").param("id", orderId).update();
            orderEvent(a, orderId, "ALLOCATION_FAILED", null, null, Map.of("shortages", shortages));
            workItems.raise(new WorkItemService.Raise(a.workspaceId(), be, (UUID) o.get("channel_account_id"), null, "ORDER_ALLOCATION_FAILED", "HIGH",
                    "출고 배정 실패: " + orderNo, "주문 전체를 한 거점에서 채울 수 있는 판매 가능 재고가 없습니다(거점 분할 출고는 하지 않음).",
                    "입고·재고 이동으로 한 거점에 재고를 모으거나, 고객 안내 후 일부 취소하세요. 재고가 생기면 주문 상세에서 출고 배정을 다시 실행하세요.",
                    "order:" + orderId + ":ALLOCATION", Map.of("order_id", orderId.toString(), "shortages", shortages)));
            return new AllocationResult("FAILED", null, null, shortages);
        }
        int seq = jdbc.sql("select count(*) + 1 from fulfillment_order where order_id = :o").param("o", orderId).query(Integer.class).single();
        String foNo = orderNo.replaceFirst("^O", "F") + "-" + seq;
        jdbc.sql("""
                insert into fulfillment_order (id, workspace_id, fo_no, order_id, business_entity_id, channel_account_id, node_id, status, dispatch_key, created_by)
                values (:id, :ws, :no, :o, :be, :ca, :n, 'ALLOCATED', :key, cast(:by as uuid))
                """).param("id", foId).param("ws", a.workspaceId()).param("no", foNo).param("o", orderId).param("be", be).param("ca", o.get("channel_account_id"))
                .param("n", chosen).param("key", foNo).param("by", a.memberId()).update();
        Map<UUID, UUID> reservationBySku = new LinkedHashMap<>();
        for (ReservationService.ReservationView r : reserved) {
            reservationBySku.put(r.skuId(), r.id());
            reservations.allocate(r.id(), new ReservationService.Transition("출고지시 " + foNo, null));
        }
        int line = 1;
        for (Map<String, Object> r : need) {
            jdbc.sql("insert into fulfillment_order_line (workspace_id, fo_id, line_no, order_item_id, sku_id, quantity, reservation_id) values (:ws, :f, :l, :i, :s, :q, :r)")
                    .param("ws", a.workspaceId()).param("f", foId).param("l", line++).param("i", r.get("order_item_id")).param("s", r.get("sku_id"))
                    .param("q", ((Number) r.get("remaining")).intValue()).param("r", reservationBySku.get((UUID) r.get("sku_id"))).update();
        }
        jdbc.sql("""
                update sales_order set allocation_status = 'ALLOCATED', status = case when status = 'CONFIRMED' then 'PROCESSING' else status end,
                       version = version + 1, updated_at = now() where id = :id
                """).param("id", orderId).update();
        workItems.resolveByKey(a.workspaceId(), "order:" + orderId + ":ALLOCATION");
        foEvent(a, foId, "ALLOCATED", null, "ALLOCATED", null, Map.of("node_id", chosen.toString(), "lines", need.size()));
        orderEvent(a, orderId, "FULFILLMENT_ALLOCATED", null, foNo, Map.of("node", chosenName));
        if ("CONFIRMED".equals(o.get("status"))) {
            orderEvent(a, orderId, "STATUS_CHANGED", "CONFIRMED", "PROCESSING", Map.of());
        }
        outbox.publish(a.workspaceId(), be, "FULFILLMENT_ORDER", foId, "FulfillmentOrderCreated", Map.of("fulfillment_order_id", foId.toString(), "order_id", orderId.toString()));
        audit.record(AuditEntry.builder(a.workspaceId(), "FULFILLMENT_ALLOCATED", "FULFILLMENT_ORDER", foId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, (UUID) o.get("channel_account_id")).after(Map.of("fo_no", foNo, "node", chosenName)).build());
        return new AllocationResult("ALLOCATED", foId, chosenName, List.of());
    }

    private boolean quickFits(UUID be, UUID node, Map<UUID, Integer> bySku) {
        for (var e : bySku.entrySet()) {
            int atp = jdbc.sql("""
                    select coalesce(max(greatest(sellable_on_hand - committed - restricted_hold, 0)), 0) from inventory_position
                     where owner_business_entity_id = :b and sku_id = :s and node_id = :n
                    """).param("b", be).param("s", e.getKey()).param("n", node).query(Integer.class).single();
            if (atp < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 물류사 접수·취소

    /** 물류사 접수 확정(API 응답·사후조회·수동 기록 공통). 3PL 거점 확보에는 접수 시각을 남겨 외부 재고 해석에 쓴다(DLG-097). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markAccepted(UUID foId, String mode, String externalRef, Instant at, UUID connectionId) {
        CurrentActor a = guard.actor();
        Map<String, Object> f = lock(a.workspaceId(), foId);
        String st = (String) f.get("status");
        if ("ACCEPTED".equals(st) || "PICKING".equals(st) || "PACKING".equals(st) || "SHIPPED".equals(st)) {
            return; // 늦게 온 같은 결과 — 역행 금지
        }
        if (!"ALLOCATED".equals(st) && !"REQUESTED".equals(st) && !"FAILED".equals(st)) {
            throw Errors.conflict("FULFILLMENT_STATE_INVALID", "접수할 수 없는 출고 상태입니다.", Map.of("status", st));
        }
        jdbc.sql("""
                update fulfillment_order set status = 'ACCEPTED', dispatch_mode = :m, external_ref = cast(:ref as text), connection_id = cast(:c as uuid),
                       accepted_at = :at, last_error_code = null, last_error_message = null, version = version + 1, updated_at = now() where id = :id
                """).param("m", mode).param("ref", externalRef).param("c", connectionId).param("at", Db.ts(at)).param("id", foId).update();
        if (!"INTERNAL".equals(mode)) {
            for (UUID r : jdbc.sql("select distinct reservation_id from fulfillment_order_line where fo_id = :f and reservation_id is not null").param("f", foId)
                    .query(UUID.class).list()) {
                reservations.acknowledgeExternal(r, new ReservationService.ExternalAck(at, externalRef));
            }
        }
        foEvent(a, foId, "ACCEPTED", st, "ACCEPTED", at, Map.of("mode", mode, "external_ref", String.valueOf(externalRef)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markRequested(UUID foId, UUID connectionId) {
        CurrentActor a = guard.actor();
        Map<String, Object> f = lock(a.workspaceId(), foId);
        String st = (String) f.get("status");
        jdbc.sql("""
                update fulfillment_order set status = case when status = 'ALLOCATED' then 'REQUESTED' else status end, dispatch_mode = 'API', connection_id = :c,
                       attempts = attempts + 1, requested_at = coalesce(requested_at, now()), version = version + 1, updated_at = now() where id = :id
                """).param("c", connectionId).param("id", foId).update();
        foEvent(a, foId, "DISPATCH_ATTEMPT", st, "REQUESTED", null, Map.of("attempt", ((Number) f.get("attempts")).intValue() + 1));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markDispatchProblem(UUID foId, String status, String mode, String code, String message) {
        CurrentActor a = guard.actor();
        Map<String, Object> f = lock(a.workspaceId(), foId);
        jdbc.sql("""
                update fulfillment_order set status = :st, dispatch_mode = coalesce(cast(:m as text), dispatch_mode), last_error_code = :c, last_error_message = :msg,
                       version = version + 1, updated_at = now() where id = :id
                """).param("st", status).param("m", mode).param("c", code).param("msg", message).param("id", foId).update();
        foEvent(a, foId, "DISPATCH_" + ("MANUAL".equals(mode) ? "MANUAL_REQUIRED" : "FAILED"), (String) f.get("status"), status, null, Map.of("code", code));
        workItems.raise(new WorkItemService.Raise(a.workspaceId(), (UUID) f.get("business_entity_id"), (UUID) f.get("channel_account_id"), null,
                "MANUAL".equals(mode) ? "FULFILLMENT_DISPATCH_MANUAL" : "FULFILLMENT_DISPATCH_FAILED", "MANUAL".equals(mode) ? "MEDIUM" : "HIGH",
                ("MANUAL".equals(mode) ? "물류사 출고지시 수동 전달 필요: " : "출고지시 전송 실패: ") + f.get("fo_no"), message,
                "MANUAL".equals(mode) ? "물류사 화면·파일로 출고지시를 전달한 뒤 출고 화면에서 '물류사 접수 기록'을 남기세요."
                        : "연동 상태·지원 기능을 확인하고 출고 화면에서 다시 전송하세요.", "fulfillment:" + foId + ":DISPATCH",
                Map.of("fulfillment_order_id", foId.toString(), "code", code)));
    }

    /** 다시 전송(실패·수동 대기·응답 불명 건). 전송은 비동기 작업으로. */
    @Transactional
    public FoView requestDispatch(UUID foId) {
        Map<String, Object> f = writable(foId);
        CurrentActor a = guard.actor();
        String st = (String) f.get("status");
        if (!Set.of("ALLOCATED", "REQUESTED", "FAILED").contains(st)) {
            throw Errors.conflict("FULFILLMENT_STATE_INVALID", "전송할 수 없는 출고 상태입니다.", Map.of("status", st));
        }
        jdbc.sql("update fulfillment_order set status = case when status = 'FAILED' then 'ALLOCATED' else status end, dispatch_mode = null, version = version + 1 where id = :id")
                .param("id", foId).update();
        workItems.resolveByKey(a.workspaceId(), "fulfillment:" + foId + ":DISPATCH");
        foEvent(a, foId, "DISPATCH_REQUESTED", st, st.equals("FAILED") ? "ALLOCATED" : st, null, Map.of());
        outbox.publish(a.workspaceId(), (UUID) f.get("business_entity_id"), "FULFILLMENT_ORDER", foId, "FulfillmentDispatchRequested",
                Map.of("fulfillment_order_id", foId.toString()));
        return get(foId);
    }

    @Transactional
    public FoView manualAccept(UUID foId, ManualAccept m) {
        writable(foId);
        if (m == null || m.externalRef() == null || m.externalRef().isBlank() || m.acceptedAt() == null) {
            throw Errors.validation("external_ref", "물류사 출고 번호와 접수 시각을 입력하세요.");
        }
        CurrentActor a = guard.actor();
        markAccepted(foId, "MANUAL", m.externalRef().trim(), m.acceptedAt(), null);
        workItems.resolveByKey(a.workspaceId(), "fulfillment:" + foId + ":DISPATCH");
        return get(foId);
    }

    /**
     * 출고지시 취소(DLG-114). 배정만 된 건은 바로 취소·확보 해제, 물류사에 전달된 건은 물류사 취소 확인이 필요(API 취소 또는 수동 확인 플래그),
     * 피킹 중은 작업 복귀 확인이 있어야 해제한다. 출고된 건은 반품으로 처리.
     */
    @Transactional
    public FoView cancel(UUID foId, Cancel c) {
        Map<String, Object> f = writable(foId);
        CurrentActor a = guard.actor();
        String reason = c == null || c.reason() == null || c.reason().isBlank() ? null : c.reason().trim();
        if (reason == null || reason.length() > 500) {
            throw Errors.validation("reason", "취소 사유를 500자 이내로 입력하세요.");
        }
        String st = (String) f.get("status");
        boolean confirmed = c != null && Boolean.TRUE.equals(c.pickReturnConfirmed());
        switch (st) {
            case "SHIPPED" -> throw Errors.conflict("FULFILLMENT_ALREADY_SHIPPED", "이미 출고된 건은 취소가 아니라 반품으로 처리합니다.");
            case "CANCELLED" -> {
                return get(foId);
            }
            case "REQUESTED", "ACCEPTED", "PICKING", "PACKING", "CANCEL_REQUESTED" -> {
                if (!confirmed) {
                    jdbc.sql("update fulfillment_order set status = 'CANCEL_REQUESTED', cancel_reason = :r, version = version + 1, updated_at = now() where id = :id")
                            .param("r", reason).param("id", foId).update();
                    foEvent(a, foId, "CANCEL_REQUESTED", st, "CANCEL_REQUESTED", null, Map.of("reason", reason));
                    workItems.raise(new WorkItemService.Raise(a.workspaceId(), (UUID) f.get("business_entity_id"), (UUID) f.get("channel_account_id"), null,
                            "FULFILLMENT_CANCEL_CONFIRM", "HIGH", "출고 취소 확인 필요: " + f.get("fo_no"),
                            "물류사에 전달된 출고지시입니다. 물류사가 출고를 멈췄는지 확인되기 전에는 재고 확보를 풀지 않습니다.",
                            "물류사에 취소를 요청·확인한 뒤 '물류사 취소 확인'으로 다시 취소하세요.", "fulfillment:" + foId + ":CANCEL", Map.of("fulfillment_order_id", foId.toString())));
                    return get(foId);
                }
            }
            default -> {
            }
        }
        reservations.releaseByDemand(new ReservationService.ReleaseByDemand("FULFILLMENT_ORDER", foId.toString(), "출고 취소: " + reason, true));
        jdbc.sql("update fulfillment_order set status = 'CANCELLED', cancel_reason = :r, cancelled_at = now(), version = version + 1, updated_at = now() where id = :id")
                .param("r", reason).param("id", foId).update();
        foEvent(a, foId, "CANCELLED", st, "CANCELLED", null, Map.of("reason", reason));
        picks.closeIfDone((UUID) f.get("wave_id"));
        workItems.resolveByKey(a.workspaceId(), "fulfillment:" + foId + ":CANCEL");
        workItems.resolveByKey(a.workspaceId(), "fulfillment:" + foId + ":DISPATCH");
        UUID orderId = (UUID) f.get("order_id");
        int active = jdbc.sql("select count(*) from fulfillment_order where order_id = :o and status <> 'CANCELLED'").param("o", orderId).query(Integer.class).single();
        if (active == 0) {
            jdbc.sql("""
                    update sales_order set allocation_status = 'PENDING', status = case when status = 'PROCESSING' then 'CONFIRMED' else status end,
                           version = version + 1, updated_at = now() where id = :id
                    """).param("id", orderId).update();
        }
        orderEvent(a, orderId, "FULFILLMENT_CANCELLED", (String) f.get("fo_no"), null, Map.of("reason", reason));
        outbox.publish(a.workspaceId(), (UUID) f.get("business_entity_id"), "FULFILLMENT_ORDER", foId, "FulfillmentOrderCancelled",
                Map.of("fulfillment_order_id", foId.toString(), "order_id", orderId.toString()));
        audit.record(AuditEntry.builder(a.workspaceId(), "FULFILLMENT_CANCELLED", "FULFILLMENT_ORDER", foId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) f.get("business_entity_id"), (UUID) f.get("channel_account_id")).reason(reason).build());
        return get(foId);
    }

    // ---------------------------------------------------------------- 조회

    public List<FoView> list(List<String> businessEntityIds, String status, UUID nodeId, UUID orderId) {
        guard.requirePermission(Perm.ORDER_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        Set<UUID> nodes = guard.nodeFilter(AccessKind.READ, nodeId);
        if (bes.isEmpty() || cas.isEmpty() || nodes.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT + " where f.workspace_id = :ws and f.business_entity_id in (:bes) and f.channel_account_id in (:cas) and f.node_id in (:nodes)");
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "bes", bes, "cas", cas, "nodes", nodes));
        if (status != null && !status.isBlank()) {
            sql.append(" and f.status = :st");
            p.put("st", status);
        }
        if (orderId != null) {
            sql.append(" and f.order_id = :o");
            p.put("o", orderId);
        }
        sql.append(" order by f.id desc limit 300");
        return jdbc.sql(sql.toString()).params(p).query((rs, i) -> view(rs)).list().stream().map(this::withParts).toList();
    }

    public FoView get(UUID id) {
        guard.requirePermission(Perm.ORDER_READ);
        FoView v = jdbc.sql(SELECT + " where f.workspace_id = :ws and f.id = :id").param("ws", guard.actor().workspaceId()).param("id", id).query((rs, i) -> view(rs))
                .optional().orElseThrow(() -> Errors.notFound("FULFILLMENT_ORDER"));
        guard.checkRead("FULFILLMENT_ORDER", id, v.businessEntityId(), v.channelAccountId());
        guard.checkNode(AccessKind.READ, "FULFILLMENT_ORDER", id, v.nodeId());
        return withParts(v);
    }

    // ---------------------------------------------------------------- 내부

    Map<String, Object> writable(UUID foId) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        Map<String, Object> f = lock(a.workspaceId(), foId);
        guard.checkWrite("FULFILLMENT_ORDER", foId, (UUID) f.get("business_entity_id"), (UUID) f.get("channel_account_id"));
        guard.checkNode(AccessKind.WRITE, "FULFILLMENT_ORDER", foId, (UUID) f.get("node_id"));
        return f;
    }

    Map<String, Object> lock(UUID ws, UUID foId) {
        return jdbc.sql("select * from fulfillment_order where workspace_id = :ws and id = :id for update").param("ws", ws).param("id", foId).query().listOfRows()
                .stream().findFirst().orElseThrow(() -> Errors.notFound("FULFILLMENT_ORDER"));
    }

    void foEvent(CurrentActor a, UUID foId, String type, String from, String to, Instant externalAt, Map<String, Object> detail) {
        jdbc.sql("""
                insert into fulfillment_event (id, workspace_id, fo_id, event_type, from_status, to_status, external_at, detail, actor_type, actor_member_id)
                values (:id, :ws, :f, :t, cast(:fr as text), cast(:to as text), :ext, cast(:d as jsonb), :at, cast(:m as uuid))
                """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("f", foId).param("t", type).param("fr", from).param("to", to)
                .param("ext", Db.ts(externalAt)).param("d", json.write(detail)).param("at", a.memberId() == null ? "SYSTEM" : "USER").param("m", a.memberId()).update();
    }

    void orderEvent(CurrentActor a, UUID orderId, String type, String from, String to, Map<String, Object> detail) {
        jdbc.sql("""
                insert into order_event (id, workspace_id, order_id, event_type, from_value, to_value, detail, actor_type, actor_member_id)
                values (:id, :ws, :o, :t, cast(:f as text), cast(:to as text), cast(:d as jsonb), :at, cast(:m as uuid))
                """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("o", orderId).param("t", type).param("f", from).param("to", to)
                .param("d", json.write(detail)).param("at", a.memberId() == null ? "SYSTEM" : "USER").param("m", a.memberId()).update();
    }

    private static final String SELECT = """
            select f.*, so.order_no, be.display_name as be_name, n.name as node_name, p.kind as provider_kind from fulfillment_order f
              join sales_order so on so.id = f.order_id join business_entity be on be.id = f.business_entity_id
              join fulfillment_node n on n.id = f.node_id join logistics_provider p on p.code = n.logistics_provider_code
            """;

    private static FoView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FoView(Db.uuid(rs, "id"), rs.getString("fo_no"), Db.uuid(rs, "order_id"), rs.getString("order_no"), Db.uuid(rs, "business_entity_id"),
                rs.getString("be_name"), Db.uuid(rs, "channel_account_id"), Db.uuid(rs, "node_id"), rs.getString("node_name"), rs.getString("provider_kind"),
                rs.getString("status"), rs.getString("dispatch_key"), rs.getString("dispatch_mode"), rs.getString("external_ref"), rs.getInt("attempts"),
                rs.getString("last_error_code"), rs.getString("last_error_message"), Db.instant(rs, "requested_at"), Db.instant(rs, "accepted_at"),
                Db.instant(rs, "created_at"), List.of(), List.of(), rs.getInt("version"));
    }

    private FoView withParts(FoView v) {
        List<LineView> lines = jdbc.sql("""
                select l.*, s.sku_code, r.state from fulfillment_order_line l join sku s on s.id = l.sku_id left join inventory_reservation r on r.id = l.reservation_id
                 where l.fo_id = :f order by l.line_no
                """).param("f", v.id()).query((rs, i) -> new LineView(rs.getInt("line_no"), Db.uuid(rs, "order_item_id"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"),
                rs.getInt("quantity"), Db.uuid(rs, "reservation_id"), rs.getString("state"))).list();
        List<EventView> events = jdbc.sql("select * from fulfillment_event where fo_id = :f order by occurred_at desc, id desc limit 100").param("f", v.id())
                .query((rs, i) -> new EventView(rs.getString("event_type"), rs.getString("from_status"), rs.getString("to_status"), Db.instant(rs, "external_at"),
                        json.read(rs.getString("detail")), rs.getString("actor_type"), Db.instant(rs, "occurred_at"))).list();
        return new FoView(v.id(), v.foNo(), v.orderId(), v.orderNo(), v.businessEntityId(), v.businessEntityName(), v.channelAccountId(), v.nodeId(), v.nodeName(),
                v.providerKind(), v.status(), v.dispatchKey(), v.dispatchMode(), v.externalRef(), v.attempts(), v.lastErrorCode(), v.lastErrorMessage(),
                v.requestedAt(), v.acceptedAt(), v.createdAt(), lines, events, v.version());
    }
}
