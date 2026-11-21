package com.commerceos.catalog;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.platform.idempotency.IdempotencyService;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.IfMatch;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
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

/** 사업자 SKU 정책·원가(docs/07 `/business-entities/{id}/sku-policies/{skuId}`, M2 C2). */
@RestController
@RequestMapping("/api/v1")
public class BusinessSkuPolicyController {
    private final BusinessSkuPolicyService service;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;

    public BusinessSkuPolicyController(BusinessSkuPolicyService service, IdempotencyService idempotency, AccessGuard guard) {
        this.service = service;
        this.idempotency = idempotency;
        this.guard = guard;
    }

    @GetMapping("/business-entities/{id}/sku-policies")
    public ApiResponse<List<BusinessSkuPolicyService.PolicyView>> list(@PathVariable UUID id, @RequestParam(required = false) String q) {
        return ApiResponse.of(service.list(id, q));
    }

    @GetMapping("/business-entities/{id}/sku-policies/{skuId}")
    public ResponseEntity<ApiResponse<BusinessSkuPolicyService.PolicyView>> get(@PathVariable UUID id, @PathVariable UUID skuId) {
        var v = service.get(id, skuId);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    /** 없으면 만든다(If-Match 생략 또는 "0"), 있으면 If-Match 필수. */
    @PutMapping("/business-entities/{id}/sku-policies/{skuId}")
    public ResponseEntity<ApiResponse<BusinessSkuPolicyService.PolicyView>> put(@PathVariable UUID id, @PathVariable UUID skuId,
                                                                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                               @RequestBody BusinessSkuPolicyService.Put req) {
        var v = service.put(id, skuId, ifMatch == null ? null : IfMatch.parse(ifMatch), req);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @GetMapping("/skus/{skuId}/business-policies")
    public ApiResponse<List<BusinessSkuPolicyService.PolicyView>> bySku(@PathVariable UUID skuId) {
        return ApiResponse.of(service.bySku(skuId));
    }

    @GetMapping("/business-entities/{id}/sku-costs/{skuId}")
    public ApiResponse<List<BusinessSkuPolicyService.CostView>> costs(@PathVariable UUID id, @PathVariable UUID skuId) {
        return ApiResponse.of(service.costs(id, skuId));
    }

    @PostMapping("/business-entities/{id}/sku-costs/{skuId}")
    public ResponseEntity<?> addCost(@PathVariable UUID id, @PathVariable UUID skuId,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                     @RequestBody BusinessSkuPolicyService.NewCost req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.addCost(id, skuId, req))));
    }
}
