package com.commerceos.integration;

import com.commerceos.platform.job.JobService;
import com.commerceos.shared.db.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 출고상태·송장 정기 수신(M4 O5). 지원 기능이 확인된 화주계정 연결마다, 대기·실행 중 작업이 없을 때만 작업을 건다.
 * 시스템 자동 처리로 실행된다(요청 사용자 없음).
 */
@Component
public class ShipmentSyncScheduler {
    private final JdbcClient jdbc;
    private final JobService jobs;
    private final CapabilityService capabilities;
    private final TransactionTemplate tx;

    public ShipmentSyncScheduler(JdbcClient jdbc, JobService jobs, CapabilityService capabilities, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.capabilities = capabilities;
        this.tx = new TransactionTemplate(tm);
    }

    @Scheduled(fixedDelayString = "${commerce.fulfillment.shipment-sync-interval:PT10M}", initialDelayString = "${commerce.fulfillment.shipment-sync-initial-delay:PT3M}")
    public void scheduled() {
        enqueueDue();
    }

    /** @return 새로 건 작업 수 */
    public int enqueueDue() {
        return TenantContext.asSystem(() -> {
            List<Map<String, Object>> conns = jdbc.sql("""
                    select c.id, c.workspace_id, s.business_entity_id from integration_connection c join shipper_account s on s.id = c.shipper_account_id
                     where c.target_type = 'SHIPPER_ACCOUNT' and c.status not in ('PAUSED', 'AUTH_EXPIRED')
                       and not exists (select 1 from async_job j where j.connection_id = c.id and j.job_type = :t and j.status in ('PENDING', 'RUNNING', 'RETRY_WAIT'))
                    """).param("t", ShipmentSyncJobHandler.TYPE).query().listOfRows();
            int n = 0;
            for (Map<String, Object> c : conns) {
                UUID ws = (UUID) c.get("workspace_id");
                UUID id = (UUID) c.get("id");
                if (!capabilities.executableSystem(ws, id, "OUTBOUND_STATUS", "READ") && !capabilities.executableSystem(ws, id, "TRACKING", "READ")) {
                    continue;
                }
                tx.executeWithoutResult(s -> jobs.enqueue(new JobService.NewJob(ws, ShipmentSyncJobHandler.TYPE, Map.of("connection_id", id.toString()),
                        (UUID) c.get("business_entity_id"), null, id, null, 3)));
                n++;
            }
            return n;
        });
    }
}
