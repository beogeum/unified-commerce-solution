package com.commerceos.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.shared.crypto.Totp;
import com.commerceos.identity.workspace.WorkspaceProvisioningService;
import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** DLG-014: 최고관리자·운영관리자 TOTP 2단계 인증 필수, 재사용 방지, 복구 코드, 실패 잠금, 관리자 초기화. */
@TestPropertySource(properties = "commerce.auth.mfa-required-roles=SUPER_ADMIN,OPS_ADMIN")
class MfaTest extends IntegrationTest {
    @Autowired
    WorkspaceProvisioningService provisioning;

    record Owner(String code, String email, Api api) {
    }

    Owner owner() {
        String code = "mfa-" + UUID.randomUUID().toString().substring(0, 12);
        String email = "owner-" + UUID.randomUUID() + "@example.test";
        provisioning.provision(code, "2단계 인증 테스트", email, "최고관리자", Fixtures.PASSWORD);
        return new Owner(code, email, fx.login(code, email, Fixtures.PASSWORD));
    }

    String mfaStatus(Api api) {
        return api.get("/api/v1/auth/mfa").data().get("mfa_status").asString();
    }

    record Enrolled(String secret, String usedCode, long usedStep, List<String> recoveryCodes) {
    }

    Enrolled enroll(Api api) {
        Api.Res start = api.post("/api/v1/auth/mfa/totp/enrollment", Map.of());
        assertThat(start.status()).as(start.body().toString()).isEqualTo(201);
        String secret = start.data().get("secret").asString();
        assertThat(start.data().get("otpauth_uri").asString()).startsWith("otpauth://totp/").contains("secret=" + secret);
        long step = Totp.step(Instant.now());
        String code = Totp.code(secret, step);
        Api.Res ok = api.post("/api/v1/auth/mfa/totp/enrollment/confirm", Map.of("code", code));
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        List<String> codes = ok.data().get("recovery_codes").valueStream().map(n -> n.asString()).toList();
        return new Enrolled(secret, code, step, codes);
    }

    @Test
    void superAdmin_mustEnrollBeforeUsingApis() {
        Owner o = owner();
        assertThat(mfaStatus(o.api())).isEqualTo("ENROLLMENT_REQUIRED");
        Api.Res blocked = o.api().get("/api/v1/business-entities");
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.errorCode()).isEqualTo("MFA_ENROLLMENT_REQUIRED");
        assertThat(o.api().get("/api/v1/me").status()).isEqualTo(200);
        // 비밀번호 변경도 2단계 인증 전에는 불가
        assertThat(o.api().post("/api/v1/auth/password", Map.of("current_password", Fixtures.PASSWORD,
                "new_password", "Another-pass-9182")).errorCode()).isEqualTo("MFA_ENROLLMENT_REQUIRED");

        o.api().post("/api/v1/auth/mfa/totp/enrollment", Map.of());
        Api.Res wrong = o.api().post("/api/v1/auth/mfa/totp/enrollment/confirm", Map.of("code", "000000"));
        assertThat(wrong.errorCode()).isEqualTo("MFA_CODE_INVALID");

        Enrolled e = enroll(o.api());
        assertThat(e.recoveryCodes()).hasSize(10).doesNotHaveDuplicates();
        assertThat(o.api().get("/api/v1/business-entities").status()).isEqualTo(200);
        assertThat(mfaStatus(o.api())).isEqualTo("VERIFIED");
        assertThat(o.api().post("/api/v1/auth/mfa/totp/enrollment", Map.of()).errorCode()).isEqualTo("MFA_ALREADY_ENROLLED");

