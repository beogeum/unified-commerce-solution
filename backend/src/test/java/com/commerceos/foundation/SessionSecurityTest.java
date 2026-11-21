package com.commerceos.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** DLG-011·012: 비밀번호 정책, 비활동 만료, 비밀번호 변경 시 다른 세션 폐기. */
class SessionSecurityTest extends IntegrationTest {

    Api.Res createUser(Fixtures.Ws ws, String email, String password) {
        return ws.owner().post("/api/v1/users", Map.of("email", email, "display_name", "직원", "initial_password", password,
                "role_codes", List.of("VIEWER")));
    }

    @Test
    void passwordPolicy_isEnforcedOnUserCreation() {
        Fixtures.Ws ws = fx.workspace();
        String email = "kim" + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
        for (String weak : List.of("short1A", "onlyletterslong", "abcd-Strong-9x", "Zzzz-Strong-9x", email.split("@")[0] + "-X9!")) {
            Api.Res r = createUser(ws, email, weak);
            assertThat(r.status()).as(weak).isEqualTo(422);
            assertThat(r.errorCode()).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(createUser(ws, email, "Gr8-day!x").status()).isEqualTo(201); // 3종 8자
    }

    @Test
    void idleSession_expires() {
        Fixtures.Ws ws = fx.workspace();
        Api user = fx.member(ws, "VIEWER");
        assertThat(user.get("/api/v1/me").status()).isEqualTo(200);
        jdbc.sql("update auth_session set last_seen_at = now() - interval '3 hours' where member_id = :m").param("m", user.memberId).update();
        assertThat(user.get("/api/v1/me").status()).isEqualTo(401);
    }

    @Test
    void passwordChange_revokesOtherSessions_andRequiresCurrentPassword() {
        Fixtures.Ws ws = fx.workspace();
        String email = "pw-" + UUID.randomUUID() + "@example.test";
        assertThat(createUser(ws, email, Fixtures.PASSWORD).status()).isEqualTo(201);
        Api s1 = fx.login(ws.code(), email, Fixtures.PASSWORD);
        Api s2 = fx.login(ws.code(), email, Fixtures.PASSWORD);

        assertThat(s1.post("/api/v1/auth/password", Map.of("current_password", "Wrong-pass-0192", "new_password", "New-pass-4826"))
                .errorCode()).isEqualTo("CURRENT_PASSWORD_MISMATCH");
        assertThat(s1.post("/api/v1/auth/password", Map.of("current_password", Fixtures.PASSWORD, "new_password", "weakpass"))
                .errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(s1.post("/api/v1/auth/password", Map.of("current_password", Fixtures.PASSWORD, "new_password", Fixtures.PASSWORD))
                .errorCode()).isEqualTo("VALIDATION_FAILED");

        assertThat(s1.post("/api/v1/auth/password", Map.of("current_password", Fixtures.PASSWORD, "new_password", "New-pass-4826"))
                .status()).isEqualTo(204);
        assertThat(s1.get("/api/v1/me").status()).isEqualTo(200);
        assertThat(s2.get("/api/v1/me").status()).isEqualTo(401);

        Api anon = new Api(mvc, mapper, null, null, null);
        assertThat(anon.call(HttpMethod.POST, "/api/v1/auth/sessions",
                Map.of("workspace_code", ws.code(), "email", email, "password", Fixtures.PASSWORD), null, null).status()).isEqualTo(401);
        fx.login(ws.code(), email, "New-pass-4826");
        assertThat(jdbc.sql("select count(*) from audit_log where target_id = :u and action = 'PASSWORD_CHANGED'")
                .param("u", s1.userId).query(Integer.class).single()).isEqualTo(1);
    }
}
