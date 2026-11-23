package com.commerceos.claim;

import com.commerceos.shared.web.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 클레임·환불·반품 입고/검수(M4 O6, docs/07 `/claims`, `/returns`, `/refunds`). */
@RestController
@RequestMapping("/api/v1")
public class ClaimController {
    private final ClaimService claims;
    private final ReturnService returns;

    public ClaimController(ClaimService claims, ReturnService returns) {
        this.claims = claims;
        this.returns = returns;
    }

    @GetMapping("/claims")
    public ApiResponse<List<ClaimService.ClaimView>> list(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                          @RequestParam(required = false) String type, @RequestParam(required = false) String status,
                                                          @RequestParam(name = "order_id", required = false) UUID orderId, @RequestParam(required = false) String q) {
        return ApiResponse.of(claims.list(be, type, status, orderId, q));
    }

    @PostMapping("/claims")
    public ResponseEntity<ApiResponse<ClaimService.ClaimView>> create(@RequestBody ClaimService.NewClaim body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(claims.create(body)));
    }

    @GetMapping("/claims/{id}")
    public ApiResponse<ClaimService.ClaimView> get(@PathVariable UUID id) {
        return ApiResponse.of(claims.get(id));
    }

    @PostMapping("/claims/{id}/approve")
    public ApiResponse<ClaimService.ClaimView> approve(@PathVariable UUID id, @RequestBody(required = false) ClaimService.Decision d) {
        return ApiResponse.of(claims.approve(id, d));
    }

    @PostMapping("/claims/{id}/reject")
    public ApiResponse<ClaimService.ClaimView> reject(@PathVariable UUID id, @RequestBody(required = false) ClaimService.Decision d) {
        return ApiResponse.of(claims.reject(id, d));
    }

    @PostMapping("/claims/{id}/withdraw")
    public ApiResponse<ClaimService.ClaimView> withdraw(@PathVariable UUID id, @RequestBody(required = false) ClaimService.Decision d) {
        return ApiResponse.of(claims.withdraw(id, d));
    }

    @PostMapping("/claims/{id}/return-shipment")
    public ApiResponse<ClaimService.ClaimView> returnShipment(@PathVariable UUID id, @RequestBody ClaimService.ReturnShipment body) {
        return ApiResponse.of(claims.returnShipment(id, body));
    }

    @PostMapping("/claims/{id}/channel-action/manual-done")
    public ApiResponse<ClaimService.ClaimView> channelDone(@PathVariable UUID id) {
        return ApiResponse.of(claims.manualChannelDone(id));
    }

    @PostMapping("/claims/{id}/refund-requests")
    public ApiResponse<ClaimService.ClaimView> refund(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                      @RequestBody ClaimService.RefundRequest body) {
        return ApiResponse.of(claims.requestRefund(id, body, key));
    }

    @PostMapping("/refunds/{id}/confirm")
    public ApiResponse<ClaimService.ClaimView> confirm(@PathVariable UUID id, @RequestBody ClaimService.RefundConfirm body) {
        return ApiResponse.of(claims.confirmRefund(id, body));
    }

    @PostMapping("/refunds/{id}/fail")
    public ApiResponse<ClaimService.ClaimView> fail(@PathVariable UUID id, @RequestBody ClaimService.RefundFail body) {
        return ApiResponse.of(claims.failRefund(id, body));
    }

    @GetMapping("/returns/receipts")
    public ApiResponse<List<ReturnService.ReceiptView>> receipts(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                 @RequestParam(required = false) String status,
                                                                 @RequestParam(required = false) String identification,
                                                                 @RequestParam(name = "claim_id", required = false) UUID claimId) {
        return ApiResponse.of(returns.list(be, status, identification, claimId));
    }

    @PostMapping("/returns/receipts")
    public ResponseEntity<ApiResponse<ReturnService.ReceiptView>> receive(@RequestBody ReturnService.NewReceipt body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(returns.receive(body)));
    }

    @GetMapping("/returns/receipts/{id}")
    public ApiResponse<ReturnService.ReceiptView> receipt(@PathVariable UUID id) {
        return ApiResponse.of(returns.get(id));
    }

    @PostMapping("/returns/receipts/{id}/inspections")
    public ApiResponse<ReturnService.ReceiptView> inspect(@PathVariable UUID id, @RequestBody ReturnService.Inspect body) {
        return ApiResponse.of(returns.inspect(id, body));
    }
}
