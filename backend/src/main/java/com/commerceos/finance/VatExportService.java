package com.commerceos.finance;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.AccessScopeService;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.identity.access.PermissionLoader;
import com.commerceos.identity.access.ResolvedScope;
import com.commerceos.operations.ExportService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.file.FileService;
import com.commerceos.platform.job.Job;
import com.commerceos.platform.job.JobHandler;
import com.commerceos.platform.job.JobOutcome;
import com.commerceos.platform.job.JobService;
import com.commerceos.shared.db.TenantContext;
import com.commerceos.shared.error.Errors;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사업자별 부가세 신고자료 내보내기(DLG-188). 항상 사업자 1곳 단위 — 여러 사업자 자료를 한 파일로 합치지 않는다.
 * 비동기 작업으로 실행 시점에 권한(내보내기·정산 조회)과 내보내기 범위(사업자·판매계정)를 다시 평가하고, CSV 수식 주입을 막는다.
 */
@Service
public class VatExportService implements JobHandler {
    public static final String TYPE = "VAT_EXPORT";
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AccessScopeService scopes;
    private final PermissionLoader permissions;
    private final JobService jobs;
    private final VatService vat;
    private final FileService files;
    private final AuditService audit;
    private final Clock clock;
    private final Duration ttl;
    private final TransactionTemplate tx;

