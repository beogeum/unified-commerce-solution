package com.commerceos.logistics;

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
public class LogisticsController {
    private final ShipperAccountService shippers;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;
    private final JdbcClient jdbc;

    public LogisticsController(ShipperAccountService shippers, IdempotencyService idempotency, AccessGuard guard, JdbcClient jdbc) {
        this.shippers = shippers;
        this.idempotency = idempotency;
        this.guard = guard;
        this.jdbc = jdbc;
    }

    public record ProviderView(String code, String nameKo, String kind) {
    }

    public record CreateRequest(@NotNull UUID businessEntityId, @NotBlank String logisticsProviderCode,
                                @NotBlank @Size(max = 100) String displayName, @Size(max = 200) String externalShipperKey) {
    }

    /** 물류사업자 기준표. 연동 지원 여부를 뜻하지 않는다. */
    @GetMapping("/logistics-providers")
    public ApiResponse<List<ProviderView>> providers() {
        guard.actor();
        return ApiResponse.of(jdbc.sql("select code, name_ko, kind from logistics_provider order by sort_order")
                .query((rs, i) -> new ProviderView(rs.getString(1), rs.getString(2), rs.getString(3))).list());
    }

    @GetMapping("/shipper-accounts")
    public ApiResponse<List<ShipperAccountService.ShipperAccountView>> list(
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
            @RequestParam(name = "business_entity_id", required = false) List<String> businessEntityIds,
            @RequestParam(name = "logistics_provider_code", required = false) String provider) {
        PageQuery page = PageQuery.of(limit, cursor);
        return page.toResponse(shippers.list(page, businessEntityIds, provider), ShipperAccountService.ShipperAccountView::id);
    }

    @GetMapping("/shipper-accounts/{id}")
    public ResponseEntity<ApiResponse<ShipperAccountService.ShipperAccountView>> get(@PathVariable UUID id) {
        var v = shippers.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @PostMapping("/shipper-accounts")
    public ResponseEntity<?> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @Valid @RequestBody CreateRequest req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(shippers.create(new ShipperAccountService.Create(
                        req.businessEntityId(), req.logisticsProviderCode(), req.displayName(), req.externalShipperKey())))));
    }

    @PatchMapping("/shipper-accounts/{id}")
    public ApiResponse<ShipperAccountService.ShipperAccountView> patch(@PathVariable UUID id,
                                                                      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                      @RequestBody ShipperAccountService.Patch req) {
        return ApiResponse.of(shippers.patch(id, IfMatch.parse(ifMatch), req));
    }
}
