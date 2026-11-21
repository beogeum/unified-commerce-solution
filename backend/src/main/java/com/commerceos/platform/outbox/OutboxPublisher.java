package com.commerceos.platform.outbox;

import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import com.commerceos.shared.web.RequestIdFilter;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 업무 변경과 같은 트랜잭션에서만 이벤트를 기록한다(트랜잭션 밖 호출은 오류). */
@Component
public class OutboxPublisher {
    private final JdbcClient jdbc;
    private final Json json;

    public OutboxPublisher(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(UUID workspaceId, UUID businessEntityId, String aggregateType, UUID aggregateId,
                        String eventType, Map<String, Object> payload) {
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into outbox_event (id, workspace_id, business_entity_id, aggregate_type, aggregate_id, event_type,
                    payload, correlation_id)
                values (:id, :ws, :be, :at, :aid, :et, cast(:payload as jsonb), :corr)
                """)
                .param("id", id)
                .param("ws", workspaceId)
                .param("be", businessEntityId)
                .param("at", aggregateType)
                .param("aid", aggregateId)
                .param("et", eventType)
                .param("payload", json.write(payload))
                .param("corr", RequestIdFilter.current())
                .update();
        return id;
    }
}