    public VatExportService(JdbcClient jdbc, AccessGuard guard, AccessScopeService scopes, PermissionLoader permissions, JobService jobs, VatService vat,
                            FileService files, AuditService audit, Clock clock, PlatformTransactionManager tm, @Value("${commerce.exports.ttl:PT24H}") Duration ttl) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.scopes = scopes;
        this.permissions = permissions;
        this.jobs = jobs;
        this.vat = vat;
        this.files = files;
        this.audit = audit;
        this.clock = clock;
        this.ttl = ttl;
        this.tx = new TransactionTemplate(tm);
    }

    public record Accepted(UUID jobId, String status) {
    }

    @org.springframework.transaction.annotation.Transactional
    public Accepted request(UUID businessEntityId, LocalDate from, LocalDate to) {
        guard.requirePermission(Perm.EXPORT_CREATE);
        guard.requirePermission(Perm.SETTLEMENT_READ);
        CurrentActor a = guard.actor();
        if (businessEntityId == null || from == null || to == null || to.isBefore(from)) {
            throw Errors.validation("business_entity_id", "사업자 한 곳과 신고기간을 지정하세요(여러 사업자 자료는 합쳐 내보내지 않습니다).");
        }
        if (!guard.businessEntityFilter(AccessKind.EXPORT, List.of(businessEntityId.toString())).contains(businessEntityId)) {
            throw Errors.notFound("BUSINESS_ENTITY");
        }
        Map<String, Object> payload = Map.of("business_entity_id", businessEntityId.toString(), "from", from.toString(), "to", to.toString());
        UUID jobId = jobs.enqueue(new JobService.NewJob(a.workspaceId(), TYPE, payload, businessEntityId, null, null, a.memberId(), 3));
        audit.record(AuditEntry.builder(a.workspaceId(), "EXPORT_REQUESTED", "JOB", jobId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(businessEntityId, null).metadata(Map.of("export_type", TYPE, "from", from.toString(), "to", to.toString())).build());
        return new Accepted(jobId, "PENDING");
    }

    @Override
    public String jobType() {
        return TYPE;
    }

    @Override
    public JobOutcome handle(Job job) {
        UUID ws = job.workspaceId();
        UUID member = job.createdByMemberId();
        UUID be = UUID.fromString(job.payload().get("business_entity_id").asString());
        LocalDate from = LocalDate.parse(job.payload().get("from").asString());
        LocalDate to = LocalDate.parse(job.payload().get("to").asString());
        Integer active = jdbc.sql("select count(*) from workspace_member where workspace_id = :ws and id = :m and status = 'ACTIVE'").param("ws", ws).param("m", member)
                .query(Integer.class).single();
        Set<String> perms = permissions.effectivePermissions(ws, member);
        if (active == 0 || !perms.contains(Perm.EXPORT_CREATE) || !perms.contains(Perm.SETTLEMENT_READ)) {
            return JobOutcome.failed("PERMISSION_REVOKED", "내보내기 권한이 없어 작업을 중단했습니다.");
        }
        ResolvedScope scope = scopes.resolve(ws, member);
        if (!scope.businessEntityIds(AccessKind.EXPORT).contains(be)) {
            return JobOutcome.failed("PERMISSION_REVOKED", "이 사업자의 내보내기 범위가 없어 작업을 중단했습니다.");
        }
        Set<UUID> cas = scope.channelAccountIds(AccessKind.EXPORT);
        List<List<String>> rows = TenantContext.asSystem(() -> build(ws, be, from, to, cas));
        byte[] csv = ExportService.toCsv(rows);
        String filename = "vat-" + from + "-" + to + "-" + job.id() + ".csv";
        FileService.FileView file = tx.execute(s -> {
            FileService.FileView f = files.store(new FileService.NewFile(ws, be, "EXPORT", filename, "text/csv; charset=UTF-8", csv, false, member,
                    clock.instant().plus(ttl)));
            audit.record(AuditEntry.builder(ws, "EXPORT_COMPLETED", "FILE", f.id()).actor(ActorType.SYSTEM, member, "export-worker")
                    .metadata(Map.of("job_id", job.id().toString(), "export_type", TYPE, "row_count", rows.size())).build());
            return f;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file_id", file.id().toString());
        result.put("row_count", rows.size());
        result.put("expires_at", file.expiresAt().toString());
        return JobOutcome.succeeded(result);
    }

    List<List<String>> build(UUID ws, UUID be, LocalDate from, LocalDate to, Set<UUID> cas) {
        Map<String, Object> e = jdbc.sql("select display_name, business_registration_no from business_entity where workspace_id = :ws and id = :id").param("ws", ws)
                .param("id", be).query().listOfRows().get(0);
        List<UUID> batches = jdbc.sql("""
                select id from vat_batch where workspace_id = :ws and business_entity_id = :be and period_from >= :f and period_to <= :t and channel_account_id in (:cas)
                 order by period_from, created_at
                """).param("ws", ws).param("be", be).param("f", from).param("t", to).param("cas", cas.isEmpty() ? Set.of(UUID.randomUUID()) : cas).query(UUID.class).list();
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("사업자", String.valueOf(e.get("display_name")), "사업자등록번호", String.valueOf(e.get("business_registration_no")), "신고기간", from + "~" + to,
                "분류규칙", VatService.RULE_VERSION));
        rows.add(List.of("안내", "세무 담당 확정 전 임시 분류 규칙. 국세청 자동 신고 자료가 아닙니다."));
        rows.add(List.of());
        rows.add(List.of("구분", "판매계정", "신고기간", "확정", "분류", "건수", "공급가", "세액", "합계"));
        Map<String, long[]> grand = new java.util.TreeMap<>();
        List<List<String>> detail = new ArrayList<>();
        detail.add(List.of("상세", "판매계정", "파일", "행", "거래일", "주문번호", "매출유형", "세금유형", "결제수단", "증빙유형", "공급가", "세액", "합계", "분류", "보정 사유", "문제"));
        for (UUID b : batches) {
            VatService.BatchView v = vat.view(b, false);
            for (VatService.ClassTotal c : v.totals()) {
                rows.add(List.of("묶음", v.channelAccountName(), v.periodFrom() + "~" + v.periodTo(), "CONFIRMED".equals(v.status()) ? "확정" : "미확정", c.label(),
                        String.valueOf(c.rows()), c.supply().toPlainString(), c.vat().toPlainString(), c.total().toPlainString()));
                long[] g = grand.computeIfAbsent(c.label(), k -> new long[4]);
                g[0] += c.rows();
                g[1] += c.supply().longValueExact();
                g[2] += c.vat().longValueExact();
                g[3] += c.total().longValueExact();
            }
            for (VatService.RowView r : vat.rows(b, "true", Map.of())) {
                detail.add(List.of("상세", v.channelAccountName(), r.fileName(), String.valueOf(r.rowNo()), r.txnDate().toString(), r.sourceKey() == null ? "" : r.sourceKey(),
                        r.salesType(), r.taxType(), r.paymentMethod(), r.evidenceType(), r.supply().toPlainString(), r.vat().toPlainString(), r.total().toPlainString(),
                        VatService.CLASS_KO.get(r.effectiveClassification()), r.overrideReason() == null ? "" : r.overrideReason(), String.join(" ", r.issues())));
            }
        }
        for (var g : grand.entrySet()) {
            rows.add(List.of("사업자 합계", "", from + "~" + to, "", g.getKey(), String.valueOf(g.getValue()[0]), String.valueOf(g.getValue()[1]),
                    String.valueOf(g.getValue()[2]), String.valueOf(g.getValue()[3])));
        }
        rows.add(List.of());
        rows.addAll(detail);
        return rows;
    }
}
