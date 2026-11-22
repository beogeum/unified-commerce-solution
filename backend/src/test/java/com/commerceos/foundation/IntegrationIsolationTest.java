package com.commerceos.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.integration.mock.MockScenario;
import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** T21-F(인증 만료 격리), T39-F(미지원·미검증 기능 서버 거부), 지원 기능 기본값(docs/06, DEC-F08/F09). */
class IntegrationIsolationTest extends IntegrationTest {

    static UUID connection(Api api, UUID channelAccountId, String name, String scenario) {
        Api.Res r = api.post("/api/v1/integrations/connections", Map.of("target_type", "CHANNEL_ACCOUNT",
                "target_id", channelAccountId.toString(), "connector_code", "mock-channel", "display_name", name,
                "auth_type", "API_KEY", "credential", Map.of("api_key", "k-" + UUID.randomUUID(), "mock_scenario", scenario)));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.id();
    }

    @Test
    void T21F_authExpiryOnOneAccount_isolatedFromOthers_andGroupedIntoOneWorkItem() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "A");
        UUID acc1 = fx.channelAccount(ws.owner(), a, "NAVER_SMARTSTORE", "네이버1");
        UUID acc2 = fx.channelAccount(ws.owner(), a, "NAVER_SMARTSTORE", "네이버2");
        UUID expired = connection(ws.owner(), acc1, "네이버1 연동", "AUTH_EXPIRED");
        UUID healthy = connection(ws.owner(), acc2, "네이버2 연동", "OK");
        assertThat(ws.owner().get("/api/v1/integrations/connections/" + healthy).data().get("status").asString()).isEqualTo("PENDING_SETUP");

        String job1 = ws.owner().post("/api/v1/integrations/" + expired + "/sync-jobs", Map.of()).data().get("job_id").asString();
        String job2 = ws.owner().post("/api/v1/integrations/" + healthy + "/sync-jobs", Map.of()).data().get("job_id").asString();
        drainAsync();

        assertThat(ws.owner().get("/api/v1/integrations/connections/" + expired).data().get("status").asString()).isEqualTo("AUTH_EXPIRED");
        assertThat(ws.owner().get("/api/v1/integrations/connections/" + healthy).data().get("status").asString()).isEqualTo("ACTIVE");
        JsonNode j1 = ws.owner().get("/api/v1/jobs/" + job1).data();
        assertThat(j1.get("status").asString()).isEqualTo("FAILED");
        assertThat(j1.get("error_code").asString()).isEqualTo("AUTH_EXPIRED");
        assertThat(j1.get("attempts").asInt()).isEqualTo(1); // 인증 만료는 재시도하지 않음
        assertThat(ws.owner().get("/api/v1/jobs/" + job2).data().get("status").asString()).isEqualTo("SUCCEEDED");

        JsonNode items = ws.owner().get("/api/v1/work-items?item_type=INTEGRATION_AUTH_EXPIRED").data();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("connection_id").asString()).isEqualTo(expired.toString());
        assertThat(items.get(0).get("channel_account_id").asString()).isEqualTo(acc1.toString());

        // 같은 원인 재발 → 같은 업무의 발생횟수 증가
        ws.owner().post("/api/v1/integrations/" + expired + "/sync-jobs", Map.of());
        drainAsync();
        items = ws.owner().get("/api/v1/work-items?item_type=INTEGRATION_AUTH_EXPIRED").data();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("occurrence_count").asInt()).isEqualTo(2);

        // 인증정보 교체 → 재점검 대기 → 성공 시 ACTIVE
        Api.Res rotated = ws.owner().post("/api/v1/integrations/connections/" + expired + "/credentials",
                Map.of("credential", Map.of("api_key", "new-key", "mock_scenario", "OK")));
        assertThat(rotated.data().get("status").asString()).isEqualTo("PENDING_SETUP");
        ws.owner().post("/api/v1/integrations/" + expired + "/sync-jobs", Map.of());
        drainAsync();
        assertThat(ws.owner().get("/api/v1/integrations/connections/" + expired).data().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("select count(*) from integration_credential where connection_id = :c").param("c", expired)
                .query(Integer.class).single()).isEqualTo(2);

        // 업무 완료 처리 후 재발은 새 업무
        String itemId = items.get(0).get("id").asString();
        int ver = items.get(0).get("version").asInt();
        assertThat(ws.owner().patch("/api/v1/work-items/" + itemId, Map.of("status", "DONE"), ver).status()).isEqualTo(200);
        assertThat(ws.owner().patch("/api/v1/work-items/" + itemId, Map.of("status", "OPEN"), ver + 1).errorCode())
                .isEqualTo("INVALID_STATE_TRANSITION");
        mockControl.set(expired, MockScenario.AUTH_EXPIRED);
        ws.owner().post("/api/v1/integrations/" + expired + "/sync-jobs", Map.of());
        drainAsync();
        mockControl.clear(expired);
        assertThat(ws.owner().get("/api/v1/work-items?item_type=INTEGRATION_AUTH_EXPIRED").data()).hasSize(2);
    }

    @Test
    void transientErrors_retryThenEscalateToErrorAfterThreshold() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "A");
        UUID acc = fx.channelAccount(ws.owner(), a, "ELEVENST", "11번가");
        UUID conn = connection(ws.owner(), acc, "11번가 연동", "TIMEOUT");
        String job = ws.owner().post("/api/v1/integrations/" + conn + "/sync-jobs", Map.of()).data().get("job_id").asString();
        for (int i = 0; i < 3; i++) {
            drainAsync();
            jdbc.sql("update async_job set available_at = now() where id = cast(:id as uuid) and status = 'RETRY_WAIT'").param("id", job).update();
        }
        drainAsync();
        JsonNode j = ws.owner().get("/api/v1/jobs/" + job).data();
        assertThat(j.get("status").asString()).isEqualTo("FAILED");
        assertThat(j.get("attempts").asInt()).isEqualTo(3);
        assertThat(ws.owner().get("/api/v1/integrations/connections/" + conn).data().get("status").asString()).isEqualTo("ERROR");
        assertThat(ws.owner().get("/api/v1/work-items?item_type=INTEGRATION_ERROR").data()).hasSize(1);
    }

    @Test
    void T39F_capabilitiesDefaultUnverified_andServerRejectsUnavailableFeatures() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "A");
        UUID shipper = fx.shipperAccount(ws.owner(), a, "FASSTO", "S-1");
        Api.Res created = ws.owner().post("/api/v1/integrations/connections", Map.of("target_type", "SHIPPER_ACCOUNT",
                "target_id", shipper.toString(), "connector_code", "mock-3pl", "display_name", "3PL 모의", "auth_type", "API_KEY",
                "credential", Map.of("api_key", "k")));
        assertThat(created.status()).isEqualTo(201);
        UUID conn = created.id();
        JsonNode caps = ws.owner().get("/api/v1/integrations/capabilities?connection_id=" + conn).data();
        assertThat(caps.size()).isEqualTo(15 * 3);
        caps.forEach(c -> {
            assertThat(c.get("support_level").asString()).isEqualTo("UNVERIFIED");
            assertThat(c.get("executable").asBoolean()).isFalse();
        });

        // 미검증 기능 요청 → 서버 거부
        Api.Res inv = ws.owner().post("/api/v1/integrations/" + conn + "/sync-jobs", Map.of("job_type", "INVENTORY_FETCH"));
        assertThat(inv.errorCode()).isEqualTo("CAPABILITY_NOT_AVAILABLE");
        // 커넥터가 구현하지 않은 반품검수는 검증 완료로 기록할 수 없음
        Api.Res cannot = ws.owner().put("/api/v1/integrations/connections/" + conn + "/capabilities/RETURN_INSPECTION/READ",
                Map.of("support_level", "OFFICIAL_DOCUMENTED", "account_verification", "VERIFIED", "evidence_ref", "doc"), 1);
        assertThat(cannot.errorCode()).isEqualTo("CAPABILITY_NOT_IMPLEMENTED");
        // 근거 없는 검증 완료 거부
        Api.Res noEvidence = ws.owner().put("/api/v1/integrations/connections/" + conn + "/capabilities/INVENTORY_QUERY/READ",
                Map.of("support_level", "OFFICIAL_DOCUMENTED", "account_verification", "VERIFIED"), 1);
        assertThat(noEvidence.errorCode()).isEqualTo("EVIDENCE_REQUIRED");
        // 근거와 함께 검증 → 연결이 ACTIVE 가 되기 전에는 여전히 실행 불가
        Api.Res verified = ws.owner().put("/api/v1/integrations/connections/" + conn + "/capabilities/INVENTORY_QUERY/READ",
                Map.of("support_level", "OFFICIAL_DOCUMENTED", "account_verification", "VERIFIED",
                        "evidence_ref", "테스트 계정 요청/응답 캡처 #1"), 1);
        assertThat(verified.status()).as(verified.body().toString()).isEqualTo(200);
        assertThat(verified.data().get("executable").asBoolean()).isFalse();
        assertThat(verified.data().get("blocked_reasons").get(0).asString()).isEqualTo("CONNECTION_PENDING_SETUP");
        ws.owner().post("/api/v1/integrations/" + conn + "/sync-jobs", Map.of());
        drainAsync();
        JsonNode inventory = ws.owner().get("/api/v1/integrations/capabilities?connection_id=" + conn).data();
        JsonNode invRead = null;
        for (JsonNode c : inventory) {
            if (c.get("feature_code").asString().equals("INVENTORY_QUERY") && c.get("operation").asString().equals("READ")) {
                invRead = c;
            }
        }
        assertThat(invRead.get("executable").asBoolean()).isTrue();
        // 가드를 통과하면 처리기가 있는 유형(M3 I4 재고 조회)은 접수된다. 처리기가 없는 유형의 거부는 SyncJobService.IMPLEMENTED_DATA_JOBS 가 맡는다
        Api.Res accepted = ws.owner().post("/api/v1/integrations/" + conn + "/sync-jobs", Map.of("job_type", "INVENTORY_FETCH"));
        assertThat(accepted.status()).as(accepted.body().toString()).isEqualTo(202);
    }

    @Test
    void realChannelConnectors_notAvailable_andTargetMismatchRejected() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "A");
        UUID acc = fx.channelAccount(ws.owner(), a, "NAVER_SMARTSTORE", "네이버");
        Api.Res real = ws.owner().post("/api/v1/integrations/connections", Map.of("target_type", "CHANNEL_ACCOUNT",
                "target_id", acc.toString(), "connector_code", "naver-commerce-api", "display_name", "실연동", "auth_type", "OAUTH2"));
        assertThat(real.errorCode()).isEqualTo("CONNECTOR_NOT_AVAILABLE");
        Api.Res mismatch = ws.owner().post("/api/v1/integrations/connections", Map.of("target_type", "CHANNEL_ACCOUNT",
                "target_id", acc.toString(), "connector_code", "mock-3pl", "display_name", "x", "auth_type", "API_KEY"));
        assertThat(mismatch.errorCode()).isEqualTo("CONNECTOR_TARGET_MISMATCH");
        JsonNode view = ws.owner().get("/api/v1/integrations/connections?channel_account_id=" + acc).data();
        assertThat(view).isEmpty();
    }

    @Test
    void pausedConnection_rejectsSync_andResumeRequiresRecheck() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "A");
        UUID acc = fx.channelAccount(ws.owner(), a, "LOTTEON", "롯데ON");
        UUID conn = connection(ws.owner(), acc, "롯데 모의", "OK");
        assertThat(ws.owner().patch("/api/v1/integrations/connections/" + conn, Map.of("status", "PAUSED"), 1).status()).isEqualTo(200);
        assertThat(ws.owner().post("/api/v1/integrations/" + conn + "/sync-jobs", Map.of()).errorCode()).isEqualTo("CONNECTION_PAUSED");
        Api.Res resumed = ws.owner().patch("/api/v1/integrations/connections/" + conn, Map.of("status", "RESUME"), 2);
        assertThat(resumed.data().get("status").asString()).isEqualTo("PENDING_SETUP");
        assertThat(ws.owner().patch("/api/v1/integrations/connections/" + conn, Map.of("status", "ACTIVE"), 3).status()).isEqualTo(422);
    }
}
