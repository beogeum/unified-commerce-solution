package com.commerceos.integration;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.integration.spi.Connector;
import com.commerceos.integration.spi.FeatureOp;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.outbox.OutboxPublisher;
import com.commerceos.shared.crypto.SecretCipher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import com.commerceos.shared.web.PageQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연동 연결(FR-ORG-04, docs/06 §1). 연결은 판매계정 또는 화주계정 하나에만 귀속되며(OQ-018 확정 전),
 * 범위 판단은 대상 계정의 현재 귀속 사업자를 따른다. 인증정보는 암호화 저장하고 어떤 응답에도 포함하지 않는다.
 */
@Service
public class ConnectionService {
    private static final Set<String> AUTH_TYPES = Set.of("API_KEY", "OAUTH2", "HMAC_SIGNATURE", "FILE_ONLY", "MANUAL");
    private static final Set<String> DEV_REG = Set.of("NOT_REQUIRED", "PENDING", "APPROVED", "REJECTED", "UNKNOWN");

    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final ConnectorRegistry registry;
    private final SecretCipher cipher;
    private final Json json;

    public ConnectionService(JdbcClient jdbc, AccessGuard guard, AuditService audit, OutboxPublisher outbox, ConnectorRegistry registry,
                             SecretCipher cipher, Json json) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.outbox = outbox;
        this.registry = registry;
        this.cipher = cipher;
        this.json = json;
    }

    public record ConnectionView(UUID id, String targetType, UUID channelAccountId, UUID shipperAccountId, UUID businessEntityId,
                                 String connectorCode, boolean isMock, String displayName, String apiVersion, String authType,
                                 String status, String developerRegistrationStatus, Instant credentialExpiresAt,
                                 boolean hasActiveCredential, String credentialFingerprint, String rateLimitNote,
                                 Instant lastTestedAt, Instant lastSuccessAt, String lastErrorCode, Instant lastErrorAt,
                                 int consecutiveFailures, int version, Instant createdAt) {
    }

    public record Create(String targetType, UUID targetId, String connectorCode, String displayName, String apiVersion, String authType,
                         String developerRegistrationStatus, Map<String, String> credential, Instant credentialExpiresAt,
                         String rateLimitNote) {
    }

    public record Patch(String displayName, String status, String developerRegistrationStatus, String rateLimitNote, String apiVersion) {
    }

    public record Target(UUID businessEntityId, UUID channelAccountId, UUID shipperAccountId) {
    }

    public List<ConnectionView> list(PageQuery page, List<String> businessEntityIds, String status, UUID channelAccountId) {
        guard.requirePermission(Perm.INTEGRATION_READ);
        CurrentActor a = guard.actor();
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        guard.channelAccountFilter(AccessKind.READ, channelAccountId);
        return jdbc.sql(SELECT + """
                 where c.workspace_id = :ws and coalesce(ca.business_entity_id, sa.business_entity_id) = any(:bes)
                   and (c.channel_account_id is null or c.channel_account_id = any(:cas))
                   and (cast(:status as text) is null or c.status = :status)
                   and (cast(:ca as uuid) is null or c.channel_account_id = :ca)
                   and (cast(:after as uuid) is null or c.id < :after)
                 order by c.id desc limit :limit
                """).param("ws", a.workspaceId()).param("bes", Db.uuids(bes))
                .param("cas", Db.uuids(guard.scope().channelAccountIds(AccessKind.READ))).param("status", status)
                .param("ca", channelAccountId).param("after", page.after()).param("limit", page.fetchSize())
                .query(this::map).list();
    }

    public ConnectionView get(UUID id) {
        guard.requirePermission(Perm.INTEGRATION_READ);
        ConnectionView v = find(id).orElseThrow(() -> Errors.notFound("INTEGRATION_CONNECTION"));
        guard.checkRead("INTEGRATION_CONNECTION", id, v.businessEntityId(), v.channelAccountId());
        return v;
    }

    /** 쓰기 권한 확인 후 연결을 돌려준다(다른 서비스에서 재사용). */
    public ConnectionView getForWrite(UUID id, String permission) {
        guard.requirePermission(permission);
        ConnectionView v = find(id).orElseThrow(() -> Errors.notFound("INTEGRATION_CONNECTION"));
        guard.checkWrite("INTEGRATION_CONNECTION", id, v.businessEntityId(), v.channelAccountId());
        return v;
    }

    @Transactional
    public ConnectionView create(Create c) {
        guard.requirePermission(Perm.INTEGRATION_MANAGE);
        CurrentActor a = guard.actor();
        if (!Set.of("CHANNEL_ACCOUNT", "SHIPPER_ACCOUNT").contains(String.valueOf(c.targetType()))) {
            throw Errors.validation("target_type", "target_type 은 CHANNEL_ACCOUNT 또는 SHIPPER_ACCOUNT 여야 합니다.");
        }
        if (!AUTH_TYPES.contains(String.valueOf(c.authType()))) {
            throw Errors.validation("auth_type", "auth_type 값이 올바르지 않습니다.");
        }
        String devReg = c.developerRegistrationStatus() == null ? "UNKNOWN" : c.developerRegistrationStatus();
        if (!DEV_REG.contains(devReg)) {
            throw Errors.validation("developer_registration_status", "developer_registration_status 값이 올바르지 않습니다.");
        }
        Target t = target(a.workspaceId(), c.targetType(), c.targetId());
        guard.checkWrite(c.targetType(), c.targetId(), t.businessEntityId(), t.channelAccountId());
        Connector connector = registry.find(c.connectorCode()).orElseThrow(() -> Errors.unprocessable("CONNECTOR_NOT_AVAILABLE",
                "사용할 수 있는 연동 모듈이 아닙니다. 실제 판매채널·물류사 연동은 계정 권한·API 확인 후 제공됩니다(현재 모의 연동만 가능).",
                Map.of("connector_code", String.valueOf(c.connectorCode()))));
        if (!connector.targetType().equals(c.targetType())) {
            throw Errors.unprocessable("CONNECTOR_TARGET_MISMATCH", "이 연동 모듈은 해당 계정 유형에 연결할 수 없습니다.");
        }
        if (c.credential() != null && !c.credential().isEmpty()) {
            guard.requirePermission(Perm.INTEGRATION_CREDENTIAL_MANAGE);
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into integration_connection (id, workspace_id, target_type, channel_account_id, shipper_account_id, connector_code,
                    is_mock, display_name, api_version, auth_type, developer_registration_status, credential_expires_at, rate_limit_note)
                values (:id, :ws, :tt, :ca, :sa, :code, :mock, :name, :ver, :auth, :dev, :exp, :rl)
                """).param("id", id).param("ws", a.workspaceId()).param("tt", c.targetType()).param("ca", t.channelAccountId())
                .param("sa", t.shipperAccountId()).param("code", connector.code()).param("mock", connector.isMock())
                .param("name", c.displayName().trim()).param("ver", c.apiVersion()).param("auth", c.authType()).param("dev", devReg)
                .param("exp", Db.ts(c.credentialExpiresAt())).param("rl", c.rateLimitNote()).update();
        // 대상 유형의 모든 기능 × 동작을 미확인 상태로 만든다. 확인 안 된 기능을 지원한다고 표시하지 않는다(CLAUDE.md).
        jdbc.sql("""
                insert into integration_capability (workspace_id, connection_id, feature_code, operation)
                select :ws, :id, f.code, op.operation from capability_feature f
                 cross join (values ('READ'), ('WRITE'), ('DELETE')) as op(operation)
                 where f.target_type = :tt
                """).param("ws", a.workspaceId()).param("id", id).param("tt", c.targetType()).update();
        if (c.credential() != null && !c.credential().isEmpty()) {
            storeCredential(a, id, c.credential(), c.credentialExpiresAt());
        }
        ConnectionView v = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "INTEGRATION_CONNECTION_CREATED", "INTEGRATION_CONNECTION", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(t.businessEntityId(), t.channelAccountId())
                .after(snapshot(v)).build());
        outbox.publish(a.workspaceId(), t.businessEntityId(), "INTEGRATION_CONNECTION", id, "IntegrationConnectionCreated",
                Map.of("connection_id", id.toString(), "connector_code", connector.code()));
        return v;
    }

    @Transactional
    public ConnectionView patch(UUID id, int expectedVersion, Patch p) {
        ConnectionView before = getForWrite(id, Perm.INTEGRATION_MANAGE);
        CurrentActor a = guard.actor();
        jdbc.sql("select id from integration_connection where id = :id for update").param("id", id).query(UUID.class).single();
        before = find(id).orElseThrow();
        if (before.version() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, before.version());
        }
        String newStatus = null;
        if (p.status() != null) {
            // 사용자가 직접 바꿀 수 있는 상태는 일시중지와 재개뿐. 재개는 상태 점검 성공 후 ACTIVE 가 된다(DEC-F09).
            if ("PAUSED".equals(p.status())) {
                newStatus = "PAUSED";
            } else if ("RESUME".equals(p.status())) {
                if (!"PAUSED".equals(before.status())) {
                    throw Errors.conflict("CONNECTION_NOT_PAUSED", "일시중지된 연결만 재개할 수 있습니다.");
                }
                newStatus = "PENDING_SETUP";
            } else {
                throw Errors.validation("status", "status 는 PAUSED 또는 RESUME 만 지정할 수 있습니다.");
            }
        }
        if (p.developerRegistrationStatus() != null && !DEV_REG.contains(p.developerRegistrationStatus())) {
            throw Errors.validation("developer_registration_status", "developer_registration_status 값이 올바르지 않습니다.");
        }
        jdbc.sql("""
                update integration_connection set display_name = coalesce(:name, display_name), status = coalesce(:st, status),
                       developer_registration_status = coalesce(:dev, developer_registration_status),
                       rate_limit_note = coalesce(:rl, rate_limit_note), api_version = coalesce(:ver, api_version),
                       version = version + 1, updated_at = now()
                 where id = :id
                """).param("name", p.displayName() == null || p.displayName().isBlank() ? null : p.displayName().trim())
                .param("st", newStatus).param("dev", p.developerRegistrationStatus()).param("rl", p.rateLimitNote())
                .param("ver", p.apiVersion()).param("id", id).update();
        ConnectionView after = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "INTEGRATION_CONNECTION_UPDATED", "INTEGRATION_CONNECTION", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(after.businessEntityId(), after.channelAccountId())
                .before(snapshot(before)).after(snapshot(after)).build());
        return after;
    }

    /** 인증정보 교체. 이전 인증정보는 ROTATED 로 남기고 인증 만료 상태였다면 재점검 대기로 돌린다. */
    @Transactional
    public ConnectionView rotateCredential(UUID id, Map<String, String> credential, Instant expiresAt) {
        getForWrite(id, Perm.INTEGRATION_CREDENTIAL_MANAGE);
        CurrentActor a = guard.actor();
        if (credential == null || credential.isEmpty()) {
            throw Errors.validation("credential", "인증정보를 입력하세요.");
        }
        jdbc.sql("select id from integration_connection where id = :id for update").param("id", id).query(UUID.class).single();
        jdbc.sql("update integration_credential set status = 'ROTATED', rotated_at = now() where connection_id = :id and status = 'ACTIVE'")
                .param("id", id).update();
        storeCredential(a, id, credential, expiresAt);
        jdbc.sql("""
                update integration_connection set credential_expires_at = :exp,
                       status = case when status in ('AUTH_EXPIRED', 'ERROR') then 'PENDING_SETUP' else status end,
                       version = version + 1, updated_at = now()
                 where id = :id
                """).param("exp", Db.ts(expiresAt)).param("id", id).update();
        ConnectionView v = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "INTEGRATION_CREDENTIAL_ROTATED", "INTEGRATION_CONNECTION", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(v.businessEntityId(), v.channelAccountId())
                .after(Map.of("fingerprint", String.valueOf(v.credentialFingerprint()))).build());
        return v;
    }

    private void storeCredential(CurrentActor a, UUID connectionId, Map<String, String> credential, Instant expiresAt) {
        String plain = json.write(new java.util.TreeMap<>(credential));
        SecretCipher.Encrypted enc = cipher.encrypt(plain);
        jdbc.sql("""
                insert into integration_credential (id, workspace_id, connection_id, secret_cipher, key_id, fingerprint, status, expires_at, created_by)
                values (:id, :ws, :conn, :c, :k, :fp, 'ACTIVE', :exp, :by)
                """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("conn", connectionId).param("c", enc.cipherText())
                .param("k", enc.keyId()).param("fp", fingerprint(plain)).param("exp", Db.ts(expiresAt)).param("by", a.memberId()).update();
    }

    /** 작업 처리기 전용: 복호화된 인증정보. 호출자는 로그·응답에 포함하지 않아야 한다. */
    Optional<Map<String, String>> activeCredential(UUID connectionId) {
        record Row(byte[] c, String k) {
        }
        return jdbc.sql("select secret_cipher, key_id from integration_credential where connection_id = :id and status = 'ACTIVE'")
                .param("id", connectionId).query((rs, i) -> new Row(rs.getBytes(1), rs.getString(2))).optional()
                .map(r -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> m = json.read(cipher.decrypt(r.c(), r.k()), Map.class);
                    return m;
                });
    }

    Target target(UUID ws, String targetType, UUID targetId) {
        if (targetId == null) {
            throw Errors.validation("target_id", "연결할 계정을 지정하세요.");
        }
        if ("CHANNEL_ACCOUNT".equals(targetType)) {
            return jdbc.sql("select business_entity_id from channel_account where workspace_id = :ws and id = :id and status <> 'CLOSED'")
                    .param("ws", ws).param("id", targetId).query(UUID.class).optional()
                    .map(be -> new Target(be, targetId, null))
                    .orElseThrow(() -> Errors.validation("target_id", "판매계정을 찾을 수 없습니다."));
        }
        return jdbc.sql("select business_entity_id from shipper_account where workspace_id = :ws and id = :id and status <> 'CLOSED'")
                .param("ws", ws).param("id", targetId).query(UUID.class).optional()
                .map(be -> new Target(be, null, targetId))
                .orElseThrow(() -> Errors.validation("target_id", "화주계정을 찾을 수 없습니다."));
    }

    /** 작업 처리기 등 요청 밖에서 쓰는 조회(범위 검사 없음 — 호출자가 책임진다). */
    Optional<ConnectionView> findSystem(UUID workspaceId, UUID id) {
        return jdbc.sql(SELECT + " where c.workspace_id = :ws and c.id = :id").param("ws", workspaceId).param("id", id)
                .query(this::map).optional();
    }

    Optional<ConnectionView> find(UUID id) {
        return findSystem(guard.actor().workspaceId(), id);
    }

    Set<FeatureOp> implemented(String connectorCode) {
        return registry.find(connectorCode).map(Connector::implemented).orElse(Set.of());
    }

    static final String SELECT = """
            select c.*, coalesce(ca.business_entity_id, sa.business_entity_id) as be_id,
                   cr.fingerprint as cred_fp
              from integration_connection c
              left join channel_account ca on ca.id = c.channel_account_id
              left join shipper_account sa on sa.id = c.shipper_account_id
              left join integration_credential cr on cr.connection_id = c.id and cr.status = 'ACTIVE'
            """;

    ConnectionView map(ResultSet rs, int i) throws SQLException {
        String fp = rs.getString("cred_fp");
        return new ConnectionView(Db.uuid(rs, "id"), rs.getString("target_type"), Db.uuid(rs, "channel_account_id"),
                Db.uuid(rs, "shipper_account_id"), Db.uuid(rs, "be_id"), rs.getString("connector_code"), rs.getBoolean("is_mock"),
                rs.getString("display_name"), rs.getString("api_version"), rs.getString("auth_type"), rs.getString("status"),
                rs.getString("developer_registration_status"), Db.instant(rs, "credential_expires_at"), fp != null, fp,
                rs.getString("rate_limit_note"), Db.instant(rs, "last_tested_at"), Db.instant(rs, "last_success_at"),
                rs.getString("last_error_code"), Db.instant(rs, "last_error_at"), rs.getInt("consecutive_failures"),
                rs.getInt("version"), Db.instant(rs, "created_at"));
    }

    private static Map<String, Object> snapshot(ConnectionView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("target_type", v.targetType());
        m.put("connector_code", v.connectorCode());
        m.put("is_mock", v.isMock());
        m.put("display_name", v.displayName());
        m.put("status", v.status());
        m.put("api_version", v.apiVersion());
        m.put("developer_registration_status", v.developerRegistrationStatus());
        m.put("has_active_credential", v.hasActiveCredential());
        return m;
    }

    /** 인증정보 식별용 지문(해시 앞 12자리). 원문 복원 불가. */
    static String fingerprint(String plain) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(plain.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(d).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
