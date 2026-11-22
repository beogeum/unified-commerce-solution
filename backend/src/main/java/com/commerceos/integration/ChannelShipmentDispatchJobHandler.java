package com.commerceos.integration;

import com.commerceos.fulfillment.ShipmentService;
import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.ActorFactory;
import com.commerceos.integration.spi.ChannelConnector;
import com.commerceos.integration.spi.ConnectorContext;
import com.commerceos.integration.spi.ConnectorEnvelope;
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
 * 판매채널 발송처리·송장 전송(M4 O5, SHIPMENT_DISPATCH/WRITE, DLG-135).
 * <ul>
 *   <li>판매계정 연결 + 지원 기능 확인 시만 API. 없으면 수동 발송처리 업무(T39).</li>
 *   <li>한 주문상품은 처음 담긴 박스의 송장으로 한 번만 보낸다. 자체 사은품(채널에 없는 상품)은 보내지 않는다.</li>
 *   <li>멱등 키 = 송장 번호(shipment_no). 일시 오류는 재시도, 마지막 시도까지 실패하면 실패 업무.</li>
 * </ul>
 */
@Component
public class ChannelShipmentDispatchJobHandler implements JobHandler {
    public static final String TYPE = "CHANNEL_SHIPMENT_DISPATCH";
    static final int MAX_ATTEMPTS = 6;
    private final JdbcClient jdbc;
    private final ConnectionService connections;
    private final ConnectorRegistry registry;
    private final CapabilityService capabilities;
    private final ShipmentService shipments;
    private final AccessGuard guard;
    private final ActorFactory actors;
    private final TransactionTemplate tx;

