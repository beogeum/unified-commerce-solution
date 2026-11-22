package com.commerceos.order;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.db.TenantContext;
import com.commerceos.shared.error.Errors;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 개인정보 보관(DLG-120). 2단계:
 * <ol>
 *   <li>운영 열람 기간(기본 주문 종료 후 90일 — 반품·교환·CS 대응): 지나면 분리보관(ARCHIVED). 원문은 법정 사유(분쟁·수사·세무)와 관리 권한으로만 열람.</li>
 *   <li>법정 보존기간(기본 주문 시각부터 5년 — 전자상거래법 시행령 '대금결제 및 재화 등의 공급에 관한 기록'): 지나면 원문 파기(PURGED).
 *       주문·금액·수량 등 거래 기록과 마스킹 값은 남긴다.</li>
 * </ol>
 * 판매채널 약관이 더 짧은 기간을 요구하면 사업자별 재정의로 줄인다(채널별 약관 확인은 실계정 단계).
 */
@Service
public class OrderPrivacyService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final int defaultActiveDays;
    private final int defaultRetentionYears;

    public OrderPrivacyService(JdbcClient jdbc, AccessGuard guard, AuditService audit, PlatformTransactionManager tm,
                               @Value("${commerce.privacy.order.active-days:90}") int defaultActiveDays,
                               @Value("${commerce.privacy.order.retention-years:5}") int defaultRetentionYears) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
        this.defaultActiveDays = defaultActiveDays;
        this.defaultRetentionYears = defaultRetentionYears;
    }

    public record PolicyView(UUID businessEntityId, int activeDays, int retentionYears, boolean configured, int version) {
    }

    public record PolicyPut(Integer activeDays, Integer retentionYears) {
    }

    public record RunResult(int archived, int purged) {
    }

    public PolicyView policy(UUID be) {
        guard.requirePermission(Perm.ORDER_READ);
        guard.checkRead("BUSINESS_ENTITY", be, be, null);
        return policyOf(guard.actor().workspaceId(), be);
    }

    /** 보관기간 변경은 접근 관리 권한자만(개인정보 보호 책임). 법정 보존 의무보다 짧게 둘 수는 있으나 감사로그에 남긴다. */
    @Transactional
    public PolicyView putPolicy(UUID be, Integer expectedVersion, PolicyPut p) {
        guard.requirePermission(Perm.ACCESS_MANAGE);
        CurrentActor a = guard.actor();
        guard.checkWrite("BUSINESS_ENTITY", be, be, null);
        if (p.activeDays() == null || p.activeDays() < 30 || p.activeDays() > 3650 || p.retentionYears() == null || p.retentionYears() < 1 || p.retentionYears() > 10) {
            throw Errors.validation("active_days", "운영 열람 기간은 30~3650일, 보존기간은 1~10년입니다.");
        }
        PolicyView before = policyOf(a.workspaceId(), be);
        if (before.configured() && (expectedVersion == null || expectedVersion != before.version())) {
            throw Errors.versionConflict(expectedVersion == null ? 0 : expectedVersion, before.version());
        }
        jdbc.sql("""
                insert into order_privacy_policy (workspace_id, business_entity_id, active_days, retention_years, updated_by) values (:ws, :b, :d, :y, :by)
                on conflict (business_entity_id) do update set active_days = excluded.active_days, retention_years = excluded.retention_years,
                    updated_by = excluded.updated_by, updated_at = now(), version = order_privacy_policy.version + 1
                """).param("ws", a.workspaceId()).param("b", be).param("d", p.activeDays()).param("y", p.retentionYears()).param("by", a.memberId()).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "ORDER_PRIVACY_POLICY_UPDATED", "BUSINESS_ENTITY", be).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).before(Map.of("active_days", before.activeDays(), "retention_years", before.retentionYears()))
                .after(Map.of("active_days", p.activeDays(), "retention_years", p.retentionYears())).build());
        return policyOf(a.workspaceId(), be);
    }

    PolicyView policyOf(UUID ws, UUID be) {
        return jdbc.sql("select active_days, retention_years, version from order_privacy_policy where workspace_id = :ws and business_entity_id = :b")
                .param("ws", ws).param("b", be).query((rs, i) -> new PolicyView(be, rs.getInt(1), rs.getInt(2), true, rs.getInt(3))).optional()
                .orElse(new PolicyView(be, defaultActiveDays, defaultRetentionYears, false, 0));
    }

    @Scheduled(cron = "${commerce.privacy.order.cron:0 23 4 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        runAll(Instant.now());
    }

    /** 모든 운영그룹 분리보관·파기(시스템). now 를 넘겨 시험에서 시각을 당겨 볼 수 있다. */
    public RunResult runAll(Instant now) {
        return TenantContext.asSystem(() -> {
            int archived = 0;
            int purged = 0;
            List<UUID> workspaces = jdbc.sql("select distinct workspace_id from sales_order where pii_state <> 'PURGED'").query(UUID.class).list();
            for (UUID ws : workspaces) {
                RunResult r = tx.execute(s -> runWorkspace(ws, now));
                archived += r.archived();
                purged += r.purged();
            }
            return new RunResult(archived, purged);
        });
    }

    private RunResult runWorkspace(UUID ws, Instant now) {
        String policy = """
                coalesce((select p.active_days from order_privacy_policy p where p.business_entity_id = o.business_entity_id), :d)
                """;
        int archived = jdbc.sql("""
                update sales_order o set pii_state = 'ARCHIVED', pii_archived_at = :now
                 where o.workspace_id = :ws and o.pii_state = 'ACTIVE' and o.closed_at is not null
                   and o.closed_at + make_interval(days => """ + policy + ") <= :now")
                .param("ws", ws).param("now", Db.ts(now)).param("d", defaultActiveDays).update();
        List<UUID> purge = jdbc.sql("""
                select o.id from sales_order o where o.workspace_id = :ws and o.pii_state <> 'PURGED'
                   and o.ordered_at + make_interval(years => coalesce((select p.retention_years from order_privacy_policy p where p.business_entity_id = o.business_entity_id), :y)) <= :now
                """).param("ws", ws).param("now", Db.ts(now)).param("y", defaultRetentionYears).query(UUID.class).list();
        for (UUID id : purge) {
            jdbc.sql("update sales_order set pii_state = 'PURGED', pii_purged_at = :now, pii_cipher = null, pii_key_id = null where id = :id")
                    .param("now", Db.ts(now)).param("id", id).update();
            jdbc.sql("update order_raw set payload_cipher = null, payload_key_id = null, payload_purged_at = :now where order_id = :id and payload_cipher is not null")
                    .param("now", Db.ts(now)).param("id", id).update();
        }
        if (archived > 0 || !purge.isEmpty()) {
            audit.record(AuditEntry.builder(ws, "ORDER_PII_RETENTION_APPLIED", "WORKSPACE", ws).actor(ActorType.SYSTEM, null, "order-privacy")
                    .metadata(Map.of("archived", archived, "purged", purge.size())).build());
        }
        return new RunResult(archived, purge.size());
    }
}
