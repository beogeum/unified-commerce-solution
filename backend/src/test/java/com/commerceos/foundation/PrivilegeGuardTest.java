package com.commerceos.foundation;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** DEC-F04 권한 상승 방지·잠김 방지, 인증. */
class PrivilegeGuardTest extends IntegrationTest {

    @Test
    void nonSuperAdmin_cannotGrantPermissionsOrScopeTheyLack() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "A");
        UUID b = fx.businessEntity(ws.owner(), "B");
        Api manager = fx.member(ws, "OPS_ADMIN");
        // OPS_ADMIN 에 access.manage 를 개별 허용(최고관리자가 부여)
        fx.setPolicy(ws.owner(), manager, List.of(Map.of("permission_code", "access.manage", "effect", "ALLOW")),
                List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", a, "ALLOW")));
        Api target = fx.member(ws, "VIEWER");

        Api.Res grantPii = fx.setPolicy(manager, target, List.of(Map.of("permission_code", "pii.read", "effect", "ALLOW")), null);
        assertThat(grantPii.errorCode()).isEqualTo("PRIVILEGE_ESCALATION");
        Api.Res grantOutOfScope = fx.setPolicy(manager, target, null, List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", b, "ALLOW")));
        assertThat(grantOutOfScope.errorCode()).isEqualTo("PRIVILEGE_ESCALATION");
        Api.Res grantAll = fx.setPolicy(manager, target, null, List.of(scope("BUSINESS_ENTITY", "WRITE", "ALL")));
        assertThat(grantAll.errorCode()).isEqualTo("PRIVILEGE_ESCALATION");
        Api.Res grantRole = manager.patch("/api/v1/users/" + target.userId, Map.of("role_codes", List.of("FINANCE")), 1);
        assertThat(grantRole.errorCode()).isEqualTo("PRIVILEGE_ESCALATION");
        // 허용 범위 안이면 가능, DENY 는 언제나 가능
        assertThat(fx.setPolicy(manager, target, null, List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", a, "ALLOW"))).status())
                .isEqualTo(200);
        assertThat(fx.setPolicy(manager, target, List.of(Map.of("permission_code", "order.read", "effect", "DENY")), null).status())
                .isEqualTo(200);
    }

    @Test
    void lastSuperAdmin_cannotBeRemovedOrDenied() {
        Fixtures.Ws ws = fx.workspace();
        Api owner = ws.owner();
        Api.Res demote = owner.patch("/api/v1/users/" + owner.userId, Map.of("role_codes", List.of("VIEWER")), 1);
        assertThat(demote.errorCode()).isEqualTo("LAST_SUPER_ADMIN");
        Api.Res suspend = owner.patch("/api/v1/users/" + owner.userId, Map.of("status", "SUSPENDED"), 1);
        assertThat(suspend.errorCode()).isEqualTo("LAST_SUPER_ADMIN");
        Api.Res deny = fx.setPolicy(owner, owner, List.of(Map.of("permission_code", "access.manage", "effect", "DENY")), null);
        assertThat(deny.errorCode()).isEqualTo("LAST_SUPER_ADMIN");
        // 두 번째 최고관리자가 있으면 가능
        Api second = fx.member(ws, "SUPER_ADMIN");
        assertThat(second.patch("/api/v1/users/" + owner.userId, Map.of("role_codes", List.of("VIEWER")), 1).status()).isEqualTo(200);
    }

    @Test
    void suspendedMember_sessionsRevokedImmediately() {
        Fixtures.Ws ws = fx.workspace();
        Api user = fx.member(ws, "VIEWER");
        assertThat(user.get("/api/v1/me").status()).isEqualTo(200);
        assertThat(ws.owner().patch("/api/v1/users/" + user.userId, Map.of("status", "SUSPENDED"), 1).status()).isEqualTo(200);
        assertThat(user.get("/api/v1/me").status()).isEqualTo(401);
    }

    @Test
    void login_failuresLockAccount_andErrorsDoNotRevealExistence() {
        Fixtures.Ws ws = fx.workspace();
        String email = "lock-" + UUID.randomUUID() + "@example.test";
        ws.owner().post("/api/v1/users", Map.of("email", email, "display_name", "잠금", "initial_password", Fixtures.PASSWORD,
                "role_codes", List.of("VIEWER")));
        Api anon = new Api(mvc, mapper, null, null, null);
        Api.Res unknown = anon.call(org.springframework.http.HttpMethod.POST, "/api/v1/auth/sessions",
                Map.of("workspace_code", ws.code(), "email", "nobody@example.test", "password", "wrong-pass-123"), null, null);
        Api.Res wrong = anon.call(org.springframework.http.HttpMethod.POST, "/api/v1/auth/sessions",
                Map.of("workspace_code", ws.code(), "email", email, "password", "wrong-pass-123"), null, null);
        assertThat(unknown.errorCode()).isEqualTo("LOGIN_FAILED");
        assertThat(wrong.errorCode()).isEqualTo("LOGIN_FAILED");
        assertThat(unknown.body().path("error").path("message").asString()).isEqualTo(wrong.body().path("error").path("message").asString());
        for (int i = 0; i < 2; i++) {
            anon.call(org.springframework.http.HttpMethod.POST, "/api/v1/auth/sessions",
                    Map.of("workspace_code", ws.code(), "email", email, "password", "wrong-pass-123"), null, null);
        }
        Api.Res locked = anon.call(org.springframework.http.HttpMethod.POST, "/api/v1/auth/sessions",
                Map.of("workspace_code", ws.code(), "email", email, "password", Fixtures.PASSWORD), null, null);
        assertThat(locked.errorCode()).isEqualTo("ACCOUNT_LOCKED");
        // 다른 운영그룹 코드로는 로그인 불가(소속 없음)
        Fixtures.Ws other = fx.workspace();
        Api.Res cross = anon.call(org.springframework.http.HttpMethod.POST, "/api/v1/auth/sessions",
                Map.of("workspace_code", other.code(), "email", email, "password", Fixtures.PASSWORD), null, null);
        assertThat(cross.status()).isEqualTo(401);
    }

    @Test
    void unauthenticated_andLogout() {
        Api anon = new Api(mvc, mapper, null, null, null);
        Api.Res r = anon.get("/api/v1/business-entities");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.errorCode()).isEqualTo("UNAUTHENTICATED");
        assertThat(r.body().get("request_id").asString()).startsWith("req-");
        Fixtures.Ws ws = fx.workspace();
        assertThat(ws.owner().delete("/api/v1/auth/sessions/current").status()).isEqualTo(204);
        assertThat(ws.owner().get("/api/v1/me").status()).isEqualTo(401);
    }
}
