package com.commerceos.operations.imports;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.ActorFactory;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.job.Job;
import com.commerceos.platform.job.JobHandler;
import com.commerceos.platform.job.JobOutcome;
import com.commerceos.shared.error.ApiException;
import com.commerceos.shared.json.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 가져오기 실행(비동기). 요청자 권한(실행 시점 기준)으로 행마다 '적용 + 결과 기록'을 한 트랜잭션으로 처리한다.
 * 재실행(lease 만료 등)되면 결과가 PENDING 인 행만 다시 처리하므로 같은 행이 두 번 적용되지 않는다.
 */
@Component
public class ImportJobHandler implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(ImportJobHandler.class);

    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final ActorFactory actors;
    private final AuditService audit;
    private final Json json;
    private final TransactionTemplate tx;
    private final Map<String, ImportTypeHandler> handlers;
    private final com.commerceos.shared.crypto.SecretCipher cipher;
    private final com.commerceos.platform.file.FileService files;

    public ImportJobHandler(JdbcClient jdbc, AccessGuard guard, ActorFactory actors, AuditService audit, Json json, PlatformTransactionManager tm,
                            List<ImportTypeHandler> handlers, com.commerceos.shared.crypto.SecretCipher cipher, com.commerceos.platform.file.FileService files) {
        this.cipher = cipher;
        this.files = files;
        this.jdbc = jdbc;
        this.guard = guard;
        this.actors = actors;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
        this.handlers = handlers.stream().collect(Collectors.toMap(ImportTypeHandler::importType, Function.identity()));
    }

    @Override
    public String jobType() {
        return ImportService.JOB_TYPE;
    }

    record Batch(UUID id, UUID workspaceId, String importType, UUID executedBy) {
    }

    @Override
    public JobOutcome handle(Job job) {
        UUID batchId = UUID.fromString(job.payload().get("batch_id").asString());
        Batch b = jdbc.sql("select id, workspace_id, import_type, executed_by from import_batch where id = :id and workspace_id = :ws")
                .param("id", batchId).param("ws", job.workspaceId())
                .query((rs, i) -> new Batch(batchId, job.workspaceId(), rs.getString("import_type"), (UUID) rs.getObject("executed_by"))).single();
        ImportTypeHandler h = handlers.get(b.importType());
        CurrentActor actor = actors.forMember(b.workspaceId(), b.executedBy()).orElse(null);
        if (h == null || actor == null || !actor.has(h.requiredPermission())) {
            finish(b, "FAILED", "실행한 사용자의 권한이 없어져 가져오기를 중단했습니다.");
            return JobOutcome.failed("PERMISSION_REVOKED", "실행한 사용자의 권한이 없어져 가져오기를 중단했습니다.");
        }
        jdbc.sql("update import_batch set status = 'RUNNING' where id = :id and status in ('QUEUED', 'RUNNING')").param("id", batchId).update();
        return guard.runAs(actor, () -> run(job, b, h, actor));
    }

    private JobOutcome run(Job job, Batch b, ImportTypeHandler h, CurrentActor actor) {
        record Row(int n, Map<String, String> normalized, String result) {
        }
        List<Row> rows = jdbc.sql("""
                select row_number, normalized, result, secret_cipher, secret_key_id from import_row where batch_id = :b and status <> 'ERROR' order by row_number
                """).param("b", b.id()).query((rs, i) -> new Row(rs.getInt("row_number"), withSecret(toMap(rs.getString("normalized")), rs.getBytes("secret_cipher"),
                        rs.getString("secret_key_id")), rs.getString("result"))).list();
        ImportTypeHandler.BatchContext ctx = new ImportTypeHandler.BatchContext(b.id(), rows.stream().map(Row::normalized).toList());
        int done = (int) rows.stream().filter(r -> !"PENDING".equals(r.result())).count();
        progress(job.id(), rows.size(), done);
        for (Row r : rows) {
            if (!"PENDING".equals(r.result())) {
                continue;
            }
            try {
                tx.executeWithoutResult(s -> {
                    String ref = h.apply(r.normalized(), ctx);
                    jdbc.sql("update import_row set result = 'APPLIED', applied_ref = :ref, processed_at = now() where batch_id = :b and row_number = :n")
                            .param("ref", ref).param("b", b.id()).param("n", r.n()).update();
                });
            } catch (ApiException e) {
                markFailed(b, r.n(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("import {} row {} failed", b.id(), r.n(), e);
                markFailed(b, r.n(), "처리 중 오류가 발생했습니다. 다시 시도하거나 관리자에게 문의하세요.");
            }
            done++;
            if (done % 50 == 0) {
                progress(job.id(), rows.size(), done);
            }
        }
        progress(job.id(), rows.size(), done);
        int applied = jdbc.sql("select count(*) from import_row where batch_id = :b and result = 'APPLIED'").param("b", b.id()).query(Integer.class).single();
        int failed = jdbc.sql("select count(*) from import_row where batch_id = :b and result = 'FAILED'").param("b", b.id()).query(Integer.class).single();
        jdbc.sql("update import_batch set applied_rows = :a, failed_rows = :f where id = :id").param("a", applied).param("f", failed).param("id", b.id()).update();
        finish(b, failed == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS", null);
        if (h.hasSensitiveFields()) {
            // 개인정보가 든 원본 파일은 처리 후 본문 삭제(DLG-236)
            UUID fileId = jdbc.sql("select file_id from import_batch where id = :id").param("id", b.id()).query(UUID.class).single();
            files.purgeContent(b.workspaceId(), fileId, "SENSITIVE_IMPORT_PROCESSED");
            jdbc.sql("update import_row set secret_cipher = null, secret_key_id = null where batch_id = :b").param("b", b.id()).update();
        }
        audit.record(AuditEntry.builder(b.workspaceId(), "IMPORT_COMPLETED", "IMPORT_BATCH", b.id())
                .actor(ActorType.USER, actor.memberId(), actor.displayName()).metadata(Map.of("applied", applied, "failed", failed, "job_id", job.id().toString()))
                .build());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batch_id", b.id().toString());
        result.put("applied_rows", applied);
        result.put("failed_rows", failed);
        return JobOutcome.succeeded(result);
    }

    private void markFailed(Batch b, int n, String message) {
        String m = message == null ? "처리하지 못했습니다." : message.length() > 500 ? message.substring(0, 500) : message;
        jdbc.sql("update import_row set result = 'FAILED', result_message = :m, processed_at = now() where batch_id = :b and row_number = :n and result = 'PENDING'")
                .param("m", m).param("b", b.id()).param("n", n).update();
    }

    private void finish(Batch b, String status, String error) {
        jdbc.sql("update import_batch set status = :st, completed_at = now(), error_message_ko = :e, version = version + 1 where id = :id")
                .param("st", status).param("e", error).param("id", b.id()).update();
    }

    private void progress(UUID jobId, int total, int done) {
        jdbc.sql("update async_job set progress_total = :t, progress_done = :d where id = :id").param("t", total).param("d", done).param("id", jobId).update();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> withSecret(Map<String, String> normalized, byte[] secret, String keyId) {
        if (secret == null) {
            return normalized;
        }
        Map<String, String> m = new LinkedHashMap<>(normalized);
        m.putAll(json.read(cipher.decrypt(secret, keyId), LinkedHashMap.class));
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toMap(String s) {
        return s == null ? Map.of() : json.read(s, LinkedHashMap.class);
    }

}
