package com.commerceos.integration.mock;

import com.commerceos.integration.spi.ConnectorContext;
import com.commerceos.integration.spi.ConnectorException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 연결별 모의 시나리오 제어와 호출 횟수 기록(테스트·데모용). */
@Component
@ConditionalOnProperty(name = "commerce.integration.mock-connectors-enabled", havingValue = "true")
public class MockScenarioControl {
    private final Map<UUID, MockScenario> overrides = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> calls = new ConcurrentHashMap<>();

    public void set(UUID connectionId, MockScenario scenario) {
        overrides.put(connectionId, scenario);
    }

    public void clear(UUID connectionId) {
        overrides.remove(connectionId);
    }

    public int calls(UUID connectionId) {
        return calls.getOrDefault(connectionId, new AtomicInteger()).get();
    }

    void apply(ConnectorContext ctx) {
        calls.computeIfAbsent(ctx.connectionId(), k -> new AtomicInteger()).incrementAndGet();
        MockScenario s = overrides.get(ctx.connectionId());
        if (s == null) {
            String v = ctx.credentials().getOrDefault("mock_scenario", "OK");
            try {
                s = MockScenario.valueOf(v);
            } catch (IllegalArgumentException e) {
                s = MockScenario.OK;
            }
        }
        switch (s) {
            case AUTH_EXPIRED -> throw new ConnectorException(ConnectorException.Kind.AUTH_EXPIRED, "mock: token expired", null);
            case PERMISSION_DENIED -> throw new ConnectorException(ConnectorException.Kind.PERMISSION_DENIED, "mock: permission denied", null);
            case RATE_LIMITED -> throw new ConnectorException(ConnectorException.Kind.RATE_LIMITED, "mock: rate limited", 30L);
            case TIMEOUT -> throw new ConnectorException(ConnectorException.Kind.TRANSIENT, "mock: timeout", null);
            case OK -> {
            }
        }
    }
}
