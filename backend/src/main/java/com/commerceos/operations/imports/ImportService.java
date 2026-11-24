package com.commerceos.operations.imports;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.file.FileService;
import com.commerceos.platform.job.JobService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 일괄 가져오기(M2 C6). 파일 → 컬럼 매핑 → 미리보기(행별 정상·경고·오류) → 확인 후 비동기 실행 → 행별 결과.
 * 오류 행은 실행에서 빠지고, 문제 행만 내려받아 고친 뒤 다시 올릴 수 있다.
 */
@Service
public class ImportService {
    static final String JOB_TYPE = "IMPORT_EXECUTE";

    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final FileService files;
    private final JobService jobs;
    private final Json json;
    private final Map<String, ImportTypeHandler> handlers;
    private final com.commerceos.shared.crypto.SecretCipher cipher;

    public ImportService(JdbcClient jdbc, AccessGuard guard, AuditService audit, FileService files, JobService jobs, Json json,
                         List<ImportTypeHandler> handlers, com.commerceos.shared.crypto.SecretCipher cipher) {
        this.cipher = cipher;
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.files = files;
        this.jobs = jobs;
        this.json = json;
        this.handlers = handlers.stream().collect(Collectors.toMap(ImportTypeHandler::importType, Function.identity()));
    }

    public record TemplateView(String importType, String labelKo, String templateVersion, String requiredPermission, List<ImportTypeHandler.Field> fields) {
    }

    public record BatchView(UUID id, String importType, String templateVersion, UUID fileId, String fileName, String fileSha256, String sheetName,
                            Map<String, String> columnMapping, String status, int totalRows, int okRows, int warningRows, int errorRows,
                            int appliedRows, int failedRows, UUID jobId, String createdByName, Instant createdAt, Instant executedAt,
                            Instant completedAt, String errorMessageKo, int version) {
    }

    public record RowView(int rowNumber, String status, JsonNode messages, JsonNode raw, String result, String resultMessage, String appliedRef) {
    }

    public record Create(UUID fileId, String importType, String sheetName, Map<String, String> columnMapping) {
    }

    public List<TemplateView> templates() {
        guard.actor();
        return handlers.values().stream().sorted(java.util.Comparator.comparing(ImportTypeHandler::importType))
                .map(h -> new TemplateView(h.importType(), h.labelKo(), h.templateVersion(), h.requiredPermission(), h.fields())).toList();
    }

