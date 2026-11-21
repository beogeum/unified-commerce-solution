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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GWP 캠페인(M2 C5). */
@RestController
@RequestMapping("/api/v1")
public class GwpController {
    private final GwpService service;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;

    public GwpController(GwpService service, IdempotencyService idempotency, AccessGuard guard) {
        this.service = service;
        this.idempotency = idempotency;
        this.guard = guard;
    }

    @GetMapping("/gwp-campaigns")
    public ApiResponse<List<GwpService.CampaignView>> list(@RequestParam(name = "business_entity_id", required = false) List<String> bes,
                                                           @RequestParam(required = false) String status) {
        return ApiResponse.of(service.list(bes, status));
    }

    @GetMapping("/gwp-campaigns/{id}")
    public ResponseEntity<ApiResponse<GwpService.CampaignView>> get(@PathVariable UUID id) {
        var v = service.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @PostMapping("/gwp-campaigns")
    public ResponseEntity<?> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @RequestBody GwpService.Upsert req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.create(req))));
    }

    @PutMapping("/gwp-campaigns/{id}")
    public ApiResponse<GwpService.CampaignView> update(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                       @RequestBody GwpService.Upsert req) {
        return ApiResponse.of(service.update(id, IfMatch.parse(ifMatch), req));
    }

    @PatchMapping("/gwp-campaigns/{id}")
    public ApiResponse<GwpService.CampaignView> patch(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                      @RequestBody GwpService.Patch req) {
        return ApiResponse.of(service.patch(id, IfMatch.parse(ifMatch), req));
    }

    @PostMapping("/gwp-campaigns/preview")
    public ApiResponse<GwpEvaluator.Result> preview(@RequestBody GwpService.Preview req) {
        return ApiResponse.of(service.preview(req));
    }
}
