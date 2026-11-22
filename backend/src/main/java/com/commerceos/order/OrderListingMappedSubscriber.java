package com.commerceos.order;

import com.commerceos.platform.outbox.OutboxEvent;
import com.commerceos.platform.outbox.OutboxSubscriber;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 채널상품을 SKU 에 연결하면(C3) 그 채널상품 때문에 보류된 주문을 다시 연결·검증한다. */
@Component
public class OrderListingMappedSubscriber implements OutboxSubscriber {
    private final JdbcClient jdbc;
    private final OrderRules rules;

    public OrderListingMappedSubscriber(JdbcClient jdbc, OrderRules rules) {
        this.jdbc = jdbc;
        this.rules = rules;
    }

    @Override
    public String consumerName() {
        return "order.listing-mapped";
    }

    @Override
    public boolean supports(String eventType) {
        return "ChannelListingMapped".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        UUID listing = UUID.fromString(event.payload().get("listing_id").asString());
        List<UUID> orders = jdbc.sql("""
                select distinct order_id from order_item where workspace_id = :ws and listing_id = :l and mapping_status = 'UNMAPPED'
                """).param("ws", event.workspaceId()).param("l", listing).query(UUID.class).list();
        for (UUID o : orders) {
            rules.mapItems(event.workspaceId(), o, null);
            rules.reevaluate(event.workspaceId(), o, null, null);
        }
    }
}
