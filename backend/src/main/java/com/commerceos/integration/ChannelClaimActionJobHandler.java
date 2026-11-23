package com.commerceos.integration;

import com.commerceos.claim.ClaimService;
import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.ActorFactory;
import com.commerceos.integration.spi.ChannelConnector;
import com.commerceos.integration.spi.ConnectorContext;
import com.commerceos.integration.spi.ConnectorException;
import com.commerceos.platform.job.Job;
import com.commerceos.platform.job.JobHandler;
import com.commerceos.platform.job.JobOutcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매채널 클레임 승인·거부 반영(M4 O6, CLAIM_* 쓰기). 지원 기능이 확인된 연결만 API, 아니면 판매자센터 수동 처리 업무(T39).
 * 멱등 키 = 클레임 번호 + 처리(승인·거부). 커넥터는 '이미 처리됨'을 성공으로 돌려야 한다.
 */
@Component
public class ChannelClaimActionJobHandler implements JobHandler {
    public static final String TYPE = "CHANNEL_CLAIM_ACTION";
    static final int MAX_ATTEMPTS = 6;
    private final JdbcClient jdbc;
    private final ConnectionService connections;
    private final ConnectorRegistry registry;
    private final CapabilityService capabilities;
    private final ClaimService claims;
    private final AccessGuard guard;
    private final ActorFactory actors;
    private final TransactionTemplate tx;

    public ChannelClaimActionJobHandler(JdbcClient jdbc, ConnectionService connections, ConnectorRegistry registry, CapabilityService capabilities, ClaimService claims,
                                        AccessGuard guard, ActorFactory actors, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.registry = registry;
        this.capabilities = capabilities;
        this.claims = claims;
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
        UUID claimId = UUID.fromString(job.payload().get("claim_id").asString());
        Optional<Map<String, Object>> found = jdbc.sql("""
                select c.*, o.external_order_key from claim c join sales_order o on o.id = c.order_id where c.workspace_id = :ws and c.id = :id
                """).param("ws", ws).param("id", claimId).query().listOfRows().stream().findFirst();
        if (found.isEmpty()) {
            return JobOutcome.failed("CLAIM_NOT_FOUND", "클레임을 찾을 수 없습니다.");
        }
        Map<String, Object> c = found.get();
        if (!"PENDING".equals(c.get("channel_action_status"))) {
            return JobOutcome.succeeded(Map.of("skipped", String.valueOf(c.get("channel_action_status"))));
        }
        String feature = "CLAIM_" + c.get("claim_type");
        UUID ca = (UUID) c.get("channel_account_id");
        Optional<UUID> connection = jdbc.sql("""
                select id from integration_connection where workspace_id = :ws and channel_account_id = :ca and status not in ('PAUSED', 'AUTH_EXPIRED')
                 order by created_at limit 1
                """).param("ws", ws).param("ca", ca).query(UUID.class).optional().filter(x -> capabilities.executableSystem(ws, x, feature, "WRITE"));
        Optional<ConnectionService.ConnectionView> view = connection.flatMap(x -> connections.findSystem(ws, x));
        Optional<ChannelConnector> connector = view.flatMap(v -> registry.find(v.connectorCode())).filter(x -> x instanceof ChannelConnector).map(x -> (ChannelConnector) x);
        if (view.isEmpty() || connector.isEmpty()) {
            mark(ws, claimId, "MANUAL_REQUIRED", "이 판매계정 연결에서 클레임 처리(쓰기) 기능이 확인되지 않았습니다(T39). 판매자센터에서 같은 결정을 처리해야 합니다.");
            return JobOutcome.succeeded(Map.of("mode", "MANUAL"));
        }
        Optional<Map<String, String>> credential = connections.activeCredential(view.get().id());
        if (credential.isEmpty()) {
            mark(ws, claimId, "FAILED", "판매채널 연동 인증정보가 없습니다.");
            return JobOutcome.failed("CREDENTIAL_MISSING", "판매채널 연동 인증정보가 없습니다.");
        }
        String externalKey = jdbc.sql("select external_account_key from channel_account where id = :id").param("id", ca).query(String.class).optional().orElse(null);
        ConnectorContext ctx = new ConnectorContext(ws, view.get().id(), view.get().targetType(), ca, externalKey, view.get().apiVersion(), credential.get());
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("external_claim_key", c.get("external_claim_key"));
        cmd.put("external_order_key", c.get("external_order_key"));
        cmd.put("claim_type", c.get("claim_type"));
        cmd.put("action", c.get("channel_action"));
        cmd.put("note", c.get("decision_note"));
        List<Map<String, Object>> items = jdbc.sql("""
                select i.external_item_key, ci.quantity from claim_item ci join order_item i on i.id = ci.order_item_id where ci.claim_id = :c order by ci.line_no
                """).param("c", claimId).query().listOfRows();
        cmd.put("items", items);
        String key = c.get("claim_no") + ":" + c.get("channel_action");
        try {
            connector.get().processClaimAction(ctx, cmd, key);
            mark(ws, claimId, "SENT", null);
            return JobOutcome.succeeded(Map.of("mode", "API"));
        } catch (ConnectorException e) {
            return switch (e.kind()) {
                case TRANSIENT, RATE_LIMITED -> {
                    if (job.attempts() >= job.maxAttempts()) {
                        mark(ws, claimId, "FAILED", "판매채널 응답이 계속 지연되어 클레임 처리를 반영하지 못했습니다.");
                        yield JobOutcome.failed("CONNECTOR_TRANSIENT", "판매채널 응답이 계속 지연되었습니다.");
                    }
                    yield JobOutcome.retry("CONNECTOR_TRANSIENT", "판매채널 응답이 지연되어 다시 시도합니다.", e.retryAfterSeconds() == null ? 10 : e.retryAfterSeconds());
                }
                case AUTH_EXPIRED, PERMISSION_DENIED, UNSUPPORTED -> {
                    mark(ws, claimId, "FAILED", "판매채널이 클레임 처리를 거부했습니다(" + e.kind().name() + ").");
                    yield JobOutcome.failed(e.kind().name(), "판매채널이 클레임 처리를 거부했습니다.");
                }
            };
        }
    }

    private void mark(UUID ws, UUID claimId, String status, String error) {
        guard.runAs(actors.systemAutomation(ws), () -> tx.execute(s -> {
            claims.markChannelAction(claimId, status, error);
            return null;
        }));
    }
}
