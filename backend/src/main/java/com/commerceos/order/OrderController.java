package com.commerceos.order;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.platform.idempotency.IdempotencyService;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.IfMatch;
import com.commerceos.shared.web.PageQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 주문(M4 O1~O2, docs/07 `/orders`). */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService orders;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;
    private final OrderPrivacyService privacy;

    public OrderController(OrderService orders, IdempotencyService idempotency, AccessGuard guard, OrderPrivacyService privacy) {
        this.privacy = privacy;
        this.orders = orders;
        this.idempotency = idempotency;
        this.guard = guard;
    }

    public record Note(String note) {
    }

    public record Reason(String reason, String purpose) {
    }

    @GetMapping
    public ApiResponse<List<OrderService.OrderRow>> list(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                        @RequestParam(name = "channel_account_id", required = false) UUID ca,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(name = "on_hold", required = false) Boolean onHold,
                                                        @RequestParam(name = "hold_reason", required = false) String holdReason,
                                                        @RequestParam(required = false) String q,
                                                        @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                                        @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return orders.list(new OrderService.Filter(be, ca, status, onHold, holdReason, q, from, to), PageQuery.of(limit, cursor));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderService.OrderDetail>> get(@PathVariable UUID id) {
        var d = orders.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etag(d.version())).body(ApiResponse.of(d));
    }

    @PostMapping("/{id}/pii")
    public ApiResponse<JsonNode> pii(@PathVariable UUID id, @RequestBody Reason req) {
        return ApiResponse.of(orders.revealPii(id, req.reason(), req.purpose()));
    }

    @PostMapping("/{id}/hold")
    public ResponseEntity<ApiResponse<OrderService.OrderDetail>> hold(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                     @RequestBody Note req) {
        var d = orders.hold(id, IfMatch.parse(ifMatch), req.note());
        return ResponseEntity.ok().eTag(IfMatch.etag(d.version())).body(ApiResponse.of(d));
    }

    @PostMapping("/{id}/release-hold")
    public ResponseEntity<ApiResponse<OrderService.OrderDetail>> release(@PathVariable UUID id,
                                                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        var d = orders.releaseHold(id, IfMatch.parse(ifMatch));
        return ResponseEntity.ok().eTag(IfMatch.etag(d.version())).body(ApiResponse.of(d));
    }

    @PostMapping("/{id}/revalidate")
    public ApiResponse<OrderService.OrderDetail> revalidate(@PathVariable UUID id) {
        return ApiResponse.of(orders.revalidate(id));
    }

    @GetMapping("/privacy-policies/{businessEntityId}")
    public ResponseEntity<ApiResponse<OrderPrivacyService.PolicyView>> privacyPolicy(@PathVariable UUID businessEntityId) {
        var v = privacy.policy(businessEntityId);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @org.springframework.web.bind.annotation.PutMapping("/privacy-policies/{businessEntityId}")
    public ResponseEntity<ApiResponse<OrderPrivacyService.PolicyView>> putPrivacyPolicy(@PathVariable UUID businessEntityId,
                                                                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                                       @RequestBody OrderPrivacyService.PolicyPut req) {
        var v = privacy.putPolicy(businessEntityId, ifMatch == null ? null : IfMatch.parse(ifMatch), req);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @PostMapping("/imports")
    public ResponseEntity<?> importOrders(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody OrderService.Import req,
                                          HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req,
                () -> ResponseEntity.ok(ApiResponse.of(orders.importOrders(req))));
    }
}
