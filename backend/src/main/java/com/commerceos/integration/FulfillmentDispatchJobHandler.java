package com.commerceos.integration;

import com.commerceos.fulfillment.FulfillmentService;
import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.ActorFactory;
import com.commerceos.integration.spi.Connector;
import com.commerceos.integration.spi.ConnectorContext;
import com.commerceos.integration.spi.ConnectorEnvelope;
import com.commerceos.integration.spi.ConnectorException;
import com.commerceos.integration.spi.LogisticsConnector;
import com.commerceos.platform.job.Job;
import com.commerceos.platform.job.JobHandler;
import com.commerceos.platform.job.JobOutcome;
import com.commerceos.shared.crypto.SecretCipher;
import com.commerceos.shared.json.Json;
import java.time.Instant;
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
 * 출고지시 전송(M4 O4, docs/04 §9, T11).
 * <ul>
 *   <li>자체 WMS 거점: 내부 작업지시로 바로 접수(INTERNAL).</li>
 *   <li>3PL·플랫폼 거점: 사업자 화주계정 연결 + 지원 기능(OUTBOUND_REQUEST/WRITE) 확인 후 API 전송. 없으면 수동 전달 업무(MANUAL).</li>
 *   <li>재시도(타임아웃 뒤)는 먼저 같은 멱등 키로 물류사 접수 여부를 조회하고, 미접수일 때만 같은 키로 다시 보낸다(중복 출고 방지).</li>
 * </ul>
 * 외부 호출은 트랜잭션 밖, 상태 반영은 시스템 자동 처리 주체로 트랜잭션 안에서.
 */
@Component
public class FulfillmentDispatchJobHandler implements JobHandler {
    public static final String TYPE = "FULFILLMENT_DISPATCH";
    static final int MAX_UNKNOWN_ATTEMPTS = 5;
    private final JdbcClient jdbc;
    private final ConnectionService connections;
    private final ConnectorRegistry registry;
    private final CapabilityService capabilities;
    private final FulfillmentService fulfillment;
    private final AccessGuard guard;
    private final ActorFactory actors;
    private final SecretCipher cipher;
    private final Json json;
    private final TransactionTemplate tx;

    public FulfillmentDispatchJobHandler(JdbcClient jdbc, ConnectionService connections, ConnectorRegistry registry, CapabilityService capabilities,
                                         FulfillmentService fulfillment, AccessGuard guard, ActorFactory actors, SecretCipher cipher, Json json,
                                         PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.registry = registry;
        this.capabilities = capabilities;
        this.fulfillment = fulfillment;
        this.guard = guard;
        this.actors = actors;
        this.cipher = cipher;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
    }

    @Override
    public String jobType() {
        return TYPE;
    }

