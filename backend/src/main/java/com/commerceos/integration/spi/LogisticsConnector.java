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

    /**
     * 출고 진행 상태(OUTBOUND_STATUS/READ, M4 O5). 행: {dispatch_key, external_ref, status(PICKING·PACKING·SHIPPED), occurred_at,
     * parcels[{carrier_code, tracking_no, items?[{sku_code, quantity}]}]}. 기간은 물류사 갱신 시각 기준.
     */
    default ConnectorEnvelope<Map<String, Object>> fetchOutboundStatus(ConnectorContext ctx, String cursor, java.time.Instant from, java.time.Instant to) {
        throw unsupported("OUTBOUND_STATUS");
    }

    /** 송장 배송 단계(TRACKING/READ, M4 O5). 행: {carrier_code, tracking_no, status, occurred_at, description, location, code}. */
    default ConnectorEnvelope<Map<String, Object>> fetchTracking(ConnectorContext ctx, String cursor, java.time.Instant from, java.time.Instant to) {
        throw unsupported("TRACKING");
    }

    ConnectorEnvelope<Map<String, Object>> cancelFulfillment(ConnectorContext ctx, String externalRef, String idempotencyKey);

    ConnectorEnvelope<Map<String, Object>> fetchReturnInspections(ConnectorContext ctx, String cursor);

    ConnectorEnvelope<Map<String, Object>> fetchLogisticsBilling(ConnectorContext ctx, LocalDate periodStart, LocalDate periodEnd);

    default ConnectorException unsupported(String feature) {
        return new ConnectorException(ConnectorException.Kind.UNSUPPORTED, "unsupported feature: " + feature, null);
    }
}
