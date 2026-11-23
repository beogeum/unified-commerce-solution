package com.commerceos.claim;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.ActorFactory;
import com.commerceos.platform.outbox.OutboxEvent;
import com.commerceos.platform.outbox.OutboxSubscriber;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 출고 쪽 사건을 클레임에 잇는다(M4 O6). 출고 취소 확인 → 대기 중 취소 클레임 반영, 출고 확정 → 교환 재출고 상태·대기 취소 재검사,
 * 판매채널 주문 수집의 취소 → 진행 중 출고지시 정리. 시스템 자동 처리 주체로 실행.
 */
@Component
public class ClaimSubscriber implements OutboxSubscriber {
    private final JdbcClient jdbc;
    private final ClaimService claims;
    private final AccessGuard guard;
    private final ActorFactory actors;

    public ClaimSubscriber(JdbcClient jdbc, ClaimService claims, AccessGuard guard, ActorFactory actors) {
        this.jdbc = jdbc;
        this.claims = claims;
        this.guard = guard;
        this.actors = actors;
    }

    @Override
    public String consumerName() {
        return "claim.fulfillment-events";
    }

    @Override
    public boolean supports(String eventType) {
        return "FulfillmentOrderCancelled".equals(eventType) || "ShipmentRecorded".equals(eventType) || "OrderItemsCanceledExternally".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        UUID ws = event.workspaceId();
        UUID orderId = event.payload().has("order_id") ? UUID.fromString(event.payload().get("order_id").asString())
                : jdbc.sql("select order_id from fulfillment_order where id = :f").param("f", UUID.fromString(event.payload().get("fulfillment_order_id").asString()))
                        .query(UUID.class).single();
        guard.runAs(actors.systemAutomation(ws), () -> {
            switch (event.eventType()) {
                case "OrderItemsCanceledExternally" -> claims.reconcileFulfillment(orderId);
                case "FulfillmentOrderCancelled" -> claims.resumePendingCancels(orderId);
                default -> {
                    claims.resumePendingCancels(orderId);
                    List<UUID> exchanges = jdbc.sql("select id from claim where order_id = :o and claim_type = 'EXCHANGE' and status in ('APPROVED', 'PROCESSING')")
                            .param("o", orderId).query(UUID.class).list();
                    exchanges.forEach(claims::refresh);
                }
            }
            return null;
        });
    }
}
