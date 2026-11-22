package com.commerceos.integration;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.ActorFactory;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.integration.spi.ChannelConnector;
import com.commerceos.integration.spi.Connector;
import com.commerceos.integration.spi.ConnectorContext;
import com.commerceos.integration.spi.ConnectorEnvelope;
import com.commerceos.integration.spi.ConnectorException;
import com.commerceos.order.OrderIngestService;
import com.commerceos.order.OrderInput;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매채널 주문 수집 작업(M4 O1). 조회 구간 = 마지막 워터마크 − 겹침(기본 30분, 늦게 반영되는 변경 대비) ~ 지금.
 * 외부 호출은 트랜잭션 밖, 주문마다 별도 트랜잭션(한 주문의 문제가 다른 주문 반영을 막지 않음). 같은 원본은 다시 와도 한 번만 반영(T10).
 */
@Component
public class OrderFetchJobHandler implements JobHandler {
    public static final String TYPE = "ORDER_FETCH";
    private static final Duration OVERLAP = Duration.ofMinutes(30);
    private static final Duration FIRST_WINDOW = Duration.ofDays(3);
    private final JdbcClient jdbc;
    private final ConnectionService connections;
    private final ConnectorRegistry registry;
    private final CapabilityService capabilities;
    private final OrderIngestService ingest;
    private final AccessGuard guard;
    private final ActorFactory actors;
    private final TransactionTemplate tx;

    public OrderFetchJobHandler(JdbcClient jdbc, ConnectionService connections, ConnectorRegistry registry, CapabilityService capabilities, OrderIngestService ingest,
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
        Optional<ConnectionService.ConnectionView> found = connections.findSystem(job.workspaceId(), job.connectionId());
        if (found.isEmpty()) {
            return JobOutcome.failed("CONNECTION_NOT_FOUND", "연동 연결을 찾을 수 없습니다.");
        }
        ConnectionService.ConnectionView c = found.get();
        if ("PAUSED".equals(c.status())) {
            return JobOutcome.failed("CONNECTION_PAUSED", "일시중지된 연결입니다. 재개한 뒤 다시 실행하세요.");
        }
        if (c.channelAccountId() == null) {
            return JobOutcome.failed("CONNECTION_TARGET_INVALID", "주문 수집은 판매계정 연결에서만 실행할 수 있습니다.");
        }
        Optional<Connector> connector = registry.find(c.connectorCode()).filter(x -> x instanceof ChannelConnector);
        if (connector.isEmpty()) {
            return JobOutcome.reviewRequired("CONNECTOR_NOT_AVAILABLE", "판매채널 연동 모듈을 사용할 수 없습니다.");
        }
        Optional<CurrentActor> actor = job.createdByMemberId() == null ? Optional.empty() : actors.forMember(job.workspaceId(), job.createdByMemberId());
        if (actor.isEmpty()) {
            return JobOutcome.failed("REQUESTER_INACTIVE", "작업을 요청한 사용자가 더 이상 활성 상태가 아닙니다.");
        }
        try {
            guard.runAs(actor.get(), () -> {
                capabilities.require(c.id(), "ORDER", "READ");
                return null;
            });
        } catch (ApiException e) {
            return JobOutcome.failed(e.code(), e.getMessage());
        }
        Optional<Map<String, String>> credential = connections.activeCredential(c.id());
        if (credential.isEmpty()) {
            return JobOutcome.failed("CREDENTIAL_MISSING", "등록된 인증정보가 없습니다.");
        }
        String externalKey = jdbc.sql("select external_account_key from channel_account where id = :id").param("id", c.channelAccountId()).query(String.class)
                .optional().orElse(null);
        ConnectorContext ctx = new ConnectorContext(job.workspaceId(), c.id(), c.targetType(), c.channelAccountId(), externalKey, c.apiVersion(), credential.get());
        Instant now = Instant.now();
        Instant watermark = jdbc.sql("select watermark from integration_sync_state where connection_id = :c and job_type = :t").param("c", c.id()).param("t", TYPE)
                .query((rs, i) -> Db.instant(rs, "watermark")).optional().orElse(null);
        Instant from = watermark == null ? now.minus(FIRST_WINDOW) : watermark.minus(OVERLAP);
        List<Map<String, Object>> rows = new ArrayList<>();
        String mappingVersion = "unknown";
        try {
            String cursor = null;
            for (int page = 0; page < 1000; page++) {
                ConnectorEnvelope<Map<String, Object>> env = ((ChannelConnector) connector.get()).fetchOrders(ctx, cursor, new ChannelConnector.Window(from, now));
                rows.addAll(env.mappedObjects());
                mappingVersion = env.mappingVersion();
                cursor = env.nextCursor();
                if (cursor == null) {
                    break;
                }
            }
        } catch (ConnectorException e) {
            return switch (e.kind()) {
                case AUTH_EXPIRED -> JobOutcome.failed("AUTH_EXPIRED", "판매채널 인증이 만료되었습니다. 인증정보를 갱신한 뒤 다시 실행하세요.");
                case PERMISSION_DENIED -> JobOutcome.failed("PERMISSION_DENIED", "판매채널 주문 조회 권한이 없습니다.");
                case RATE_LIMITED -> JobOutcome.retry("RATE_LIMITED", "호출 제한으로 잠시 후 다시 시도합니다.", e.retryAfterSeconds() == null ? 60 : e.retryAfterSeconds());
                case UNSUPPORTED -> JobOutcome.reviewRequired("UNSUPPORTED", "연동 모듈이 주문 조회를 지원하지 않습니다.");
                default -> JobOutcome.retry("CONNECTOR_TRANSIENT", "판매채널 응답이 지연되어 다시 시도합니다.", 30L);
            };
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Instant maxSeen = watermark;
        for (Map<String, Object> raw : rows) {
            OrderInput input = OrderInput.fromMap(raw);
            String mv = mappingVersion;
            OrderIngestService.Result r = tx.execute(s -> ingest.ingest(new OrderIngestService.Incoming(job.workspaceId(), c.channelAccountId(), c.id(), job.id(),
                    "API", mv, raw, input, null)));
            counts.merge(r.outcome(), 1, Integer::sum);
            Instant seen = input.externalUpdatedAt() != null ? input.externalUpdatedAt() : input.orderedAt();
            if (seen != null && (maxSeen == null || seen.isAfter(maxSeen))) {
                maxSeen = seen;
            }
        }
        Instant mark = maxSeen == null ? now : maxSeen;
        tx.executeWithoutResult(s -> jdbc.sql("""
                insert into integration_sync_state (workspace_id, connection_id, job_type, watermark) values (:ws, :c, :t, :w)
                on conflict (connection_id, job_type) do update set watermark = greatest(integration_sync_state.watermark, excluded.watermark), updated_at = now()
                """).param("ws", job.workspaceId()).param("c", c.id()).param("t", TYPE).param("w", Db.ts(mark)).update());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connection_id", c.id().toString());
        out.put("window_from", from.toString());
        out.put("window_to", now.toString());
        out.put("row_count", rows.size());
        out.put("outcomes", counts);
        return JobOutcome.succeeded(out);
    }
}