    /** 양식 파일(CSV, 머리글 + 예시 1행). 엑셀에서 열어 그대로 채워 저장하면 된다. */
    public byte[] templateCsv(String importType) {
        ImportTypeHandler h = handler(importType);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(h.fields().stream().map(f -> csv(f.labelKo() + (f.required() ? "*" : ""))).collect(Collectors.joining(","))).append("\r\n");
        sb.append(h.fields().stream().map(f -> csv(f.exampleValue() == null ? "" : f.exampleValue())).collect(Collectors.joining(","))).append("\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---------------- 미리보기 ----------------

    @Transactional
    public BatchView create(Create c) {
        CurrentActor a = guard.actor();
        ImportTypeHandler h = handler(c.importType());
        guard.requirePermission(h.requiredPermission());
        guard.requirePermission(Perm.FILE_UPLOAD);
        if (c.fileId() == null) {
            throw Errors.validation("file_id", "가져올 파일을 먼저 올려 주세요.");
        }
        FileService.Download d = files.download(c.fileId());
        if (!"GENERIC_IMPORT".equals(d.file().purpose())) {
            throw Errors.unprocessable("IMPORT_FILE_PURPOSE_INVALID", "가져오기용으로 올린 파일이 아닙니다.");
        }
        TabularReader.Table table = TabularReader.read(d.content(), d.file().originalFilename(), blank(c.sheetName()));
        Map<String, String> mapping = mapColumns(h, table.headers(), c.columnMapping());
        List<String> missing = h.fields().stream().filter(ImportTypeHandler.Field::required).filter(f -> !mapping.containsValue(f.key()))
                .map(ImportTypeHandler.Field::labelKo).toList();
        if (!missing.isEmpty()) {
            throw Errors.unprocessable("IMPORT_COLUMNS_MISSING", "필수 컬럼을 찾지 못했습니다: " + String.join(", ", missing)
                    + ". 양식의 머리글을 쓰거나 컬럼 매핑을 지정하세요.", Map.of("missing", missing, "headers", table.headers()));
        }
        if (table.rows().isEmpty()) {
            throw Errors.unprocessable("IMPORT_EMPTY", "머리글 아래에 데이터 행이 없습니다.");
        }
        List<Map<String, String>> mapped = new ArrayList<>();
        for (TabularReader.DataRow r : table.rows()) {
            Map<String, String> m = new LinkedHashMap<>();
            r.values().forEach((header, value) -> {
                String key = mapping.get(header);
                if (key != null) {
                    m.put(key, value == null ? "" : value.trim());
                }
            });
            mapped.add(m);
        }
        List<ImportTypeHandler.RowCheck> checks = h.validate(mapped);
        UUID id = Ids.newId();
        int ok = 0, warn = 0, err = 0;
        for (ImportTypeHandler.RowCheck rc : checks) {
            switch (rc.status()) {
                case "OK" -> ok++;
                case "WARNING" -> warn++;
                default -> err++;
            }
        }
        jdbc.sql("""
                insert into import_batch (id, workspace_id, import_type, template_version, file_id, sheet_name, column_mapping, total_rows,
                                          ok_rows, warning_rows, error_rows, created_by)
                values (:id, :ws, :t, :v, :f, :sheet, cast(:map as jsonb), :total, :ok, :warn, :err, :by)
                """).param("id", id).param("ws", a.workspaceId()).param("t", h.importType()).param("v", h.templateVersion())
                .param("f", c.fileId()).param("sheet", table.sheetName()).param("map", json.write(mapping)).param("total", table.rows().size())
                .param("ok", ok).param("warn", warn).param("err", err).param("by", a.memberId()).update();
        Set<String> sensitive = h.fields().stream().filter(ImportTypeHandler.Field::sensitive).map(ImportTypeHandler.Field::key).collect(Collectors.toSet());
        for (int i = 0; i < checks.size(); i++) {
            ImportTypeHandler.RowCheck rc = checks.get(i);
            Map<String, String> raw = new LinkedHashMap<>(table.rows().get(i).values());
            Map<String, String> norm = rc.normalized() == null ? null : new LinkedHashMap<>(rc.normalized());
            com.commerceos.shared.crypto.SecretCipher.Encrypted secret = null;
            if (!sensitive.isEmpty()) {
                // 미리보기 원문은 마스킹, 실행에 필요한 값은 암호화 묶음으로만 보관(DLG-235)
                raw.replaceAll((header, value) -> sensitive.contains(mapping.get(header)) ? maskValue(value) : value);
                if (norm != null) {
                    Map<String, String> hidden = new LinkedHashMap<>();
                    for (String k : sensitive) {
                        if (norm.containsKey(k)) {
                            hidden.put(k, norm.remove(k));
                        }
                    }
                    secret = hidden.isEmpty() ? null : cipher.encrypt(json.write(hidden));
                }
            }
            jdbc.sql("""
                    insert into import_row (workspace_id, batch_id, row_number, status, messages, raw, normalized, secret_cipher, secret_key_id)
                    values (:ws, :b, :n, :st, cast(:msg as jsonb), cast(:raw as jsonb), cast(:norm as jsonb), :sc, cast(:sk as text))
                    """).param("ws", a.workspaceId()).param("b", id).param("n", table.rows().get(i).rowNumber()).param("st", rc.status())
                    .param("msg", json.write(rc.messages())).param("raw", json.write(raw)).param("norm", norm == null ? null : json.write(norm))
                    .param("sc", secret == null ? null : secret.cipherText()).param("sk", secret == null ? null : secret.keyId()).update();
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "IMPORT_PREVIEWED", "IMPORT_BATCH", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .metadata(Map.of("import_type", h.importType(), "template_version", h.templateVersion(), "file_id", c.fileId().toString(),
                        "sha256", d.file().sha256(), "rows", table.rows().size(), "ok", ok, "warning", warn, "error", err, "encoding", table.encoding()))
                .build());
        return get(id);
    }

    /** 미리보기용 마스킹: 앞 1글자 + 나머지 *(최대 8자). */
    static String maskValue(String v) {
        if (v == null || v.isBlank()) {
            return v;
        }
        String t = v.trim();
        return t.charAt(0) + "*".repeat(Math.min(8, Math.max(1, t.length() - 1)));
    }

    public BatchView get(UUID id) {
        CurrentActor a = guard.actor();
        BatchView b = find(id).orElseThrow(() -> Errors.notFound("IMPORT_BATCH"));
        boolean own = jdbc.sql("select created_by from import_batch where id = :id").param("id", id).query(UUID.class).single().equals(a.memberId());
        if (!own && !a.has(handler(b.importType()).requiredPermission())) {
            throw Errors.notFound("IMPORT_BATCH");
        }
        return b;
    }

    public List<BatchView> list() {
        CurrentActor a = guard.actor();
        return jdbc.sql(SELECT + " where b.workspace_id = :ws order by b.created_at desc limit 50").param("ws", a.workspaceId()).query(this::map).list()
                .stream().filter(b -> handlers.containsKey(b.importType()) && a.has(handlers.get(b.importType()).requiredPermission())).toList();
    }

    public List<RowView> rows(UUID id, String status, int offset, int limit) {
        get(id);
        return jdbc.sql("""
                select * from import_row where workspace_id = :ws and batch_id = :b and (cast(:st as text) is null or status = :st or result = :st)
                 order by row_number offset :off limit :lim
                """).param("ws", guard.actor().workspaceId()).param("b", id).param("st", status).param("off", Math.max(0, offset))
                .param("lim", Math.min(Math.max(limit, 1), 500))
                .query((rs, i) -> new RowView(rs.getInt("row_number"), rs.getString("status"), json.read(rs.getString("messages")),
                        json.read(rs.getString("raw")), rs.getString("result"), rs.getString("result_message"), rs.getString("applied_ref"))).list();
    }

    /** 문제 행(오류·경고·실행 실패)을 원래 컬럼 + 상태·사유로 내려받는다. 고쳐서 다시 올리면 된다. */
    public byte[] problemRowsCsv(UUID id) {
        BatchView b = get(id);
        List<RowView> rows = jdbc.sql("""
                select * from import_row where workspace_id = :ws and batch_id = :b and (status <> 'OK' or result = 'FAILED') order by row_number
                """).param("ws", guard.actor().workspaceId()).param("b", id)
                .query((rs, i) -> new RowView(rs.getInt("row_number"), rs.getString("status"), json.read(rs.getString("messages")),
                        json.read(rs.getString("raw")), rs.getString("result"), rs.getString("result_message"), rs.getString("applied_ref"))).list();
        List<String> headers = new ArrayList<>(b.columnMapping().keySet());
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("원본 행,상태,사유,").append(headers.stream().map(ImportService::csv).collect(Collectors.joining(","))).append("\r\n");
        for (RowView r : rows) {
            List<String> reasons = new ArrayList<>();
            r.messages().forEach(m -> reasons.add(m.get("text").asString()));
            if ("FAILED".equals(r.result()) && r.resultMessage() != null) {
                reasons.add("실행 실패: " + r.resultMessage());
            }
            String state = "FAILED".equals(r.result()) ? "실행 실패" : "ERROR".equals(r.status()) ? "오류" : "경고";
            sb.append(r.rowNumber()).append(',').append(csv(state)).append(',').append(csv(String.join(" / ", reasons)));
            for (String h : headers) {
                JsonNode v = r.raw().get(h);
                sb.append(',').append(csv(v == null || v.isNull() ? "" : v.asString()));
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---------------- 실행 ----------------

    /** 미리보기에서 본 실행 대상 행 수(confirm_rows)를 함께 보내야 실행된다(실행 전 확인, DLG-082). */
    @Transactional
    public BatchView execute(UUID id, int expectedVersion, Integer confirmRows) {
        CurrentActor a = guard.actor();
        BatchView b = get(id);
        guard.requirePermission(handler(b.importType()).requiredPermission());
        jdbc.sql("select id from import_batch where id = :id for update").param("id", id).query(UUID.class).single();
        b = find(id).orElseThrow();
        if (b.version() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, b.version());
        }
        if (!"PREVIEWED".equals(b.status())) {
            throw Errors.conflict("IMPORT_NOT_PREVIEWED", "이미 실행했거나 취소한 가져오기입니다.", Map.of("status", b.status()));
        }
        int executable = b.okRows() + b.warningRows();
        if (executable == 0) {
            throw Errors.unprocessable("IMPORT_NOTHING_TO_RUN", "실행할 수 있는 행이 없습니다. 오류를 고쳐 다시 올려 주세요.");
        }
        if (confirmRows == null || confirmRows != executable) {
            throw Errors.conflict("IMPORT_CONFIRMATION_MISMATCH", "미리보기에서 확인한 실행 행 수와 다릅니다. 미리보기를 다시 확인하세요.",
                    Map.of("executable_rows", executable));
        }
        UUID jobId = jobs.enqueue(new JobService.NewJob(a.workspaceId(), JOB_TYPE, Map.of("batch_id", id.toString()), null, null, null,
                a.memberId(), 3));
        jdbc.sql("""
                update import_batch set status = 'QUEUED', job_id = :j, executed_by = :by, executed_at = now(), version = version + 1 where id = :id
                """).param("j", jobId).param("by", a.memberId()).param("id", id).update();
        jdbc.sql("update import_row set result = 'SKIPPED' where batch_id = :id and status = 'ERROR'").param("id", id).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "IMPORT_EXECUTION_REQUESTED", "IMPORT_BATCH", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).metadata(Map.of("job_id", jobId.toString(), "rows", executable)).build());
        return find(id).orElseThrow();
    }

    @Transactional
    public BatchView cancel(UUID id, int expectedVersion) {
        CurrentActor a = guard.actor();
        BatchView b = get(id);
        if (b.version() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, b.version());
        }
        if (!"PREVIEWED".equals(b.status())) {
            throw Errors.conflict("IMPORT_NOT_PREVIEWED", "실행 전 가져오기만 취소할 수 있습니다.", Map.of("status", b.status()));
        }
        jdbc.sql("update import_batch set status = 'CANCELLED', completed_at = now(), version = version + 1 where id = :id").param("id", id).update();
        if (handler(b.importType()).hasSensitiveFields()) {
            files.purgeContent(a.workspaceId(), b.fileId(), "SENSITIVE_IMPORT_PROCESSED");
            jdbc.sql("update import_row set secret_cipher = null, secret_key_id = null where batch_id = :b").param("b", id).update();
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "IMPORT_CANCELLED", "IMPORT_BATCH", id).actor(ActorType.USER, a.memberId(), a.displayName()).build());
        return find(id).orElseThrow();
    }

