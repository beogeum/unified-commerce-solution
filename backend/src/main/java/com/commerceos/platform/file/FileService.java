package com.commerceos.platform.file;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파일 업로드·원본 보존(docs/02 §1, docs/15 수집). 원본은 수정하지 않고 SHA-256 을 보관한다.
 * 같은 해시의 재업로드는 거부하지 않고 duplicate_of 로 알린다(정산 파일 재업로드 중복 판단은 M5 가 행 단위로 한다).
 */
@Service
public class FileService {
    static final Set<String> ALLOWED_EXT = Set.of("csv", "xlsx", "xls", "zip", "pdf", "png", "jpg", "jpeg", "json", "txt");
    static final Set<String> UPLOAD_PURPOSES = Set.of("GENERIC_IMPORT", "SETTLEMENT_SOURCE", "VAT_SOURCE", "CAPABILITY_EVIDENCE");

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final FileScanner scanner;
    private final AccessGuard guard;
    private final AuditService audit;
    private final Clock clock;

    public FileService(JdbcClient jdbc, FileStorage storage, FileScanner scanner, AccessGuard guard, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.scanner = scanner;
        this.guard = guard;
        this.audit = audit;
        this.clock = clock;
    }

    public record FileView(UUID id, UUID businessEntityId, String purpose, String originalFilename, String contentType, long sizeBytes,
                           String sha256, String scanStatus, boolean containsPii, UUID uploadedBy, Instant createdAt, Instant expiresAt,
                           List<UUID> duplicateOf) {
    }

    public record NewFile(UUID workspaceId, UUID businessEntityId, String purpose, String filename, String contentType, byte[] content,
                          boolean containsPii, UUID uploadedBy, Instant expiresAt) {
    }

    @Transactional
    public FileView upload(UUID businessEntityId, String purpose, String filename, String contentType, byte[] content, boolean containsPii) {
        guard.requirePermission(Perm.FILE_UPLOAD);
        CurrentActor a = guard.actor();
        if (!UPLOAD_PURPOSES.contains(purpose)) {
            throw Errors.validation("purpose", "purpose 값이 올바르지 않습니다.");
        }
        if (businessEntityId != null) {
            if (jdbc.sql("select count(*) from business_entity where workspace_id = :ws and id = :id")
                    .param("ws", a.workspaceId()).param("id", businessEntityId).query(Integer.class).single() == 0) {
                throw Errors.validation("business_entity_id", "사업자를 찾을 수 없습니다.");
            }
            guard.checkWrite("BUSINESS_ENTITY", businessEntityId, businessEntityId, null);
        }
        validateName(filename);
        if (content.length == 0) {
            throw Errors.validation("file", "빈 파일은 업로드할 수 없습니다.");
        }
        FileView v = store(new NewFile(a.workspaceId(), businessEntityId, purpose, filename, contentType, content, containsPii,
                a.memberId(), null));
        audit.record(AuditEntry.builder(a.workspaceId(), "FILE_UPLOADED", "FILE", v.id())
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(businessEntityId, null)
                .after(Map.of("purpose", purpose, "filename", v.originalFilename(), "sha256", v.sha256(), "size", v.sizeBytes(),
                        "scan_status", v.scanStatus(), "duplicate_count", v.duplicateOf().size()))
                .build());
        return v;
    }

