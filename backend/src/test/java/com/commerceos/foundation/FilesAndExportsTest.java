package com.commerceos.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** 파일 원본 보존·다운로드 통제, 내보내기 파일 접근(DEC-F14/F15). */
class FilesAndExportsTest extends IntegrationTest {

    Api.Res upload(Api api, String name, byte[] content, UUID be, boolean pii) {
        var req = multipart("/api/v1/files").file(new MockMultipartFile("file", name, "text/csv", content))
                .param("purpose", "SETTLEMENT_SOURCE");
        if (be != null) {
            req.param("business_entity_id", be.toString());
        }
        req.param("contains_pii", String.valueOf(pii));
        return api.perform(req);
    }

    @Test
    void upload_keepsHash_flagsDuplicates_andRejectsDisallowedTypes() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "A");
        byte[] content = "정산일,금액\n2026-11-01,1000\n".getBytes(StandardCharsets.UTF_8);
        Api.Res first = upload(ws.owner(), "settlement.csv", content, a, false);
        assertThat(first.status()).as(first.body().toString()).isEqualTo(201);
        assertThat(first.data().get("sha256").asString()).hasSize(64);
        assertThat(first.data().get("scan_status").asString()).isEqualTo("SCAN_UNAVAILABLE");
        Api.Res second = upload(ws.owner(), "settlement-again.csv", content, a, false);
        assertThat(second.data().get("duplicate_of").get(0).asString()).isEqualTo(first.data().get("id").asString());
        assertThat(upload(ws.owner(), "run.exe", content, a, false).errorCode()).isEqualTo("FILE_TYPE_NOT_ALLOWED");
        assertThat(ws.owner().download("/api/v1/files/" + first.data().get("id").asString() + "/content")).isEqualTo(content);
        assertThatImmutable(UUID.fromString(first.data().get("id").asString()));
    }

    private void assertThatImmutable(UUID fileId) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("update stored_file set sha256 = repeat('0', 64) where id = :id").param("id", fileId).update())
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    void fileAccess_followsScopeAndPiiPermission() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "A");
        UUID b = fx.businessEntity(ws.owner(), "B");
        String fileB = upload(ws.owner(), "b.csv", "x".getBytes(), b, false).data().get("id").asString();
        String piiFile = upload(ws.owner(), "pii.csv", "name,phone".getBytes(), a, true).data().get("id").asString();
        Api finance = fx.member(ws, "FINANCE");
        assertThat(finance.get("/api/v1/files/" + fileB).status()).isEqualTo(200);
        assertThat(finance.get("/api/v1/files/" + piiFile).status()).isEqualTo(403);
        fx.setPolicy(ws.owner(), finance, null, java.util.List.of(Fixtures.deny("BUSINESS_ENTITY", b)));
        assertThat(finance.get("/api/v1/files/" + fileB).status()).isEqualTo(404);
        Api viewer = fx.member(ws, "VIEWER");
        assertThat(viewer.get("/api/v1/files/" + fileB).status()).isEqualTo(403);
    }

    @Test
    void exportFile_onlyRequesterCanDownload_csvInjectionNeutralized_andExpires() {
        Fixtures.Ws ws = fx.workspace();
        fx.businessEntity(ws.owner(), "=HYPERLINK(\"http://evil\")");
        Api.Res accepted = ws.owner().post("/api/v1/exports", Map.of("export_type", "BUSINESS_ENTITIES"));
        drainAsync();
        JsonNode job = ws.owner().get("/api/v1/jobs/" + accepted.data().get("job_id").asString()).data();
        String fileId = job.get("result").get("file_id").asString();
        String csv = new String(ws.owner().download("/api/v1/files/" + fileId + "/content"), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿사업자ID").contains("\"'=HYPERLINK(\"\"http://evil\"\")\"");

        Api otherAdmin = fx.member(ws, "SUPER_ADMIN");
        assertThat(otherAdmin.get("/api/v1/files/" + fileId).status()).isEqualTo(404);
        assertThat(otherAdmin.get("/api/v1/jobs/" + accepted.data().get("job_id").asString()).status()).isEqualTo(404);

        int downloads = jdbc.sql("select count(*) from audit_log where workspace_id = :ws and action = 'FILE_DOWNLOADED'")
                .param("ws", ws.id()).query(Integer.class).single();
        assertThat(downloads).isEqualTo(1);
        jdbc.sql("alter table stored_file disable trigger stored_file_immutable").update();
        try {
            jdbc.sql("update stored_file set expires_at = now() - interval '1 minute' where id = cast(:id as uuid)").param("id", fileId).update();
        } finally {
            jdbc.sql("alter table stored_file enable trigger stored_file_immutable").update();
        }
        assertThat(ws.owner().get("/api/v1/files/" + fileId).errorCode()).isEqualTo("FILE_EXPIRED");
    }

    @Test
    void exportRequiresPermission() {
        Fixtures.Ws ws = fx.workspace();
        Api viewer = fx.member(ws, "VIEWER");
        assertThat(viewer.post("/api/v1/exports", Map.of("export_type", "BUSINESS_ENTITIES")).status()).isEqualTo(403);
    }

    @Test
    void savedViews_arePrivatePerUser() {
        Fixtures.Ws ws = fx.workspace();
        Api u1 = fx.member(ws, "VIEWER");
        Api u2 = fx.member(ws, "VIEWER");
        Api.Res created = u1.post("/api/v1/saved-views", Map.of("screen_key", "channel-accounts", "name", "네이버만",
                "filters", Map.of("sales_channel_code", "NAVER_SMARTSTORE"), "is_default", true));
        assertThat(created.status()).isEqualTo(201);
        assertThat(u1.get("/api/v1/saved-views?screen_key=channel-accounts").data()).hasSize(1);
        assertThat(u2.get("/api/v1/saved-views?screen_key=channel-accounts").data()).isEmpty();
        assertThat(u2.patch("/api/v1/saved-views/" + created.id(), Map.of("name", "탈취"), 1).status()).isEqualTo(404);
        assertThat(u2.delete("/api/v1/saved-views/" + created.id()).status()).isEqualTo(404);
        assertThat(u1.delete("/api/v1/saved-views/" + created.id()).status()).isEqualTo(204);
    }
}
