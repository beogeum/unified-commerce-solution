package com.commerceos.fulfillment;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.ActorFactory;
import com.commerceos.platform.outbox.OutboxEvent;
import com.commerceos.platform.outbox.OutboxSubscriber;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 출고 구성이 확정된 주문을 자동 출고 배정한다(시스템 자동 처리 주체). 끄면(commerce.fulfillment.auto-allocate=false) 화면의 출고 배정으로만.
 * 보류·미확정 주문은 건너뛴다(배정 메서드가 예외를 던져 구독 트랜잭션을 망치지 않게 먼저 확인).
 */
@Component
public class OrderComposedSubscriber implements OutboxSubscriber {
    private final JdbcClient jdbc;
    private final FulfillmentService fulfillment;
    private final AccessGuard guard;
    private final ActorFactory actors;
    private final boolean enabled;

    public OrderComposedSubscriber(JdbcClient jdbc, FulfillmentService fulfillment, AccessGuard guard, ActorFactory actors,
                                   @Value("${commerce.fulfillment.auto-allocate:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.fulfillment = fulfillment;
        this.guard = guard;
        this.actors = actors;
        this.enabled = enabled;
    }

    @Override
    public String consumerName() {
        return "fulfillment.auto-allocate";
    }

    @Override
    public boolean supports(String eventType) {
        return enabled && "OrderComposed".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        UUID orderId = UUID.fromString(event.payload().get("order_id").asString());
        boolean ready = jdbc.sql("""
                select count(*) from sales_order where id = :id and not on_hold and status in ('CONFIRMED', 'PROCESSING') and composition_status = 'FIXED'
                   and allocation_status = 'PENDING' and source_type <> 'PLATFORM_FULFILLMENT'
                """).param("id", orderId).query(Integer.class).single() > 0;
        if (ready) {
            guard.runAs(actors.systemAutomation(event.workspaceId()), () -> fulfillment.allocate(orderId));
        }
    }
}