        // 비밀키·복구 코드 원문은 DB 에 평문으로 남지 않는다
        byte[] stored = jdbc.sql("select secret_cipher from user_mfa_totp where user_id = :u").param("u", o.api().userId)
                .query(byte[].class).single();
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(e.secret());
        assertThat(jdbc.sql("select count(*) from audit_log where workspace_id = (select workspace_id from workspace_member where id = :m) and action = 'MFA_ENROLLED'")
                .param("m", o.api().memberId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void enrolledUser_mustVerifyEveryLogin_andCodesCannotBeReplayed() {
        Owner o = owner();
        Enrolled e = enroll(o.api());

        Api second = fx.login(o.code(), o.email(), Fixtures.PASSWORD);
        assertThat(mfaStatus(second)).isEqualTo("VERIFICATION_REQUIRED");
        assertThat(second.get("/api/v1/business-entities").errorCode()).isEqualTo("MFA_VERIFICATION_REQUIRED");

        // 등록 때 쓴 코드는 다시 쓸 수 없다
        assertThat(second.post("/api/v1/auth/mfa/verify", Map.of("code", e.usedCode())).errorCode()).isEqualTo("MFA_CODE_INVALID");
        Api.Res ok = second.post("/api/v1/auth/mfa/verify", Map.of("code", Totp.code(e.secret(), e.usedStep() + 1)));
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        assertThat(second.get("/api/v1/business-entities").status()).isEqualTo(200);

        // 복구 코드는 한 번만
        Api third = fx.login(o.code(), o.email(), Fixtures.PASSWORD);
        assertThat(third.post("/api/v1/auth/mfa/verify", Map.of("recovery_code", e.recoveryCodes().get(0).toLowerCase())).status()).isEqualTo(200);
        Api fourth = fx.login(o.code(), o.email(), Fixtures.PASSWORD);
        assertThat(fourth.post("/api/v1/auth/mfa/verify", Map.of("recovery_code", e.recoveryCodes().get(0))).errorCode()).isEqualTo("MFA_CODE_INVALID");
        assertThat(fourth.get("/api/v1/auth/mfa").data().get("recovery_codes_remaining").asInt()).isEqualTo(9);
        assertThat(fourth.post("/api/v1/auth/mfa/verify", Map.of("code", "123456", "recovery_code", "AAAAA-BBBBB")).errorCode())
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void roleNotRequiringMfa_isNotBlocked_butGrantingAdminRoleRequiresEnrollmentImmediately() {
        Owner o = owner();
        enroll(o.api());
        Fixtures.Ws ws = new Fixtures.Ws(null, o.code(), o.api());
        Api viewer = fx.member(ws, "VIEWER");
        assertThat(mfaStatus(viewer)).isEqualTo("NOT_REQUIRED");
        assertThat(viewer.get("/api/v1/business-entities").status()).isEqualTo(200);

        Api.Res promote = o.api().patch("/api/v1/users/" + viewer.userId, Map.of("role_codes", List.of("OPS_ADMIN")), 1);
        assertThat(promote.status()).as(promote.body().toString()).isEqualTo(200);
        assertThat(viewer.get("/api/v1/business-entities").errorCode()).isEqualTo("MFA_ENROLLMENT_REQUIRED");
    }

    @Test
    void unverifiedSession_expiresAfterPendingWindow() {
        Owner o = owner();
        jdbc.sql("update auth_session set created_at = now() - interval '11 minutes' where user_id = :u").param("u", o.api().userId).update();
        assertThat(o.api().get("/api/v1/me").status()).isEqualTo(401);
    }

    @Test
    void repeatedWrongCodes_lockAccountAndRevokeSessions() {
        Owner o = owner();
        enroll(o.api());
        Api pending = fx.login(o.code(), o.email(), Fixtures.PASSWORD);
        for (int i = 0; i < 3; i++) { // 테스트 프로필 잠금 기준 3회
            assertThat(pending.post("/api/v1/auth/mfa/verify", Map.of("code", "000000")).errorCode()).isIn("MFA_CODE_INVALID");
        }
        assertThat(pending.get("/api/v1/me").status()).isEqualTo(401);
        assertThat(o.api().get("/api/v1/me").status()).isEqualTo(401);
        Api anon = new Api(mvc, mapper, null, null, null);
        Api.Res login = anon.call(org.springframework.http.HttpMethod.POST, "/api/v1/auth/sessions",
                Map.of("workspace_code", o.code(), "email", o.email(), "password", Fixtures.PASSWORD), null, null);
        assertThat(login.errorCode()).isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    void adminReset_revokesSessions_andIsLimitedToSingleWorkspaceUsers() {
        Owner o = owner();
        enroll(o.api());
        Fixtures.Ws ws = new Fixtures.Ws(null, o.code(), o.api());
        String email = "ops-" + UUID.randomUUID() + "@example.test";
        Api.Res created = o.api().post("/api/v1/users", Map.of("email", email, "display_name", "운영", "initial_password", Fixtures.PASSWORD,
                "role_codes", List.of("OPS_ADMIN")));
        assertThat(created.status()).isEqualTo(201);
        Api ops = fx.login(o.code(), email, Fixtures.PASSWORD);
        enroll(ops);
        assertThat(ops.get("/api/v1/business-entities").status()).isEqualTo(200);

        assertThat(ops.post("/api/v1/users/" + o.api().userId + "/mfa-reset", Map.of()).errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(o.api().post("/api/v1/users/" + o.api().userId + "/mfa-reset", Map.of()).errorCode()).isEqualTo("MFA_RESET_SELF");
        Owner other = owner();
        assertThat(o.api().post("/api/v1/users/" + other.api().userId + "/mfa-reset", Map.of()).status()).isEqualTo(404);

        assertThat(o.api().post("/api/v1/users/" + ops.userId + "/mfa-reset", Map.of()).status()).isEqualTo(204);
        assertThat(ops.get("/api/v1/me").status()).isEqualTo(401);
        Api again = fx.login(o.code(), email, Fixtures.PASSWORD);
        assertThat(mfaStatus(again)).isEqualTo("ENROLLMENT_REQUIRED");

        // 다른 운영그룹에도 소속된 사용자는 초기화할 수 없다
        provisioning.provision("mfa2-" + UUID.randomUUID().toString().substring(0, 10), "두 번째", email, "운영", Fixtures.PASSWORD);
        assertThat(o.api().post("/api/v1/users/" + ops.userId + "/mfa-reset", Map.of()).errorCode()).isEqualTo("MFA_RESET_SHARED_USER");
    }
}
