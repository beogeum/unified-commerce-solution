package com.commerceos.operations;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.Perm;
import com.commerceos.shared.db.Db;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 운영 현황(M7 운영 준비, docs/16 §운영 모니터링, docs/08 §6, DLG-216·217). 한 운영그룹의 처리 적체·실패·연동 상태·데이터 최신성·보안 이벤트를 한 화면에.
 * 판정(정상·주의·위험)은 설정 임계값 기준이며, 연동별 행은 사용자 사업자·판매계정 범위로 거른다. 권한 `audit.read`.
 */
@Service
public class OpsStatusService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final Duration outboxWarn;
    private final Duration syncStale;

    public OpsStatusService(JdbcClient jdbc, AccessGuard guard, @Value("${commerce.ops.outbox-warn:PT10M}") Duration outboxWarn,
                            @Value("${commerce.ops.sync-stale:PT24H}") Duration syncStale) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.outboxWarn = outboxWarn;
        this.syncStale = syncStale;
    }

    public record Check(String code, String level, String message, long value) {
    }

    public record ConnectionRow(UUID id, String displayName, String connectorCode, boolean mock, String status, String businessEntityName, Instant lastSuccessAt,
                                Instant lastErrorAt, String lastErrorCode, int consecutiveFailures, Instant credentialExpiresAt, List<String> flags) {
    }

    public record FreshnessRow(UUID channelAccountId, String channelAccountName, String businessEntityName, Instant lastOrderAt) {
    }

    public record Status(String overall, Instant checkedAt, List<Check> checks, Map<String, Object> outbox, Map<String, Object> jobs, List<Map<String, Object>> failingJobTypes,
                         Map<String, Object> workItems, List<ConnectionRow> connections, List<FreshnessRow> orderFreshness, Map<String, Object> security) {
    }

    public Status status() {
        guard.requirePermission(Perm.AUDIT_READ);
        UUID ws = guard.actor().workspaceId();
        Instant now = Instant.now();
        List<Check> checks = new ArrayList<>();

        Map<String, Object> outbox = jdbc.sql("""
                select count(*) filter (where status = 'PENDING') as pending, count(*) filter (where status = 'FAILED') as failed,
                       min(occurred_at) filter (where status = 'PENDING') as oldest_pending
                  from outbox_event where workspace_id = :ws
                """).param("ws", ws).query().listOfRows().get(0);
        Instant oldest = ts(outbox.get("oldest_pending"));
        long pendingAge = oldest == null ? 0 : Duration.between(oldest, now).toSeconds();
        Map<String, Object> ob = map("pending", n(outbox.get("pending")), "failed", n(outbox.get("failed")), "oldest_pending_seconds", pendingAge);
        if (n(outbox.get("failed")) > 0) {
            checks.add(new Check("OUTBOX_FAILED", "CRITICAL", "전달에 실패한 내부 이벤트가 있습니다(후속 처리 누락 위험).", n(outbox.get("failed"))));
        }
        if (pendingAge > outboxWarn.toSeconds()) {
            checks.add(new Check("OUTBOX_BACKLOG", "WARN", "내부 이벤트 처리가 " + pendingAge / 60 + "분 밀려 있습니다.", pendingAge));
        }

        Map<String, Object> jobs = jdbc.sql("""
                select count(*) filter (where status in ('PENDING', 'RETRY_WAIT')) as waiting, count(*) filter (where status = 'RUNNING') as running,
                       count(*) filter (where status = 'RUNNING' and lease_expires_at < now()) as stuck,
                       count(*) filter (where status = 'REVIEW_REQUIRED') as review_required,
                       count(*) filter (where status = 'FAILED' and finished_at > now() - interval '24 hours') as failed_24h,
                       count(*) filter (where status = 'RETRY_WAIT') as retrying,
                       min(available_at) filter (where status = 'PENDING') as oldest_waiting
                  from async_job where workspace_id = :ws
                """).param("ws", ws).query().listOfRows().get(0);
        Map<String, Object> jb = map("waiting", n(jobs.get("waiting")), "running", n(jobs.get("running")), "stuck", n(jobs.get("stuck")),
                "review_required", n(jobs.get("review_required")), "failed_24h", n(jobs.get("failed_24h")), "retrying", n(jobs.get("retrying")),
                "oldest_waiting_seconds", ts(jobs.get("oldest_waiting")) == null ? 0 : Math.max(0, Duration.between(ts(jobs.get("oldest_waiting")), now).toSeconds()));
        if (n(jobs.get("stuck")) > 0) {
            checks.add(new Check("JOB_STUCK", "WARN", "처리 시간이 지난 작업이 있습니다(작업자 재시작 후 자동 회수).", n(jobs.get("stuck"))));
        }
        if (n(jobs.get("failed_24h")) > 0) {
            checks.add(new Check("JOB_FAILED", "WARN", "최근 24시간 실패한 작업이 있습니다.", n(jobs.get("failed_24h"))));
        }
        if (n(jobs.get("review_required")) > 0) {
            checks.add(new Check("JOB_REVIEW", "WARN", "확인이 필요한 작업이 있습니다.", n(jobs.get("review_required"))));
        }
        List<Map<String, Object>> failingTypes = jdbc.sql("""
                select job_type, count(*) as failed, max(finished_at) as last_failed_at from async_job
                 where workspace_id = :ws and status in ('FAILED', 'REVIEW_REQUIRED') and coalesce(finished_at, updated_at) > now() - interval '7 days'
                 group by job_type order by 2 desc limit 10
                """).param("ws", ws).query().listOfRows().stream().map(r -> map("job_type", r.get("job_type"), "failed", n(r.get("failed")),
                "last_failed_at", ts(r.get("last_failed_at")))).toList();

        Map<String, Object> wi = jdbc.sql("""
                select count(*) filter (where status in ('OPEN', 'IN_PROGRESS', 'ON_HOLD')) as open,
                       count(*) filter (where status in ('OPEN', 'IN_PROGRESS', 'ON_HOLD') and severity = 'CRITICAL') as critical,
                       count(*) filter (where status in ('OPEN', 'IN_PROGRESS', 'ON_HOLD') and severity = 'HIGH') as high,
                       count(*) filter (where status in ('OPEN', 'IN_PROGRESS', 'ON_HOLD') and due_at < now()) as overdue,
                       count(*) filter (where status in ('OPEN', 'IN_PROGRESS', 'ON_HOLD') and assignee_member_id is null) as unassigned
                  from work_item where workspace_id = :ws
                """).param("ws", ws).query().listOfRows().get(0);
        Map<String, Object> wim = map("open", n(wi.get("open")), "critical", n(wi.get("critical")), "high", n(wi.get("high")), "overdue", n(wi.get("overdue")),
                "unassigned", n(wi.get("unassigned")));
        if (n(wi.get("critical")) > 0) {
            checks.add(new Check("WORK_CRITICAL", "CRITICAL", "긴급 업무가 열려 있습니다.", n(wi.get("critical"))));
        }

        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, null);
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        List<ConnectionRow> conns = new ArrayList<>();
        for (Map<String, Object> c : jdbc.sql("""
                select c.*, coalesce(be1.display_name, be2.display_name) as be_name, coalesce(ca.business_entity_id, sa.business_entity_id) as be_id
                  from integration_connection c left join channel_account ca on ca.id = c.channel_account_id left join business_entity be1 on be1.id = ca.business_entity_id
                  left join shipper_account sa on sa.id = c.shipper_account_id left join business_entity be2 on be2.id = sa.business_entity_id
                 where c.workspace_id = :ws order by c.display_name
                """).param("ws", ws).query().listOfRows()) {
            UUID be = (UUID) c.get("be_id");
            UUID ca = (UUID) c.get("channel_account_id");
            if ((be != null && !bes.contains(be)) || (ca != null && !cas.contains(ca))) {
                continue;
            }
            List<String> flags = new ArrayList<>();
            String st = (String) c.get("status");
            Instant last = ts(c.get("last_success_at"));
            Instant exp = ts(c.get("credential_expires_at"));
            if ("AUTH_EXPIRED".equals(st)) {
                flags.add("AUTH_EXPIRED");
            } else if (exp != null && exp.isBefore(now.plus(Duration.ofDays(14)))) {
                flags.add("CREDENTIAL_EXPIRING");
            }
            if ("ERROR".equals(st) || n(c.get("consecutive_failures")) >= 3) {
                flags.add("FAILING");
            }
            if ("ACTIVE".equals(st) && (last == null || last.isBefore(now.minus(syncStale)))) {
                flags.add("STALE");
            }
            conns.add(new ConnectionRow((UUID) c.get("id"), (String) c.get("display_name"), (String) c.get("connector_code"), Boolean.TRUE.equals(c.get("is_mock")), st,
                    (String) c.get("be_name"), last, ts(c.get("last_error_at")), (String) c.get("last_error_code"), (int) n(c.get("consecutive_failures")), exp, flags));
        }
        long authIssues = conns.stream().filter(c -> c.flags().contains("AUTH_EXPIRED")).count();
        long failing = conns.stream().filter(c -> c.flags().contains("FAILING")).count();
        long stale = conns.stream().filter(c -> c.flags().contains("STALE")).count();
        long expiring = conns.stream().filter(c -> c.flags().contains("CREDENTIAL_EXPIRING")).count();
        if (authIssues > 0) {
            checks.add(new Check("CONNECTION_AUTH_EXPIRED", "CRITICAL", "인증이 만료된 연동이 있습니다(해당 계정 수집·전송 중지).", authIssues));
        }
        if (failing > 0) {
            checks.add(new Check("CONNECTION_FAILING", "WARN", "연속 실패 중인 연동이 있습니다.", failing));
        }
        if (stale > 0) {
            checks.add(new Check("CONNECTION_STALE", "WARN", "최근 " + syncStale.toHours() + "시간 동안 성공한 동기화가 없는 연동이 있습니다.", stale));
        }
        if (expiring > 0) {
            checks.add(new Check("CREDENTIAL_EXPIRING", "WARN", "14일 안에 인증이 만료되는 연동이 있습니다.", expiring));
        }

        List<FreshnessRow> fresh = cas.isEmpty() ? List.of() : jdbc.sql("""
                select ca.id, ca.display_name, be.display_name as be_name, (select max(so.created_at) from sales_order so where so.channel_account_id = ca.id) as last_order
                  from channel_account ca join business_entity be on be.id = ca.business_entity_id
                 where ca.workspace_id = :ws and ca.id in (:cas) and ca.business_entity_id in (:bes) and ca.status = 'ACTIVE' order by be.display_name, ca.display_name
                """).param("ws", ws).param("cas", cas).param("bes", bes.isEmpty() ? Set.of(UUID.randomUUID()) : bes)
                .query((rs, i) -> new FreshnessRow(Db.uuid(rs, "id"), rs.getString("display_name"), rs.getString("be_name"), Db.instant(rs, "last_order"))).list();

        Map<String, Object> sec = jdbc.sql("""
                select count(*) filter (where action = 'PERMISSION_DENIED') as denied, count(*) filter (where action = 'ORDER_PII_REVEALED') as pii_revealed,
                       count(*) filter (where action like 'EXPORT_%') as exports
                  from audit_log where workspace_id = :ws and occurred_at > now() - interval '24 hours'
                """).param("ws", ws).query().listOfRows().get(0);
        Map<String, Object> secm = map("permission_denied_24h", n(sec.get("denied")), "pii_revealed_24h", n(sec.get("pii_revealed")), "exports_24h", n(sec.get("exports")));
        if (n(sec.get("denied")) >= 20) {
            checks.add(new Check("PERMISSION_DENIED_SPIKE", "WARN", "최근 24시간 권한 거부가 많습니다(권한 설정 또는 비정상 접근 확인).", n(sec.get("denied"))));
        }
        String overall = checks.stream().anyMatch(c -> "CRITICAL".equals(c.level())) ? "CRITICAL" : checks.isEmpty() ? "OK" : "WARN";
        return new Status(overall, now, checks, ob, jb, failingTypes, wim, conns, fresh, secm);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static long n(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static Instant ts(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (o instanceof java.time.OffsetDateTime t) {
            return t.toInstant();
        }
        return (Instant) o;
    }
}
