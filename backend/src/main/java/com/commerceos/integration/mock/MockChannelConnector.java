package com.commerceos.integration.mock;

import com.commerceos.integration.spi.ChannelConnector;
import com.commerceos.integration.spi.ConnectorContext;
import com.commerceos.integration.spi.ConnectorEnvelope;
import com.commerceos.integration.spi.FeatureOp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 모의 판매채널 커넥터. 실제 채널 API 동작을 추정해 흉내 내지 않으며, 계약(SPI)과 장애 격리 흐름 검증용이다.
 * 운영 프로필에서는 등록되지 않는다(commerce.integration.mock-connectors-enabled=false).
 */
@Component
@ConditionalOnProperty(name = "commerce.integration.mock-connectors-enabled", havingValue = "true")
public class MockChannelConnector implements ChannelConnector {
    public static final String CODE = "mock-channel";
    private static final Set<FeatureOp> IMPLEMENTED = Set.of(
            FeatureOp.read("ORDER"), FeatureOp.read("PRODUCT"), FeatureOp.read("CLAIM_CANCEL"), FeatureOp.read("CLAIM_RETURN"),
            FeatureOp.read("SETTLEMENT"), FeatureOp.write("SHIPMENT_DISPATCH"));

    private final MockScenarioControl control;

    public MockChannelConnector(MockScenarioControl control) {
        this.control = control;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String targetType() {
        return "CHANNEL_ACCOUNT";
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
    public ConnectorEnvelope<Map<String, Object>> fetchOrders(ConnectorContext ctx, String cursor, Window window) {
        control.apply(ctx);
        List<Map<String, Object>> rows = control.orders(ctx.connectionId());
        int from = cursor == null ? 0 : Integer.parseInt(cursor);
        int to = Math.min(rows.size(), from + 2);
        return new ConnectorEnvelope<>(CODE, ctx.externalAccountKey(), "mock://orders/" + ctx.connectionId() + "/" + from, null,
                List.copyOf(rows.subList(from, to)), "mock-order-mapping-v1", Instant.now(), Instant.now(), 1, to < rows.size() ? String.valueOf(to) : null);
    }

    @Override
    public ConnectorEnvelope<Map<String, Object>> fetchProducts(ConnectorContext ctx, String cursor) {
        return empty(ctx);
    }

    @Override
    public ConnectorEnvelope<Map<String, Object>> fetchClaims(ConnectorContext ctx, String cursor, Window window) {
        return empty(ctx);
    }

    @Override
    public ConnectorEnvelope<Map<String, Object>> fetchSettlement(ConnectorContext ctx, LocalDate periodStart, LocalDate periodEnd) {
        return empty(ctx);
    }

    @Override
    public ConnectorEnvelope<Map<String, Object>> processClaimAction(ConnectorContext ctx, Map<String, Object> command, String idempotencyKey) {
        throw unsupported("CLAIM_* WRITE");
    }
}
