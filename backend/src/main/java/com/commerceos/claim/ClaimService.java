package com.commerceos.claim;

import com.commerceos.fulfillment.FulfillmentService;
import com.commerceos.fulfillment.ShipmentService;
import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.ActorFactory;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.operations.WorkItemService;
import com.commerceos.order.OrderRules;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.outbox.OutboxPublisher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * 취소·반품·교환 클레임(M4 O6, docs/04 §5·§6, DLG-115·137~146).
 * <ul>
 *   <li>상태축 4개(클레임·회수·환불·재출고)는 독립. 요청만으로 주문 수량·재고·매출을 바꾸지 않는다.</li>
 *   <li>취소: 승인 시 출고 전이면 바로 취소 수량 반영 + 남은 수량 재배정. 물류에 전달된 출고지시가 있으면 출고 취소 확인 뒤 반영(T14).</li>
 *   <li>반품·교환: 회수 실물은 반품 입고·검수(ReturnService)로만 재고에 들어간다. 교환 대체품은 0원 주문상품으로 따로 출고(원판매 매출 중복 없음, T18).</li>
 *   <li>환불: 예정액 → 요청(멱등) → 확정. 확정액 합 ≤ 예정액. 환불 완료와 검수 완료는 서로 기다리지 않는다.</li>
 * </ul>
 * 금액은 부가세 포함 원 단위 정수(BigDecimal). 주문상품 환불액 = (상품 금액 − 판매자·채널 할인) × 수량 비례, 마지막 수량이 나머지를 가져 합이 결제액을 넘지 않는다.
 */
@Service
public class ClaimService {
    static final Set<String> OPEN = Set.of("REQUESTED", "REVIEWING", "APPROVED", "PROCESSING");
    static final Set<String> REASONS = Set.of("CHANGE_OF_MIND", "DEFECT", "WRONG_ITEM", "DAMAGED_IN_TRANSIT", "DELIVERY_DELAY", "OUT_OF_STOCK", "WRONG_ADDRESS", "OTHER");
    static final Set<String> FAULTS = Set.of("CUSTOMER", "SELLER", "CARRIER", "UNKNOWN");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final ActorFactory actors;
    private final FulfillmentService fulfillment;
    private final ShipmentService shipments;
    private final OrderRules rules;
    private final WorkItemService workItems;
    private final OutboxPublisher outbox;
    private final AuditService audit;
    private final Json json;
    private final Clock clock;

    public ClaimService(JdbcClient jdbc, AccessGuard guard, ActorFactory actors, FulfillmentService fulfillment, ShipmentService shipments, OrderRules rules,
                        WorkItemService workItems, OutboxPublisher outbox, AuditService audit, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.actors = actors;
        this.fulfillment = fulfillment;
        this.shipments = shipments;
        this.rules = rules;
        this.workItems = workItems;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public record ItemInput(UUID orderItemId, Integer quantity, UUID replacementSkuId, Integer replacementQuantity) {
    }

    public record NewClaim(UUID orderId, String claimType, String reasonCode, String reasonNote, String faultParty, List<ItemInput> items,
                           BigDecimal returnShippingFee, BigDecimal priceDifference, Boolean shipBeforeReturn) {
    }

    public record Decision(String note) {
    }

    public record ReturnShipment(String carrierCode, String trackingNo) {
    }

    public record RefundRequest(BigDecimal amount, String executor, String note) {
    }

    public record RefundConfirm(BigDecimal confirmedAmount, String externalRef, Instant confirmedAt) {
    }

    public record RefundFail(String reason) {
    }

    public record ItemView(UUID id, int lineNo, UUID orderItemId, String productName, UUID skuId, String skuCode, int quantity, BigDecimal refundAmount,
                           UUID replacementSkuId, String replacementSkuCode, Integer replacementQuantity, UUID replacementOrderItemId, int expectedReturnQty,
                           int receivedQty, int inspectedQty) {
    }

    public record RefundView(UUID id, String executor, String status, BigDecimal requestedAmount, BigDecimal confirmedAmount, String externalRef, String source,
                             String note, Instant requestedAt, Instant confirmedAt, String failureReason) {
    }

    public record EventView(String eventType, String fromValue, String toValue, JsonNode detail, String actorType, Instant occurredAt) {
    }

    public record ClaimView(UUID id, String claimNo, UUID orderId, String orderNo, UUID businessEntityId, String businessEntityName, UUID channelAccountId,
                            String channelAccountName, String claimType, String source, String externalClaimKey, String status, String returnStatus,
                            String refundStatus, String reshipStatus, boolean cancelApplied, String reasonCode, String reasonNote, String faultParty,
                            boolean shipBeforeReturn, String returnCarrierCode, String returnTrackingNo, BigDecimal itemRefundAmount,
                            BigDecimal shippingRefundAmount, BigDecimal returnShippingFee, BigDecimal priceDifference, BigDecimal expectedRefundAmount,
                            BigDecimal additionalPaymentAmount, BigDecimal confirmedRefundAmount, String channelActionStatus, String channelAction,
                            String channelActionError, String externalStatus, String decisionNote, Instant requestedAt, Instant decidedAt, Instant completedAt,
                            Instant createdAt, List<ItemView> items, List<RefundView> refunds, List<EventView> events, int version) {
    }

    // ---------------------------------------------------------------- 접수

    /** CS 접수. */
    @Transactional
    public ClaimView create(NewClaim c) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        UUID id = createInternal(c, "MANUAL", null, null, clock.instant());
        return get(id);
    }

