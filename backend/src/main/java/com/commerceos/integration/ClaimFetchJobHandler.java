package com.commerceos.integration;

import com.commerceos.claim.ClaimIngestService;
import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.ActorFactory;
import com.commerceos.integration.spi.ChannelConnector;
import com.commerceos.integration.spi.Connector;
import com.commerceos.integration.spi.ConnectorContext;
import com.commerceos.integration.spi.ConnectorEnvelope;
import com.commerceos.integration.spi.ConnectorException;
import com.commerceos.platform.job.Job;
import com.commerceos.platform.job.JobHandler;
import com.commerceos.platform.job.JobOutcome;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매채널 클레임 수집(M4 O6, CLAIM_CANCEL·CLAIM_RETURN·CLAIM_EXCHANGE READ). 확인된 유형의 행만 반영하고, 행마다 따로 트랜잭션(시스템 자동 처리).
 * 반영 실패는 원본·업무함으로 남긴다. 구간 = 워터마크 − 30분 ~ 지금(첫 실행 3일).
 */
@Component
public class ClaimFetchJobHandler implements JobHandler {
    public static final String TYPE = "CLAIM_FETCH";
    static final List<String> FEATURES = List.of("CLAIM_CANCEL", "CLAIM_RETURN", "CLAIM_EXCHANGE");
    private static final Duration OVERLAP = Duration.ofMinutes(30);
    private static final Duration FIRST_WINDOW = Duration.ofDays(3);
    private final JdbcClient jdbc;
    private final ConnectionService connections;
    private final ConnectorRegistry registry;
    private final CapabilityService capabilities;
    private final ClaimIngestService ingest;
    private final AccessGuard guard;
    private final ActorFactory actors;
    private final TransactionTemplate tx;

    public ClaimFetchJobHandler(JdbcClient jdbc, ConnectionService connections, ConnectorRegistry registry, CapabilityService capabilities, ClaimIngestService ingest,
                                AccessGuard guard, ActorFactory actors, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.registry = registry;
        this.capabilities = capabilities;
        this.ingest = ingest;
        this.guard = guard;
        this.actors = actors;
        this.tx = new TransactionTemplate(tm);
    }

    @Override
    public String jobType() {
        return TYPE;
    }

