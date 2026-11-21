package com.commerceos.platform.audit;

import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import com.commerceos.shared.web.RequestIdFilter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 감사로그 기록. 성공한 변경은 같은 트랜잭션(record), 거부·실패는 요청 롤백과 무관하게 남도록 별도 트랜잭션(recordIsolated).
 */
@Service
public class AuditService {
    private final JdbcClient jdbc;
    private final Json json;
    private final TransactionTemplate isolated;

    public AuditService(JdbcClient jdbc, Json json, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.json = json;
        this.isolated = new TransactionTemplate(tm);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(AuditEntry e) {
        jdbc.sql("""
                insert into audit_log (id, workspace_id, actor_type, actor_member_id, actor_label, action, outcome,
                    target_type, target_id, business_entity_id, channel_account_id, request_id, reason,
                    before_state, after_state, metadata)
                values (:id, :ws, :actorType, :actorMember, :actorLabel, :action, :outcome, :targetType, :targetId,
                    :be, :ca, :requestId, :reason, cast(:before as jsonb), cast(:after as jsonb), cast(:metadata as jsonb))
                """)
                .param("id", Ids.newId())
                .param("ws", e.workspaceId())
                .param("actorType", e.actorType().name())
                .param("actorMember", e.actorMemberId())
                .param("actorLabel", e.actorLabel())
                .param("action", e.action())
                .param("outcome", e.outcome())
                .param("targetType", e.targetType())
                .param("targetId", e.targetId())
                .param("be", e.businessEntityId())
                .param("ca", e.channelAccountId())
                .param("requestId", RequestIdFilter.current())
                .param("reason", e.reason())
                .param("before", json.write(e.before()))
                .param("after", json.write(e.after()))
                .param("metadata", json.write(e.metadata()))
                .update();
    }

    public void recordIsolated(AuditEntry e) {
        isolated.executeWithoutResult(status -> record(e));
    }
}
