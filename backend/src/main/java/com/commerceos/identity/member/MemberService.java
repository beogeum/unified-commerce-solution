package com.commerceos.identity.member;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.identity.access.PermissionLoader;
import com.commerceos.identity.auth.SessionService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.web.PageQuery;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영그룹 구성원(사용자) 관리. API 경로의 {id} 는 전역 user_id 이며 항상 현재 운영그룹 소속으로 한정된다. */
@Service
public class MemberService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final PasswordEncoder encoder;
    private final PermissionLoader permissions;
    private final MemberGuards guards;
    private final SessionService sessions;

    public MemberService(JdbcClient jdbc, AccessGuard guard, AuditService audit, PasswordEncoder encoder,
                         PermissionLoader permissions, MemberGuards guards, SessionService sessions) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.encoder = encoder;
        this.permissions = permissions;
        this.guards = guards;
        this.sessions = sessions;
    }

    public record MemberView(UUID userId, UUID memberId, String email, String displayName, String status,
                             List<String> roleCodes, int version, int accessPolicyVersion, Instant createdAt) {
    }

    public record CreateMember(String email, String displayName, String initialPassword, List<String> roleCodes) {
    }

    public record UpdateMember(String status, List<String> roleCodes) {
    }

    public List<MemberView> list(PageQuery page, String status) {
        CurrentActor a = guard.actor();
        requireMemberRead(a);
        return jdbc.sql("""
                select m.id as member_id, m.user_id, u.email, u.display_name, m.status, m.version, m.access_policy_version, m.created_at
                  from workspace_member m join user_account u on u.id = m.user_id
                 where m.workspace_id = :ws and (cast(:status as text) is null or m.status = :status)
                   and (cast(:after as uuid) is null or m.id < :after)
                 order by m.id desc limit :limit
                """).param("ws", a.workspaceId()).param("status", status).param("after", page.after()).param("limit", page.fetchSize())
                .query((rs, i) -> view(rs.getObject("member_id", UUID.class), rs)).list();
    }

    public MemberView get(UUID userId) {
        CurrentActor a = guard.actor();
        if (!a.userId().equals(userId)) {
            requireMemberRead(a);
        }
        return find(a.workspaceId(), userId).orElseThrow(() -> Errors.notFound("USER"));
    }

    @Transactional
    public MemberView create(CreateMember req) {
        CurrentActor a = guard.actor();
        guard.requirePermission(Perm.ACCESS_MANAGE);
        String email = req.email().trim().toLowerCase();
        Set<UUID> roleIds = resolveRoles(a, req.roleCodes());
        Optional<UUID> existingUser = jdbc.sql("select id from user_account where email = :e").param("e", email).query(UUID.class).optional();
        UUID userId = existingUser.orElseGet(() -> {
            PasswordPolicy.validate(req.initialPassword(), req.email());
            UUID id = Ids.newId();
            jdbc.sql("insert into user_account (id, email, display_name, password_hash) values (:id, :e, :n, :h)")
                    .param("id", id).param("e", email).param("n", req.displayName().trim())
                    .param("h", encoder.encode(req.initialPassword())).update();
            return id;
        });
        // 다른 운영그룹에 이미 있는 사용자라면 비밀번호·이름은 바꾸지 않는다(타 테넌트 계정 변경 금지).
        if (jdbc.sql("select count(*) from workspace_member where workspace_id = :ws and user_id = :u")
                .param("ws", a.workspaceId()).param("u", userId).query(Integer.class).single() > 0) {
            throw Errors.conflict("MEMBER_EXISTS", "이미 이 운영그룹에 등록된 사용자입니다.");
        }
        UUID memberId = Ids.newId();
        jdbc.sql("insert into workspace_member (id, workspace_id, user_id) values (:id, :ws, :u)")
                .param("id", memberId).param("ws", a.workspaceId()).param("u", userId).update();
        for (UUID r : roleIds) {
            jdbc.sql("insert into member_role (workspace_id, member_id, role_id, granted_by) values (:ws, :m, :r, :by)")
                    .param("ws", a.workspaceId()).param("m", memberId).param("r", r).param("by", a.memberId()).update();
        }
        MemberView v = find(a.workspaceId(), userId).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "MEMBER_ADDED", "USER", userId)
                .actor(ActorType.USER, a.memberId(), a.displayName())
                .after(Map.of("email", maskEmail(email), "roles", v.roleCodes(), "existing_user", existingUser.isPresent())).build());
        return v;
    }

    @Transactional
    public MemberView update(UUID userId, int expectedVersion, UpdateMember req) {
        CurrentActor a = guard.actor();
        guard.requirePermission(Perm.ACCESS_MANAGE);
        MemberView before = jdbc.sql("""
                select m.id as member_id, m.user_id, u.email, u.display_name, m.status, m.version, m.access_policy_version, m.created_at
                  from workspace_member m join user_account u on u.id = m.user_id
                 where m.workspace_id = :ws and m.user_id = :u for update of m
                """).param("ws", a.workspaceId()).param("u", userId)
                .query((rs, i) -> view(rs.getObject("member_id", UUID.class), rs)).optional()
                .orElseThrow(() -> Errors.notFound("USER"));
        if (before.version() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, before.version());
        }
        if (req.status() != null) {
            if (!Set.of("ACTIVE", "SUSPENDED", "REMOVED").contains(req.status())) {
                throw Errors.validation("status", "status 는 ACTIVE, SUSPENDED, REMOVED 중 하나여야 합니다.");
            }
            jdbc.sql("update workspace_member set status = :s where id = :m").param("s", req.status()).param("m", before.memberId()).update();
        }
        if (req.roleCodes() != null) {
            Set<UUID> roleIds = resolveRoles(a, req.roleCodes());
            // 회수하는 역할도 본인이 가진 권한 범위 안에서만 변경 가능
            resolveRoles(a, before.roleCodes());
            jdbc.sql("delete from member_role where member_id = :m").param("m", before.memberId()).update();
            for (UUID r : roleIds) {
                jdbc.sql("insert into member_role (workspace_id, member_id, role_id, granted_by) values (:ws, :m, :r, :by)")
                        .param("ws", a.workspaceId()).param("m", before.memberId()).param("r", r).param("by", a.memberId()).update();
            }
        }
        jdbc.sql("""
                update workspace_member set version = version + 1, access_policy_version = access_policy_version + 1, updated_at = now()
                 where id = :m
                """).param("m", before.memberId()).update();
        guards.requireActiveSuperAdminRemains(a.workspaceId());
        if (req.status() != null && !"ACTIVE".equals(req.status())) {
            sessions.revokeAll(a.workspaceId(), before.memberId());
        }
        MemberView after = find(a.workspaceId(), userId).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "MEMBER_UPDATED", "USER", userId)
                .actor(ActorType.USER, a.memberId(), a.displayName())
                .before(Map.of("status", before.status(), "roles", before.roleCodes()))
                .after(Map.of("status", after.status(), "roles", after.roleCodes())).build());
        return after;
    }

    /** 권한 상승 방지(DEC-F04): 최고관리자가 아니면 자신이 가진 권한만으로 구성된 역할만 부여·회수할 수 있다. */
    private Set<UUID> resolveRoles(CurrentActor a, List<String> codes) {
        Set<UUID> ids = new HashSet<>();
        if (codes == null) {
            return ids;
        }
        for (String code : codes) {
            UUID roleId = jdbc.sql("select id from role where workspace_id = :ws and code = :c")
                    .param("ws", a.workspaceId()).param("c", code).query(UUID.class).optional()
                    .orElseThrow(() -> Errors.validation("role_codes", "존재하지 않는 역할입니다: " + code));
            if (!a.isSuperAdmin()) {
                List<String> rolePerms = jdbc.sql("select permission_code from role_permission where role_id = :r")
                        .param("r", roleId).query(String.class).list();
                if (!a.permissions().containsAll(rolePerms)) {
                    throw Errors.unprocessable("PRIVILEGE_ESCALATION",
                            "본인에게 없는 권한이 포함된 역할은 부여하거나 회수할 수 없습니다.", Map.of("role_code", code));
                }
            }
            ids.add(roleId);
        }
        return ids;
    }

    private void requireMemberRead(CurrentActor a) {
        if (!a.has(Perm.MEMBER_READ) && !a.has(Perm.ACCESS_MANAGE)) {
            guard.requirePermission(Perm.MEMBER_READ);
        }
    }

    Optional<MemberView> find(UUID workspaceId, UUID userId) {
        return jdbc.sql("""
                select m.id as member_id, m.user_id, u.email, u.display_name, m.status, m.version, m.access_policy_version, m.created_at
                  from workspace_member m join user_account u on u.id = m.user_id
                 where m.workspace_id = :ws and m.user_id = :u
                """).param("ws", workspaceId).param("u", userId)
                .query((rs, i) -> view(rs.getObject("member_id", UUID.class), rs)).optional();
    }

    private MemberView view(UUID memberId, java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID ws = guard.actor().workspaceId();
        List<String> roles = permissions.roleCodes(ws, memberId).stream().sorted().toList();
        return new MemberView(Db.uuid(rs, "user_id"), memberId, rs.getString("email"), rs.getString("display_name"),
                rs.getString("status"), roles, rs.getInt("version"), rs.getInt("access_policy_version"), Db.instant(rs, "created_at"));
    }

    static String maskEmail(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" + email.substring(Math.max(at, 0)) : email.charAt(0) + "***" + email.substring(at);
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
