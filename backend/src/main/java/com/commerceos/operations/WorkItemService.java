package com.commerceos.operations;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import com.commerceos.shared.web.PageQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 업무함 기본(FR-OPS-01/02). 같은 근본 원인(dedupe_key)의 열린 항목이 있으면 발생횟수만 늘린다.
 * 완료·무시 이후 재발은 새 항목으로 만든다(DEC-F09).
 */
@Service
public class WorkItemService {
    static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "OPEN", Set.of("IN_PROGRESS", "ON_HOLD", "DONE", "IGNORED"),
            "IN_PROGRESS", Set.of("OPEN", "ON_HOLD", "DONE", "IGNORED"),
            "ON_HOLD", Set.of("OPEN", "IN_PROGRESS", "DONE", "IGNORED"),
            "DONE", Set.of(),
            "IGNORED", Set.of());

    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final Json json;

    public WorkItemService(JdbcClient jdbc, AccessGuard guard, AuditService audit, Json json) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.json = json;
    }

    public record WorkItemView(UUID id, UUID businessEntityId, UUID channelAccountId, UUID connectionId, String itemType,
                               String severity, String status, String titleKo, String causeKo, String recommendedActionKo,
                               int occurrenceCount, Instant firstOccurredAt, Instant lastOccurredAt, UUID assigneeMemberId,
                               Instant dueAt, Instant snoozedUntil, JsonNode related, Instant resolvedAt, int version) {
    }

    public record Raise(UUID workspaceId, UUID businessEntityId, UUID channelAccountId, UUID connectionId, String itemType,
                        String severity, String titleKo, String causeKo, String recommendedActionKo, String dedupeKey,
                        Map<String, Object> related) {
    }

    public record Patch(String status, UUID assigneeMemberId, Instant snoozedUntil, Instant dueAt) {
    }

    /** 시스템(이벤트 소비자 등)이 업무를 올린다. 호출자 트랜잭션 안에서 실행. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID raise(Raise r) {
        return jdbc.sql("""
                insert into work_item (id, workspace_id, business_entity_id, channel_account_id, connection_id, item_type, severity,
                    title_ko, cause_ko, recommended_action_ko, dedupe_key, first_occurred_at, last_occurred_at, related)
                values (:id, :ws, :be, :ca, :conn, :type, :sev, :title, :cause, :action, :key, now(), now(), cast(:related as jsonb))
                on conflict (workspace_id, dedupe_key) where status in ('OPEN', 'IN_PROGRESS', 'ON_HOLD')
                do update set occurrence_count = work_item.occurrence_count + 1, last_occurred_at = now(), updated_at = now(),
                              version = work_item.version + 1
                returning id
                """).param("id", Ids.newId()).param("ws", r.workspaceId()).param("be", r.businessEntityId())
                .param("ca", r.channelAccountId()).param("conn", r.connectionId()).param("type", r.itemType()).param("sev", r.severity())
                .param("title", r.titleKo()).param("cause", r.causeKo()).param("action", r.recommendedActionKo())
                .param("key", r.dedupeKey()).param("related", json.write(r.related() == null ? Map.of() : r.related()))
                .query(UUID.class).single();
    }

    /** 원인이 사라지면 시스템이 열린 업무를 닫는다(예: 미연결 채널상품을 모두 연결). 호출자 트랜잭션 안에서 실행. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int resolveByKey(UUID workspaceId, String dedupeKey) {
        return jdbc.sql("""
                update work_item set status = 'DONE', resolved_at = now(), updated_at = now(), version = version + 1
                 where workspace_id = :ws and dedupe_key = :key and status in ('OPEN', 'IN_PROGRESS', 'ON_HOLD')
                """).param("ws", workspaceId).param("key", dedupeKey).update();
    }

    public List<WorkItemView> list(PageQuery page, List<String> businessEntityIds, String status, String itemType) {
        guard.requirePermission(Perm.WORK_ITEM_READ);
        CurrentActor a = guard.actor();
        boolean allBe = businessEntityIds == null || businessEntityIds.isEmpty();
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        return jdbc.sql("""
                select * from work_item
                 where workspace_id = :ws
                   and ((business_entity_id is null and :allBe) or business_entity_id = any(:bes))
                   and (channel_account_id is null or channel_account_id = any(:cas))
                   and (cast(:status as text) is null or status = :status)
                   and (cast(:type as text) is null or item_type = :type)
                   and (cast(:after as uuid) is null or id < :after)
                 order by id desc limit :limit
                """).param("ws", a.workspaceId()).param("allBe", allBe).param("bes", Db.uuids(bes))
                .param("cas", Db.uuids(guard.scope().channelAccountIds(AccessKind.READ))).param("status", status).param("type", itemType)
                .param("after", page.after()).param("limit", page.fetchSize()).query(this::map).list();
    }

    public WorkItemView get(UUID id) {
        guard.requirePermission(Perm.WORK_ITEM_READ);
        WorkItemView v = find(id);
        guard.checkRead("WORK_ITEM", id, v.businessEntityId(), v.channelAccountId());
        return v;
    }

    @Transactional
    public WorkItemView patch(UUID id, int expectedVersion, Patch p) {
        guard.requirePermission(Perm.WORK_ITEM_UPDATE);
        CurrentActor a = guard.actor();
        jdbc.sql("select id from work_item where workspace_id = :ws and id = :id for update").param("ws", a.workspaceId()).param("id", id)
                .query(UUID.class).optional().orElseThrow(() -> Errors.notFound("WORK_ITEM"));
        WorkItemView before = find(id);
        guard.checkWrite("WORK_ITEM", id, before.businessEntityId(), before.channelAccountId());
        if (before.version() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, before.version());
        }
        if (p.status() != null && !p.status().equals(before.status())) {
            if (!TRANSITIONS.containsKey(p.status())) {
                throw Errors.validation("status", "status 값이 올바르지 않습니다.");
            }
            if (!TRANSITIONS.get(before.status()).contains(p.status())) {
                throw Errors.conflict("INVALID_STATE_TRANSITION", "현재 상태에서 바꿀 수 없는 상태입니다.",
                        Map.of("from", before.status(), "to", p.status()));
            }
        }
        if (p.assigneeMemberId() != null && jdbc.sql("""
                select count(*) from workspace_member where workspace_id = :ws and id = :m and status = 'ACTIVE'
                """).param("ws", a.workspaceId()).param("m", p.assigneeMemberId()).query(Integer.class).single() == 0) {
            throw Errors.validation("assignee_member_id", "담당자를 찾을 수 없습니다.");
        }
        boolean terminal = p.status() != null && Set.of("DONE", "IGNORED").contains(p.status());
        jdbc.sql("""
                update work_item set status = coalesce(:st, status), assignee_member_id = coalesce(:as, assignee_member_id),
                       snoozed_until = coalesce(:sn, snoozed_until), due_at = coalesce(:due, due_at),
                       resolved_at = case when :terminal then now() else resolved_at end,
                       version = version + 1, updated_at = now()
                 where id = :id
                """).param("st", p.status()).param("as", p.assigneeMemberId()).param("sn", Db.ts(p.snoozedUntil()))
                .param("due", Db.ts(p.dueAt())).param("terminal", terminal).param("id", id).update();
        WorkItemView after = find(id);
        audit.record(AuditEntry.builder(a.workspaceId(), "WORK_ITEM_UPDATED", "WORK_ITEM", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(after.businessEntityId(), after.channelAccountId())
                .before(Map.of("status", before.status(), "assignee", String.valueOf(before.assigneeMemberId())))
                .after(Map.of("status", after.status(), "assignee", String.valueOf(after.assigneeMemberId()))).build());
        return after;
    }

    private WorkItemView find(UUID id) {
        return jdbc.sql("select * from work_item where workspace_id = :ws and id = :id").param("ws", guard.actor().workspaceId()).param("id", id)
                .query(this::map).optional().orElseThrow(() -> Errors.notFound("WORK_ITEM"));
    }

    private WorkItemView map(ResultSet rs, int i) throws SQLException {
        return new WorkItemView(Db.uuid(rs, "id"), Db.uuid(rs, "business_entity_id"), Db.uuid(rs, "channel_account_id"),
                Db.uuid(rs, "connection_id"), rs.getString("item_type"), rs.getString("severity"), rs.getString("status"),
                rs.getString("title_ko"), rs.getString("cause_ko"), rs.getString("recommended_action_ko"), rs.getInt("occurrence_count"),
                Db.instant(rs, "first_occurred_at"), Db.instant(rs, "last_occurred_at"), Db.uuid(rs, "assignee_member_id"),
                Db.instant(rs, "due_at"), Db.instant(rs, "snoozed_until"), json.read(rs.getString("related")),
                Db.instant(rs, "resolved_at"), rs.getInt("version"));
    }
}