    /** 개인정보 유형 가져오기 중 미리보기 후 오래 실행하지 않은 배치: 취소하고 원본 본문·암호화 값을 지운다(DLG-236, 보관기간 정리에서 호출). */
    public int expireSensitive(java.time.Instant olderThan) {
        List<String> types = handlers.values().stream().filter(ImportTypeHandler::hasSensitiveFields).map(ImportTypeHandler::importType).toList();
        if (types.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Map<String, Object> b : jdbc.sql("""
                select id, workspace_id, file_id from import_batch where status = 'PREVIEWED' and import_type in (:t) and created_at < :at
                """).param("t", types).param("at", com.commerceos.shared.db.Db.ts(olderThan)).query().listOfRows()) {
            UUID id = (UUID) b.get("id");
            UUID ws = (UUID) b.get("workspace_id");
            jdbc.sql("update import_batch set status = 'CANCELLED', completed_at = now(), error_message_ko = :m, version = version + 1 where id = :id and status = 'PREVIEWED'")
                    .param("m", "개인정보 보호를 위해 7일 동안 실행하지 않은 가져오기를 자동 취소했습니다.").param("id", id).update();
            jdbc.sql("update import_row set secret_cipher = null, secret_key_id = null where batch_id = :b").param("b", id).update();
            files.purgeContent(ws, (UUID) b.get("file_id"), "SENSITIVE_IMPORT_EXPIRED");
            audit.record(AuditEntry.builder(ws, "IMPORT_EXPIRED", "IMPORT_BATCH", id).actor(ActorType.SYSTEM, null, "system").build());
            n++;
        }
        return n;
    }

