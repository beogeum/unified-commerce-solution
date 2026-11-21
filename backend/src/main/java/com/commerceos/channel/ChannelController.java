package com.commerceos.channel;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.platform.idempotency.IdempotencyService;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.IfMatch;
import com.commerceos.shared.web.PageQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ChannelController {
    private final ChannelAccountService accounts;
    private final SalesUnitService units;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;
    private final JdbcClient jdbc;

    public ChannelController(ChannelAccountService accounts, SalesUnitService units, IdempotencyService idempotency, AccessGuard guard,
                             JdbcClient jdbc) {
        this.accounts = accounts;
        this.units = units;
        this.idempotency = idempotency;
        this.guard = guard;
        this.jdbc = jdbc;
    }

    public record SalesChannelView(String code, String nameKo, String channelGroup, String kind, String rolloutTier,
                                   boolean supportsSubUnits, String subUnitLabelKo) {
    }

    public record CreateAccountRequest(@NotNull UUID businessEntityId, @NotBlank String salesChannelCode,
                                       @NotBlank @Size(max = 100) String displayName, @Size(max = 200) String externalAccountKey,
                                       ChannelAccountService.DefaultUnit defaultSalesUnit) {
    }

    public record CreateUnitRequest(@NotBlank @Size(max = 100) String externalUnitKey, @Size(max = 100) String displayName) {
    }

    /** 판매채널 기준표. 연동 지원 여부를 뜻하지 않는다(실제 지원은 /integrations/capabilities). */
    @GetMapping("/sales-channels")
    public ApiResponse<List<SalesChannelView>> salesChannels() {
        guard.actor();
        return ApiResponse.of(jdbc.sql("select * from sales_channel order by sort_order")
                .query((rs, i) -> new SalesChannelView(rs.getString("code"), rs.getString("name_ko"), rs.getString("channel_group"),
                        rs.getString("kind"), rs.getString("rollout_tier"), rs.getBoolean("supports_sub_units"),
                        rs.getString("sub_unit_label_ko"))).list());
    }

    @GetMapping("/channel-accounts")
    public ApiResponse<List<ChannelAccountService.ChannelAccountView>> list(
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
            @RequestParam(name = "business_entity_id", required = false) List<String> businessEntityIds,
            @RequestParam(name = "sales_channel_code", required = false) String salesChannelCode,
            @RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        PageQuery page = PageQuery.of(limit, cursor);
        return page.toResponse(accounts.list(page, new ChannelAccountService.Filter(businessEntityIds, salesChannelCode, status, q)),
                ChannelAccountService.ChannelAccountView::id);
    }

    @GetMapping("/channel-accounts/{id}")
    public ResponseEntity<ApiResponse<ChannelAccountService.ChannelAccountView>> get(@PathVariable UUID id) {
        var v = accounts.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @PostMapping("/channel-accounts")
    public ResponseEntity<?> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @Valid @RequestBody CreateAccountRequest req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(accounts.create(new ChannelAccountService.Create(
                        req.businessEntityId(), req.salesChannelCode(), req.displayName(), req.externalAccountKey(), req.defaultSalesUnit())))));
    }

    @PatchMapping("/channel-accounts/{id}")
    public ResponseEntity<ApiResponse<ChannelAccountService.ChannelAccountView>> patch(
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody ChannelAccountService.Patch req) {
        var v = accounts.patch(id, IfMatch.parse(ifMatch), req);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @PostMapping("/channel-accounts/{id}/attribution-changes")
    public ResponseEntity<?> changeAttribution(@PathVariable UUID id,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                               @RequestBody ChannelAccountService.AttributionChange req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        int version = IfMatch.parse(ifMatch);
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.ok(ApiResponse.of(accounts.changeAttribution(id, version, req))));
    }

    @GetMapping("/channel-accounts/{id}/attributions")
    public ApiResponse<List<ChannelAccountService.AttributionView>> attributions(@PathVariable UUID id) {
        return ApiResponse.of(accounts.attributions(id));
    }

    @GetMapping("/channel-accounts/{id}/sales-units")
    public ApiResponse<List<SalesUnitService.SalesUnitView>> units(@PathVariable UUID id) {
        return ApiResponse.of(units.list(id));
    }

    @PostMapping("/channel-accounts/{id}/sales-units")
    public ResponseEntity<?> createUnit(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        @Valid @RequestBody CreateUnitRequest req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(units.create(id,
                        new SalesUnitService.Create(req.externalUnitKey(), req.displayName())))));
    }

    @PatchMapping("/channel-accounts/{id}/sales-units/{unitId}")
    public ApiResponse<SalesUnitService.SalesUnitView> patchUnit(@PathVariable UUID id, @PathVariable UUID unitId,
                                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                @RequestBody SalesUnitService.Patch req) {
        return ApiResponse.of(units.patch(id, unitId, IfMatch.parse(ifMatch), req));
    }
}
