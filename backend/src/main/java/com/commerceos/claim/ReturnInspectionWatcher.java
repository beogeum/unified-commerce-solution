package com.commerceos.claim;

import com.commerceos.operations.WorkItemService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.db.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 반품 검수 기한 감시(DLG-244): 기한이 지난 미검수 반품 입고마다 업무 RETURN_INSPECTION_OVERDUE. 검수를 마치면 반품 서비스가 닫는다. */
@Component
public class ReturnInspectionWatcher {
    private final JdbcClient jdbc;
    private final WorkItemService workItems;
    private final TransactionTemplate tx;

    public ReturnInspectionWatcher(JdbcClient jdbc, WorkItemService workItems, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.workItems = workItems;
        this.tx = new TransactionTemplate(tm);
    }

    @Scheduled(fixedDelayString = "${commerce.returns.watch-interval:PT30M}", initialDelayString = "${commerce.returns.watch-initial-delay:PT7M}")
    public void scheduled() {
        scan(Instant.now());
    }

    /** @return 새로 만든 업무 수 */
    public int scan(Instant now) {
        return TenantContext.asSystem(() -> tx.execute(status -> {
            int n = 0;
            for (Map<String, Object> r : jdbc.sql("""
                    select r.id, r.workspace_id, r.business_entity_id, r.receipt_no, r.inspection_due_at, n.name as node_name from return_receipt r
                      join fulfillment_node n on n.id = r.node_id
                     where r.status in ('RECEIVED', 'INSPECTING') and r.inspection_due_at < :now
                       and not exists (select 1 from work_item w where w.workspace_id = r.workspace_id and w.dedupe_key = 'return-inspect-overdue:' || r.id
                                         and w.status in ('OPEN', 'IN_PROGRESS', 'ON_HOLD'))
                    """).param("now", Db.ts(now)).query().listOfRows()) {
                workItems.raise(new WorkItemService.Raise((UUID) r.get("workspace_id"), (UUID) r.get("business_entity_id"), null, null, "RETURN_INSPECTION_OVERDUE",
                        "MEDIUM", "반품 검수 기한 초과: " + r.get("receipt_no"), r.get("node_name") + " 에 입고된 반품의 검수 기한이 지났습니다. 환불·재판매 판단이 늦어집니다.",
                        "반품 입고 화면에서 검수 결과를 기록하세요.", "return-inspect-overdue:" + r.get("id"), Map.of("return_receipt_id", r.get("id").toString())));
                n++;
            }
            return n;
        }));
    }
}
