package com.commerceos.operations;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.AccessScopeService;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.identity.access.PermissionLoader;
import com.commerceos.identity.access.ResolvedScope;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.file.FileService;
import com.commerceos.platform.job.Job;
import com.commerceos.platform.job.JobHandler;
import com.commerceos.platform.job.JobOutcome;
import com.commerceos.platform.job.JobService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비동기 내보내기(docs/07 /exports, DEC-F14). 실행 시점에 요청자의 권한·EXPORT 범위를 다시 계산하므로
 * 요청 후 권한이 회수되면 내보내기도 막힌다. CSV 수식 주입을 막기 위해 위험 문자로 시작하는 값은 작은따옴표로 감싼다.
 */
@Service
public class ExportService implements JobHandler {
    public static final String TYPE = "EXPORT";
    static final Set<String> TYPES = Set.of("BUSINESS_ENTITIES", "CHANNEL_ACCOUNTS", "SKUS");

    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AccessScopeService scopes;
    private final PermissionLoader permissions;
    private final JobService jobs;
    private final FileService files;
    private final AuditService audit;
    private final Clock clock;
    private final Duration ttl;
    private final TransactionTemplate tx;

    public ExportService(JdbcClient jdbc, AccessGuard guard, AccessScopeService scopes, PermissionLoader permissions, JobService jobs,
                         FileService files, AuditService audit, Clock clock, PlatformTransactionManager tm,
                         @Value("${commerce.exports.ttl:PT24H}") Duration ttl) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.scopes = scopes;
        this.permissions = permissions;
        this.jobs = jobs;
        this.files = files;
        this.audit = audit;
        this.clock = clock;
        this.ttl = ttl;
        this.tx = new TransactionTemplate(tm);
    }

    public record Accepted(UUID jobId, String exportType, String status) {
    }

    @Transactional
    public Accepted request(String exportType, List<String> businessEntityIds) {
        guard.requirePermission(Perm.EXPORT_CREATE);
        CurrentActor a = guard.actor();
        if (!TYPES.contains(String.valueOf(exportType))) {
            throw Errors.validation("export_type", "export_type 은 BUSINESS_ENTITIES, CHANNEL_ACCOUNTS, SKUS 중 하나여야 합니다.");
        }
        requireReadPermission(a.permissions(), exportType);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.EXPORT, businessEntityIds);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("export_type", exportType);
        payload.put("business_entity_ids", businessEntityIds == null || businessEntityIds.isEmpty() ? null
                : bes.stream().map(UUID::toString).sorted().toList());
        UUID jobId = jobs.enqueue(new JobService.NewJob(a.workspaceId(), TYPE, payload, null, null, null, a.memberId(), 3));
        audit.record(AuditEntry.builder(a.workspaceId(), "EXPORT_REQUESTED", "JOB", jobId)
                .actor(ActorType.USER, a.memberId(), a.displayName()).metadata(payload).build());
        return new Accepted(jobId, exportType, "PENDING");
    }

    @Override
    public String jobType() {
        return TYPE;
    }

    @Override
    public JobOutcome handle(Job job) {
        UUID ws = job.workspaceId();
        UUID member = job.createdByMemberId();
        String type = job.payload().get("export_type").asString();
        Integer active = jdbc.sql("select count(*) from workspace_member where workspace_id = :ws and id = :m and status = 'ACTIVE'")
                .param("ws", ws).param("m", member).query(Integer.class).single();
        Set<String> perms = permissions.effectivePermissions(ws, member);
        if (active == 0 || !perms.contains(Perm.EXPORT_CREATE)) {
            return JobOutcome.failed("PERMISSION_REVOKED", "내보내기 권한이 없어 작업을 중단했습니다.");
        }
        try {
            requireReadPermission(perms, type);
        } catch (RuntimeException e) {
            return JobOutcome.failed("PERMISSION_REVOKED", "내보내기 대상 조회 권한이 없어 작업을 중단했습니다.");
        }
        ResolvedScope scope = scopes.resolve(ws, member);
        Set<UUID> bes = new java.util.HashSet<>(scope.businessEntityIds(AccessKind.EXPORT));
        var requested = job.payload().get("business_entity_ids");
        if (requested != null && requested.isArray()) {
            Set<UUID> req = new java.util.HashSet<>();
            requested.forEach(n -> req.add(UUID.fromString(n.asString())));
            bes.retainAll(req);
        }
        List<List<String>> rows = new ArrayList<>();
        if ("BUSINESS_ENTITIES".equals(type)) {
            rows.add(List.of("사업자ID", "표시명", "법인명", "구분", "사업자등록번호", "과세유형", "상태"));
            rows.addAll(jdbc.sql("""
                    select * from business_entity where workspace_id = :ws and id = any(:bes) order by display_name, id
                    """).param("ws", ws).param("bes", Db.uuids(bes))
                    .query((rs, i) -> List.of(rs.getString("id"), rs.getString("display_name"), rs.getString("legal_name"),
                            rs.getString("legal_type"), rs.getString("business_registration_no"), rs.getString("tax_type"), rs.getString("status")))
                    .list());
        } else if ("SKUS".equals(type)) {
            // 운영그룹 공통 마스터. 원가·가격 같은 사업자별 민감 정보는 포함하지 않는다(C2 에서 별도 권한)
            rows.add(List.of("SKU ID", "SKU 코드", "상품코드", "상품명", "SKU명", "유형", "판매가능", "구매가능", "재고관리", "바코드", "상태"));
            rows.addAll(jdbc.sql("""
                    select s.*, p.product_code, p.name as product_name from sku s join product p on p.workspace_id = s.workspace_id and p.id = s.product_id
                     where s.workspace_id = :ws order by s.sku_code
                    """).param("ws", ws)
                    .query((rs, i) -> List.of(rs.getString("id"), rs.getString("sku_code"), rs.getString("product_code"), rs.getString("product_name"),
                            String.valueOf(rs.getString("name") == null ? "" : rs.getString("name")), rs.getString("sku_type"),
                            rs.getBoolean("sellable") ? "Y" : "N", rs.getBoolean("purchasable") ? "Y" : "N", rs.getBoolean("inventory_managed") ? "Y" : "N",
                            String.valueOf(rs.getString("barcode") == null ? "" : rs.getString("barcode")), rs.getString("status")))
                    .list());
        } else {
            rows.add(List.of("판매계정ID", "사업자ID", "사업자", "판매채널", "계정명", "외부계정ID", "상태"));
            rows.addAll(jdbc.sql("""
                    select ca.*, be.display_name as be_name from channel_account ca join business_entity be on be.id = ca.business_entity_id
                     where ca.workspace_id = :ws and ca.business_entity_id = any(:bes) and ca.id = any(:cas) order by be.display_name, ca.display_name
                    """).param("ws", ws).param("bes", Db.uuids(bes)).param("cas", Db.uuids(scope.channelAccountIds(AccessKind.EXPORT)))
                    .query((rs, i) -> List.of(rs.getString("id"), rs.getString("business_entity_id"), rs.getString("be_name"),
                            rs.getString("sales_channel_code"), rs.getString("display_name"),
                            String.valueOf(rs.getString("external_account_key") == null ? "" : rs.getString("external_account_key")),
                            rs.getString("status")))
                    .list());
        }
        byte[] csv = toCsv(rows);
        String filename = type.toLowerCase() + "-" + job.id() + ".csv";
        FileService.FileView file = tx.execute(s -> {
            FileService.FileView f = files.store(new FileService.NewFile(ws, null, "EXPORT", filename, "text/csv; charset=UTF-8", csv,
                    false, member, clock.instant().plus(ttl)));
            audit.record(AuditEntry.builder(ws, "EXPORT_COMPLETED", "FILE", f.id())
                    .actor(ActorType.SYSTEM, member, "export-worker")
                    .metadata(Map.of("job_id", job.id().toString(), "export_type", type, "row_count", rows.size() - 1)).build());
            return f;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file_id", file.id().toString());
        result.put("row_count", rows.size() - 1);
        result.put("expires_at", file.expiresAt().toString());
        return JobOutcome.succeeded(result);
    }

    private static void requireReadPermission(Set<String> perms, String type) {
        String needed = switch (type) {
            case "BUSINESS_ENTITIES" -> Perm.BUSINESS_ENTITY_READ;
            case "SKUS" -> Perm.CATALOG_READ;
            default -> Perm.CHANNEL_ACCOUNT_READ;
        };
        if (!perms.contains(needed)) {
            throw Errors.permissionDenied(needed);
        }
    }

    public static byte[] toCsv(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("﻿"); // Excel 한글 인식용 BOM
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(cell(row.get(i)));
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String cell(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
