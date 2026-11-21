package com.commerceos.integration;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.integration.spi.FeatureOp;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연결별 지원 기능(docs/06 §1, DEC-F08).
 * 실행 가능 = 커넥터 구현 ∧ 지원수준(OFFICIAL_DOCUMENTED|PARTIAL) ∧ 계정 검증 VERIFIED ∧ 연결 ACTIVE.
 * UI 는 executable=false 인 기능의 버튼을 비활성화하고, 서버는 require() 로 다시 거부한다(T39).
 */
@Service
public class CapabilityService {
    static final Set<String> SUPPORT_LEVELS = Set.of("OFFICIAL_DOCUMENTED", "PERMISSION_REQUIRED", "PARTIAL", "FILE", "MANUAL",
            "UNVERIFIED", "UNSUPPORTED");
    static final Set<String> EXECUTABLE_LEVELS = Set.of("OFFICIAL_DOCUMENTED", "PARTIAL");

    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final ConnectionService connections;

    public CapabilityService(JdbcClient jdbc, AccessGuard guard, AuditService audit, ConnectionService connections) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.connections = connections;
    }

    public record CapabilityView(UUID connectionId, String featureCode, String featureNameKo, String operation, String supportLevel,
                                 String accountVerification, boolean connectorImplemented, boolean executable,
                                 List<String> blockedReasons, String evidenceRef, UUID evidenceFileId, String note,
                                 Instant verifiedAt, int capabilityVersion) {
    }

    public record Update(String supportLevel, String accountVerification, String evidenceRef, UUID evidenceFileId, String note) {
    }

    public List<CapabilityView> list(UUID connectionId) {
        ConnectionService.ConnectionView c = connections.get(connectionId);
        return load(c);
    }

    @Transactional
    public CapabilityView update(UUID connectionId, String feature, String operation, int expectedVersion, Update u) {
        ConnectionService.ConnectionView c = connections.getForWrite(connectionId, Perm.INTEGRATION_CAPABILITY_VERIFY);
        CurrentActor a = guard.actor();
        CapabilityView before = load(c).stream().filter(x -> x.featureCode().equals(feature) && x.operation().equals(operation))
                .findFirst().orElseThrow(() -> Errors.notFound("INTEGRATION_CAPABILITY"));
        if (before.capabilityVersion() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, before.capabilityVersion());
        }
        String level = u.supportLevel() == null ? before.supportLevel() : u.supportLevel();
        String verification = u.accountVerification() == null ? before.accountVerification() : u.accountVerification();
        if (!SUPPORT_LEVELS.contains(level)) {
            throw Errors.validation("support_level", "support_level 값이 올바르지 않습니다.");
        }
        if (!Set.of("NOT_VERIFIED", "VERIFIED", "DENIED").contains(verification)) {
            throw Errors.validation("account_verification", "account_verification 값이 올바르지 않습니다.");
        }
        if ("VERIFIED".equals(verification)) {
            if ((u.evidenceRef() == null || u.evidenceRef().isBlank()) && u.evidenceFileId() == null) {
                throw Errors.unprocessable("EVIDENCE_REQUIRED",
                        "계정 검증 완료로 기록하려면 근거(테스트 요청·응답 캡처, 공식 문서 위치 등)를 함께 남겨야 합니다.");
            }
            if (!before.connectorImplemented()) {
                throw Errors.unprocessable("CAPABILITY_NOT_IMPLEMENTED",
                        "이 연동 모듈은 해당 기능을 구현하지 않아 검증 완료로 기록할 수 없습니다.");
            }
            if ("UNVERIFIED".equals(level) || "UNSUPPORTED".equals(level)) {
                throw Errors.unprocessable("CAPABILITY_LEVEL_CONFLICT", "지원수준이 미확인/미지원인 기능은 계정 검증 완료로 기록할 수 없습니다.");
            }
        }
        if (u.evidenceFileId() != null && jdbc.sql("select count(*) from stored_file where workspace_id = :ws and id = :id")
                .param("ws", a.workspaceId()).param("id", u.evidenceFileId()).query(Integer.class).single() == 0) {
            throw Errors.validation("evidence_file_id", "근거 파일을 찾을 수 없습니다.");
        }
        boolean verified = "VERIFIED".equals(verification);
        jdbc.sql("""
                update integration_capability
                   set support_level = :lvl, account_verification = :ver, evidence_ref = coalesce(:ref, evidence_ref),
                       evidence_file_id = coalesce(:file, evidence_file_id), note = coalesce(:note, note),
                       verified_at = case when :verified then now() else null end,
                       verified_by = case when :verified then cast(:by as uuid) else null end,
                       capability_version = capability_version + 1, updated_at = now()
                 where connection_id = :c and feature_code = :f and operation = :op
                """).param("lvl", level).param("ver", verification).param("ref", u.evidenceRef()).param("file", u.evidenceFileId())
                .param("note", u.note()).param("verified", verified).param("by", a.memberId())
                .param("c", connectionId).param("f", feature).param("op", operation).update();
        CapabilityView after = load(c).stream().filter(x -> x.featureCode().equals(feature) && x.operation().equals(operation)).findFirst().orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "INTEGRATION_CAPABILITY_UPDATED", "INTEGRATION_CONNECTION", connectionId)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(c.businessEntityId(), c.channelAccountId())
                .before(Map.of("feature", feature, "operation", operation, "support_level", before.supportLevel(),
                        "account_verification", before.accountVerification()))
                .after(Map.of("feature", feature, "operation", operation, "support_level", after.supportLevel(),
                        "account_verification", after.accountVerification(), "evidence_ref", String.valueOf(after.evidenceRef())))
                .build());
        return after;
    }

    /** 서버측 실행 가드. 실행 불가 사유를 사용자 안내와 함께 돌려준다. */
    public void require(UUID connectionId, String feature, String operation) {
        ConnectionService.ConnectionView c = connections.get(connectionId);
        CapabilityView cap = load(c).stream().filter(x -> x.featureCode().equals(feature) && x.operation().equals(operation))
                .findFirst().orElseThrow(() -> Errors.unprocessable("CAPABILITY_NOT_AVAILABLE",
                        "이 연결에서 지원하지 않는 기능입니다. 수동 또는 파일 처리 절차를 이용하세요.", Map.of("feature", feature)));
        if (!cap.executable()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("feature", feature);
            details.put("operation", operation);
            details.put("blocked_reasons", cap.blockedReasons());
            throw Errors.unprocessable("CAPABILITY_NOT_AVAILABLE",
                    "이 연결에서 아직 사용할 수 없는 기능입니다. 지원 기능 확인 상태를 확인하거나 수동·파일 처리 절차를 이용하세요.", details);
        }
    }

    private List<CapabilityView> load(ConnectionService.ConnectionView c) {
        Set<FeatureOp> implemented = connections.implemented(c.connectorCode());
        return jdbc.sql("""
                select ic.*, f.name_ko from integration_capability ic join capability_feature f on f.code = ic.feature_code
                 where ic.connection_id = :c order by f.sort_order, ic.operation
                """).param("c", c.id()).query((rs, i) -> {
            String feature = rs.getString("feature_code");
            String op = rs.getString("operation");
            String level = rs.getString("support_level");
            String verification = rs.getString("account_verification");
            boolean impl = implemented.contains(new FeatureOp(feature, op));
            List<String> reasons = new ArrayList<>();
            if (!impl) {
                reasons.add("CONNECTOR_NOT_IMPLEMENTED");
            }
            if (!EXECUTABLE_LEVELS.contains(level)) {
                reasons.add("SUPPORT_LEVEL_" + level);
            }
            if (!"VERIFIED".equals(verification)) {
                reasons.add("ACCOUNT_" + verification);
            }
            if (!"ACTIVE".equals(c.status())) {
                reasons.add("CONNECTION_" + c.status());
            }
            return new CapabilityView(c.id(), feature, rs.getString("name_ko"), op, level, verification, impl, reasons.isEmpty(),
                    List.copyOf(reasons), rs.getString("evidence_ref"), Db.uuid(rs, "evidence_file_id"), rs.getString("note"),
                    Db.instant(rs, "verified_at"), rs.getInt("capability_version"));
        }).list();
    }
}
