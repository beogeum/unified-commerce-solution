package com.commerceos.integration;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.job.JobService;
import com.commerceos.shared.error.Errors;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연동 작업 요청(POST /integrations/{id}/sync-jobs). Foundation 에서 실행 가능한 유형은 상태 점검뿐이다.
 * 데이터 수집 유형은 지원 기능 가드를 먼저 통과해야 하며(T39), 처리기는 해당 마일스톤(M2~M5)에서 추가된다.
 */
@Service
public class SyncJobService {
    /** 작업 유형 → 필요한 지원 기능. 처리기 미구현 유형은 가드 통과 후에도 거부한다. */
    static final Map<String, String[]> DATA_JOB_CAPABILITY = Map.of(
            "ORDER_FETCH", new String[]{"ORDER", "READ", "M4"},
            "PRODUCT_FETCH", new String[]{"PRODUCT", "READ", "M2"},
            "CLAIM_FETCH", new String[]{"CLAIM_RETURN", "READ", "M4"},
            "SETTLEMENT_FETCH", new String[]{"SETTLEMENT", "READ", "M5"},
            "INVENTORY_FETCH", new String[]{"INVENTORY_QUERY", "READ", "M3"},
            "RETURN_INSPECTION_FETCH", new String[]{"RETURN_INSPECTION", "READ", "M4"});

    private final ConnectionService connections;
    private final CapabilityService capabilities;
    private final JobService jobs;
    private final AccessGuard guard;
    private final AuditService audit;

    public SyncJobService(ConnectionService connections, CapabilityService capabilities, JobService jobs, AccessGuard guard, AuditService audit) {
        this.connections = connections;
        this.capabilities = capabilities;
        this.jobs = jobs;
        this.guard = guard;
        this.audit = audit;
    }

    public record SyncJobAccepted(UUID jobId, String jobType, String status) {
    }

    @Transactional
    public SyncJobAccepted request(UUID connectionId, String jobType) {
        ConnectionService.ConnectionView c = connections.getForWrite(connectionId, Perm.INTEGRATION_SYNC);
        CurrentActor a = guard.actor();
        String type = jobType == null ? HealthCheckJobHandler.TYPE : jobType;
        if (!HealthCheckJobHandler.TYPE.equals(type)) {
            String[] cap = DATA_JOB_CAPABILITY.get(type);
            if (cap == null) {
                throw Errors.validation("job_type", "알 수 없는 작업 유형입니다.");
            }
            capabilities.require(connectionId, cap[0], cap[1]);
            throw Errors.unprocessable("JOB_TYPE_NOT_IMPLEMENTED",
                    "이 작업 유형은 아직 제공되지 않습니다(" + cap[2] + " 단계에서 제공 예정).", Map.of("job_type", type));
        }
        if ("PAUSED".equals(c.status())) {
            throw Errors.conflict("CONNECTION_PAUSED", "일시중지된 연결입니다. 재개한 뒤 점검하세요.");
        }
        UUID jobId = jobs.enqueue(new JobService.NewJob(a.workspaceId(), type, Map.of("connection_id", connectionId.toString()),
                c.businessEntityId(), c.channelAccountId(), connectionId, a.memberId(), 3));
        audit.record(AuditEntry.builder(a.workspaceId(), "INTEGRATION_SYNC_REQUESTED", "INTEGRATION_CONNECTION", connectionId)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(c.businessEntityId(), c.channelAccountId())
                .metadata(Map.of("job_id", jobId.toString(), "job_type", type)).build());
        return new SyncJobAccepted(jobId, type, "PENDING");
    }
}
