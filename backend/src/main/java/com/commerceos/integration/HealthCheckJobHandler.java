package com.commerceos.integration;

import com.commerceos.integration.spi.Connector;
import com.commerceos.integration.spi.ConnectorContext;
import com.commerceos.integration.spi.ConnectorException;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.job.Job;
import com.commerceos.platform.job.JobHandler;
import com.commerceos.platform.job.JobOutcome;
import com.commerceos.platform.outbox.OutboxPublisher;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연동 상태 점검 작업. 연결 단위로 격리되어 한 연결의 인증 만료·장애가 다른 연결에 영향을 주지 않는다(T21, docs/04 §9).
 * 외부 호출은 트랜잭션 밖에서, 결과 반영(상태·감사·이벤트)은 하나의 트랜잭션에서 한다.
 */
@Component
public class HealthCheckJobHandler implements JobHandler {
    public static final String TYPE = "INTEGRATION_HEALTH_CHECK";

    private final JdbcClient jdbc;
    private final ConnectionService connections;
    private final ConnectorRegistry registry;
    private final OutboxPublisher outbox;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final int errorThreshold;

    public HealthCheckJobHandler(JdbcClient jdbc, ConnectionService connections, ConnectorRegistry registry, OutboxPublisher outbox,
                                 AuditService audit, PlatformTransactionManager tm,
                                 @Value("${commerce.integration.error-threshold:3}") int errorThreshold) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.registry = registry;
        this.outbox = outbox;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
        this.errorThreshold = errorThreshold;
    }

    @Override
    public String jobType() {
        return TYPE;
    }

    @Override
    public JobOutcome handle(Job job) {
        Optional<ConnectionService.ConnectionView> found = connections.findSystem(job.workspaceId(), job.connectionId());
        if (found.isEmpty()) {
            return JobOutcome.failed("CONNECTION_NOT_FOUND", "연동 연결을 찾을 수 없습니다.");
        }
        ConnectionService.ConnectionView c = found.get();
        if ("PAUSED".equals(c.status())) {
            return JobOutcome.failed("CONNECTION_PAUSED", "일시중지된 연결입니다. 재개한 뒤 다시 점검하세요.");
        }
        Optional<Connector> connector = registry.find(c.connectorCode());
        if (connector.isEmpty()) {
            return JobOutcome.reviewRequired("CONNECTOR_NOT_AVAILABLE", "연동 모듈을 사용할 수 없습니다. 관리자 확인이 필요합니다.");
        }
        Optional<Map<String, String>> credential = connections.activeCredential(c.id());
        if (credential.isEmpty()) {
            return JobOutcome.failed("CREDENTIAL_MISSING", "등록된 인증정보가 없습니다. 인증정보를 등록한 뒤 다시 점검하세요.");
        }
        String externalKey = c.channelAccountId() != null
                ? jdbc.sql("select external_account_key from channel_account where id = :id").param("id", c.channelAccountId()).query(String.class).optional().orElse(null)
                : jdbc.sql("select external_shipper_key from shipper_account where id = :id").param("id", c.shipperAccountId()).query(String.class).optional().orElse(null);
        ConnectorContext ctx = new ConnectorContext(job.workspaceId(), c.id(), c.targetType(),
                c.channelAccountId() != null ? c.channelAccountId() : c.shipperAccountId(), externalKey, c.apiVersion(), credential.get());
        try {
            Connector.HealthResult r = connector.get().checkAuth(ctx);
            tx.executeWithoutResult(s -> onSuccess(job.workspaceId(), c));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connection_id", c.id().toString());
            result.put("status", "ACTIVE");
            result.put("api_version", r.apiVersion());
            return JobOutcome.succeeded(result);
        } catch (ConnectorException e) {
            return tx.execute(s -> onFailure(job.workspaceId(), c, e));
        }
    }

    private void onSuccess(UUID ws, ConnectionService.ConnectionView c) {
        jdbc.sql("""
                update integration_connection set status = 'ACTIVE', last_tested_at = now(), last_success_at = now(),
                       consecutive_failures = 0, last_error_code = null, updated_at = now()
                 where id = :id and status <> 'PAUSED'
                """).param("id", c.id()).update();
        if (!"ACTIVE".equals(c.status())) {
            audit.record(AuditEntry.builder(ws, "INTEGRATION_CONNECTION_ACTIVATED", "INTEGRATION_CONNECTION", c.id())
                    .actor(ActorType.INTEGRATION, null, c.connectorCode()).scope(c.businessEntityId(), c.channelAccountId())
                    .before(Map.of("status", c.status())).after(Map.of("status", "ACTIVE")).build());
            outbox.publish(ws, c.businessEntityId(), "INTEGRATION_CONNECTION", c.id(), "IntegrationConnectionRecovered",
                    payload(c, "RECOVERED"));
        }
    }

    private JobOutcome onFailure(UUID ws, ConnectionService.ConnectionView c, ConnectorException e) {
        String newStatus;
        JobOutcome outcome;
        switch (e.kind()) {
            case AUTH_EXPIRED -> {
                newStatus = "AUTH_EXPIRED";
                outcome = JobOutcome.failed("AUTH_EXPIRED", "판매채널 인증이 만료되었습니다. 인증정보를 갱신한 뒤 다시 점검하세요.");
            }
            case PERMISSION_DENIED -> {
                newStatus = "ERROR";
                outcome = JobOutcome.failed("PERMISSION_DENIED", "연동 권한이 없습니다. 판매자센터의 API 권한·솔루션사 등록 상태를 확인하세요.");
            }
            case RATE_LIMITED -> {
                newStatus = c.consecutiveFailures() + 1 >= errorThreshold ? "ERROR" : "DELAYED";
                long delay = e.retryAfterSeconds() == null ? 60 : e.retryAfterSeconds();
                outcome = JobOutcome.retry("RATE_LIMITED", "호출 제한으로 잠시 후 다시 시도합니다.", delay);
            }
            case UNSUPPORTED -> {
                newStatus = c.status();
                outcome = JobOutcome.reviewRequired("UNSUPPORTED", "연동 모듈이 지원하지 않는 요청입니다.");
            }
            default -> {
                newStatus = c.consecutiveFailures() + 1 >= errorThreshold ? "ERROR" : "DELAYED";
                outcome = JobOutcome.retry("CONNECTOR_TRANSIENT", "연동 응답이 지연되어 다시 시도합니다.", 0L);
            }
        }
        if (outcome.retryDelaySeconds() != null && outcome.retryDelaySeconds() == 0L) {
            outcome = JobOutcome.retry(outcome.errorCode(), outcome.errorMessageKo(), 30L);
        }
        jdbc.sql("""
                update integration_connection set status = :st, last_tested_at = now(), last_error_code = :code, last_error_at = now(),
                       consecutive_failures = consecutive_failures + 1, updated_at = now()
                 where id = :id and status <> 'PAUSED'
                """).param("st", newStatus).param("code", e.kind().name()).param("id", c.id()).update();
        audit.record(AuditEntry.builder(ws, "INTEGRATION_HEALTH_CHECK_FAILED", "INTEGRATION_CONNECTION", c.id())
                .actor(ActorType.INTEGRATION, null, c.connectorCode()).outcome("FAILED").scope(c.businessEntityId(), c.channelAccountId())
                .before(Map.of("status", c.status())).after(Map.of("status", newStatus))
                .metadata(Map.of("error_kind", e.kind().name())).build());
        if ("AUTH_EXPIRED".equals(newStatus)) {
            outbox.publish(ws, c.businessEntityId(), "INTEGRATION_CONNECTION", c.id(), "IntegrationConnectionAuthExpired",
                    payload(c, "AUTH_EXPIRED"));
        } else if ("ERROR".equals(newStatus)) {
            outbox.publish(ws, c.businessEntityId(), "INTEGRATION_CONNECTION", c.id(), "IntegrationConnectionErrored",
                    payload(c, e.kind().name()));
        }
        return outcome;
    }

    private static Map<String, Object> payload(ConnectionService.ConnectionView c, String errorCode) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("connection_id", c.id().toString());
        p.put("connection_name", c.displayName());
        p.put("connector_code", c.connectorCode());
        p.put("target_type", c.targetType());
        p.put("channel_account_id", c.channelAccountId() == null ? null : c.channelAccountId().toString());
        p.put("shipper_account_id", c.shipperAccountId() == null ? null : c.shipperAccountId().toString());
        p.put("error_code", errorCode);
        return p;
    }
}
