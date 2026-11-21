package com.commerceos.integration;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.integration.spi.Connector;
import com.commerceos.platform.idempotency.IdempotencyService;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.IfMatch;
import com.commerceos.shared.web.PageQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationController {
    private final ConnectionService connections;
    private final CapabilityService capabilities;
    private final SyncJobService syncJobs;
    private final ConnectorRegistry registry;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;

    public IntegrationController(ConnectionService connections, CapabilityService capabilities, SyncJobService syncJobs,
                                 ConnectorRegistry registry, IdempotencyService idempotency, AccessGuard guard) {
        this.connections = connections;
        this.capabilities = capabilities;
        this.syncJobs = syncJobs;
        this.registry = registry;
        this.idempotency = idempotency;
        this.guard = guard;
    }

    public record CreateRequest(@NotBlank String targetType, @NotNull UUID targetId, @NotBlank String connectorCode,
                                @NotBlank @Size(max = 100) String displayName, @Size(max = 50) String apiVersion,
                                @NotBlank String authType, String developerRegistrationStatus, Map<String, String> credential,
                                Instant credentialExpiresAt, @Size(max = 500) String rateLimitNote) {
        @Override
        public String toString() {
            return "CreateRequest[targetType=" + targetType + ", targetId=" + targetId + ", connectorCode=" + connectorCode + ", credential=***]";
        }
    }

    public record CredentialRequest(@NotNull Map<String, String> credential, Instant expiresAt) {
        @Override
        public String toString() {
            return "CredentialRequest[credential=***]";
        }
    }

    public record SyncJobRequest(String jobType) {
    }

    public record ConnectorView(String code, String targetType, boolean isMock) {
    }

    @GetMapping("/connectors")
    public ApiResponse<List<ConnectorView>> connectors() {
        guard.actor();
        return ApiResponse.of(registry.all().stream().map(c -> new ConnectorView(c.code(), c.targetType(), c.isMock())).toList());
    }

    @GetMapping("/connections")
    public ApiResponse<List<ConnectionService.ConnectionView>> list(
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
            @RequestParam(name = "business_entity_id", required = false) List<String> businessEntityIds,
            @RequestParam(name = "channel_account_id", required = false) UUID channelAccountId,
            @RequestParam(required = false) String status) {
        PageQuery page = PageQuery.of(limit, cursor);
        return page.toResponse(connections.list(page, businessEntityIds, status, channelAccountId), ConnectionService.ConnectionView::id);
    }

    @GetMapping("/connections/{id}")
    public ResponseEntity<ApiResponse<ConnectionService.ConnectionView>> get(@PathVariable UUID id) {
        var v = connections.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    /**
     * 연결 생성. 인증정보가 요청 본문에 있으므로 멱등 기록에는 요청 해시만 저장되고(원문 미저장),
     * 응답에도 인증정보를 포함하지 않는다.
     */
    @PostMapping("/connections")
    public ResponseEntity<?> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @Valid @RequestBody CreateRequest req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(connections.create(new ConnectionService.Create(
                        req.targetType(), req.targetId(), req.connectorCode(), req.displayName(), req.apiVersion(), req.authType(),
                        req.developerRegistrationStatus(), req.credential(), req.credentialExpiresAt(), req.rateLimitNote())))));
    }

    @PatchMapping("/connections/{id}")
    public ResponseEntity<ApiResponse<ConnectionService.ConnectionView>> patch(@PathVariable UUID id,
                                                                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                               @RequestBody ConnectionService.Patch req) {
        var v = connections.patch(id, IfMatch.parse(ifMatch), req);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @PostMapping("/connections/{id}/credentials")
    public ResponseEntity<?> rotate(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @Valid @RequestBody CredentialRequest req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.ok(ApiResponse.of(connections.rotateCredential(id, req.credential(), req.expiresAt()))));
    }

    @GetMapping("/capabilities")
    public ApiResponse<List<CapabilityService.CapabilityView>> capabilities(@RequestParam("connection_id") UUID connectionId) {
        return ApiResponse.of(capabilities.list(connectionId));
    }

    @PutMapping("/connections/{id}/capabilities/{feature}/{operation}")
    public ApiResponse<CapabilityService.CapabilityView> updateCapability(@PathVariable UUID id, @PathVariable String feature,
                                                                          @PathVariable String operation,
                                                                          @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                          @RequestBody CapabilityService.Update req) {
        return ApiResponse.of(capabilities.update(id, feature, operation, IfMatch.parse(ifMatch), req));
    }

    /** docs/07 경로 그대로: {id} 는 연결 ID (REV-041, CHG-004). */
    @PostMapping("/{id}/sync-jobs")
    public ResponseEntity<?> sync(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                  @RequestBody(required = false) SyncJobRequest req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        SyncJobRequest body = req == null ? new SyncJobRequest(null) : req;
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), body, () ->
                ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(syncJobs.request(id, body.jobType()))));
    }
}
