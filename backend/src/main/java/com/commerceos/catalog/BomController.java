package com.commerceos.catalog;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.platform.idempotency.IdempotencyService;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.IfMatch;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BOM(M2 C4). 수정·게시·종료는 If-Match(= version_lock). */
@RestController
@RequestMapping("/api/v1")
public class BomController {
    private final BomService boms;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;

    public BomController(BomService boms, IdempotencyService idempotency, AccessGuard guard) {
        this.boms = boms;
        this.idempotency = idempotency;
        this.guard = guard;
    }

    public record Components(List<BomService.ComponentInput> components, Boolean splitShipmentAllowed) {
    }

    public record Reason(String reason) {
    }

    @GetMapping("/boms")
    public ApiResponse<List<BomService.BomView>> list(@RequestParam(name = "parent_sku_id", required = false) UUID parentSkuId,
                                                      @RequestParam(name = "bom_type", required = false) String bomType,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(name = "packaging_profile_code", required = false) String profile) {
        return ApiResponse.of(boms.list(parentSkuId, bomType, status, profile));
    }

    @GetMapping("/boms/{id}")
    public ResponseEntity<ApiResponse<BomService.BomView>> get(@PathVariable UUID id) {
        var v = boms.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.versionLock())).body(ApiResponse.of(v));
    }

    @GetMapping("/boms/{id}/cost")
    public ApiResponse<BomService.CostView> cost(@PathVariable UUID id, @RequestParam(name = "business_entity_id") UUID businessEntityId) {
        return ApiResponse.of(boms.cost(id, businessEntityId));
    }

    @GetMapping("/skus/{skuId}/boms/effective")
    public ApiResponse<BomService.BomView> effective(@PathVariable UUID skuId, @RequestParam(name = "bom_type") String bomType,
                                                     @RequestParam(required = false) Instant at) {
        return ApiResponse.of(boms.effective(skuId, bomType, at == null ? Instant.now() : at)
                .orElseThrow(() -> com.commerceos.shared.error.Errors.notFound("BOM")));
    }

    @PostMapping("/boms")
    public ResponseEntity<?> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @RequestBody BomService.Create req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(boms.create(req))));
    }

    @PutMapping("/boms/{id}/components")
    public ApiResponse<BomService.BomView> components(@PathVariable UUID id,
                                                      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                      @RequestBody Components req) {
        return ApiResponse.of(boms.replaceComponents(id, IfMatch.parse(ifMatch), req.components(), req.splitShipmentAllowed()));
    }

    @PostMapping("/boms/{id}/publish")
    public ApiResponse<BomService.BomView> publish(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                   @RequestBody(required = false) BomService.Publish req) {
        return ApiResponse.of(boms.publish(id, IfMatch.parse(ifMatch), req));
    }

    @PostMapping("/boms/{id}/retire")
    public ApiResponse<BomService.BomView> retire(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                  @RequestBody Reason req) {
        return ApiResponse.of(boms.retire(id, IfMatch.parse(ifMatch), req.reason()));
    }

    @DeleteMapping("/boms/{id}")
    public ResponseEntity<Void> deleteDraft(@PathVariable UUID id) {
        boms.deleteDraft(id);
        return ResponseEntity.noContent().build();
    }
}
