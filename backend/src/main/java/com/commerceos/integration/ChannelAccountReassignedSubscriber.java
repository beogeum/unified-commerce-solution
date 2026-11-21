package com.commerceos.integration;

import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.outbox.OutboxEvent;
import com.commerceos.platform.outbox.OutboxSubscriber;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 판매계정 귀속 변경 시 해당 계정의 연동 연결을 일시중지한다(DEC-F16).
 * 기존 인증정보는 이전 사업자가 발급한 것일 수 있으므로 새 사업자 기준으로 재확인하기 전까지 수집을 멈춘다.
 */
@Component
public class ChannelAccountReassignedSubscriber implements OutboxSubscriber {
    private final JdbcClient jdbc;
    private final AuditService audit;

    public ChannelAccountReassignedSubscriber(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Override
    public String consumerName() {
        return "integration.channel-account-reassigned";
    }

    @Override
    public boolean supports(String eventType) {
        return "ChannelAccountReassigned".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        UUID accountId = UUID.fromString(event.payload().get("channel_account_id").asString());
        List<UUID> paused = jdbc.sql("""
                update integration_connection set status = 'PAUSED', version = version + 1, updated_at = now()
                 where workspace_id = :ws and channel_account_id = :ca and status <> 'PAUSED'
                returning id
                """).param("ws", event.workspaceId()).param("ca", accountId).query(UUID.class).list();
        for (UUID id : paused) {
            audit.record(AuditEntry.builder(event.workspaceId(), "INTEGRATION_CONNECTION_PAUSED", "INTEGRATION_CONNECTION", id)
                    .actor(ActorType.SYSTEM, null, consumerName()).scope(event.businessEntityId(), accountId)
                    .reason("판매계정 사업자 귀속 변경").metadata(Map.of("source_event_id", event.eventId().toString())).build());
        }
    }
}
