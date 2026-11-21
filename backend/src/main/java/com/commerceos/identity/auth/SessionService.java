package com.commerceos.identity.auth;

import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.PermissionLoader;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로컬 계정 로그인과 불투명 접근 토큰 세션(OQ-023 확정 전 임시 방식).
 * 세션은 (사용자, 운영그룹) 단위로 발급되어 운영그룹 간 토큰 재사용이 불가능하다(REV-011).
 */
@Service
public class SessionService {
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final PermissionLoader permissions;
    private final AuditService audit;
    private final Clock clock;
    private final Duration ttl;
    private final int maxFailures;
    private final Duration lockDuration;
    private final TransactionTemplate tx;
    private final String dummyHash;

    public SessionService(JdbcClient jdbc, PasswordEncoder encoder, PermissionLoader permissions, AuditService audit, Clock clock,
                          PlatformTransactionManager tm,
                          @Value("${commerce.auth.session-ttl:PT12H}") Duration ttl,
                          @Value("${commerce.auth.max-failed-logins:5}") int maxFailures,
                          @Value("${commerce.auth.lock-duration:PT15M}") Duration lockDuration) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.permissions = permissions;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
        this.ttl = ttl;
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
        this.dummyHash = encoder.encode("timing-equalizer");
    }

    public record LoginResult(String accessToken, Instant expiresAt, UUID workspaceId, String workspaceCode, UUID memberId, UUID userId) {
    }

    record UserRow(UUID id, String passwordHash, String status, int failedCount, Instant lockedUntil, String displayName) {
    }

    record MemberRow(UUID workspaceId, UUID memberId) {
    }

    /** 실패 횟수 기록이 롤백되지 않도록 트랜잭션을 직접 나눈다. */
    public LoginResult login(String workspaceCode, String email, String password, String clientIp, String userAgent) {
        Instant now = clock.instant();
        Optional<UserRow> user = jdbc.sql("""
                select id, password_hash, status, failed_login_count, locked_until, display_name from user_account where email = :e
                """).param("e", email)
                .query((rs, i) -> new UserRow(Db.uuid(rs, "id"), rs.getString("password_hash"), rs.getString("status"),
                        rs.getInt("failed_login_count"), Db.instant(rs, "locked_until"), rs.getString("display_name")))
                .optional();
        if (user.isEmpty()) {
            encoder.matches(password, dummyHash); // 사용자 존재 여부가 응답 시간으로 드러나지 않게
            throw Errors.loginFailed();
        }
        UserRow u = user.get();
        if (u.lockedUntil() != null && u.lockedUntil().isAfter(now)) {
            throw Errors.accountLocked();
        }
        Optional<MemberRow> member = jdbc.sql("""
                select m.workspace_id, m.id from workspace_member m join workspace w on w.id = m.workspace_id
                 where w.code = :code and w.status = 'ACTIVE' and m.user_id = :u and m.status = 'ACTIVE'
                """).param("code", workspaceCode).param("u", u.id())
                .query((rs, i) -> new MemberRow(Db.uuid(rs, "workspace_id"), Db.uuid(rs, "id"))).optional();
        boolean passwordOk = encoder.matches(password, u.passwordHash());
        if (!passwordOk || !"ACTIVE".equals(u.status()) || member.isEmpty()) {
            if (!passwordOk) {
                tx.executeWithoutResult(s -> registerFailure(u, now, member.orElse(null)));
            }
            throw Errors.loginFailed();
        }
        MemberRow m = member.get();
        String token = TokenHasher.newToken();
        Instant expiresAt = now.plus(ttl);
        tx.executeWithoutResult(s -> {
            jdbc.sql("update user_account set failed_login_count = 0, locked_until = null where id = :id").param("id", u.id()).update();
            jdbc.sql("""
                    insert into auth_session (id, token_hash, user_id, workspace_id, member_id, expires_at, client_ip, user_agent)
                    values (:id, :h, :u, :ws, :m, :exp, :ip, :ua)
                    """).param("id", Ids.newId()).param("h", TokenHasher.hash(token)).param("u", u.id())
                    .param("ws", m.workspaceId()).param("m", m.memberId()).param("exp", Db.ts(expiresAt))
                    .param("ip", clientIp).param("ua", userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length())))
                    .update();
            audit.record(AuditEntry.builder(m.workspaceId(), "LOGIN_SUCCEEDED", "USER", u.id())
                    .actor(ActorType.USER, m.memberId(), u.displayName()).build());
        });
        return new LoginResult(token, expiresAt, m.workspaceId(), workspaceCode, m.memberId(), u.id());
    }

    private void registerFailure(UserRow u, Instant now, MemberRow member) {
        int failures = u.failedCount() + 1;
        Instant lockUntil = failures >= maxFailures ? now.plus(lockDuration) : null;
        jdbc.sql("update user_account set failed_login_count = :f, locked_until = :l where id = :id")
                .param("f", lockUntil != null ? 0 : failures).param("l", Db.ts(lockUntil)).param("id", u.id()).update();
        if (member != null) {
            audit.record(AuditEntry.builder(member.workspaceId(), lockUntil != null ? "LOGIN_LOCKED" : "LOGIN_FAILED", "USER", u.id())
                    .actor(ActorType.USER, member.memberId(), u.displayName()).outcome("DENIED")
                    .metadata(Map.of("failed_count", failures)).build());
        }
    }

    /** 토큰으로 현재 주체를 만든다. 매 요청 권한을 다시 읽는다. */
    @Transactional
    public Optional<CurrentActor> authenticate(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) {
            return Optional.empty();
        }
        record Row(UUID sessionId, UUID workspaceId, UUID memberId, UUID userId, String name, int policyVersion, Instant lastSeen) {
        }
        Optional<Row> row = jdbc.sql("""
                select s.id, s.workspace_id, s.member_id, s.user_id, u.display_name, m.access_policy_version, s.last_seen_at
                  from auth_session s
                  join workspace_member m on m.id = s.member_id and m.workspace_id = s.workspace_id
                  join user_account u on u.id = s.user_id
                  join workspace w on w.id = s.workspace_id
                 where s.token_hash = :h and s.revoked_at is null and s.expires_at > now()
                   and m.status = 'ACTIVE' and u.status = 'ACTIVE' and w.status = 'ACTIVE'
                """).param("h", TokenHasher.hash(token))
                .query((rs, i) -> new Row(Db.uuid(rs, "id"), Db.uuid(rs, "workspace_id"), Db.uuid(rs, "member_id"),
                        Db.uuid(rs, "user_id"), rs.getString("display_name"), rs.getInt("access_policy_version"),
                        Db.instant(rs, "last_seen_at")))
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Row r = row.get();
        if (r.lastSeen().isBefore(clock.instant().minusSeconds(60))) {
            jdbc.sql("update auth_session set last_seen_at = now() where id = :id").param("id", r.sessionId()).update();
        }
        return Optional.of(new CurrentActor(r.workspaceId(), r.memberId(), r.userId(), r.name(),
                permissions.effectivePermissions(r.workspaceId(), r.memberId()),
                permissions.roleCodes(r.workspaceId(), r.memberId()), r.policyVersion(), r.sessionId()));
    }

    @Transactional
    public void logout(CurrentActor actor) {
        jdbc.sql("update auth_session set revoked_at = now() where id = :id and revoked_at is null").param("id", actor.sessionId()).update();
        audit.record(AuditEntry.builder(actor.workspaceId(), "LOGOUT", "USER", actor.userId())
                .actor(ActorType.USER, actor.memberId(), actor.displayName()).build());
    }

    /** 구성원 중지·제거 시 열린 세션을 모두 폐기한다. */
    @Transactional
    public int revokeAll(UUID workspaceId, UUID memberId) {
        return jdbc.sql("update auth_session set revoked_at = now() where workspace_id = :ws and member_id = :m and revoked_at is null")
                .param("ws", workspaceId).param("m", memberId).update();
    }
}
