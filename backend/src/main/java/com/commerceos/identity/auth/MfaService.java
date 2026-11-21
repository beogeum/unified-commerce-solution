package com.commerceos.identity.auth;

import com.commerceos.identity.access.CurrentActor;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.crypto.SecretCipher;
import com.commerceos.shared.crypto.Totp;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TOTP 2단계 인증 등록·확인·복구 코드·관리자 초기화(DLG-014).
 * 비밀키는 암호화 저장하고 등록 시작 응답에서 한 번만 보여 준다. 복구 코드는 해시만 저장한다.
 * 잘못된 코드는 로그인 실패와 같은 잠금 횟수에 포함한다(무차별 대입 방지).
 */
@Service
public class MfaService {
    static final String ISSUER = "CommerceOS";
    static final int RECOVERY_CODES = 10;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final int maxFailures;
    private final Duration lockDuration;
    private final SecureRandom random = new SecureRandom();

    public MfaService(JdbcClient jdbc, SecretCipher cipher, AuditService audit, Clock clock, PlatformTransactionManager tm,
                      @Value("${commerce.auth.max-failed-logins:5}") int maxFailures,
                      @Value("${commerce.auth.lock-duration:PT15M}") Duration lockDuration) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
    }

    public record Enrollment(String secret, String otpauthUri, int periodSeconds, int digits) {
    }

    public record Confirmed(String mfaStatus, List<String> recoveryCodes) {
    }

    public record Status(String mfaStatus, boolean enrolled, int recoveryCodesRemaining) {
    }

    private record TotpRow(String secret, Instant confirmedAt, long lastUsedStep) {
    }

    public Status status(CurrentActor a) {
        Optional<TotpRow> t = row(a.userId(), false);
        int remaining = jdbc.sql("select count(*) from user_mfa_recovery_code where user_id = :u and used_at is null")
                .param("u", a.userId()).query(Integer.class).single();
        return new Status(a.mfaStatus(), t.isPresent() && t.get().confirmedAt() != null, remaining);
    }

    public Enrollment startEnrollment(CurrentActor a) {
        return tx.execute(s -> {
            Optional<TotpRow> existing = row(a.userId(), true);
            if (existing.isPresent() && existing.get().confirmedAt() != null) {
                throw Errors.conflict("MFA_ALREADY_ENROLLED", "이미 2단계 인증이 등록되어 있습니다.");
            }
            String secret = Totp.newSecret(random);
            SecretCipher.Encrypted enc = cipher.encrypt(secret);
            jdbc.sql("""
                    insert into user_mfa_totp (user_id, secret_cipher, key_id) values (:u, :c, :k)
                    on conflict (user_id) do update set secret_cipher = excluded.secret_cipher, key_id = excluded.key_id,
                           confirmed_at = null, last_used_step = 0, created_at = now()
                    """).param("u", a.userId()).param("c", enc.cipherText()).param("k", enc.keyId()).update();
            String email = jdbc.sql("select email from user_account where id = :u").param("u", a.userId()).query(String.class).single();
            audit.record(AuditEntry.builder(a.workspaceId(), "MFA_ENROLLMENT_STARTED", "USER", a.userId())
                    .actor(ActorType.USER, a.memberId(), a.displayName()).build());
            String label = URLEncoder.encode(ISSUER + ":" + email, StandardCharsets.UTF_8).replace("+", "%20");
            String uri = "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + ISSUER
                    + "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.PERIOD_SECONDS;
            return new Enrollment(secret, uri, Totp.PERIOD_SECONDS, Totp.DIGITS);
        });
    }

    public Confirmed confirmEnrollment(CurrentActor a, String code) {
        Instant now = clock.instant();
        Confirmed result = tx.execute(s -> {
            TotpRow t = row(a.userId(), true).orElseThrow(() ->
                    Errors.unprocessable("MFA_ENROLLMENT_NOT_STARTED", "먼저 2단계 인증 등록을 시작하세요."));
            if (t.confirmedAt() != null) {
                throw Errors.conflict("MFA_ALREADY_ENROLLED", "이미 2단계 인증이 등록되어 있습니다.");
            }
            long step = Totp.verify(t.secret(), code, now, t.lastUsedStep());
            if (step < 0) {
                return null;
            }
            jdbc.sql("update user_mfa_totp set confirmed_at = now(), last_used_step = :s where user_id = :u")
                    .param("s", step).param("u", a.userId()).update();
            List<String> codes = issueRecoveryCodes(a.userId());
            markVerified(a);
            audit.record(AuditEntry.builder(a.workspaceId(), "MFA_ENROLLED", "USER", a.userId())
                    .actor(ActorType.USER, a.memberId(), a.displayName()).build());
            return new Confirmed(MfaPolicy.VERIFIED, codes);
        });
        if (result == null) {
            fail(a);
        }
        return result;
    }

    public String verify(CurrentActor a, String code, String recoveryCode) {
        if ((code == null || code.isBlank()) == (recoveryCode == null || recoveryCode.isBlank())) {
            throw Errors.validation("code", "인증 코드 또는 복구 코드 중 하나만 입력하세요.");
        }
        Instant now = clock.instant();
        Boolean ok = tx.execute(s -> {
            TotpRow t = row(a.userId(), true).filter(r -> r.confirmedAt() != null).orElseThrow(() ->
                    Errors.unprocessable("MFA_NOT_ENROLLED", "2단계 인증이 등록되어 있지 않습니다."));
            if (code != null && !code.isBlank()) {
                long step = Totp.verify(t.secret(), code, now, t.lastUsedStep());
                if (step < 0) {
                    return false;
                }
                jdbc.sql("update user_mfa_totp set last_used_step = :s where user_id = :u").param("s", step).param("u", a.userId()).update();
            } else {
                int used = jdbc.sql("update user_mfa_recovery_code set used_at = now() where user_id = :u and code_hash = :h and used_at is null")
                        .param("u", a.userId()).param("h", hashCode(recoveryCode)).update();
                if (used == 0) {
                    return false;
                }
                int remaining = jdbc.sql("select count(*) from user_mfa_recovery_code where user_id = :u and used_at is null")
                        .param("u", a.userId()).query(Integer.class).single();
                audit.record(AuditEntry.builder(a.workspaceId(), "MFA_RECOVERY_CODE_USED", "USER", a.userId())
                        .actor(ActorType.USER, a.memberId(), a.displayName()).metadata(Map.of("remaining", remaining)).build());
            }
            markVerified(a);
            audit.record(AuditEntry.builder(a.workspaceId(), "MFA_VERIFIED", "USER", a.userId())
                    .actor(ActorType.USER, a.memberId(), a.displayName()).build());
            return true;
        });
        if (!Boolean.TRUE.equals(ok)) {
            fail(a);
        }
        return MfaPolicy.VERIFIED;
    }

    /** 복구 코드를 새로 발급한다(기존 코드는 모두 무효). 확인을 마친 세션에서만 가능. */
    public List<String> regenerateRecoveryCodes(CurrentActor a) {
        if (!MfaPolicy.VERIFIED.equals(a.mfaStatus())) {
            throw Errors.unprocessable("MFA_VERIFICATION_REQUIRED", "2단계 인증을 마친 뒤 다시 시도하세요.");
        }
        return tx.execute(s -> {
            List<String> codes = issueRecoveryCodes(a.userId());
            audit.record(AuditEntry.builder(a.workspaceId(), "MFA_RECOVERY_CODES_REGENERATED", "USER", a.userId())
                    .actor(ActorType.USER, a.memberId(), a.displayName()).build());
            return codes;
        });
    }

    /**
     * 관리자 초기화(기기 분실). 다른 운영그룹에도 소속된 사용자는 그 운영그룹에 영향을 주므로 초기화할 수 없다(DEC-F20과 같은 원칙).
     * 초기화하면 대상 사용자의 모든 세션을 폐기한다.
     */
    public void reset(CurrentActor a, UUID userId) {
        if (!a.has("access.manage")) {
            throw Errors.permissionDenied("access.manage");
        }
        tx.executeWithoutResult(s -> {
            int inWorkspace = jdbc.sql("select count(*) from workspace_member where workspace_id = :ws and user_id = :u")
                    .param("ws", a.workspaceId()).param("u", userId).query(Integer.class).single();
            if (inWorkspace == 0) {
                throw Errors.notFound("user");
            }
            if (userId.equals(a.userId())) {
                throw Errors.unprocessable("MFA_RESET_SELF", "본인의 2단계 인증은 복구 코드로 로그인한 뒤 다시 등록하세요.");
            }
            int workspaces = jdbc.sql("select count(*) from workspace_member where user_id = :u").param("u", userId).query(Integer.class).single();
            if (workspaces > 1) {
                throw Errors.conflict("MFA_RESET_SHARED_USER",
                        "다른 운영그룹에도 소속된 사용자라 여기서 초기화할 수 없습니다. 본인이 복구 코드로 다시 등록해야 합니다.");
            }
            jdbc.sql("delete from user_mfa_recovery_code where user_id = :u").param("u", userId).update();
            int removed = jdbc.sql("delete from user_mfa_totp where user_id = :u").param("u", userId).update();
            int revoked = jdbc.sql("update auth_session set revoked_at = now() where user_id = :u and revoked_at is null")
                    .param("u", userId).update();
            audit.record(AuditEntry.builder(a.workspaceId(), "MFA_RESET", "USER", userId)
                    .actor(ActorType.USER, a.memberId(), a.displayName())
                    .metadata(Map.of("had_totp", removed > 0, "revoked_sessions", revoked)).build());
        });
    }

    private void markVerified(CurrentActor a) {
        jdbc.sql("update auth_session set mfa_verified_at = now() where id = :s").param("s", a.sessionId()).update();
        jdbc.sql("update user_account set failed_login_count = 0, locked_until = null where id = :u").param("u", a.userId()).update();
    }

    /** 실패 기록은 별도 트랜잭션. 잠금에 걸리면 이 사용자의 세션을 모두 폐기한다. */
    private void fail(CurrentActor a) {
        tx.executeWithoutResult(s -> {
            int failures = jdbc.sql("update user_account set failed_login_count = failed_login_count + 1 where id = :u returning failed_login_count")
                    .param("u", a.userId()).query(Integer.class).single();
            boolean locked = failures >= maxFailures;
            if (locked) {
                jdbc.sql("update user_account set failed_login_count = 0, locked_until = :l where id = :u")
                        .param("l", Db.ts(clock.instant().plus(lockDuration))).param("u", a.userId()).update();
                jdbc.sql("update auth_session set revoked_at = now() where user_id = :u and revoked_at is null").param("u", a.userId()).update();
            }
            audit.record(AuditEntry.builder(a.workspaceId(), locked ? "LOGIN_LOCKED" : "MFA_FAILED", "USER", a.userId())
                    .actor(ActorType.USER, a.memberId(), a.displayName()).outcome("DENIED")
                    .metadata(Map.of("failed_count", failures)).build());
        });
        throw Errors.unprocessable("MFA_CODE_INVALID", "인증 코드가 맞지 않습니다. 앱에 표시된 최신 코드를 입력하세요.");
    }

    private Optional<TotpRow> row(UUID userId, boolean forUpdate) {
        return jdbc.sql("select secret_cipher, key_id, confirmed_at, last_used_step from user_mfa_totp where user_id = :u"
                        + (forUpdate ? " for update" : ""))
                .param("u", userId)
                .query((rs, i) -> new TotpRow(cipher.decrypt(rs.getBytes("secret_cipher"), rs.getString("key_id")),
                        Db.instant(rs, "confirmed_at"), rs.getLong("last_used_step")))
                .optional();
    }

    private List<String> issueRecoveryCodes(UUID userId) {
        jdbc.sql("delete from user_mfa_recovery_code where user_id = :u").param("u", userId).update();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 10; j++) {
                if (j == 5) {
                    sb.append('-');
                }
                sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            String c = sb.toString();
            jdbc.sql("insert into user_mfa_recovery_code (id, user_id, code_hash) values (:id, :u, :h)")
                    .param("id", Ids.newId()).param("u", userId).param("h", hashCode(c)).update();
            codes.add(c);
        }
        return codes;
    }

    static byte[] hashCode(String code) {
        String normalized = code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        try {
            return MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
