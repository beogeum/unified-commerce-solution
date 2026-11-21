package com.commerceos.operations;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.identity.access.ResolvedScope;
import com.commerceos.platform.job.Job;
import com.commerceos.platform.job.JobService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.json.Json;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.IfMatch;
import com.commerceos.shared.web.PageQuery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
public class OperationsController {
    private final WorkItemService workItems;
    private final JobService jobs;
    private final AccessGuard guard;
    private final JdbcClient jdbc;
    private final Json json;

    public OperationsController(WorkItemService workItems, JobService jobs, AccessGuard guard, JdbcClient jdbc, Json json) {
        this.workItems = workItems;
        this.jobs = jobs;
        this.guard = guard;
        this.jdbc = jdbc;
        this.json = json;
    }

    public record JobView(UUID id, String jobType, String status, JsonNode result, String errorCode, String errorMessageKo,
                          int attempts, int maxAttempts, Integer progressTotal, Integer progressDone, Instant createdAt,
                          Instant startedAt, Instant finishedAt) {
    }

    public record AuditView(UUID id, Instant occurredAt, String actorType, UUID actorMemberId, String actorLabel, String action,
                            String outcome, String targetType, UUID targetId, UUID businessEntityId, UUID channelAccountId,
                            String requestId, String reason, JsonNode before, JsonNode after, JsonNode metadata) {
    }

    @GetMapping("/work-items")
    public ApiResponse<List<WorkItemService.WorkItemView>> workItems(
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
            @RequestParam(name = "business_entity_id", required = false) List<String> businessEntityIds,
            @RequestParam(required = false) String status, @RequestParam(name = "item_type", required = false) String itemType) {
        PageQuery page = PageQuery.of(limit, cursor);
        return page.toResponse(workItems.list(page, businessEntityIds, status, itemType), WorkItemService.WorkItemView::id);
    }

    @GetMapping("/work-items/{id}")
    public ResponseEntity<ApiResponse<WorkItemService.WorkItemView>> workItem(@PathVariable UUID id) {
        var v = workItems.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @PatchMapping("/work-items/{id}")
    public ApiResponse<WorkItemService.WorkItemView> patchWorkItem(@PathVariable UUID id,
                                                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                  @RequestBody WorkItemService.Patch req) {
        return ApiResponse.of(workItems.patch(id, IfMatch.parse(ifMatch), req));
    }

    /** 작업은 요청자 본인, 또는 작업이 속한 사업자·판매계정의 READ 범위가 있는 사용자만 볼 수 있다. */
    @GetMapping("/jobs/{id}")
    public ApiResponse<JobView> job(@PathVariable UUID id) {
        CurrentActor a = guard.actor();
        Job j = jobs.find(a.workspaceId(), id).orElseThrow(() -> Errors.notFound("JOB"));
        boolean own = a.memberId().equals(j.createdByMemberId());
        if (!own) {
            if (j.businessEntityId() == null) {
                throw Errors.notFound("JOB");
            }
            guard.checkRead("JOB", id, j.businessEntityId(), j.channelAccountId());
        }
        return ApiResponse.of(new JobView(j.id(), j.jobType(), j.status().name(), j.result(), j.errorCode(), j.errorMessageKo(),
                j.attempts(), j.maxAttempts(), j.progressTotal(), j.progressDone(), j.createdAt(), j.startedAt(), j.finishedAt()));
    }

    /**
     * 감사이력 조회. 사업자·판매계정이 있는 기록은 READ 범위 안의 것만 보인다(REV-062 보수적 처리).
     */
    @GetMapping("/audit-logs")
    public ApiResponse<List<AuditView>> auditLogs(@RequestParam(required = false) Integer limit,
                                                  @RequestParam(required = false) String cursor,
                                                  @RequestParam(name = "target_type", required = false) String targetType,
                                                  @RequestParam(name = "target_id", required = false) UUID targetId,
                                                  @RequestParam(required = false) String action,
                                                  @RequestParam(required = false) String outcome) {
        guard.requirePermission(Perm.AUDIT_READ);
        CurrentActor a = guard.actor();
        ResolvedScope s = guard.scope();
        PageQuery page = PageQuery.of(limit, cursor);
        List<AuditView> rows = jdbc.sql("""
                select * from audit_log where workspace_id = :ws
                   and (business_entity_id is null or business_entity_id = any(:bes))
                   and (channel_account_id is null or channel_account_id = any(:cas))
                   and (cast(:tt as text) is null or target_type = :tt)
                   and (cast(:tid as uuid) is null or target_id = :tid)
                   and (cast(:action as text) is null or action = :action)
                   and (cast(:outcome as text) is null or outcome = :outcome)
                   and (cast(:after as uuid) is null or id < :after)
                 order by id desc limit :limit
                """).param("ws", a.workspaceId()).param("bes", Db.uuids(s.businessEntityIds(AccessKind.READ)))
                .param("cas", Db.uuids(s.channelAccountIds(AccessKind.READ))).param("tt", targetType).param("tid", targetId)
                .param("action", action).param("outcome", outcome).param("after", page.after()).param("limit", page.fetchSize())
                .query((rs, i) -> new AuditView(Db.uuid(rs, "id"), Db.instant(rs, "occurred_at"), rs.getString("actor_type"),
                        Db.uuid(rs, "actor_member_id"), rs.getString("actor_label"), rs.getString("action"), rs.getString("outcome"),
                        rs.getString("target_type"), Db.uuid(rs, "target_id"), Db.uuid(rs, "business_entity_id"),
                        Db.uuid(rs, "channel_account_id"), rs.getString("request_id"), rs.getString("reason"),
                        json.read(rs.getString("before_state")), json.read(rs.getString("after_state")), json.read(rs.getString("metadata"))))
                .list();
        return page.toResponse(rows, AuditView::id);
    }
}
