package com.commerceos.inventory;

import com.commerceos.shared.db.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 외부 재고 스냅샷이 오래되면 판매 제한으로 돌린다(I4, ARCH-005). 운영그룹마다 따로 트랜잭션. */
@Component
public class ExternalInventoryWatch {
    private final JdbcClient jdbc;
    private final ExternalSnapshotService snapshots;
    private final TransactionTemplate tx;

    public ExternalInventoryWatch(JdbcClient jdbc, ExternalSnapshotService snapshots, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.tx = new TransactionTemplate(tm);
    }

    @Scheduled(fixedDelayString = "${commerce.inventory.external-stale-interval:PT5M}", initialDelayString = "${commerce.inventory.external-stale-initial-delay:PT2M}")
    public void scheduled() {
        scan();
    }

    /** @return 새로 판매 제한한 집계 수 */
    public int scan() {
        return TenantContext.asSystem(() -> {
            List<UUID> workspaces = jdbc.sql("""
                    select distinct p.workspace_id from inventory_position p join fulfillment_node n on n.id = p.node_id
                     where n.inventory_source = 'EXTERNAL' and not p.restricted and p.external_as_of is not null
                    """).query(UUID.class).list();
            int n = 0;
            for (UUID ws : workspaces) {
                Integer r = tx.execute(s -> snapshots.restrictStale(ws));
                n += r == null ? 0 : r;
            }
            return n;
        });
    }
}
