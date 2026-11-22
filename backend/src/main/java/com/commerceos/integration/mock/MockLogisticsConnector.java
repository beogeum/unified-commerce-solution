package com.commerceos.integration.mock;

import com.commerceos.integration.spi.ConnectorContext;
import com.commerceos.integration.spi.ConnectorEnvelope;
import com.commerceos.integration.spi.FeatureOp;
import com.commerceos.integration.spi.LogisticsConnector;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 모의 3PL 커넥터. 반품검수 결과(RETURN_INSPECTION)는 일부러 구현하지 않아,
 * 공개 API 가 검수 세부를 제공하지 않는 물류사(docs/06 §4 예: 파스토) 상황의 가드 동작을 검증한다.
 */
@Component
@ConditionalOnProperty(name = "commerce.integration.mock-connectors-enabled", havingValue = "true")
public class MockLogisticsConnector implements LogisticsConnector {
    public static final String CODE = "mock-3pl";
    private static final Set<FeatureOp> IMPLEMENTED = Set.of(
            FeatureOp.read("INVENTORY_QUERY"), FeatureOp.write("OUTBOUND_REQUEST"), FeatureOp.read("OUTBOUND_STATUS"),
            FeatureOp.delete("OUTBOUND_CANCEL"), FeatureOp.read("TRACKING"));

    private final MockScenarioControl control;

    public MockLogisticsConnector(MockScenarioControl control) {
        this.control = control;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String targetType() {
        return "SHIPPER_ACCOUNT";
    }

    @Override
    public boolean isMock() {
        return true;
    }

    @Override
    public Set<FeatureOp> implemented() {
        return IMPLEMENTED;
    }

    @Override
    public HealthResult checkAuth(ConnectorContext ctx) {
        control.apply(ctx);
        return new HealthResult("mock-v1", "mock ok");
    }

    private ConnectorEnvelope<Map<String, Object>> empty(ConnectorContext ctx) {
        control.apply(ctx);
        return new ConnectorEnvelope<>(CODE, ctx.externalAccountKey(), null, null, List.of(), "mock-mapping-v1", Instant.now(),
                Instant.now(), 1, null);
    }

    @Override
    public ConnectorEnvelope<Map<String, Object>> fetchInventory(ConnectorContext ctx, String cursor) {
        control.apply(ctx);
        MockScenarioControl.InventoryFeed feed = control.inventory(ctx.connectionId());
        // 한 쪽에 2행씩 나눠 커서 동작도 흉내 낸다
        int from = cursor == null ? 0 : Integer.parseInt(cursor);
        int to = Math.min(feed.rows().size(), from + 2);
        String next = to < feed.rows().size() ? String.valueOf(to) : null;
        return new ConnectorEnvelope<>(CODE, ctx.externalAccountKey(), "mock://inventory/" + ctx.connectionId() + "/" + from, null,
                List.copyOf(feed.rows().subList(from, to)), "mock-inventory-mapping-v1", feed.asOf(), Instant.now(), 1, next);
    }

    @Override
    public ConnectorEnvelope<Map<String, Object>> requestFulfillment(ConnectorContext ctx, Map<String, Object> command, String idempotencyKey) {
        control.apply(ctx);
        String ref = control.acceptOutbound(ctx.connectionId(), idempotencyKey);
        return new ConnectorEnvelope<>(CODE, ctx.externalAccountKey(), null, ref, List.of(Map.of("external_ref", ref)), "mock-outbound-v1", Instant.now(),
                Instant.now(), 1, null);
    }

    @Override
    public ConnectorEnvelope<Map<String, Object>> checkFulfillment(ConnectorContext ctx, String externalRef) {
        control.apply(ctx);
        String ref = control.findOutbound(ctx.connectionId(), externalRef);
        return new ConnectorEnvelope<>(CODE, ctx.externalAccountKey(), null, ref, ref == null ? List.of() : List.of(Map.of("external_ref", ref)),
                "mock-outbound-v1", Instant.now(), Instant.now(), 1, null);
    }

    @Override
    public ConnectorEnvelope<Map<String, Object>> fetchOutboundStatus(ConnectorContext ctx, String cursor, Instant from, Instant to) {
        control.apply(ctx);
        return new ConnectorEnvelope<>(CODE, ctx.externalAccountKey(), "mock://outbound-status/" + ctx.connectionId(), null,
                List.copyOf(control.outboundStatus(ctx.connectionId())), "mock-outbound-status-v1", Instant.now(), Instant.now(), 1, null);
    }

    @Override
    public ConnectorEnvelope<Map<String, Object>> fetchTracking(ConnectorContext ctx, String cursor, Instant from, Instant to) {
        control.apply(ctx);
        return new ConnectorEnvelope<>(CODE, ctx.externalAccountKey(), "mock://tracking/" + ctx.connectionId(), null,
                List.copyOf(control.tracking(ctx.connectionId())), "mock-tracking-v1", Instant.now(), Instant.now(), 1, null);
    }

    @Override
    public ConnectorEnvelope<Map<String, Object>> cancelFulfillment(ConnectorContext ctx, String externalRef, String idempotencyKey) {
        return empty(ctx);
    }

    @Override
    public ConnectorEnvelope<Map<String, Object>> fetchReturnInspections(ConnectorContext ctx, String cursor) {
        throw unsupported("RETURN_INSPECTION");
    }

    @Override
    public ConnectorEnvelope<Map<String, Object>> fetchLogisticsBilling(ConnectorContext ctx, LocalDate periodStart, LocalDate periodEnd) {
        throw unsupported("LOGISTICS_BILLING");
    }
}