    @Override
    public JobOutcome handle(Job job) {
        UUID ws = job.workspaceId();
        Optional<ConnectionService.ConnectionView> found = connections.findSystem(ws, job.connectionId());
        if (found.isEmpty()) {
            return JobOutcome.failed("CONNECTION_NOT_FOUND", "연동 연결을 찾을 수 없습니다.");
        }
        ConnectionService.ConnectionView c = found.get();
        if ("PAUSED".equals(c.status()) || c.channelAccountId() == null) {
            return JobOutcome.failed("CONNECTION_NOT_USABLE", "일시중지되었거나 판매계정 연결이 아닙니다.");
        }
        List<String> allowed = FEATURES.stream().filter(f -> capabilities.executableSystem(ws, c.id(), f, "READ")).toList();
        if (allowed.isEmpty()) {
            return JobOutcome.failed("CAPABILITY_NOT_AVAILABLE", "클레임 조회 기능이 확인되지 않은 연결입니다(T39).");
        }
        Optional<Connector> connector = registry.find(c.connectorCode()).filter(x -> x instanceof ChannelConnector);
        if (connector.isEmpty()) {
            return JobOutcome.reviewRequired("CONNECTOR_NOT_AVAILABLE", "판매채널 연동 모듈을 사용할 수 없습니다.");
        }
        Optional<Map<String, String>> credential = connections.activeCredential(c.id());
        if (credential.isEmpty()) {
            return JobOutcome.failed("CREDENTIAL_MISSING", "등록된 인증정보가 없습니다.");
        }
        String externalKey = jdbc.sql("select external_account_key from channel_account where id = :id").param("id", c.channelAccountId()).query(String.class)
                .optional().orElse(null);
        ConnectorContext ctx = new ConnectorContext(ws, c.id(), c.targetType(), c.channelAccountId(), externalKey, c.apiVersion(), credential.get());
        Instant now = Instant.now();
        Instant watermark = jdbc.sql("select watermark from integration_sync_state where connection_id = :c and job_type = :t").param("c", c.id()).param("t", TYPE)
                .query((rs, i) -> Db.instant(rs, "watermark")).optional().orElse(null);
        Instant from = watermark == null ? now.minus(FIRST_WINDOW) : watermark.minus(OVERLAP);
        List<Map<String, Object>> rows = new ArrayList<>();
        String mapping = "unknown";
        try {
            String cursor = null;
            for (int page = 0; page < 1000; page++) {
                ConnectorEnvelope<Map<String, Object>> env = ((ChannelConnector) connector.get()).fetchClaims(ctx, cursor, new ChannelConnector.Window(from, now));
                rows.addAll(env.mappedObjects());
                mapping = env.mappingVersion();
                cursor = env.nextCursor();
                if (cursor == null) {
                    break;
                }
            }
        } catch (ConnectorException e) {
            return switch (e.kind()) {
                case AUTH_EXPIRED -> JobOutcome.failed("AUTH_EXPIRED", "판매채널 인증이 만료되었습니다.");
                case PERMISSION_DENIED -> JobOutcome.failed("PERMISSION_DENIED", "판매채널 클레임 조회 권한이 없습니다.");
                case RATE_LIMITED -> JobOutcome.retry("RATE_LIMITED", "호출 제한으로 잠시 후 다시 시도합니다.", e.retryAfterSeconds() == null ? 60 : e.retryAfterSeconds());
                case UNSUPPORTED -> JobOutcome.reviewRequired("UNSUPPORTED", "연동 모듈이 클레임 조회를 지원하지 않습니다.");
                default -> JobOutcome.retry("CONNECTOR_TRANSIENT", "판매채널 응답이 지연되어 다시 시도합니다.", 30L);
            };
        }
        Map<String, Integer> counts = new TreeMap<>();
        Instant maxSeen = watermark;
        for (Map<String, Object> row : rows) {
            String type = row.get("claim_type") == null ? null : String.valueOf(row.get("claim_type"));
            if (type == null || !allowed.contains("CLAIM_" + type)) {
                counts.merge("SKIPPED_UNSUPPORTED_TYPE", 1, Integer::sum);
                continue;
            }
            ClaimIngestService.Incoming in = new ClaimIngestService.Incoming(c.channelAccountId(), c.id(), mapping, row);
            if (ingest.seen(ws, c.channelAccountId(), ingest.hash(in))) {
                counts.merge("DUPLICATE", 1, Integer::sum);
                continue;
            }
            String outcome;
            try {
                outcome = guard.runAs(actors.systemAutomation(ws), () -> tx.execute(s -> ingest.ingest(in).outcome()));
            } catch (ApiException e) {
                outcome = "REJECTED";
                guard.runAs(actors.systemAutomation(ws), () -> tx.execute(s -> {
                    ingest.reject(ws, in, e.getMessage());
                    return null;
                }));
            }
            counts.merge(outcome, 1, Integer::sum);
            try {
                Instant u = row.get("updated_at") == null ? null : Instant.parse(String.valueOf(row.get("updated_at")));
                if (u != null && (maxSeen == null || u.isAfter(maxSeen))) {
                    maxSeen = u;
                }
            } catch (java.time.format.DateTimeParseException ignore) {
                // 시각 없는 행은 워터마크에 쓰지 않는다
            }
        }
        Instant mark = maxSeen == null ? now : maxSeen;
        tx.executeWithoutResult(s -> jdbc.sql("""
                insert into integration_sync_state (workspace_id, connection_id, job_type, watermark) values (:ws, :c, :t, :w)
                on conflict (connection_id, job_type) do update set watermark = greatest(integration_sync_state.watermark, excluded.watermark), updated_at = now()
                """).param("ws", ws).param("c", c.id()).param("t", TYPE).param("w", Db.ts(mark)).update());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("row_count", rows.size());
        out.put("outcomes", counts);
        return JobOutcome.succeeded(out);
    }
}
