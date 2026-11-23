package com.commerceos.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** M7 운영 준비: 운영 현황(적체·실패·연동·보안 이벤트 판정, 범위·권한), 보관기간 정리(만료 내보내기·멱등키). */
class OpsReadinessTest extends IntegrationTest {
    @Autowired
    RetentionService retention;

    @Test
    void opsStatus_flagsFailedOutboxStuckJobsAndAuthExpiredConnection_permissionRequired() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        UUID be = fx.businessEntity(o, "운영상사");
        UUID ca = fx.channelAccount(o, be, "NAVER_SMARTSTORE", "운영 스토어");
        JsonNode ok = o.get("/api/v1/ops/status").data();
        assertThat(ok.get("overall").asString()).isIn("OK", "WARN");
        jdbc.sql("""
                insert into outbox_event (id, workspace_id, aggregate_type, aggregate_id, event_type, payload, status, attempts)
                values (gen_random_uuid(), :ws, 'TEST', gen_random_uuid(), 'TestEvent', '{}'::jsonb, 'FAILED', 5)
                """).param("ws", ws.id()).update();
        jdbc.sql("""
                insert into async_job (id, workspace_id, job_type, payload, status, attempts, lease_expires_at, started_at)
                values (gen_random_uuid(), :ws, 'TEST_JOB', '{}'::jsonb, 'RUNNING', 1, now() - interval '1 hour', now() - interval '2 hours')
                """).param("ws", ws.id()).update();
        jdbc.sql("""
                insert into integration_connection (id, workspace_id, target_type, channel_account_id, connector_code, is_mock, display_name, auth_type, status)
                values (gen_random_uuid(), :ws, 'CHANNEL_ACCOUNT', :ca, 'MOCK_CHANNEL', true, '만료 연동', 'API_KEY', 'AUTH_EXPIRED')
                """).param("ws", ws.id()).param("ca", ca).update();
        JsonNode s = o.get("/api/v1/ops/status").data();
        assertThat(s.get("overall").asString()).isEqualTo("CRITICAL");
        List<String> codes = new ArrayList<>();
        s.get("checks").forEach(c -> codes.add(c.get("code").asString()));
        assertThat(codes).contains("OUTBOX_FAILED", "JOB_STUCK", "CONNECTION_AUTH_EXPIRED");
        assertThat(s.get("outbox").get("failed").asInt()).isEqualTo(1);
        assertThat(s.get("jobs").get("stuck").asInt()).isEqualTo(1);
        assertThat(s.get("connections").toString()).contains("AUTH_EXPIRED");
        assertThat(s.get("order_freshness")).hasSize(1);
        assertThat(fx.member(ws, "CS_AGENT").get("/api/v1/ops/status").status()).isEqualTo(403);
        assertThat(fx.workspace().owner().get("/api/v1/ops/status").data().get("outbox").get("failed").asInt()).isZero();
    }

    @Test
    void retention_purgesExpiredExportFilesAndIdempotencyRecords_keepsFresh() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        Api.Res ex = o.postWithKey("/api/v1/exports", Map.of("export_type", "BUSINESS_ENTITIES"), "retention-export-01");
        assertThat(ex.status()).as(ex.body().toString()).isIn(200, 201, 202);
        drainAsync();
        UUID file = jdbc.sql("select id from stored_file where workspace_id = :ws and purpose = 'EXPORT'").param("ws", ws.id()).query(UUID.class).single();
        assertThat(retention.purge(Instant.now()).exportFiles()).isZero();
        RetentionService.Result r = retention.purge(Instant.now().plusSeconds(3 * 24 * 3600));
        assertThat(r.exportFiles()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("select count(*) from stored_file where id = :id").param("id", file).query(Integer.class).single()).isZero();
        assertThat(r.idempotencyRecords()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("select count(*) from audit_log where workspace_id = :ws and action = 'RETENTION_PURGED'").param("ws", ws.id()).query(Integer.class).single())
                .isEqualTo(1);
    }
}
