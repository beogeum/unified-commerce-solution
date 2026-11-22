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
    private final Map<UUID, InventoryFeed> inventory = new ConcurrentHashMap<>();

    /** 모의 3PL 재고 응답. asOf 가 null 이면 기준시각 없는 응답(워터마크 미제공 물류사)을 흉내 낸다. */
    public record InventoryFeed(java.time.Instant asOf, java.util.List<Map<String, Object>> rows) {
    }

    public void setInventory(UUID connectionId, java.time.Instant asOf, java.util.List<Map<String, Object>> rows) {
        inventory.put(connectionId, new InventoryFeed(asOf, rows));
    }

    private final Map<UUID, java.util.List<Map<String, Object>>> orders = new ConcurrentHashMap<>();
    private final Map<String, String> outbound = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> outboundRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> timeoutAfterAccept = new ConcurrentHashMap<>();

    /** 다음 출고지시 한 번은 물류사가 접수하고도 응답이 끊긴 것처럼(타임아웃) 만든다(T11). */
    public void timeoutAfterAcceptOnce(UUID connectionId) {
        timeoutAfterAccept.put(connectionId, true);
    }

    /** 이 연결·출고 키로 물류사에 실제 출고지시가 몇 번 들어왔는지. */
    public int outboundRequests(UUID connectionId, String key) {
        return outboundRequests.getOrDefault(connectionId + ":" + key, new AtomicInteger()).get();
    }

    String acceptOutbound(UUID connectionId, String key) {
        outboundRequests.computeIfAbsent(connectionId + ":" + key, k -> new AtomicInteger()).incrementAndGet();
        String ref = outbound.computeIfAbsent(connectionId + ":" + key, k -> "MOCK-OUT-" + Math.abs(k.hashCode() % 100000));
        if (Boolean.TRUE.equals(timeoutAfterAccept.remove(connectionId))) {
            throw new ConnectorException(ConnectorException.Kind.TRANSIENT, "mock: timeout after accept", null);
        }
        return ref;
    }

    String findOutbound(UUID connectionId, String key) {
        return outbound.get(connectionId + ":" + key);
    }

    boolean cancelOutbound(UUID connectionId, String key) {
        return outbound.remove(connectionId + ":" + key) != null;
    }

    /** 모의 판매채널 주문 응답(표준 필드 형태). */
    public void setOrders(UUID connectionId, java.util.List<Map<String, Object>> rows) {
        orders.put(connectionId, rows);
    }

    java.util.List<Map<String, Object>> orders(UUID connectionId) {
        return orders.getOrDefault(connectionId, java.util.List.of());
    }

    InventoryFeed inventory(UUID connectionId) {
        return inventory.getOrDefault(connectionId, new InventoryFeed(java.time.Instant.now(), java.util.List.of()));
    }

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