    @Override
    public JobOutcome handle(Job job) {
        UUID ws = job.workspaceId();
        UUID foId = UUID.fromString(job.payload().get("fulfillment_order_id").asString());
        Optional<Map<String, Object>> found = jdbc.sql("""
                select f.*, p.kind as provider_kind, n.logistics_provider_code from fulfillment_order f join fulfillment_node n on n.id = f.node_id
                  join logistics_provider p on p.code = n.logistics_provider_code where f.workspace_id = :ws and f.id = :id
                """).param("ws", ws).param("id", foId).query().listOfRows().stream().findFirst();
        if (found.isEmpty()) {
            return JobOutcome.failed("FULFILLMENT_NOT_FOUND", "출고지시를 찾을 수 없습니다.");
        }
        Map<String, Object> f = found.get();
        String status = (String) f.get("status");
        if (!"ALLOCATED".equals(status) && !"REQUESTED".equals(status)) {
            return JobOutcome.succeeded(Map.of("skipped", status));
        }
        if ("SELF_WMS".equals(f.get("provider_kind"))) {
            asSystem(ws, () -> fulfillment.markAccepted(foId, "INTERNAL", null, Instant.now(), null));
            return JobOutcome.succeeded(Map.of("mode", "INTERNAL"));
        }
        UUID be = (UUID) f.get("business_entity_id");
        Optional<UUID> connection = jdbc.sql("""
                select c.id from fulfillment_node_shipper l join integration_connection c on c.shipper_account_id = l.shipper_account_id
                 where l.node_id = :n and l.business_entity_id = :b and c.status not in ('PAUSED', 'AUTH_EXPIRED') order by c.created_at limit 1
                """).param("n", f.get("node_id")).param("b", be).query(UUID.class).optional()
                .filter(c -> capabilities.executableSystem(ws, c, "OUTBOUND_REQUEST", "WRITE"));
        Optional<Connector> connector = connection.flatMap(c -> connections.findSystem(ws, c)).flatMap(c -> registry.find(c.connectorCode()))
                .filter(x -> x instanceof LogisticsConnector);
        if (connection.isEmpty() || connector.isEmpty()) {
            if ("ALLOCATED".equals(status) && f.get("dispatch_mode") == null) {
                asSystem(ws, () -> fulfillment.markDispatchProblem(foId, "ALLOCATED", "MANUAL", "NO_DISPATCH_CAPABILITY",
                        "이 거점의 물류사 연결에서 출고지시 전송 기능이 확인되지 않았습니다(T39). 물류사 화면·파일로 전달해야 합니다."));
            }
            return JobOutcome.succeeded(Map.of("mode", "MANUAL"));
        }
        ConnectionService.ConnectionView c = connections.findSystem(ws, connection.get()).orElseThrow();
        Optional<Map<String, String>> credential = connections.activeCredential(c.id());
        if (credential.isEmpty()) {
            asSystem(ws, () -> fulfillment.markDispatchProblem(foId, "FAILED", "API", "CREDENTIAL_MISSING", "물류사 연동 인증정보가 없습니다."));
            return JobOutcome.failed("CREDENTIAL_MISSING", "물류사 연동 인증정보가 없습니다.");
        }
        String externalKey = jdbc.sql("select external_shipper_key from shipper_account where id = :id").param("id", c.shipperAccountId()).query(String.class)
                .optional().orElse(null);
        ConnectorContext ctx = new ConnectorContext(ws, c.id(), c.targetType(), c.shipperAccountId(), externalKey, c.apiVersion(), credential.get());
        LogisticsConnector logistics = (LogisticsConnector) connector.get();
        String key = (String) f.get("dispatch_key");
        int attempts = ((Number) f.get("attempts")).intValue();
        asSystem(ws, () -> fulfillment.markRequested(foId, c.id()));
        try {
            if (attempts > 0) {
                // 이전 시도가 물류사에 닿았을 수 있다 → 먼저 사후조회(T11)
                ConnectorEnvelope<Map<String, Object>> check = logistics.checkFulfillment(ctx, key);
                if (!check.mappedObjects().isEmpty()) {
                    String ref = String.valueOf(check.mappedObjects().get(0).get("external_ref"));
                    asSystem(ws, () -> fulfillment.markAccepted(foId, "API", ref, Instant.now(), c.id()));
                    return JobOutcome.succeeded(Map.of("mode", "API", "external_ref", ref, "via", "CHECK"));
                }
            }
            ConnectorEnvelope<Map<String, Object>> res = logistics.requestFulfillment(ctx, command(ws, f), key);
            String ref = res.mappedObjects().isEmpty() ? res.externalId() : String.valueOf(res.mappedObjects().get(0).get("external_ref"));
            asSystem(ws, () -> fulfillment.markAccepted(foId, "API", ref, Instant.now(), c.id()));
            return JobOutcome.succeeded(Map.of("mode", "API", "external_ref", String.valueOf(ref), "via", "REQUEST"));
        } catch (ConnectorException e) {
            return switch (e.kind()) {
                case TRANSIENT, RATE_LIMITED -> {
                    if (attempts + 1 >= MAX_UNKNOWN_ATTEMPTS) {
                        asSystem(ws, () -> fulfillment.markDispatchProblem(foId, "REQUESTED", "API", "DISPATCH_UNKNOWN",
                                "물류사 응답이 계속 지연되어 접수 여부를 확인하지 못했습니다. 물류사 화면에서 출고번호 " + key + " 접수 여부를 확인하세요."));
                        yield JobOutcome.reviewRequired("DISPATCH_UNKNOWN", "물류사 접수 여부 확인이 필요합니다.");
                    }
                    yield JobOutcome.retry("CONNECTOR_TRANSIENT", "물류사 응답이 지연되어 접수 여부를 확인한 뒤 다시 시도합니다.",
                            e.retryAfterSeconds() == null ? 5 : e.retryAfterSeconds());
                }
                case AUTH_EXPIRED, PERMISSION_DENIED, UNSUPPORTED -> {
                    asSystem(ws, () -> fulfillment.markDispatchProblem(foId, "FAILED", "API", e.kind().name(),
                            "물류사 출고지시 전송이 거부되었습니다(" + e.kind().name() + "). 연동 인증·권한·지원 기능을 확인하세요."));
                    yield JobOutcome.failed(e.kind().name(), "물류사 출고지시 전송이 거부되었습니다.");
                }
            };
        }
    }

    /** 물류사 출고지시 본문. 수령인 원문은 전송에만 쓰고 기록하지 않는다. */
    private Map<String, Object> command(UUID ws, Map<String, Object> f) {
        Map<String, Object> o = jdbc.sql("select order_no, pii_cipher, pii_key_id from sales_order where id = :id").param("id", f.get("order_id")).query().listOfRows().get(0);
        List<Map<String, Object>> lines = jdbc.sql("""
                select s.sku_code, sum(l.quantity) as qty from fulfillment_order_line l join sku s on s.id = l.sku_id where l.fo_id = :f group by s.sku_code order by s.sku_code
                """).param("f", f.get("id")).query().listOfRows().stream().map(r -> Map.<String, Object>of("sku_code", r.get("sku_code"),
                "quantity", ((Number) r.get("qty")).intValue())).toList();
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("dispatch_key", f.get("dispatch_key"));
        cmd.put("order_no", o.get("order_no"));
        cmd.put("lines", lines);
        if (o.get("pii_cipher") != null) {
            cmd.put("recipient", json.read(cipher.decrypt((byte[]) o.get("pii_cipher"), (String) o.get("pii_key_id"))).get("recipient"));
        }
        return cmd;
    }

    private void asSystem(UUID ws, Runnable work) {
        guard.runAs(actors.systemAutomation(ws), () -> tx.execute(s -> {
            work.run();
            return null;
        }));
    }
}