    /** 시스템 생성 파일(내보내기 등) 저장. 범위 검사는 호출자 책임. */
    @Transactional
    public FileView store(NewFile f) {
        String sha = sha256(f.content());
        String scan = scanner.scan(f.filename(), f.content());
        if ("INFECTED".equals(scan)) {
            throw Errors.unprocessable("FILE_REJECTED", "보안 검사에서 차단된 파일입니다.");
        }
        List<UUID> duplicates = jdbc.sql("""
                select id from stored_file where workspace_id = :ws and sha256 = :h and purpose = :p order by created_at
                """).param("ws", f.workspaceId()).param("h", sha).param("p", f.purpose()).query(UUID.class).list();
        UUID id = Ids.newId();
        Instant now = clock.instant();
        String key = f.workspaceId() + "/" + now.atZone(ZoneOffset.UTC).getYear() + "/" + id;
        storage.put(key, f.content());
        jdbc.sql("""
                insert into stored_file (id, workspace_id, business_entity_id, purpose, original_filename, content_type, size_bytes,
                    sha256, storage_key, scan_status, contains_pii, uploaded_by, expires_at)
                values (:id, :ws, :be, :p, :name, :ct, :size, :sha, :key, :scan, :pii, :by, :exp)
                """).param("id", id).param("ws", f.workspaceId()).param("be", f.businessEntityId()).param("p", f.purpose())
                .param("name", f.filename()).param("ct", f.contentType() == null ? "application/octet-stream" : f.contentType())
                .param("size", (long) f.content().length).param("sha", sha).param("key", key).param("scan", scan)
                .param("pii", f.containsPii()).param("by", f.uploadedBy()).param("exp", Db.ts(f.expiresAt())).update();
        return new FileView(id, f.businessEntityId(), f.purpose(), f.filename(), f.contentType(), f.content().length, sha, scan,
                f.containsPii(), f.uploadedBy(), now, f.expiresAt(), duplicates);
    }

    public record Download(FileView file, byte[] content) {
    }

    record Row(FileView view, String storageKey) {
    }

    public FileView get(UUID id) {
        return authorize(id).view();
    }

    /** 다운로드: 내보내기 파일은 요청자 본인만·만료 전까지, 그 외는 file.read + 사업자 범위. 개인정보 포함 파일은 pii.read 필요. */
    public Download download(UUID id) {
        Row r = authorize(id);
        CurrentActor a = guard.actor();
        try (var in = storage.open(r.storageKey())) {
            byte[] content = in.readAllBytes();
            audit.recordIsolated(AuditEntry.builder(a.workspaceId(), "FILE_DOWNLOADED", "FILE", id)
                    .actor(ActorType.USER, a.memberId(), a.displayName()).scope(r.view().businessEntityId(), null)
                    .metadata(Map.of("purpose", r.view().purpose(), "sha256", r.view().sha256())).build());
            return new Download(r.view(), content);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private Row authorize(UUID id) {
        CurrentActor a = guard.actor();
        Row r = jdbc.sql("select * from stored_file where workspace_id = :ws and id = :id").param("ws", a.workspaceId()).param("id", id)
                .query((rs, i) -> new Row(new FileView(Db.uuid(rs, "id"), Db.uuid(rs, "business_entity_id"), rs.getString("purpose"),
                        rs.getString("original_filename"), rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                        rs.getString("scan_status"), rs.getBoolean("contains_pii"), Db.uuid(rs, "uploaded_by"),
                        Db.instant(rs, "created_at"), Db.instant(rs, "expires_at"), List.of()), rs.getString("storage_key")))
                .optional().orElseThrow(() -> Errors.notFound("FILE"));
        FileView f = r.view();
        boolean own = a.memberId().equals(f.uploadedBy());
        if ("EXPORT".equals(f.purpose())) {
            if (!own) {
                throw Errors.notFound("FILE");
            }
            if (f.expiresAt() != null && f.expiresAt().isBefore(clock.instant())) {
                throw Errors.conflict("FILE_EXPIRED", "다운로드 기간이 지난 내보내기 파일입니다. 다시 내보내기를 요청하세요.");
            }
        } else if (!own) {
            guard.requirePermission(Perm.FILE_READ);
            if (f.businessEntityId() == null) {
                throw Errors.notFound("FILE");
            }
            guard.checkRead("FILE", id, f.businessEntityId(), null);
        }
        if (f.containsPii()) {
            guard.requirePermission(Perm.PII_READ);
        }
        return r;
    }

    static void validateName(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 255 || filename.contains("/") || filename.contains("\\")
                || filename.contains("\0")) {
            throw Errors.validation("file", "파일 이름이 올바르지 않습니다.");
        }
        int dot = filename.lastIndexOf('.');
        String ext = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXT.contains(ext)) {
            throw Errors.unprocessable("FILE_TYPE_NOT_ALLOWED", "허용되지 않는 파일 형식입니다.",
                    Map.of("allowed", ALLOWED_EXT.stream().sorted().toList()));
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
