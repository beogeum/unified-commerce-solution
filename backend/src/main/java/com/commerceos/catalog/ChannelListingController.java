package com.commerceos.catalog;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.platform.idempotency.IdempotencyService;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.PageQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 채널상품·매핑(docs/07 `/channel-listings`, M2 C3). */
@RestController
@RequestMapping("/api/v1/channel-listings")
public class ChannelListingController {
    private final ChannelListingService service;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;

    public ChannelListingController(ChannelListingService service, IdempotencyService idempotency, AccessGuard guard) {
        this.service = service;
        this.idempotency = idempotency;
        this.guard = guard;
    }

    public record Reason(String reason) {
    }

    @GetMapping
    public ApiResponse<List<ChannelListingService.ListingView>> list(@RequestParam(required = false) Integer limit,
                                                                    @RequestParam(required = false) String cursor,
                                                                    @RequestParam(name = "business_entity_id", required = false) List<String> businessEntityIds,
                                                                    @RequestParam(name = "channel_account_id", required = false) UUID channelAccountId,
                                                                    @RequestParam(name = "sales_unit_id", required = false) UUID salesUnitId,
                                                                    @RequestParam(required = false) String mapping,
                                                                    @RequestParam(required = false) String q) {
        PageQuery page = PageQuery.of(limit, cursor);
        return page.toResponse(service.list(page, businessEntityIds, channelAccountId, salesUnitId, mapping, q),
                ChannelListingService.ListingView::id);
    }

    @GetMapping("/{id}")
    public ApiResponse<ChannelListingService.ListingDetail> get(@PathVariable UUID id) {
        return ApiResponse.of(service.get(id));
    }

    @GetMapping("/{id}/suggestions")
    public ApiResponse<List<ChannelListingService.Suggestion>> suggestions(@PathVariable UUID id) {
        return ApiResponse.of(service.suggestions(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @RequestBody ChannelListingService.Create req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.create(req))));
    }

    @PostMapping("/{id}/mapping")
    public ApiResponse<ChannelListingService.MapResult> map(@PathVariable UUID id, @RequestBody ChannelListingService.MapRequest req) {
        return ApiResponse.of(service.map(id, req));
    }

    @DeleteMapping("/{id}/mapping")
    public ApiResponse<ChannelListingService.ListingDetail> unmap(@PathVariable UUID id, @RequestParam String reason) {
        return ApiResponse.of(service.unmap(id, reason));
    }

    @PostMapping("/{id}/external-id-change")
    public ApiResponse<ChannelListingService.ListingDetail> changeId(@PathVariable UUID id, @RequestBody ChannelListingService.IdChange req) {
        return ApiResponse.of(service.changeExternalId(id, req));
    }
}
