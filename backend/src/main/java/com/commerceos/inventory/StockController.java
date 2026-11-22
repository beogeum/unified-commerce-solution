package com.commerceos.inventory;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.platform.idempotency.IdempotencyService;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.IfMatch;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 재고 실사·재실사 기준·조정·이동(M3 I5). 쓰기는 Idempotency-Key 필수. */
@RestController
@RequestMapping("/api/v1/inventory")
public class StockController {
    private final StockCountService counts;
    private final AdjustmentService adjustments;
    private final TransferService transfers;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;

    public StockController(StockCountService counts, AdjustmentService adjustments, TransferService transfers, IdempotencyService idempotency, AccessGuard guard) {
        this.counts = counts;
        this.adjustments = adjustments;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.guard = guard;
    }

    public record Reason(String reason) {
    }

    // ---- 재실사 기준
    @GetMapping("/count-policies")
    public ApiResponse<List<StockCountService.PolicyView>> policies(@RequestParam(name = "business_entity_id") UUID be,
                                                                   @RequestParam(required = false) Boolean history) {
        return ApiResponse.of(Boolean.TRUE.equals(history) ? counts.policyHistory(be) : counts.policies(be));
    }

    @PostMapping("/count-policies")
    public ResponseEntity<?> putPolicy(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody StockCountService.PolicyPut req,
                                       HttpServletRequest http) {
        return idem(key, http, req, HttpStatus.CREATED, () -> counts.putPolicy(req));
    }

    // ---- 조정 승인 기준
    @GetMapping("/adjustment-policies/{businessEntityId}")
    public ResponseEntity<ApiResponse<AdjustmentService.PolicyView>> adjustmentPolicy(@PathVariable UUID businessEntityId) {
        var v = adjustments.policy(businessEntityId);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @PutMapping("/adjustment-policies/{businessEntityId}")
    public ResponseEntity<ApiResponse<AdjustmentService.PolicyView>> putAdjustmentPolicy(@PathVariable UUID businessEntityId,
                                                                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                                        @RequestBody AdjustmentService.PolicyPut req) {
        var v = adjustments.putPolicy(businessEntityId, ifMatch == null ? null : IfMatch.parse(ifMatch), req);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    // ---- 수동 조정
    @PostMapping("/adjustments")
    public ResponseEntity<?> adjust(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody AdjustmentService.Adjust req,
                                    HttpServletRequest http) {
        return idem(key, http, req, HttpStatus.CREATED, () -> adjustments.adjust(req));
    }

    // ---- 실사
    @GetMapping("/stock-counts")
    public ApiResponse<List<StockCountService.CountView>> counts(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                @RequestParam(name = "node_id", required = false) UUID node,
                                                                @RequestParam(required = false) String status) {
        return ApiResponse.of(counts.list(be, node, status));
    }

    @GetMapping("/count-assignees")
    public ApiResponse<List<StockCountService.Assignee>> assignees() {
        return ApiResponse.of(counts.assignees());
    }

    @PostMapping("/stock-counts/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestBody StockCountService.Create req) {
        return ApiResponse.of(counts.preview(req));
    }

    @PostMapping("/stock-counts")
    public ResponseEntity<?> createCount(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody StockCountService.Create req,
                                         HttpServletRequest http) {
        return idem(key, http, req, HttpStatus.CREATED, () -> counts.create(req));
    }

    @GetMapping("/stock-counts/{id}")
    public ApiResponse<StockCountService.CountDetail> count(@PathVariable UUID id) {
        return ApiResponse.of(counts.get(id));
    }

    @PostMapping("/stock-counts/{id}/entries")
    public ResponseEntity<?> enter(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                   @RequestBody StockCountService.Entries req, HttpServletRequest http) {
        return idem(key, http, req, HttpStatus.OK, () -> counts.enter(id, req));
    }

    @PostMapping("/stock-counts/{id}/submit")
    public ResponseEntity<?> submit(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        return idem(key, http, Map.of(), HttpStatus.OK, () -> counts.submit(id));
    }

    @PostMapping("/stock-counts/{id}/recount")
    public ResponseEntity<?> recount(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                     @RequestBody StockCountService.LineIds req, HttpServletRequest http) {
        return idem(key, http, req, HttpStatus.OK, () -> counts.recount(id, req));
    }

    @PostMapping("/stock-counts/{id}/decisions")
    public ResponseEntity<?> decide(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @RequestBody StockCountService.Decision req, HttpServletRequest http) {
        return idem(key, http, req, HttpStatus.OK, () -> counts.decide(id, req));
    }

    @PostMapping("/stock-counts/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody Reason req,
                                    HttpServletRequest http) {
        return idem(key, http, req, HttpStatus.OK, () -> counts.cancel(id, req.reason()));
    }

    // ---- 이동
    @GetMapping("/transfers")
    public ApiResponse<List<TransferService.TransferView>> transfers(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                    @RequestParam(required = false) String status) {
        return ApiResponse.of(transfers.list(be, status));
    }

    @GetMapping("/transfers/{id}")
    public ApiResponse<TransferService.TransferView> transfer(@PathVariable UUID id) {
        return ApiResponse.of(transfers.get(id));
    }

    @PostMapping("/transfers")
    public ResponseEntity<?> createTransfer(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody TransferService.Create req,
                                            HttpServletRequest http) {
        return idem(key, http, req, HttpStatus.CREATED, () -> transfers.create(req));
    }

    @PostMapping("/transfers/{id}/receive")
    public ResponseEntity<?> receive(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                     @RequestBody TransferService.Receive req, HttpServletRequest http) {
        return idem(key, http, req, HttpStatus.OK, () -> transfers.receive(id, req));
    }

    private ResponseEntity<?> idem(String key, HttpServletRequest http, Object req, HttpStatus status, Supplier<Object> work) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req,
                () -> ResponseEntity.status(status).body(ApiResponse.of(work.get())));
    }
}
