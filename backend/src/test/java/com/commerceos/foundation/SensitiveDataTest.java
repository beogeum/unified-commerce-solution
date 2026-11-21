package com.commerceos.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** T24-F: 조회는 가능하나 민감정보 권한이 없으면 마스킹. 인증정보는 어떤 응답·감사·멱등 기록에도 원문이 남지 않는다. */
class SensitiveDataTest extends IntegrationTest {

    @Test
    void T24F_bankAccountNumberMaskedWithoutPermission_revealedAndAuditedWithPermission() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "사업자A");
        Api.Res created = ws.owner().post("/api/v1/business-entities/" + a + "/bank-accounts",
                Map.of("bank_code", "004", "account_holder", "사업자A", "account_number", "123-456-789012"));
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        assertThat(created.data().get("account_number").asString()).isEqualTo("****9012");
        assertThat(created.data().get("is_default").asBoolean()).isTrue();

        Api viewer = fx.member(ws, "VIEWER");
        JsonNode masked = viewer.get("/api/v1/business-entities/" + a + "/bank-accounts").data().get(0);
        assertThat(masked.get("account_number").asString()).isEqualTo("****9012");
        assertThat(masked.get("account_number_masked").asBoolean()).isTrue();

        Api finance = fx.member(ws, "FINANCE");
        JsonNode revealed = finance.get("/api/v1/business-entities/" + a + "/bank-accounts").data().get(0);
        assertThat(revealed.get("account_number").asString()).isEqualTo("123456789012");
        int views = jdbc.sql("select count(*) from audit_log where actor_member_id = :m and action = 'SENSITIVE_FIELD_VIEWED'")
                .param("m", finance.memberId).query(Integer.class).single();
        assertThat(views).isEqualTo(1);

        // 저장소·감사·멱등 기록 어디에도 원문 없음
        assertThat(jdbc.sql("select count(*) from audit_log where workspace_id = :ws and (before_state::text like '%456789012%' or after_state::text like '%456789012%')")
                .param("ws", ws.id()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from idempotency_record where workspace_id = :ws and response_body::text like '%456789012%'")
                .param("ws", ws.id()).query(Integer.class).single()).isZero();

        // 중복 계좌 거부, 기본계좌 하나만
        Api.Res dup = ws.owner().post("/api/v1/business-entities/" + a + "/bank-accounts",
                Map.of("bank_code", "004", "account_holder", "사업자A", "account_number", "123456789012"));
        assertThat(dup.errorCode()).isEqualTo("BANK_ACCOUNT_DUPLICATE");
        Api.Res second = ws.owner().post("/api/v1/business-entities/" + a + "/bank-accounts",
                Map.of("bank_code", "088", "account_holder", "사업자A", "account_number", "110-222-333444", "is_default", true));
        assertThat(second.data().get("is_default").asBoolean()).isTrue();
        assertThat(jdbc.sql("select count(*) from bank_account where business_entity_id = :be and is_default").param("be", a)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void bankAccountManage_requiresPermission() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "사업자A");
        Api ops = fx.member(ws, "OPS_ADMIN");
        Api.Res r = ops.post("/api/v1/business-entities/" + a + "/bank-accounts",
                Map.of("bank_code", "004", "account_holder", "A", "account_number", "123456789"));
        assertThat(r.status()).isEqualTo(403);
    }

    @Test
    void integrationCredential_neverReturned_neverInAuditOrIdempotencyRecords() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "사업자A");
        UUID acc = fx.channelAccount(ws.owner(), a, "NAVER_SMARTSTORE", "네이버");
        String secret = "super-secret-" + UUID.randomUUID();
        Api.Res created = ws.owner().post("/api/v1/integrations/connections", Map.of("target_type", "CHANNEL_ACCOUNT",
                "target_id", acc.toString(), "connector_code", "mock-channel", "display_name", "네이버 모의", "auth_type", "API_KEY",
                "credential", Map.of("api_key", secret)));
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        assertThat(created.body().toString()).doesNotContain(secret);
        assertThat(created.data().get("has_active_credential").asBoolean()).isTrue();
        assertThat(created.data().get("credential_fingerprint").asString()).startsWith("sha256:");
        assertThat(ws.owner().get("/api/v1/integrations/connections/" + created.id()).body().toString()).doesNotContain(secret);
        assertThat(jdbc.sql("select count(*) from audit_log where workspace_id = :ws and coalesce(after_state::text,'') || coalesce(metadata::text,'') like :s")
                .param("ws", ws.id()).param("s", "%" + secret + "%").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from idempotency_record where workspace_id = :ws and response_body::text like :s")
                .param("ws", ws.id()).param("s", "%" + secret + "%").query(Integer.class).single()).isZero();
        byte[] cipher = jdbc.sql("select secret_cipher from integration_credential where connection_id = :c").param("c", created.id())
                .query(byte[].class).single();
        assertThat(new String(cipher, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(secret);

        // 인증정보 등록 권한 없이 인증정보를 넣으면 거부
        Api ops = fx.member(ws, "OPS_ADMIN");
        Api.Res denied = ops.post("/api/v1/integrations/connections", Map.of("target_type", "CHANNEL_ACCOUNT",
                "target_id", acc.toString(), "connector_code", "mock-channel", "display_name", "x", "auth_type", "API_KEY",
                "credential", Map.of("api_key", "k")));
        assertThat(denied.status()).isEqualTo(403);
    }
}
