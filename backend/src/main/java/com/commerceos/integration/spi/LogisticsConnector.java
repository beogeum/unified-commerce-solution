package com.commerceos.integration.spi;

import java.time.LocalDate;
import java.util.Map;

/** 3PL·WMS 커넥터 계약(docs/06 §5~6). 재고 응답은 수량 의미(on_hand/available/post_outbound_estimate)를 함께 돌려준다. */
public interface LogisticsConnector extends Connector {

    ConnectorEnvelope<Map<String, Object>> fetchInventory(ConnectorContext ctx, String cursor);

    /** 출고요청. 같은 idempotencyKey 로 다시 호출해도 외부에 중복 출고가 생기면 안 된다. */
    ConnectorEnvelope<Map<String, Object>> requestFulfillment(ConnectorContext ctx, Map<String, Object> command, String idempotencyKey);

    /** 타임아웃 후 외부 접수 여부 사후 조회(docs/04 §9). */
    ConnectorEnvelope<Map<String, Object>> checkFulfillment(ConnectorContext ctx, String externalRef);

    ConnectorEnvelope<Map<String, Object>> cancelFulfillment(ConnectorContext ctx, String externalRef, String idempotencyKey);

    ConnectorEnvelope<Map<String, Object>> fetchReturnInspections(ConnectorContext ctx, String cursor);

    ConnectorEnvelope<Map<String, Object>> fetchLogisticsBilling(ConnectorContext ctx, LocalDate periodStart, LocalDate periodEnd);

    default ConnectorException unsupported(String feature) {
        return new ConnectorException(ConnectorException.Kind.UNSUPPORTED, "unsupported feature: " + feature, null);
    }
}
