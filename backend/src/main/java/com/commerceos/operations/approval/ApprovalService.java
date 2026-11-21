package com.commerceos.operations.approval;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 공통 승인 요청(DLG-043, CLAUDE.md "민감·파괴 작업은 승인·영향도 미리보기"). 요청자는 스스로 승인할 수 없고(DB CHECK),
 * 승인자는 `approval.decide` 와 대상 사업자 WRITE 범위가 있어야 한다. 요청 이후 대상이 바뀌었으면 승인 대신 취소한다.
 */
@Service
public class ApprovalService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final Json json;
    private final TransactionTemplate tx;
    private final Map<String, ApprovalHandler> handlers;

    public ApprovalService(JdbcClient jdbc, AccessGuard guard, AuditService audit, Json json, PlatformTransactionManager tm,
                           List<ApprovalHandler> handlers) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
        this.handlers = handlers.stream().collect(Collectors.toMap(ApprovalHandler::requestType, Function.identity()));
    }

    public record Request(UUID id, String requestType, String targetType, String targetKey, UUID businessEntityId, JsonNode payload,
                          JsonNode impact, String reason, String status, UUID requestedBy, String requestedByName, Instant requestedAt,
                          UUID decidedBy, String decidedByName, Instant decidedAt, String decisionNote, int version) {
    }

    public record NewRequest(String requestType, String targetType, String targetKey, UUID businessEntityId, Map<String, Object> payload,
                             Map<String, Object> impact, String reason) {
    }

    /** 도메인 서비스가 자기 트랜잭션 안에서 요청을 만든다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Request create(NewRequest r) {
        CurrentActor a = guard.actor();
        if (!handlers.containsKey(r.requestType())) {
            throw new IllegalStateException("no approval handler for " + r.requestType());
        }
        if (r.reason() == null || r.reason().isBlank() || r.reason().length() > 500) {
            throw Errors.validation("reason", "승인 요청 사유를 500자 이내로 입력하세요.");
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    insert into approval_request (id, workspace_id, request_type, target_type, target_key, business_entity_id, payload, impact,
                                                  reason, requested_by)
                    values (:id, :ws, :type, :tt, :tk, :be, cast(:p as jsonb), cast(:i as jsonb), :reason, :by)
                    """).param("id", id).param("ws", a.workspaceId()).param("type", r.requestType()).param("tt", r.targetType())
                    .param("tk", r.targetKey()).param("be", r.businessEntityId()).param("p", json.write(r.payload()))
                    .param("i", json.write(r.impact() == null ? Map.of() : r.impact())).param("reason", r.reason().trim())
                    .param("by", a.memberId()).update();
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("APPROVAL_ALREADY_PENDING", "같은 대상의 승인 요청이 이미 대기 중입니다. 기존 요청이 처리된 뒤 다시 요청하세요.");
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "APPROVAL_REQUESTED", "APPROVAL_REQUEST", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(r.businessEntityId(), null)
                .metadata(Map.of("request_type", r.requestType(), "target_key", r.targetKey())).after(r.payload()).build());
        return find(id).orElseThrow();
    }

    public List<Request> list(String status, boolean mineOnly) {
        CurrentActor a = guard.actor();
        boolean decider = a.has(Perm.APPROVAL_DECIDE);
        return jdbc.sql(SELECT + """
                 where r.workspace_id = :ws and (cast(:st as text) is null or r.status = :st)
                   and (r.business_entity_id is null or r.business_entity_id = any(:bes))
                   and (:mine = false or r.requested_by = :me)
                 order by r.requested_at desc limit 200
                """).param("ws", a.workspaceId()).param("st", status)
                .param("bes", Db.uuids(guard.businessEntityFilter(com.commerceos.identity.access.AccessKind.READ, null)))
                .param("mine", mineOnly || !decider).param("me", a.memberId())
                .query(this::map).list();
    }

    public Request get(UUID id) {
        CurrentActor a = guard.actor();
        Request r = find(id).orElseThrow(() -> Errors.notFound("APPROVAL_REQUEST"));
        if (r.businessEntityId() != null) {
            guard.checkRead("APPROVAL_REQUEST", id, r.businessEntityId(), null);
        }
        if (!a.has(Perm.APPROVAL_DECIDE) && !r.requestedBy().equals(a.memberId())) {
            throw Errors.notFound("APPROVAL_REQUEST");
        }
        return r;
    }

    /** 승인·반려. 대상이 요청 이후 바뀌었으면 요청을 취소로 기록하고 409 APPROVAL_STALE 을 돌려준다. */
    public Request decide(UUID id, boolean approve, String note) {
        guard.requirePermission(Perm.APPROVAL_DECIDE);
        CurrentActor a = guard.actor();
        boolean stale = Boolean.TRUE.equals(tx.execute(s -> {
            Request r = jdbc.sql(SELECT + " where r.workspace_id = :ws and r.id = :id for update of r").param("ws", a.workspaceId())
                    .param("id", id).query(this::map).optional().orElseThrow(() -> Errors.notFound("APPROVAL_REQUEST"));
            if (r.businessEntityId() != null) {
                guard.checkWrite("APPROVAL_REQUEST", id, r.businessEntityId(), null);
            }
            if (r.requestedBy().equals(a.memberId())) {
                throw Errors.unprocessable("SELF_APPROVAL_FORBIDDEN", "본인이 요청한 건은 승인하거나 반려할 수 없습니다. 다른 승인자에게 요청하세요.");
            }
            if (!"PENDING".equals(r.status())) {
                throw Errors.conflict("APPROVAL_NOT_PENDING", "이미 처리된 승인 요청입니다.", Map.of("status", r.status()));
            }
            ApprovalHandler h = handlers.get(r.requestType());
            if (approve && !h.isCurrent(r)) {
                close(a, r, "CANCELLED", "요청 이후 대상이 바뀌어 자동 취소되었습니다.");
                return true;
            }
            if (approve) {
                h.apply(r);
            }
            close(a, r, approve ? "APPROVED" : "REJECTED", note);
            return false;
        }));
        if (stale) {
            throw Errors.conflict("APPROVAL_STALE", "요청 이후 대상 정보가 바뀌어 승인할 수 없습니다. 요청을 취소했으니 최신 내용으로 다시 요청하세요.");
        }
        return find(id).orElseThrow();
    }

    @Transactional
    public Request cancel(UUID id) {
        CurrentActor a = guard.actor();
        Request r = jdbc.sql(SELECT + " where r.workspace_id = :ws and r.id = :id for update of r").param("ws", a.workspaceId())
                .param("id", id).query(this::map).optional().orElseThrow(() -> Errors.notFound("APPROVAL_REQUEST"));
        if (!r.requestedBy().equals(a.memberId())) {
            throw Errors.notFound("APPROVAL_REQUEST");
        }
        if (!"PENDING".equals(r.status())) {
            throw Errors.conflict("APPROVAL_NOT_PENDING", "이미 처리된 승인 요청입니다.", Map.of("status", r.status()));
        }
        jdbc.sql("update approval_request set status = 'CANCELLED', decided_at = now(), decision_note = '요청자 취소', version = version + 1 where id = :id")
                .param("id", id).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "APPROVAL_CANCELLED", "APPROVAL_REQUEST", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(r.businessEntityId(), null).build());
        return find(id).orElseThrow();
    }

    /** 대상의 대기 중 요청(화면 표시용). */
    public Optional<Request> pending(String requestType, String targetKey) {
        return jdbc.sql(SELECT + " where r.workspace_id = :ws and r.request_type = :t and r.target_key = :k and r.status = 'PENDING'")
                .param("ws", guard.actor().workspaceId()).param("t", requestType).param("k", targetKey).query(this::map).optional();
    }

    private void close(CurrentActor a, Request r, String status, String note) {
        // 자동 취소는 승인자 기록 없이 닫는다(요청자=결정자 CHECK 와 무관)
        boolean auto = "CANCELLED".equals(status);
        jdbc.sql("""
                update approval_request set status = :st, decided_by = :by, decided_at = now(), decision_note = :note, version = version + 1
                 where id = :id
                """).param("st", status).param("by", auto ? null : a.memberId()).param("note", note).param("id", r.id()).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "APPROVAL_" + status, "APPROVAL_REQUEST", r.id())
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(r.businessEntityId(), null)
                .metadata(Map.of("request_type", r.requestType(), "target_key", r.targetKey(), "note", note == null ? "" : note)).build());
    }

    private static final String SELECT = """
            select r.*, rq.display_name as requested_by_name, dc.display_name as decided_by_name
              from approval_request r
              join workspace_member mq on mq.workspace_id = r.workspace_id and mq.id = r.requested_by
              join user_account rq on rq.id = mq.user_id
              left join workspace_member md on md.workspace_id = r.workspace_id and md.id = r.decided_by
              left join user_account dc on dc.id = md.user_id
            """;

    private Optional<Request> find(UUID id) {
        return jdbc.sql(SELECT + " where r.workspace_id = :ws and r.id = :id").param("ws", guard.actor().workspaceId()).param("id", id)
                .query(this::map).optional();
    }

    private Request map(ResultSet rs, int i) throws SQLException {
        return new Request(Db.uuid(rs, "id"), rs.getString("request_type"), rs.getString("target_type"), rs.getString("target_key"),
                Db.uuid(rs, "business_entity_id"), json.read(rs.getString("payload")), json.read(rs.getString("impact")), rs.getString("reason"),
                rs.getString("status"), Db.uuid(rs, "requested_by"), rs.getString("requested_by_name"), Db.instant(rs, "requested_at"),
                Db.uuid(rs, "decided_by"), rs.getString("decided_by_name"), Db.instant(rs, "decided_at"), rs.getString("decision_note"),
                rs.getInt("version"));
    }
}
