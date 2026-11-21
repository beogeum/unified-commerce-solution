package com.commerceos.operations;

import com.commerceos.shared.db.Db;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.commerceos.shared.db.TenantContext;

/**
 * 연동 인증정보 만료 사전 경고(F9). 만료 예정일 N일(기본 14일) 전부터 업무함에 1건 올린다.
 * 같은 만료일에는 처리 상태와 관계없이 한 번만 만들고, 인증정보를 교체해 만료일이 바뀌면 새로 판단한다.
 * 운영에서는 매일 09:05(KST)에 실행되며, 작업자가 꺼진 환경(테스트)에서는 직접 호출한다.
 */
@Component
public class CredentialExpiryScanner {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Seoul"));
    private final JdbcClient jdbc;
    private final WorkItemService workItems;
    private final Clock clock;
    private final Duration lead;
    private final TransactionTemplate tx;

    public CredentialExpiryScanner(JdbcClient jdbc, WorkItemService workItems, Clock clock, PlatformTransactionManager tm,
                                   @Value("${commerce.integration.credential-expiry-warning:P14D}") Duration lead) {
        this.tx = new TransactionTemplate(tm);
        this.jdbc = jdbc;
        this.workItems = workItems;
        this.clock = clock;
        this.lead = lead;
    }

    record Due(UUID workspaceId, UUID connectionId, UUID businessEntityId, UUID channelAccountId, String name, Instant expiresAt) {
    }

    @Scheduled(cron = "${commerce.integration.credential-expiry-scan-cron:0 5 9 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        scan();
    }

    /** @return 새로 만든 업무 수. 모든 운영그룹을 훑는 시스템 작업이므로 RLS 우회 문맥(F8). */
    public int scan() {
        return TenantContext.asSystem(() -> tx.execute(s -> doScan()));
    }

    private int doScan() {
        Instant now = clock.instant();
        List<Due> due = jdbc.sql("""
                select c.workspace_id, c.id, coalesce(ca.business_entity_id, sa.business_entity_id) as business_entity_id,
                       c.channel_account_id, c.display_name, c.credential_expires_at
                  from integration_connection c
                  left join channel_account ca on ca.workspace_id = c.workspace_id and ca.id = c.channel_account_id
                  left join shipper_account sa on sa.workspace_id = c.workspace_id and sa.id = c.shipper_account_id
                 where c.credential_expires_at is not null and c.credential_expires_at <= :until
                   and c.status not in ('PAUSED', 'AUTH_EXPIRED')
                """).param("until", Db.ts(now.plus(lead)))
                .query((rs, i) -> new Due(Db.uuid(rs, "workspace_id"), Db.uuid(rs, "id"), Db.uuid(rs, "business_entity_id"),
                        Db.uuid(rs, "channel_account_id"), rs.getString("display_name"), Db.instant(rs, "credential_expires_at")))
                .list();
        int created = 0;
        for (Due d : due) {
            String day = DAY.format(d.expiresAt());
            String key = "integration:" + d.connectionId() + ":CREDENTIAL_EXPIRING:" + day;
            boolean exists = jdbc.sql("select count(*) from work_item where workspace_id = :ws and dedupe_key = :k")
                    .param("ws", d.workspaceId()).param("k", key).query(Integer.class).single() > 0;
            if (exists) {
                continue;
            }
            long days = Math.max(0, Duration.between(now, d.expiresAt()).toDays());
            Map<String, Object> related = new LinkedHashMap<>();
            related.put("connection_id", d.connectionId().toString());
            related.put("credential_expires_at", d.expiresAt().toString());
            workItems.raise(new WorkItemService.Raise(d.workspaceId(), d.businessEntityId(), d.channelAccountId(), d.connectionId(),
                    "INTEGRATION_CREDENTIAL_EXPIRING", days <= 3 ? "HIGH" : "MEDIUM",
                    "연동 인증정보 만료 예정(" + day + "): " + d.name(),
                    "이 연결의 인증정보가 " + day + "에 만료됩니다(" + days + "일 남음). 만료되면 이 연결의 수집·전송이 중단됩니다.",
                    "판매자센터·물류사에서 인증정보를 재발급해 [연동 관리 > 인증정보 교체]로 미리 바꾸세요.", key, related));
            created++;
        }
        return created;
    }
}