    public ChannelShipmentDispatchJobHandler(JdbcClient jdbc, ConnectionService connections, ConnectorRegistry registry, CapabilityService capabilities,
                                             ShipmentService shipments, AccessGuard guard, ActorFactory actors, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.registry = registry;
        this.capabilities = capabilities;
        this.shipments = shipments;
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
        UUID shipmentId = UUID.fromString(job.payload().get("shipment_id").asString());
        Optional<Map<String, Object>> found = jdbc.sql("""
                select s.*, o.external_order_key, o.source_type from shipment s join sales_order o on o.id = s.order_id where s.workspace_id = :ws and s.id = :id
                """).param("ws", ws).param("id", shipmentId).query().listOfRows().stream().findFirst();
        if (found.isEmpty()) {
            return JobOutcome.failed("SHIPMENT_NOT_FOUND", "송장을 찾을 수 없습니다.");
        }
        Map<String, Object> s = found.get();
        if (!"PENDING".equals(s.get("channel_dispatch_status"))) {
            return JobOutcome.succeeded(Map.of("skipped", String.valueOf(s.get("channel_dispatch_status"))));
        }
        // 이 송장이 처음 담은 주문상품(같은 출고지시의 앞 박스에 이미 담긴 상품은 제외), 자체 사은품 제외
        List<Map<String, Object>> items = jdbc.sql("""
                select oi.external_item_key, oi.shipped_qty from shipment_item si join order_item oi on oi.id = si.order_item_id
                 where si.shipment_id = :s and not (oi.is_gift and oi.gift_source = 'INTERNAL')
                   and not exists (select 1 from shipment_item p join shipment ps on ps.id = p.shipment_id
                                    where ps.fo_id = :f and ps.parcel_no < :pn and p.order_item_id = si.order_item_id)
                 group by oi.external_item_key, oi.shipped_qty, oi.line_no order by oi.line_no
                """).param("s", shipmentId).param("f", s.get("fo_id")).param("pn", s.get("parcel_no")).query().listOfRows();
        if (items.isEmpty()) {
            asSystem(ws, () -> shipments.markChannelDispatch(shipmentId, "NOT_REQUIRED", null, null, null));
            return JobOutcome.succeeded(Map.of("mode", "NOT_REQUIRED"));
        }
        UUID ca = (UUID) s.get("channel_account_id");
        Optional<UUID> connection = jdbc.sql("""
                select id from integration_connection where workspace_id = :ws and channel_account_id = :ca and status not in ('PAUSED', 'AUTH_EXPIRED')
                 order by created_at limit 1
                """).param("ws", ws).param("ca", ca).query(UUID.class).optional()
                .filter(c -> capabilities.executableSystem(ws, c, "SHIPMENT_DISPATCH", "WRITE"));
        Optional<ConnectionService.ConnectionView> view = connection.flatMap(c -> connections.findSystem(ws, c));
        Optional<ChannelConnector> connector = view.flatMap(v -> registry.find(v.connectorCode())).filter(x -> x instanceof ChannelConnector)
                .map(x -> (ChannelConnector) x);
        if (view.isEmpty() || connector.isEmpty()) {
            asSystem(ws, () -> shipments.markChannelDispatch(shipmentId, "MANUAL_REQUIRED", "MANUAL", null,
                    "이 판매계정 연결에서 발송처리·송장 전송 기능이 확인되지 않았습니다(T39). 판매자센터에서 직접 발송처리해야 합니다."));
            return JobOutcome.succeeded(Map.of("mode", "MANUAL"));
        }
        ConnectionService.ConnectionView c = view.get();
        Optional<Map<String, String>> credential = connections.activeCredential(c.id());
        if (credential.isEmpty()) {
            asSystem(ws, () -> shipments.markChannelDispatch(shipmentId, "FAILED", "API", null, "판매채널 연동 인증정보가 없습니다."));
            return JobOutcome.failed("CREDENTIAL_MISSING", "판매채널 연동 인증정보가 없습니다.");
        }
        String externalKey = jdbc.sql("select external_account_key from channel_account where id = :id").param("id", ca).query(String.class).optional().orElse(null);
        ConnectorContext ctx = new ConnectorContext(ws, c.id(), c.targetType(), ca, externalKey, c.apiVersion(), credential.get());
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("external_order_key", s.get("external_order_key"));
        cmd.put("items", items.stream().map(i -> Map.<String, Object>of("external_item_key", i.get("external_item_key"), "quantity", i.get("shipped_qty"))).toList());
        cmd.put("carrier_code", s.get("carrier_code"));
        cmd.put("tracking_no", s.get("tracking_no"));
        cmd.put("shipped_at", String.valueOf(s.get("shipped_at")));
        String key = (String) s.get("shipment_no");
        try {
            ConnectorEnvelope<Map<String, Object>> res = connector.get().dispatchShipment(ctx, cmd, key);
            String ref = res.mappedObjects().isEmpty() ? res.externalId() : String.valueOf(res.mappedObjects().get(0).get("dispatch_ref"));
            asSystem(ws, () -> shipments.markChannelDispatch(shipmentId, "SENT", "API", ref, null));
            return JobOutcome.succeeded(Map.of("mode", "API", "dispatch_ref", String.valueOf(ref)));
        } catch (ConnectorException e) {
            return switch (e.kind()) {
                case TRANSIENT, RATE_LIMITED -> {
                    if (job.attempts() >= job.maxAttempts()) {
                        asSystem(ws, () -> shipments.markChannelDispatch(shipmentId, "FAILED", "API", null, "판매채널 응답이 계속 지연되어 발송처리하지 못했습니다."));
                        yield JobOutcome.failed("CONNECTOR_TRANSIENT", "판매채널 응답이 계속 지연되었습니다.");
                    }
                    yield JobOutcome.retry("CONNECTOR_TRANSIENT", "판매채널 응답이 지연되어 다시 시도합니다.", e.retryAfterSeconds() == null ? 10 : e.retryAfterSeconds());
                }
                case AUTH_EXPIRED, PERMISSION_DENIED, UNSUPPORTED -> {
                    asSystem(ws, () -> shipments.markChannelDispatch(shipmentId, "FAILED", "API", null,
                            "판매채널이 발송처리를 거부했습니다(" + e.kind().name() + "). 연동 인증·권한·지원 기능을 확인하세요."));
                    yield JobOutcome.failed(e.kind().name(), "판매채널이 발송처리를 거부했습니다.");
                }
            };
        }
    }

    private void asSystem(UUID ws, Runnable work) {
        guard.runAs(actors.systemAutomation(ws), () -> tx.execute(st -> {
            work.run();
            return null;
        }));
    }
}