    ImportTypeHandler handler(String type) {
        ImportTypeHandler h = handlers.get(type);
        if (h == null) {
            throw Errors.validation("import_type", "지원하지 않는 가져오기 유형입니다. 허용값: " + String.join(", ", handlers.keySet().stream().sorted().toList()));
        }
        return h;
    }

    /** 머리글 → 필드: 요청 매핑 우선, 없으면 양식 이름·별칭(공백·별표·괄호 무시, 대소문자 무시)으로 맞춘다. */
    static Map<String, String> mapColumns(ImportTypeHandler h, List<String> headers, Map<String, String> requested) {
        Map<String, String> byAlias = new LinkedHashMap<>();
        for (ImportTypeHandler.Field f : h.fields()) {
            byAlias.put(norm(f.labelKo()), f.key());
            byAlias.put(norm(f.key()), f.key());
            for (String al : f.aliases()) {
                byAlias.put(norm(al), f.key());
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (String header : headers) {
            if (header.isEmpty()) {
                continue;
            }
            String key = requested != null && requested.containsKey(header) ? requested.get(header) : byAlias.get(norm(header));
            if (key != null && h.fields().stream().anyMatch(f -> f.key().equals(key))) {
                if (!used.add(key)) {
                    throw Errors.unprocessable("IMPORT_COLUMN_DUPLICATE", "같은 항목에 연결된 컬럼이 두 개 있습니다: " + header);
                }
                out.put(header, key);
            }
        }
        return out;
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s*()\\[\\]_\\-·/]", "");
    }

    static String csv(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s; // 수식 주입 방지(엑셀에서 열 때)
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static final String SELECT = """
            select b.*, f.original_filename, f.sha256, u.display_name as created_by_name from import_batch b
              join stored_file f on f.workspace_id = b.workspace_id and f.id = b.file_id
              join workspace_member m on m.workspace_id = b.workspace_id and m.id = b.created_by
              join user_account u on u.id = m.user_id
            """;

    Optional<BatchView> find(UUID id) {
        return jdbc.sql(SELECT + " where b.workspace_id = :ws and b.id = :id").param("ws", guard.actor().workspaceId()).param("id", id)
                .query(this::map).optional();
    }

    @SuppressWarnings("unchecked")
    private BatchView map(ResultSet rs, int i) throws SQLException {
        Map<String, String> mapping = json.read(rs.getString("column_mapping"), LinkedHashMap.class);
        return new BatchView(Db.uuid(rs, "id"), rs.getString("import_type"), rs.getString("template_version"), Db.uuid(rs, "file_id"),
                rs.getString("original_filename"), rs.getString("sha256"), rs.getString("sheet_name"), mapping, rs.getString("status"),
                rs.getInt("total_rows"), rs.getInt("ok_rows"), rs.getInt("warning_rows"), rs.getInt("error_rows"), rs.getInt("applied_rows"),
                rs.getInt("failed_rows"), Db.uuid(rs, "job_id"), rs.getString("created_by_name"), Db.instant(rs, "created_at"),
                Db.instant(rs, "executed_at"), Db.instant(rs, "completed_at"), rs.getString("error_message_ko"), rs.getInt("version"));
    }
}
