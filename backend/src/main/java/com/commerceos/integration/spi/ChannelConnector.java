package com.commerceos.integration.spi;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * 판매채널 커넥터 계약(docs/06 §6). 지원하지 않는 기능은 UNSUPPORTED 로 명시적으로 실패해야 한다.
 * 매핑 대상 타입은 각 모듈(M2 상품, M4 주문·클레임, M5 정산)이 정의하므로 여기서는 원본 Map 으로 둔다.
 */
public interface ChannelConnector extends Connector {
    record Window(Instant from, Instant to) {
    }

    ConnectorEnvelope<Map<String, Object>> fetchOrders(ConnectorContext ctx, String cursor, Window window);

    ConnectorEnvelope<Map<String, Object>> fetchProducts(ConnectorContext ctx, String cursor);

    ConnectorEnvelope<Map<String, Object>> fetchClaims(ConnectorContext ctx, String cursor, Window window);

    ConnectorEnvelope<Map<String, Object>> fetchSettlement(ConnectorContext ctx, LocalDate periodStart, LocalDate periodEnd);

    /** 클레임 승인·거부 등 쓰기. 멱등키로 중복 실행을 막고 타임아웃 시 사후 조회한다. */
    ConnectorEnvelope<Map<String, Object>> processClaimAction(ConnectorContext ctx, Map<String, Object> command, String idempotencyKey);

    default ConnectorException unsupported(String feature) {
        return new ConnectorException(ConnectorException.Kind.UNSUPPORTED, "unsupported feature: " + feature, null);
    }
}
