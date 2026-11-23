package com.commerceos.operations;

import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.file.FileStorage;
import com.commerceos.shared.db.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보관기간 정리(M7 운영 준비, docs/08 §5·§6, DLG-215). 매일 03:47(KST).
 * <ul>
 *   <li>만료 후 유예기간이 지난 내보내기 파일(본문·기록) 삭제 — 내보내기는 원본이 아닌 사본이며 다운로드는 이미 만료로 막혀 있다.</li>
 *   <li>만료된 멱등키 기록 삭제.</li>
 *   <li>원본 payload·감사로그·원장은 지우지 않는다(주문 개인정보 파기는 OrderPrivacyService).</li>
 * </ul>
 */
@Service
public class RetentionService {
    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Duration exportGrace;

    public RetentionService(JdbcClient jdbc, FileStorage storage, AuditService audit, PlatformTransactionManager tm,
                            @Value("${commerce.retention.export-grace:PT24H}") Duration exportGrace) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
        this.exportGrace = exportGrace;
    }

    public record Result(int exportFiles, int idempotencyRecords) {
    }

    @Scheduled(cron = "${commerce.retention.cron:0 47 3 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        purge(Instant.now());
    }

    public Result purge(Instant now) {
        return TenantContext.asSystem(() -> {
            List<Map<String, Object>> files = jdbc.sql("""
                    select id, workspace_id, storage_key from stored_file where purpose = 'EXPORT' and expires_at is not null and expires_at < :cut limit 1000
                    """).param("cut", com.commerceos.shared.db.Db.ts(now.minus(exportGrace))).query().listOfRows();
            Map<UUID, Integer> perWs = new LinkedHashMap<>();
            int deleted = 0;
            for (Map<String, Object> f : files) {
                try {
                    tx.executeWithoutResult(s -> jdbc.sql("delete from stored_file where id = :id").param("id", f.get("id")).update());
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    continue; // 다른 기록이 참조하는 파일은 남긴다
                }
                storage.delete((String) f.get("storage_key"));
                perWs.merge((UUID) f.get("workspace_id"), 1, Integer::sum);
                deleted++;
            }
            int idem = tx.execute(s -> jdbc.sql("delete from idempotency_record where expires_at < :now").param("now", com.commerceos.shared.db.Db.ts(now)).update());
            perWs.forEach((ws, n) -> tx.executeWithoutResult(s -> audit.record(AuditEntry.builder(ws, "RETENTION_PURGED", "STORED_FILE", null)
                    .actor(ActorType.SYSTEM, null, "retention").metadata(Map.of("export_files", n)).build())));
            return new Result(deleted, idem);
        });
    }
}