    /** CS 접수·채널 수집 공통. @return 클레임 id */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createInternal(NewClaim c, String source, String externalKey, String externalStatus, Instant requestedAt) {
        CurrentActor a = guard.actor();
        UUID ws = a.workspaceId();
        if (c == null || c.orderId() == null) {
            throw Errors.validation("order_id", "주문을 지정하세요.");
        }
        Map<String, Object> o = jdbc.sql("select * from sales_order where workspace_id = :ws and id = :id for update").param("ws", ws).param("id", c.orderId())
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SALES_ORDER"));
        UUID be = (UUID) o.get("business_entity_id");
        UUID ca = (UUID) o.get("channel_account_id");
        guard.checkWrite("SALES_ORDER", c.orderId(), be, ca);
        String type = c.claimType();
        if (!Set.of("CANCEL", "RETURN", "EXCHANGE").contains(type)) {
            throw Errors.validation("claim_type", "클레임 유형(취소·반품·교환)을 선택하세요.");
        }
        String reason = c.reasonCode() == null ? "OTHER" : c.reasonCode();
        if (!REASONS.contains(reason)) {
            throw Errors.validation("reason_code", "클레임 사유를 선택하세요.");
        }
        String fault = c.faultParty() == null ? defaultFault(reason) : c.faultParty();
        if (!FAULTS.contains(fault)) {
            throw Errors.validation("fault_party", "귀책 주체를 선택하세요.");
        }
        if (c.items() == null || c.items().isEmpty()) {
            throw Errors.validation("items", "클레임 상품과 수량을 하나 이상 입력하세요.");
        }
        if ("PLATFORM_FULFILLMENT".equals(o.get("source_type")) && !"API".equals(source)) {
            throw Errors.unprocessable("PLATFORM_FULFILLMENT_CLAIM", "플랫폼 물류 주문의 클레임은 판매채널에서 처리하고 연동으로 수집합니다.");
        }
        BigDecimal returnFee = nonNegative(c.returnShippingFee(), "return_shipping_fee");
        BigDecimal diff = c.priceDifference() == null ? BigDecimal.ZERO : c.priceDifference().setScale(0, RoundingMode.UNNECESSARY);
        if (!"EXCHANGE".equals(type) && diff.signum() != 0) {
            throw Errors.validation("price_difference", "교환 차액은 교환 클레임에만 입력합니다.");
        }
        if ("CANCEL".equals(type) && returnFee.signum() != 0) {
            throw Errors.validation("return_shipping_fee", "취소에는 반품 배송비가 없습니다.");
        }
        boolean shipBefore = Boolean.TRUE.equals(c.shipBeforeReturn());
        if (shipBefore && !"EXCHANGE".equals(type)) {
            throw Errors.validation("ship_before_return", "선출고는 교환에만 해당합니다.");
        }
        UUID claimId = Ids.newId();
        String claimNo = number(ws, (String) o.get("order_no"));
        List<Map<String, Object>> lines = new ArrayList<>();
        Set<UUID> seen = new java.util.HashSet<>();
        BigDecimal itemRefund = BigDecimal.ZERO;
        int lineNo = 1;
        for (ItemInput in : c.items()) {
            if (in == null || in.orderItemId() == null || in.quantity() == null || in.quantity() < 1) {
                throw Errors.validation("items", "클레임 상품과 수량(1 이상)을 확인하세요.");
            }
            if (!seen.add(in.orderItemId())) {
                throw Errors.validation("items", "같은 주문상품을 두 번 넣었습니다.");
            }
            Map<String, Object> it = jdbc.sql("select * from order_item where workspace_id = :ws and id = :id and order_id = :o for update").param("ws", ws)
                    .param("id", in.orderItemId()).param("o", c.orderId()).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> Errors.validation("items", "이 주문의 상품이 아닙니다."));
            int q = in.quantity();
            int limit = limit(type, it);
            if (q > limit) {
                throw Errors.unprocessable("CLAIM_QUANTITY_EXCEEDED", ("CANCEL".equals(type)
                                ? "취소할 수 있는 수량을 넘었습니다(이미 출고된 수량은 반품으로 처리)."
                                : "반품·교환할 수 있는 수량을 넘었습니다(출고된 수량 − 이미 접수된 반품·교환)."),
                        Map.of("order_item_id", in.orderItemId().toString(), "requested", q, "available", limit));
            }
            BigDecimal refund = "EXCHANGE".equals(type) ? BigDecimal.ZERO : refundFor(it, q);
            itemRefund = itemRefund.add(refund);
            UUID replSku = null;
            Integer replQty = null;
            if ("EXCHANGE".equals(type)) {
                replSku = in.replacementSkuId() == null ? (UUID) it.get("sku_id") : in.replacementSkuId();
                replQty = in.replacementQuantity() == null ? q : in.replacementQuantity();
                if (replSku == null) {
                    throw Errors.validation("replacement_sku_id", "교환 상품 SKU 를 지정하세요(연결되지 않은 주문상품).");
                }
                if (replQty < 1) {
                    throw Errors.validation("replacement_quantity", "교환 수량은 1 이상입니다.");
                }
                Map<String, Object> sku = jdbc.sql("select sku_type, inventory_managed, sellable from sku where workspace_id = :ws and id = :id").param("ws", ws)
                        .param("id", replSku).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.validation("replacement_sku_id", "SKU 를 찾을 수 없습니다."));
                boolean same = replSku.equals(it.get("sku_id"));
                if (!same && (!Boolean.TRUE.equals(sku.get("inventory_managed")) || "BUNDLE".equals(sku.get("sku_type")))) {
                    throw Errors.unprocessable("EXCHANGE_SKU_UNSUPPORTED", "다른 상품으로 교환은 재고 관리 단품 SKU 만 지원합니다(묶음·서비스 SKU 제외).");
                }
            }
            int expectedReturn = "CANCEL".equals(type) ? 0 : jdbc.sql("select coalesce(sum(quantity_per_unit), 0) from order_item_component where order_item_id = :i")
                    .param("i", in.orderItemId()).query(Integer.class).single() * q;
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", Ids.newId());
            line.put("line_no", lineNo++);
            line.put("order_item_id", in.orderItemId());
            line.put("quantity", q);
            line.put("refund", refund);
            line.put("repl_sku", replSku);
            line.put("repl_qty", replQty);
            line.put("expected", expectedReturn);
            lines.add(line);
        }
        // 배송비: 출고 전 전체 취소면 배송비도 환불
        BigDecimal shippingRefund = BigDecimal.ZERO;
        if ("CANCEL".equals(type)) {
            int remainingAfter = jdbc.sql("select coalesce(sum(original_qty - canceled_qty), 0) from order_item where order_id = :o and item_kind = 'ORIGINAL'")
                    .param("o", c.orderId()).query(Integer.class).single() - lines.stream().mapToInt(l -> (Integer) l.get("quantity")).sum()
                    - openCancelQty(c.orderId(), null);
            boolean refundedShipping = jdbc.sql("select count(*) from claim where order_id = :o and shipping_refund_amount > 0 and status not in ('REJECTED', 'CANCELLED')")
                    .param("o", c.orderId()).query(Integer.class).single() > 0;
            if (remainingAfter <= 0 && !refundedShipping) {
                shippingRefund = (BigDecimal) o.get("shipping_fee");
            }
        }
        BigDecimal expected;
        BigDecimal additional = BigDecimal.ZERO;
        if ("EXCHANGE".equals(type)) {
            expected = diff.signum() < 0 ? diff.negate() : BigDecimal.ZERO;
            additional = (diff.signum() > 0 ? diff : BigDecimal.ZERO).add(returnFee);
        } else {
            expected = itemRefund.add(shippingRefund).subtract(returnFee).max(BigDecimal.ZERO);
        }
        String returnStatus = "CANCEL".equals(type) ? "NOT_REQUIRED" : "REQUESTED";
        String reship = "EXCHANGE".equals(type) ? "PENDING" : "NOT_REQUIRED";
        jdbc.sql("""
                insert into claim (id, workspace_id, claim_no, order_id, business_entity_id, channel_account_id, claim_type, source, external_claim_key, status,
                                   return_status, refund_status, reship_status, reason_code, reason_note, fault_party, ship_before_return, item_refund_amount,
                                   shipping_refund_amount, return_shipping_fee, price_difference, expected_refund_amount, additional_payment_amount, external_status,
                                   requested_at, created_by)
                values (:id, :ws, :no, :o, :be, :ca, :t, :src, cast(:ek as text), 'REQUESTED', :rs, :fs, :ss, :r, cast(:rn as text), :fp, :sb, :ir, :sr, :rf, :pd,
                        :er, :ap, cast(:es as text), :at, cast(:by as uuid))
                """).param("id", claimId).param("ws", ws).param("no", claimNo).param("o", c.orderId()).param("be", be).param("ca", ca).param("t", type)
                .param("src", source).param("ek", externalKey).param("rs", returnStatus).param("fs", expected.signum() > 0 ? "EXPECTED" : "NOT_REQUIRED")
                .param("ss", reship).param("r", reason).param("rn", trim(c.reasonNote(), 500)).param("fp", fault).param("sb", shipBefore).param("ir", itemRefund)
                .param("sr", shippingRefund).param("rf", returnFee).param("pd", diff).param("er", expected).param("ap", additional).param("es", externalStatus)
                .param("at", Db.ts(requestedAt)).param("by", a.memberId()).update();
        for (Map<String, Object> l : lines) {
            jdbc.sql("""
                    insert into claim_item (id, workspace_id, claim_id, line_no, order_item_id, quantity, refund_amount, replacement_sku_id, replacement_quantity,
                                            expected_return_qty)
                    values (:id, :ws, :c, :ln, :oi, :q, :rf, cast(:rs as uuid), cast(:rq as integer), :ex)
                    """).param("id", l.get("id")).param("ws", ws).param("c", claimId).param("ln", l.get("line_no")).param("oi", l.get("order_item_id"))
                    .param("q", l.get("quantity")).param("rf", l.get("refund")).param("rs", l.get("repl_sku")).param("rq", l.get("repl_qty"))
                    .param("ex", l.get("expected")).update();
        }
        event(a, claimId, "CLAIM_CREATED", null, "REQUESTED", Map.of("type", type, "source", source, "expected_refund", expected.toPlainString()));
        orderEvent(a, c.orderId(), "CLAIM_CREATED", null, claimNo, Map.of("type", type));
        workItems.raise(new WorkItemService.Raise(ws, be, ca, null, "CLAIM_REVIEW", "MEDIUM", typeKo(type) + " 요청 검토: " + claimNo,
                "고객 " + typeKo(type) + " 요청이 접수되었습니다.", "주문·출고 상태를 확인하고 승인 또는 거부하세요.", "claim:" + claimId + ":REVIEW",
                Map.of("claim_id", claimId.toString(), "order_id", c.orderId().toString())));
        audit.record(AuditEntry.builder(ws, "CLAIM_CREATED", "CLAIM", claimId).actor(ActorType.USER, a.memberId(), a.displayName()).scope(be, ca)
                .after(Map.of("claim_no", claimNo, "type", type, "items", lines.size(), "expected_refund", expected.toPlainString(), "source", source)).build());
        return claimId;
    }

    // ---------------------------------------------------------------- 결정

    @Transactional
    public ClaimView approve(UUID claimId, Decision d) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        Map<String, Object> c = writable(claimId);
        approveInternal(claimId, d == null ? null : d.note(), true);
        return get(claimId);
    }

    /** @param fromUser true = 사람이 결정(채널 수집 클레임이면 채널 반영 필요), false = 채널에서 이미 결정됨 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void approveInternal(UUID claimId, String note, boolean fromUser) {
        CurrentActor a = guard.actor();
        Map<String, Object> c = lock(a.workspaceId(), claimId);
        String st = (String) c.get("status");
        if (!"REQUESTED".equals(st) && !"REVIEWING".equals(st)) {
            if ("APPROVED".equals(st) || "PROCESSING".equals(st) || "COMPLETED".equals(st)) {
                return;
            }
            throw Errors.conflict("CLAIM_STATE_INVALID", "승인할 수 없는 클레임 상태입니다.", Map.of("status", st));
        }
        boolean channelAction = fromUser && "API".equals(c.get("source"));
        jdbc.sql("""
                update claim set status = 'APPROVED', decided_at = now(), decided_by = cast(:by as uuid), decision_note = cast(:n as text),
                       channel_action_status = case when :ch then 'PENDING' else channel_action_status end, channel_action = case when :ch then 'APPROVE' else channel_action end,
                       version = version + 1, updated_at = now() where id = :id
                """).param("by", a.memberId()).param("n", trim(note, 500)).param("ch", channelAction).param("id", claimId).update();
        event(a, claimId, "CLAIM_APPROVED", st, "APPROVED", Map.of());
        workItems.resolveByKey(a.workspaceId(), "claim:" + claimId + ":REVIEW");
        if (channelAction) {
            outbox.publish(a.workspaceId(), (UUID) c.get("business_entity_id"), "CLAIM", claimId, "ClaimChannelActionRequested", Map.of("claim_id", claimId.toString()));
        }
        String type = (String) c.get("claim_type");
        if ("CANCEL".equals(type)) {
            applyCancel(claimId);
        } else if ("EXCHANGE".equals(type)) {
            createReplacements(claimId);
            if (Boolean.TRUE.equals(c.get("ship_before_return"))) {
                allocateAsSystem((UUID) c.get("order_id"));
            }
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "CLAIM_APPROVED", "CLAIM", claimId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) c.get("business_entity_id"), (UUID) c.get("channel_account_id")).reason(trim(note, 500)).build());
        refresh(claimId);
    }

    @Transactional
    public ClaimView reject(UUID claimId, Decision d) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        writable(claimId);
        String note = d == null ? null : trim(d.note(), 500);
        if (note == null) {
            throw Errors.validation("note", "거부 사유를 입력하세요.");
        }
        rejectInternal(claimId, note, true);
        return get(claimId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rejectInternal(UUID claimId, String note, boolean fromUser) {
        CurrentActor a = guard.actor();
        Map<String, Object> c = lock(a.workspaceId(), claimId);
        String st = (String) c.get("status");
        if ("REJECTED".equals(st)) {
            return;
        }
        if (!"REQUESTED".equals(st) && !"REVIEWING".equals(st)) {
            throw Errors.conflict("CLAIM_STATE_INVALID", "승인된 클레임은 거부할 수 없습니다. 철회하거나 진행을 완료하세요.", Map.of("status", st));
        }
        requireNoPhysicalReturn(claimId, "거부");
        boolean channelAction = fromUser && "API".equals(c.get("source"));
        jdbc.sql("""
                update claim set status = 'REJECTED', decided_at = now(), decided_by = cast(:by as uuid), decision_note = cast(:n as text),
                       refund_status = case when refund_status = 'EXPECTED' then 'CANCELLED' else refund_status end,
                       return_status = 'NOT_REQUIRED', reship_status = 'NOT_REQUIRED',
                       channel_action_status = case when :ch then 'PENDING' else channel_action_status end, channel_action = case when :ch then 'REJECT' else channel_action end,
                       version = version + 1, updated_at = now() where id = :id
                """).param("by", a.memberId()).param("n", note).param("ch", channelAction).param("id", claimId).update();
        event(a, claimId, "CLAIM_REJECTED", st, "REJECTED", Map.of("note", String.valueOf(note)));
        workItems.resolveByKey(a.workspaceId(), "claim:" + claimId + ":REVIEW");
        if (channelAction) {
            outbox.publish(a.workspaceId(), (UUID) c.get("business_entity_id"), "CLAIM", claimId, "ClaimChannelActionRequested", Map.of("claim_id", claimId.toString()));
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "CLAIM_REJECTED", "CLAIM", claimId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) c.get("business_entity_id"), (UUID) c.get("channel_account_id")).reason(note).build());
    }

    /** 고객 철회. 실물 회수·환불·취소 반영이 시작되기 전까지만. */
    @Transactional
    public ClaimView withdraw(UUID claimId, Decision d) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        writable(claimId);
        withdrawInternal(claimId, d == null ? null : d.note());
        return get(claimId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void withdrawInternal(UUID claimId, String note) {
        CurrentActor a = guard.actor();
        Map<String, Object> c = lock(a.workspaceId(), claimId);
        String st = (String) c.get("status");
        if ("CANCELLED".equals(st)) {
            return;
        }
        if (!OPEN.contains(st)) {
            throw Errors.conflict("CLAIM_STATE_INVALID", "철회할 수 없는 클레임 상태입니다.", Map.of("status", st));
        }
        if (Boolean.TRUE.equals(c.get("cancel_applied"))) {
            throw Errors.conflict("CLAIM_ALREADY_APPLIED", "이미 취소 수량이 반영된 클레임은 철회할 수 없습니다.");
        }
        requireNoPhysicalReturn(claimId, "철회");
        int refunds = jdbc.sql("select count(*) from refund where claim_id = :c and status in ('REQUESTED', 'CONFIRMED')").param("c", claimId).query(Integer.class).single();
        if (refunds > 0) {
            throw Errors.conflict("CLAIM_REFUND_IN_PROGRESS", "환불 요청·확정이 있는 클레임은 철회할 수 없습니다.");
        }
        int shippedReplacement = jdbc.sql("""
                select count(*) from claim_item ci join fulfillment_order_line l on l.order_item_id = ci.replacement_order_item_id
                  join fulfillment_order f on f.id = l.fo_id where ci.claim_id = :c and f.status <> 'CANCELLED'
                """).param("c", claimId).query(Integer.class).single();
        if (shippedReplacement > 0) {
            throw Errors.conflict("CLAIM_RESHIP_IN_PROGRESS", "교환품 출고가 배정된 클레임은 출고를 먼저 취소해야 철회할 수 있습니다.");
        }
        // 교환 대체 주문상품은 취소 수량으로 닫는다(삭제하지 않음)
        jdbc.sql("""
                update order_item set canceled_qty = original_qty, version = order_item.version + 1, updated_at = now()
                 where id in (select replacement_order_item_id from claim_item where claim_id = :c and replacement_order_item_id is not null)
                """).param("c", claimId).update();
        jdbc.sql("""
                update claim set status = 'CANCELLED', cancelled_at = now(), decision_note = coalesce(cast(:n as text), decision_note),
                       refund_status = case when refund_status in ('EXPECTED', 'FAILED') then 'CANCELLED' else refund_status end,
                       return_status = 'NOT_REQUIRED', reship_status = 'NOT_REQUIRED', version = version + 1, updated_at = now() where id = :id
                """).param("n", trim(note, 500)).param("id", claimId).update();
        event(a, claimId, "CLAIM_WITHDRAWN", st, "CANCELLED", Map.of());
        workItems.resolveByKey(a.workspaceId(), "claim:" + claimId + ":REVIEW");
        audit.record(AuditEntry.builder(a.workspaceId(), "CLAIM_WITHDRAWN", "CLAIM", claimId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) c.get("business_entity_id"), (UUID) c.get("channel_account_id")).reason(trim(note, 500)).build());
    }

    /** 고객 회수 송장(반품·교환). */
    @Transactional
    public ClaimView returnShipment(UUID claimId, ReturnShipment r) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        Map<String, Object> c = writable(claimId);
        CurrentActor a = guard.actor();
        if ("CANCEL".equals(c.get("claim_type")) || !OPEN.contains((String) c.get("status"))) {
            throw Errors.conflict("CLAIM_STATE_INVALID", "회수 송장을 기록할 수 없는 클레임입니다.");
        }
        String carrier = r == null || r.carrierCode() == null ? null : r.carrierCode().trim().toUpperCase(java.util.Locale.ROOT);
        String tracking = r == null || r.trackingNo() == null ? null : r.trackingNo().replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
        if (carrier == null || jdbc.sql("select count(*) from carrier where code = :c").param("c", carrier).query(Integer.class).single() == 0
                || tracking == null || !tracking.matches("^[A-Z0-9]{4,40}$")) {
            throw Errors.validation("tracking_no", "택배사와 회수 송장번호(숫자·영문 4~40자)를 확인하세요.");
        }
        jdbc.sql("""
                update claim set return_carrier_code = :c, return_tracking_no = :t,
                       return_status = case when return_status = 'REQUESTED' then 'IN_TRANSIT' else return_status end, version = version + 1, updated_at = now() where id = :id
                """).param("c", carrier).param("t", tracking).param("id", claimId).update();
        event(a, claimId, "RETURN_SHIPMENT_RECORDED", (String) c.get("return_status"), "IN_TRANSIT", Map.of("carrier", carrier, "tracking", tracking));
        return get(claimId);
    }

    // ---------------------------------------------------------------- 취소 반영

    /**
     * 취소 승인 반영. 같은 주문의 진행 중 출고지시 중 이 상품을 담은 것: 배정만 됐으면 바로 취소(확보 해제), 물류에 전달됐으면 취소 요청(확인 대기).
     * 확인 대기 출고가 없을 때 취소 수량을 반영하고 남은 수량을 다시 배정한다(T14). 출고된 수량은 반품으로.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyCancel(UUID claimId) {
        CurrentActor a = guard.actor();
        Map<String, Object> c = lock(a.workspaceId(), claimId);
        if (Boolean.TRUE.equals(c.get("cancel_applied")) || !Set.of("APPROVED", "PROCESSING").contains((String) c.get("status"))) {
            return;
        }
        UUID orderId = (UUID) c.get("order_id");
        String claimNo = (String) c.get("claim_no");
        List<Map<String, Object>> fos = jdbc.sql("""
                select distinct f.id, f.status from fulfillment_order f join fulfillment_order_line l on l.fo_id = f.id
                  join claim_item ci on ci.order_item_id = l.order_item_id
                 where ci.claim_id = :c and f.status not in ('CANCELLED', 'SHIPPED')
                """).param("c", claimId).query().listOfRows();
        boolean pending = false;
        for (Map<String, Object> f : fos) {
            UUID foId = (UUID) f.get("id");
            String st = (String) f.get("status");
            if ("CANCEL_REQUESTED".equals(st)) {
                pending = true;
                continue;
            }
            boolean immediate = "ALLOCATED".equals(st) || "FAILED".equals(st);
            asSystem(a.workspaceId(), () -> fulfillment.cancel(foId, new FulfillmentService.Cancel("클레임 " + claimNo + " 취소 승인", immediate ? Boolean.TRUE : Boolean.FALSE)));
            if (!immediate) {
                pending = true;
            }
        }
        if (pending) {
            event(a, claimId, "CANCEL_WAITING_LOGISTICS", null, null, Map.of("fulfillment_orders", fos.size()));
            jdbc.sql("update claim set status = 'PROCESSING', version = version + 1, updated_at = now() where id = :id").param("id", claimId).update();
            return;
        }
        // 출고 확인 사이에 출고됐을 수 있다 → 취소 가능 수량 재검사
        for (Map<String, Object> ci : jdbc.sql("select ci.*, i.original_qty, i.canceled_qty, i.shipped_qty from claim_item ci join order_item i on i.id = ci.order_item_id where ci.claim_id = :c for update of i")
                .param("c", claimId).query().listOfRows()) {
            int q = ((Number) ci.get("quantity")).intValue();
            int canceled = ((Number) ci.get("canceled_qty")).intValue();
            int byClaims = jdbc.sql("""
                    select coalesce(sum(x.quantity), 0) from claim_item x join claim y on y.id = x.claim_id
                     where x.order_item_id = :i and y.claim_type = 'CANCEL' and y.cancel_applied
                    """).param("i", ci.get("order_item_id")).query(Integer.class).single();
            int cancellable = ((Number) ci.get("original_qty")).intValue() - byClaims - ((Number) ci.get("shipped_qty")).intValue();
            if (q > cancellable) {
                workItems.raise(new WorkItemService.Raise(a.workspaceId(), (UUID) c.get("business_entity_id"), (UUID) c.get("channel_account_id"), null,
                        "CLAIM_CANCEL_SHIPPED", "HIGH", "취소 승인 후 출고됨: " + claimNo, "취소를 기다리는 사이 상품이 출고되어 취소 수량을 반영하지 못했습니다.",
                        "고객 안내 후 이 클레임을 철회하고 반품으로 다시 접수하세요.", "claim:" + claimId + ":CANCEL_SHIPPED", Map.of("claim_id", claimId.toString())));
                jdbc.sql("update claim set status = 'PROCESSING', version = version + 1, updated_at = now() where id = :id").param("id", claimId).update();
                return;
            }
        }
        for (Map<String, Object> ci : jdbc.sql("select * from claim_item where claim_id = :c order by line_no").param("c", claimId).query().listOfRows()) {
            UUID itemId = (UUID) ci.get("order_item_id");
            int q = ((Number) ci.get("quantity")).intValue();
            int canceled = jdbc.sql("select canceled_qty from order_item where id = :id").param("id", itemId).query(Integer.class).single();
            // 판매채널 주문 수집이 이미 반영한 취소 수량(클레임 없이 들어온 몫)은 다시 더하지 않는다(DLG-139)
            int byClaims = jdbc.sql("""
                    select coalesce(sum(x.quantity), 0) from claim_item x join claim y on y.id = x.claim_id
                     where x.order_item_id = :i and y.claim_type = 'CANCEL' and y.cancel_applied
                    """).param("i", itemId).query(Integer.class).single();
            int external = Math.max(0, canceled - byClaims);
            int add = Math.max(0, q - external);
            if (add > 0) {
                jdbc.sql("update order_item set canceled_qty = canceled_qty + :q, version = version + 1, updated_at = now() where id = :id").param("q", add)
                        .param("id", itemId).update();
                orderEventItem(a, orderId, itemId, "ITEM_CANCELED", String.valueOf(canceled), String.valueOf(canceled + add), Map.of("claim_no", claimNo));
            }
        }
        jdbc.sql("update claim set cancel_applied = true, status = 'PROCESSING', version = version + 1, updated_at = now() where id = :id").param("id", claimId).update();
        event(a, claimId, "CANCEL_APPLIED", null, null, Map.of());
        rules.reevaluate(a.workspaceId(), orderId, a.memberId(), null);
        allocateAsSystem(orderId);
        workItems.resolveByKey(a.workspaceId(), "claim:" + claimId + ":CANCEL_SHIPPED");
    }

    /** 출고 취소 확인·출고 확정 뒤 대기 중인 취소 클레임을 이어서 반영한다(구독자). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void resumePendingCancels(UUID orderId) {
        List<UUID> pending = jdbc.sql("""
                select id from claim where order_id = :o and claim_type = 'CANCEL' and not cancel_applied and status in ('APPROVED', 'PROCESSING') order by created_at
                """).param("o", orderId).query(UUID.class).list();
        for (UUID id : pending) {
            int waiting = jdbc.sql("""
                    select count(*) from fulfillment_order f join fulfillment_order_line l on l.fo_id = f.id join claim_item ci on ci.order_item_id = l.order_item_id
                     where ci.claim_id = :c and f.status = 'CANCEL_REQUESTED'
                    """).param("c", id).query(Integer.class).single();
            if (waiting == 0) {
                applyCancel(id);
                refresh(id);
            }
        }
    }

    /**
     * 판매채널 주문 수집으로 취소 수량이 늘어난 경우(클레임 없이): 필요 수량보다 많이 담긴 진행 중 출고지시를 정리한다.
     * 배정만 된 출고지시는 바로 취소 후 남은 수량을 다시 배정, 물류에 전달된 출고지시는 취소 요청(확인 업무)으로 둔다(DLG-139).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileFulfillment(UUID orderId) {
        CurrentActor a = guard.actor();
        List<Map<String, Object>> over = jdbc.sql("""
                select distinct f.id, f.status, f.fo_no from fulfillment_order f join fulfillment_order_line l on l.fo_id = f.id
                  join order_item i on i.id = l.order_item_id join order_item_component c on c.order_item_id = i.id and c.component_sku_id = l.sku_id
                 where f.order_id = :o and f.status not in ('CANCELLED', 'SHIPPED', 'CANCEL_REQUESTED')
                   and (select coalesce(sum(l2.quantity), 0) from fulfillment_order_line l2 join fulfillment_order f2 on f2.id = l2.fo_id
                         where l2.order_item_id = l.order_item_id and l2.sku_id = l.sku_id and f2.status <> 'CANCELLED')
                       > (i.original_qty - i.canceled_qty) * c.quantity_per_unit
                """).param("o", orderId).query().listOfRows();
        boolean released = false;
        for (Map<String, Object> f : over) {
            String st = (String) f.get("status");
            boolean immediate = "ALLOCATED".equals(st) || "FAILED".equals(st);
            asSystem(a.workspaceId(), () -> fulfillment.cancel((UUID) f.get("id"), new FulfillmentService.Cancel("판매채널 주문 취소 반영", immediate ? Boolean.TRUE : Boolean.FALSE)));
            released |= immediate;
        }
        if (released) {
            allocateAsSystem(orderId);
        }
    }

    // ---------------------------------------------------------------- 교환 대체품

    private void createReplacements(UUID claimId) {
        CurrentActor a = guard.actor();
        Map<String, Object> c = lock(a.workspaceId(), claimId);
        UUID orderId = (UUID) c.get("order_id");
        String claimNo = (String) c.get("claim_no");
        for (Map<String, Object> ci : jdbc.sql("""
                select ci.*, i.sku_id as orig_sku, i.sku_qty_per_unit, i.external_product_id, i.product_name from claim_item ci join order_item i on i.id = ci.order_item_id
                 where ci.claim_id = :c and ci.replacement_order_item_id is null order by ci.line_no
                """).param("c", claimId).query().listOfRows()) {
            UUID replSku = (UUID) ci.get("replacement_sku_id");
            int replQty = ((Number) ci.get("replacement_quantity")).intValue();
            boolean same = replSku.equals(ci.get("orig_sku"));
            Map<String, Object> sku = jdbc.sql("select sku_code, name from sku where id = :id").param("id", replSku).query().listOfRows().get(0);
            int lineNo = jdbc.sql("select coalesce(max(line_no), 0) + 1 from order_item where order_id = :o").param("o", orderId).query(Integer.class).single();
            UUID itemId = Ids.newId();
            jdbc.sql("""
                    insert into order_item (id, workspace_id, order_id, line_no, external_item_key, external_product_id, external_option_id, product_name, option_name,
                                            sku_id, sku_qty_per_unit, mapping_status, original_qty, unit_price, line_amount, item_kind, claim_id)
                    values (:id, :ws, :o, :ln, :k, :p, '', :n, :on, :s, :qpu, 'MAPPED', :q, 0, 0, 'EXCHANGE_REPLACEMENT', :c)
                    """).param("id", itemId).param("ws", a.workspaceId()).param("o", orderId).param("ln", lineNo).param("k", "EXCH:" + claimNo + ":" + ci.get("line_no"))
                    .param("p", ci.get("external_product_id")).param("n", "[교환] " + (same ? ci.get("product_name") : sku.get("name"))).param("on", sku.get("sku_code"))
                    .param("s", replSku).param("qpu", same && ci.get("sku_qty_per_unit") != null ? ci.get("sku_qty_per_unit") : 1).param("q", replQty)
                    .param("c", claimId).update();
            if (same) {
                // 같은 상품: 원주문상품의 출고 구성(BOM 버전 포함)을 그대로 쓴다
                jdbc.sql("""
                        insert into order_item_component (id, workspace_id, order_id, order_item_id, component_sku_id, quantity_per_unit, source, bom_id, bom_version,
                                                          split_shipment_allowed)
                        select gen_random_uuid(), workspace_id, order_id, :ni, component_sku_id, quantity_per_unit, source, bom_id, bom_version, split_shipment_allowed
                          from order_item_component where order_item_id = :oi
                        """).param("ni", itemId).param("oi", ci.get("order_item_id")).update();
            } else {
                jdbc.sql("""
                        insert into order_item_component (id, workspace_id, order_id, order_item_id, component_sku_id, quantity_per_unit, source)
                        values (:id, :ws, :o, :i, :s, 1, 'DIRECT')
                        """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("o", orderId).param("i", itemId).param("s", replSku).update();
            }
            jdbc.sql("update claim_item set replacement_order_item_id = :i, version = version + 1 where id = :id").param("i", itemId).param("id", ci.get("id")).update();
            orderEventItem(a, orderId, itemId, "EXCHANGE_ITEM_ADDED", null, sku.get("sku_code") + " ×" + replQty, Map.of("claim_no", claimNo));
        }
        jdbc.sql("""
                update sales_order set allocation_status = 'PENDING', status = case when status in ('COMPLETED', 'CONFIRMED') then 'PROCESSING' else status end,
                       closed_at = case when status = 'COMPLETED' then null else closed_at end, version = version + 1, updated_at = now() where id = :id
                """).param("id", orderId).update();
        shipments.refreshOrderShipping(a, orderId);
    }

    /** 회수품 도착(교환) → 대체품 출고 배정. ReturnService 가 부른다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReturnReceived(UUID claimId) {
        Map<String, Object> c = lock(guard.actor().workspaceId(), claimId);
        if ("EXCHANGE".equals(c.get("claim_type")) && Set.of("APPROVED", "PROCESSING").contains((String) c.get("status"))) {
            allocateAsSystem((UUID) c.get("order_id"));
        }
        refresh(claimId);
    }

    // ---------------------------------------------------------------- 환불

    @Transactional
    public ClaimView requestRefund(UUID claimId, RefundRequest r, String idempotencyKey) {
        guard.requirePermission(Perm.CLAIM_REFUND);
        Map<String, Object> c = writable(claimId);
        CurrentActor a = guard.actor();
        if (!Set.of("APPROVED", "PROCESSING").contains((String) c.get("status"))) {
            throw Errors.conflict("CLAIM_NOT_APPROVED", "승인된 클레임만 환불을 요청할 수 있습니다.", Map.of("status", c.get("status")));
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw Errors.idempotencyKeyRequired();
        }
        String key = "refund:" + idempotencyKey.trim();
        if (jdbc.sql("select count(*) from refund where workspace_id = :ws and idempotency_key = :k").param("ws", a.workspaceId()).param("k", key).query(Integer.class)
                .single() > 0) {
            return get(claimId); // 같은 요청 재시도
        }
        BigDecimal amount = r == null || r.amount() == null ? null : r.amount().setScale(0, RoundingMode.UNNECESSARY);
        if (amount == null || amount.signum() <= 0) {
            throw Errors.validation("amount", "환불 요청 금액(원)을 입력하세요.");
        }
        String executor = r.executor() == null ? defaultExecutor((UUID) c.get("order_id")) : r.executor();
        if (!Set.of("CHANNEL", "PG", "MANUAL").contains(executor)) {
            throw Errors.validation("executor", "환불 실행 주체를 선택하세요.");
        }
        BigDecimal used = jdbc.sql("""
                select coalesce(sum(case when status = 'CONFIRMED' then confirmed_amount else requested_amount end), 0) from refund
                 where claim_id = :c and status in ('REQUESTED', 'CONFIRMED')
                """).param("c", claimId).query(BigDecimal.class).single();
        BigDecimal expected = (BigDecimal) c.get("expected_refund_amount");
        if (used.add(amount).compareTo(expected) > 0) {
            throw Errors.unprocessable("REFUND_EXCEEDS_EXPECTED", "환불 요청 합계가 환불 예정액을 넘습니다.",
                    Map.of("expected", expected.toPlainString(), "already", used.toPlainString(), "requested", amount.toPlainString()));
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into refund (id, workspace_id, claim_id, order_id, business_entity_id, channel_account_id, executor, status, requested_amount, idempotency_key,
                                    source, note, requested_by)
                values (:id, :ws, :c, :o, :be, :ca, :ex, 'REQUESTED', :amt, :k, 'MANUAL', cast(:n as text), :by)
                """).param("id", id).param("ws", a.workspaceId()).param("c", claimId).param("o", c.get("order_id")).param("be", c.get("business_entity_id"))
                .param("ca", c.get("channel_account_id")).param("ex", executor).param("amt", amount).param("k", key).param("n", trim(r.note(), 500))
                .param("by", a.memberId()).update();
        event(a, claimId, "REFUND_REQUESTED", null, amount.toPlainString(), Map.of("executor", executor, "refund_id", id.toString()));
        workItems.raise(new WorkItemService.Raise(a.workspaceId(), (UUID) c.get("business_entity_id"), (UUID) c.get("channel_account_id"), null, "REFUND_CONFIRM",
                "HIGH", "환불 확정 필요: " + c.get("claim_no") + " " + amount.toPlainString() + "원",
                "환불을 요청했습니다(" + executorKo(executor) + "). 실제 환불이 끝나면 확정 금액과 처리 번호를 기록하세요.",
                "판매채널·PG 에서 환불 처리 결과를 확인하고 환불 확정을 기록하세요.", "refund:" + id + ":CONFIRM", Map.of("refund_id", id.toString())));
        audit.record(AuditEntry.builder(a.workspaceId(), "REFUND_REQUESTED", "REFUND", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) c.get("business_entity_id"), (UUID) c.get("channel_account_id"))
                .after(Map.of("claim_no", c.get("claim_no"), "amount", amount.toPlainString(), "executor", executor)).build());
        refresh(claimId);
        return get(claimId);
    }

    @Transactional
    public ClaimView confirmRefund(UUID refundId, RefundConfirm in) {
        guard.requirePermission(Perm.CLAIM_REFUND);
        CurrentActor a = guard.actor();
        Map<String, Object> rf = jdbc.sql("select * from refund where workspace_id = :ws and id = :id for update").param("ws", a.workspaceId()).param("id", refundId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("REFUND"));
        UUID claimId = (UUID) rf.get("claim_id");
        writable(claimId);
        if ("CONFIRMED".equals(rf.get("status"))) {
            return get(claimId);
        }
        if (!"REQUESTED".equals(rf.get("status"))) {
            throw Errors.conflict("REFUND_STATE_INVALID", "확정할 수 없는 환불 상태입니다.", Map.of("status", rf.get("status")));
        }
        BigDecimal amount = in == null || in.confirmedAmount() == null ? null : in.confirmedAmount().setScale(0, RoundingMode.UNNECESSARY);
        if (amount == null || amount.signum() < 0 || amount.compareTo((BigDecimal) rf.get("requested_amount")) > 0) {
            throw Errors.validation("confirmed_amount", "확정 금액은 0 이상, 요청 금액 이하입니다.");
        }
        Instant at = in.confirmedAt() == null ? clock.instant() : in.confirmedAt();
        if (at.isAfter(clock.instant().plusSeconds(300))) {
            throw Errors.validation("confirmed_at", "확정 시각이 미래입니다.");
        }
        confirmInternal(refundId, amount, trim(in.externalRef(), 200), at);
        audit.record(AuditEntry.builder(a.workspaceId(), "REFUND_CONFIRMED", "REFUND", refundId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) rf.get("business_entity_id"), (UUID) rf.get("channel_account_id"))
                .after(Map.of("confirmed_amount", amount.toPlainString(), "external_ref", String.valueOf(in.externalRef()))).build());
        return get(claimId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmInternal(UUID refundId, BigDecimal amount, String externalRef, Instant at) {
        CurrentActor a = guard.actor();
        Map<String, Object> rf = jdbc.sql("select * from refund where id = :id for update").param("id", refundId).query().listOfRows().get(0);
        jdbc.sql("""
                update refund set status = 'CONFIRMED', confirmed_amount = :amt, external_ref = cast(:ref as text), confirmed_at = :at, confirmed_by = cast(:by as uuid),
                       version = version + 1 where id = :id
                """).param("amt", amount).param("ref", externalRef).param("at", Db.ts(at)).param("by", a.memberId()).param("id", refundId).update();
        UUID claimId = (UUID) rf.get("claim_id");
        event(a, claimId, "REFUND_CONFIRMED", null, amount.toPlainString(), Map.of("refund_id", refundId.toString(), "external_ref", String.valueOf(externalRef)));
        workItems.resolveByKey(a.workspaceId(), "refund:" + refundId + ":CONFIRM");
        refresh(claimId);
    }

    /** 채널이 환불을 끝냈다고 알려 온 경우(채널 수집): 확정 환불을 한 번만 만든다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordChannelRefund(UUID claimId, BigDecimal amount, String externalRef, Instant at) {
        CurrentActor a = guard.actor();
        Map<String, Object> c = lock(a.workspaceId(), claimId);
        String key = "channel-refund:" + claimId + ":" + (externalRef == null ? "final" : externalRef);
        if (jdbc.sql("select count(*) from refund where workspace_id = :ws and idempotency_key = :k").param("ws", a.workspaceId()).param("k", key).query(Integer.class)
                .single() > 0) {
            return;
        }
        BigDecimal confirmed = jdbc.sql("select coalesce(sum(confirmed_amount), 0) from refund where claim_id = :c and status = 'CONFIRMED'").param("c", claimId)
                .query(BigDecimal.class).single();
        BigDecimal room = ((BigDecimal) c.get("expected_refund_amount")).subtract(confirmed);
        BigDecimal amt = amount == null ? room : amount;
        if (amt.signum() <= 0) {
            return;
        }
        if (amt.compareTo(room) > 0) {
            workItems.raise(new WorkItemService.Raise(a.workspaceId(), (UUID) c.get("business_entity_id"), (UUID) c.get("channel_account_id"), null,
                    "REFUND_AMOUNT_MISMATCH", "HIGH", "채널 환불액이 예정액보다 큼: " + c.get("claim_no"),
                    "판매채널 환불액 " + amt.toPlainString() + "원이 남은 환불 예정액 " + room.toPlainString() + "원보다 큽니다. 예정액까지만 확정했습니다.",
                    "채널 클레임·정산 내역을 확인하고 차액을 정산 대사에서 처리하세요.", "claim:" + claimId + ":REFUND_MISMATCH", Map.of("claim_id", claimId.toString())));
            amt = room;
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into refund (id, workspace_id, claim_id, order_id, business_entity_id, channel_account_id, executor, status, requested_amount, confirmed_amount,
                                    idempotency_key, external_ref, source, confirmed_at)
                values (:id, :ws, :c, :o, :be, :ca, 'CHANNEL', 'CONFIRMED', :amt, :amt, :k, cast(:ref as text), 'API', :at)
                """).param("id", id).param("ws", a.workspaceId()).param("c", claimId).param("o", c.get("order_id")).param("be", c.get("business_entity_id"))
                .param("ca", c.get("channel_account_id")).param("amt", amt).param("k", key).param("ref", externalRef).param("at", Db.ts(at)).update();
        event(a, claimId, "REFUND_CONFIRMED", null, amt.toPlainString(), Map.of("refund_id", id.toString(), "executor", "CHANNEL"));
        refresh(claimId);
    }

    @Transactional
    public ClaimView failRefund(UUID refundId, RefundFail in) {
        guard.requirePermission(Perm.CLAIM_REFUND);
        CurrentActor a = guard.actor();
        Map<String, Object> rf = jdbc.sql("select * from refund where workspace_id = :ws and id = :id for update").param("ws", a.workspaceId()).param("id", refundId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("REFUND"));
        UUID claimId = (UUID) rf.get("claim_id");
        writable(claimId);
        if (!"REQUESTED".equals(rf.get("status"))) {
            throw Errors.conflict("REFUND_STATE_INVALID", "실패로 기록할 수 없는 환불 상태입니다.", Map.of("status", rf.get("status")));
        }
        String reason = in == null ? null : trim(in.reason(), 500);
        if (reason == null) {
            throw Errors.validation("reason", "실패 사유를 입력하세요.");
        }
        jdbc.sql("update refund set status = 'FAILED', failure_reason = :r, version = version + 1 where id = :id").param("r", reason).param("id", refundId).update();
        event(a, claimId, "REFUND_FAILED", null, null, Map.of("refund_id", refundId.toString(), "reason", reason));
        workItems.resolveByKey(a.workspaceId(), "refund:" + refundId + ":CONFIRM");
        audit.record(AuditEntry.builder(a.workspaceId(), "REFUND_FAILED", "REFUND", refundId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) rf.get("business_entity_id"), (UUID) rf.get("channel_account_id")).reason(reason).build());
        refresh(claimId);
        return get(claimId);
    }

    // ---------------------------------------------------------------- 채널 처리 반영 상태

    @Transactional(propagation = Propagation.MANDATORY)
    public void markChannelAction(UUID claimId, String status, String error) {
        CurrentActor a = guard.actor();
        Map<String, Object> c = lock(a.workspaceId(), claimId);
        jdbc.sql("update claim set channel_action_status = :s, channel_action_error = cast(:e as text), version = version + 1, updated_at = now() where id = :id")
                .param("s", status).param("e", error).param("id", claimId).update();
        event(a, claimId, "CHANNEL_ACTION_" + status, (String) c.get("channel_action_status"), status, Map.of());
        String key = "claim:" + claimId + ":CHANNEL";
        if ("MANUAL_REQUIRED".equals(status) || "FAILED".equals(status)) {
            workItems.raise(new WorkItemService.Raise(a.workspaceId(), (UUID) c.get("business_entity_id"), (UUID) c.get("channel_account_id"), null,
                    "MANUAL_REQUIRED".equals(status) ? "CLAIM_CHANNEL_ACTION_MANUAL" : "CLAIM_CHANNEL_ACTION_FAILED", "HIGH",
                    "판매채널 클레임 " + ("APPROVE".equals(c.get("channel_action")) ? "승인" : "거부") + " 처리 필요: " + c.get("claim_no"), error,
                    "판매자센터에서 같은 결정을 처리한 뒤 클레임 화면에서 '채널 처리 완료'를 기록하세요.", key, Map.of("claim_id", claimId.toString())));
        } else {
            workItems.resolveByKey(a.workspaceId(), key);
        }
    }

    @Transactional
    public ClaimView manualChannelDone(UUID claimId) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        Map<String, Object> c = writable(claimId);
        if (!Set.of("MANUAL_REQUIRED", "FAILED").contains((String) c.get("channel_action_status"))) {
            throw Errors.conflict("CHANNEL_ACTION_STATE_INVALID", "채널 처리 완료를 기록할 수 없는 상태입니다.", Map.of("status", c.get("channel_action_status")));
        }
        markChannelAction(claimId, "MANUAL_DONE", null);
        CurrentActor a = guard.actor();
        audit.record(AuditEntry.builder(a.workspaceId(), "CLAIM_CHANNEL_ACTION_MANUAL_DONE", "CLAIM", claimId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) c.get("business_entity_id"), (UUID) c.get("channel_account_id")).build());
        return get(claimId);
    }

    // ---------------------------------------------------------------- 상태 집계

    /** 축 상태(환불·재출고)를 다시 계산하고, 모두 끝났으면 완료. 주문 결제 상태(부분 환불·환불)도 갱신. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(UUID claimId) {
        CurrentActor a = guard.actor();
        Map<String, Object> c = lock(a.workspaceId(), claimId);
        String st = (String) c.get("status");
        UUID orderId = (UUID) c.get("order_id");
        BigDecimal expected = (BigDecimal) c.get("expected_refund_amount");
        Map<String, Object> agg = jdbc.sql("""
                select coalesce(sum(confirmed_amount) filter (where status = 'CONFIRMED'), 0) as confirmed, count(*) filter (where status = 'REQUESTED') as open,
                       count(*) filter (where status = 'FAILED') as failed from refund where claim_id = :c
                """).param("c", claimId).query().listOfRows().get(0);
        BigDecimal confirmed = (BigDecimal) agg.get("confirmed");
        String refund = (String) c.get("refund_status");
        if (!"CANCELLED".equals(refund)) {
            if (expected.signum() == 0) {
                refund = "NOT_REQUIRED";
            } else if (confirmed.compareTo(expected) >= 0) {
                refund = "CONFIRMED";
            } else if (((Number) agg.get("open")).intValue() > 0) {
                refund = "REQUESTED";
            } else if (confirmed.signum() > 0) {
                refund = "PARTIALLY_CONFIRMED";
            } else if (((Number) agg.get("failed")).intValue() > 0) {
                refund = "FAILED";
            } else {
                refund = "EXPECTED";
            }
        }
        String reship = (String) c.get("reship_status");
        if ("EXCHANGE".equals(c.get("claim_type")) && !"NOT_REQUIRED".equals(reship)) {
            int left = jdbc.sql("""
                    select count(*) from claim_item ci join order_item i on i.id = ci.replacement_order_item_id
                     where ci.claim_id = :c and i.shipped_qty < i.original_qty - i.canceled_qty
                    """).param("c", claimId).query(Integer.class).single();
            int made = jdbc.sql("select count(*) from claim_item where claim_id = :c and replacement_order_item_id is not null").param("c", claimId).query(Integer.class).single();
            reship = made > 0 && left == 0 ? "SHIPPED" : "PENDING";
        }
        String next = st;
        if (Set.of("APPROVED", "PROCESSING").contains(st)) {
            boolean cancelDone = !"CANCEL".equals(c.get("claim_type")) || Boolean.TRUE.equals(c.get("cancel_applied"));
            boolean returnDone = Set.of("NOT_REQUIRED", "INSPECTED").contains((String) c.get("return_status"));
            boolean refundDone = Set.of("NOT_REQUIRED", "CONFIRMED").contains(refund);
            boolean reshipDone = Set.of("NOT_REQUIRED", "SHIPPED").contains(reship);
            next = cancelDone && returnDone && refundDone && reshipDone ? "COMPLETED" : "PROCESSING";
        }
        if (!next.equals(st) || !refund.equals(c.get("refund_status")) || !reship.equals(c.get("reship_status"))) {
            jdbc.sql("""
                    update claim set status = :s, refund_status = :r, reship_status = :rs, completed_at = case when :s = 'COMPLETED' then now() else completed_at end,
                           version = version + 1, updated_at = now() where id = :id
                    """).param("s", next).param("r", refund).param("rs", reship).param("id", claimId).update();
            if (!next.equals(st)) {
                event(a, claimId, "STATUS_CHANGED", st, next, Map.of());
            }
            if (!refund.equals(c.get("refund_status"))) {
                event(a, claimId, "REFUND_STATUS_CHANGED", (String) c.get("refund_status"), refund, Map.of());
            }
            if (!reship.equals(c.get("reship_status"))) {
                event(a, claimId, "RESHIP_STATUS_CHANGED", (String) c.get("reship_status"), reship, Map.of());
            }
        }
        // 주문 결제 상태
        Map<String, Object> o = jdbc.sql("select payment_status, total_amount from sales_order where id = :o").param("o", orderId).query().listOfRows().get(0);
        BigDecimal refunded = jdbc.sql("select coalesce(sum(confirmed_amount), 0) from refund where order_id = :o and status = 'CONFIRMED'").param("o", orderId)
                .query(BigDecimal.class).single();
        String pay = (String) o.get("payment_status");
        String nextPay = pay;
        if (refunded.signum() > 0 && Set.of("PAID", "PARTIALLY_REFUNDED", "REFUNDED").contains(pay)) {
            nextPay = refunded.compareTo((BigDecimal) o.get("total_amount")) >= 0 ? "REFUNDED" : "PARTIALLY_REFUNDED";
        }
        if (!nextPay.equals(pay)) {
            jdbc.sql("update sales_order set payment_status = :p, version = version + 1, updated_at = now() where id = :o").param("p", nextPay).param("o", orderId).update();
            orderEvent(a, orderId, "PAYMENT_STATUS_CHANGED", pay, nextPay, Map.of("refunded", refunded.toPlainString()));
        }
    }

    // ---------------------------------------------------------------- 조회

    public List<ClaimView> list(List<String> businessEntityIds, String type, String status, UUID orderId, String q) {
        guard.requirePermission(Perm.ORDER_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        if (bes.isEmpty() || cas.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT + " where c.workspace_id = :ws and c.business_entity_id in (:bes) and c.channel_account_id in (:cas)");
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "bes", bes, "cas", cas));
        if (type != null && !type.isBlank()) {
            sql.append(" and c.claim_type = :t");
            p.put("t", type);
        }
        if (status != null && !status.isBlank()) {
            if ("OPEN".equals(status)) {
                sql.append(" and c.status in ('REQUESTED', 'REVIEWING', 'APPROVED', 'PROCESSING')");
            } else {
                sql.append(" and c.status = :st");
                p.put("st", status);
            }
        }
        if (orderId != null) {
            sql.append(" and c.order_id = :o");
            p.put("o", orderId);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" and (upper(c.claim_no) like :q or upper(so.order_no) like :q or upper(so.external_order_key) like :q or upper(coalesce(c.external_claim_key, '')) like :q)");
            p.put("q", "%" + q.trim().toUpperCase(java.util.Locale.ROOT) + "%");
        }
        sql.append(" order by c.created_at desc limit 300");
        return jdbc.sql(sql.toString()).params(p).query((rs, i) -> view(rs)).list().stream().map(v -> withParts(v, false)).toList();
    }

    public ClaimView get(UUID id) {
        guard.requirePermission(Perm.ORDER_READ);
        ClaimView v = jdbc.sql(SELECT + " where c.workspace_id = :ws and c.id = :id").param("ws", guard.actor().workspaceId()).param("id", id).query((rs, i) -> view(rs))
                .optional().orElseThrow(() -> Errors.notFound("CLAIM"));
        guard.checkRead("CLAIM", id, v.businessEntityId(), v.channelAccountId());
        return withParts(v, true);
    }

    // ---------------------------------------------------------------- 내부

    Map<String, Object> writable(UUID claimId) {
        CurrentActor a = guard.actor();
        Map<String, Object> c = jdbc.sql("select * from claim where workspace_id = :ws and id = :id").param("ws", a.workspaceId()).param("id", claimId).query()
                .listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("CLAIM"));
        guard.checkWrite("CLAIM", claimId, (UUID) c.get("business_entity_id"), (UUID) c.get("channel_account_id"));
        return c;
    }

    Map<String, Object> lock(UUID ws, UUID claimId) {
        return jdbc.sql("select * from claim where workspace_id = :ws and id = :id for update").param("ws", ws).param("id", claimId).query().listOfRows().stream()
                .findFirst().orElseThrow(() -> Errors.notFound("CLAIM"));
    }

    private int limit(String type, Map<String, Object> it) {
        UUID itemId = (UUID) it.get("id");
        int original = ((Number) it.get("original_qty")).intValue();
        int canceled = ((Number) it.get("canceled_qty")).intValue();
        int shipped = ((Number) it.get("shipped_qty")).intValue();
        if ("CANCEL".equals(type)) {
            return original - canceled - shipped - openCancelQty((UUID) it.get("order_id"), itemId);
        }
        int claimed = jdbc.sql("""
                select coalesce(sum(ci.quantity), 0) from claim_item ci join claim c on c.id = ci.claim_id
                 where ci.order_item_id = :i and c.claim_type in ('RETURN', 'EXCHANGE') and c.status not in ('REJECTED', 'CANCELLED')
                """).param("i", itemId).query(Integer.class).single();
        return shipped - claimed;
    }

    private int openCancelQty(UUID orderId, UUID itemId) {
        return jdbc.sql("""
                select coalesce(sum(ci.quantity), 0) from claim_item ci join claim c on c.id = ci.claim_id
                 where c.order_id = :o and (cast(:i as uuid) is null or ci.order_item_id = :i) and c.claim_type = 'CANCEL' and not c.cancel_applied
                   and c.status in ('REQUESTED', 'REVIEWING', 'APPROVED', 'PROCESSING')
                """).param("o", orderId).param("i", itemId).query(Integer.class).single();
    }

    /** 주문상품 환불액: (상품 금액 − 할인) × 수량 비례(원 미만 버림). 마지막 남은 수량은 나머지 전부 → 합이 결제액을 넘지 않는다. */
    private BigDecimal refundFor(Map<String, Object> it, int q) {
        BigDecimal net = ((BigDecimal) it.get("line_amount")).subtract((BigDecimal) it.get("seller_discount")).subtract((BigDecimal) it.get("channel_discount"))
                .max(BigDecimal.ZERO);
        int original = ((Number) it.get("original_qty")).intValue();
        Map<String, Object> used = jdbc.sql("""
                select coalesce(sum(ci.quantity), 0) as qty, coalesce(sum(ci.refund_amount), 0) as amt from claim_item ci join claim c on c.id = ci.claim_id
                 where ci.order_item_id = :i and c.claim_type in ('CANCEL', 'RETURN') and c.status not in ('REJECTED', 'CANCELLED')
                """).param("i", it.get("id")).query().listOfRows().get(0);
        int usedQty = ((Number) used.get("qty")).intValue();
        BigDecimal usedAmt = (BigDecimal) used.get("amt");
        if (usedQty + q >= original) {
            return net.subtract(usedAmt).max(BigDecimal.ZERO);
        }
        return net.multiply(BigDecimal.valueOf(q)).divide(BigDecimal.valueOf(original), 0, RoundingMode.DOWN);
    }

    private void requireNoPhysicalReturn(UUID claimId, String action) {
        int received = jdbc.sql("select count(*) from return_receipt where claim_id = :c").param("c", claimId).query(Integer.class).single();
        if (received > 0) {
            throw Errors.conflict("CLAIM_RETURN_RECEIVED", "회수품이 이미 입고된 클레임은 " + action + "할 수 없습니다. 검수를 마치고 처리하세요.");
        }
    }

    private void allocateAsSystem(UUID orderId) {
        Map<String, Object> o = jdbc.sql("select status, on_hold, composition_status, source_type from sales_order where id = :o").param("o", orderId).query().listOfRows().get(0);
        if (Boolean.TRUE.equals(o.get("on_hold")) || !"FIXED".equals(o.get("composition_status")) || "PLATFORM_FULFILLMENT".equals(o.get("source_type"))
                || !Set.of("CONFIRMED", "PROCESSING").contains((String) o.get("status"))) {
            return;
        }
        asSystem(guard.actor().workspaceId(), () -> fulfillment.allocate(orderId));
    }

    private void asSystem(UUID ws, Runnable work) {
        guard.runAs(actors.systemAutomation(ws), () -> {
            work.run();
            return null;
        });
    }

    /** 자사몰은 PG 환불, 그 밖의 판매채널은 채널(클레임 승인 시 채널이 환불). */
    private String defaultExecutor(UUID orderId) {
        String kind = jdbc.sql("""
                select c.kind from sales_order o join channel_account a on a.id = o.channel_account_id join sales_channel c on c.code = a.sales_channel_code where o.id = :o
                """).param("o", orderId).query(String.class).optional().orElse("");
        return "OWN_MALL".equals(kind) ? "PG" : "CHANNEL";
    }

    private String number(UUID ws, String orderNo) {
        LocalDate day = LocalDate.now(KST);
        int n = jdbc.sql("""
                insert into claim_number_counter (workspace_id, kind, day, last_value) values (:ws, 'CLAIM', :d, 1)
                on conflict (workspace_id, kind, day) do update set last_value = claim_number_counter.last_value + 1 returning last_value
                """).param("ws", ws).param("d", day).query(Integer.class).single();
        return "C" + day.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")) + "-" + String.format("%05d", n);
    }

    void event(CurrentActor a, UUID claimId, String type, String from, String to, Map<String, Object> detail) {
        jdbc.sql("""
                insert into claim_event (id, workspace_id, claim_id, event_type, from_value, to_value, detail, actor_type, actor_member_id)
                values (:id, :ws, :c, :t, cast(:f as text), cast(:to as text), cast(:d as jsonb), :at, cast(:m as uuid))
                """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("c", claimId).param("t", type).param("f", from).param("to", to)
                .param("d", json.write(detail)).param("at", a.memberId() == null ? "SYSTEM" : "USER").param("m", a.memberId()).update();
    }

    private void orderEvent(CurrentActor a, UUID orderId, String type, String from, String to, Map<String, Object> detail) {
        orderEventItem(a, orderId, null, type, from, to, detail);
    }

    private void orderEventItem(CurrentActor a, UUID orderId, UUID itemId, String type, String from, String to, Map<String, Object> detail) {
        jdbc.sql("""
                insert into order_event (id, workspace_id, order_id, order_item_id, event_type, from_value, to_value, detail, actor_type, actor_member_id)
                values (:id, :ws, :o, cast(:i as uuid), :t, cast(:f as text), cast(:to as text), cast(:d as jsonb), :at, cast(:m as uuid))
                """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("o", orderId).param("i", itemId).param("t", type).param("f", from).param("to", to)
                .param("d", json.write(detail)).param("at", a.memberId() == null ? "SYSTEM" : "USER").param("m", a.memberId()).update();
    }

    static String defaultFault(String reason) {
        return switch (reason) {
            case "CHANGE_OF_MIND", "WRONG_ADDRESS" -> "CUSTOMER";
            case "DEFECT", "WRONG_ITEM", "OUT_OF_STOCK", "DELIVERY_DELAY" -> "SELLER";
            case "DAMAGED_IN_TRANSIT" -> "CARRIER";
            default -> "UNKNOWN";
        };
    }

    static String typeKo(String t) {
        return switch (t) {
            case "CANCEL" -> "취소";
            case "RETURN" -> "반품";
            default -> "교환";
        };
    }

    private static String executorKo(String e) {
        return switch (e) {
            case "CHANNEL" -> "판매채널";
            case "PG" -> "PG";
            default -> "수동 송금 등";
        };
    }

    private static BigDecimal nonNegative(BigDecimal v, String field) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v.signum() < 0 || v.scale() > 0 && v.stripTrailingZeros().scale() > 0) {
            throw Errors.validation(field, "금액은 0 이상 원 단위입니다.");
        }
        return v.setScale(0, RoundingMode.UNNECESSARY);
    }

    static String trim(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static final String SELECT = """
            select c.*, so.order_no, be.display_name as be_name, ca.display_name as ca_name,
                   (select coalesce(sum(r.confirmed_amount), 0) from refund r where r.claim_id = c.id and r.status = 'CONFIRMED') as confirmed_refund
              from claim c join sales_order so on so.id = c.order_id join business_entity be on be.id = c.business_entity_id
              join channel_account ca on ca.id = c.channel_account_id
            """;

    private static ClaimView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ClaimView(Db.uuid(rs, "id"), rs.getString("claim_no"), Db.uuid(rs, "order_id"), rs.getString("order_no"), Db.uuid(rs, "business_entity_id"),
                rs.getString("be_name"), Db.uuid(rs, "channel_account_id"), rs.getString("ca_name"), rs.getString("claim_type"), rs.getString("source"),
                rs.getString("external_claim_key"), rs.getString("status"), rs.getString("return_status"), rs.getString("refund_status"), rs.getString("reship_status"),
                rs.getBoolean("cancel_applied"), rs.getString("reason_code"), rs.getString("reason_note"), rs.getString("fault_party"), rs.getBoolean("ship_before_return"),
                rs.getString("return_carrier_code"), rs.getString("return_tracking_no"), rs.getBigDecimal("item_refund_amount"), rs.getBigDecimal("shipping_refund_amount"),
                rs.getBigDecimal("return_shipping_fee"), rs.getBigDecimal("price_difference"), rs.getBigDecimal("expected_refund_amount"),
                rs.getBigDecimal("additional_payment_amount"), rs.getBigDecimal("confirmed_refund"), rs.getString("channel_action_status"),
                rs.getString("channel_action"), rs.getString("channel_action_error"), rs.getString("external_status"), rs.getString("decision_note"),
                Db.instant(rs, "requested_at"), Db.instant(rs, "decided_at"), Db.instant(rs, "completed_at"), Db.instant(rs, "created_at"), List.of(), List.of(), List.of(),
                rs.getInt("version"));
    }

    private ClaimView withParts(ClaimView v, boolean full) {
        List<ItemView> items = jdbc.sql("""
                select ci.*, i.product_name, i.sku_id, s.sku_code, rs.sku_code as repl_code from claim_item ci join order_item i on i.id = ci.order_item_id
                  left join sku s on s.id = i.sku_id left join sku rs on rs.id = ci.replacement_sku_id where ci.claim_id = :c order by ci.line_no
                """).param("c", v.id()).query((rs, i) -> new ItemView(Db.uuid(rs, "id"), rs.getInt("line_no"), Db.uuid(rs, "order_item_id"), rs.getString("product_name"),
                Db.uuid(rs, "sku_id"), rs.getString("sku_code"), rs.getInt("quantity"), rs.getBigDecimal("refund_amount"), Db.uuid(rs, "replacement_sku_id"), rs.getString("repl_code"),
                (Integer) rs.getObject("replacement_quantity"), Db.uuid(rs, "replacement_order_item_id"), rs.getInt("expected_return_qty"), rs.getInt("received_qty"),
                rs.getInt("inspected_qty"))).list();
        List<RefundView> refunds = !full ? List.of() : jdbc.sql("select * from refund where claim_id = :c order by requested_at").param("c", v.id())
                .query((rs, i) -> new RefundView(Db.uuid(rs, "id"), rs.getString("executor"), rs.getString("status"), rs.getBigDecimal("requested_amount"),
                        rs.getBigDecimal("confirmed_amount"), rs.getString("external_ref"), rs.getString("source"), rs.getString("note"), Db.instant(rs, "requested_at"),
                        Db.instant(rs, "confirmed_at"), rs.getString("failure_reason"))).list();
        List<EventView> events = !full ? List.of() : jdbc.sql("select * from claim_event where claim_id = :c order by occurred_at desc limit 100").param("c", v.id())
                .query((rs, i) -> new EventView(rs.getString("event_type"), rs.getString("from_value"), rs.getString("to_value"), json.read(rs.getString("detail")),
                        rs.getString("actor_type"), Db.instant(rs, "occurred_at"))).list();
        return new ClaimView(v.id(), v.claimNo(), v.orderId(), v.orderNo(), v.businessEntityId(), v.businessEntityName(), v.channelAccountId(), v.channelAccountName(),
                v.claimType(), v.source(), v.externalClaimKey(), v.status(), v.returnStatus(), v.refundStatus(), v.reshipStatus(), v.cancelApplied(), v.reasonCode(),
                v.reasonNote(), v.faultParty(), v.shipBeforeReturn(), v.returnCarrierCode(), v.returnTrackingNo(), v.itemRefundAmount(), v.shippingRefundAmount(),
                v.returnShippingFee(), v.priceDifference(), v.expectedRefundAmount(), v.additionalPaymentAmount(), v.confirmedRefundAmount(), v.channelActionStatus(),
                v.channelAction(), v.channelActionError(), v.externalStatus(), v.decisionNote(), v.requestedAt(), v.decidedAt(), v.completedAt(), v.createdAt(), items,
                refunds, events, v.version());
    }
}
