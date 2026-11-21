package com.commerceos.foundation;

import static com.commerceos.support.Fixtures.deny;
import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 사용자별 접근 범위(FR-ORG-05, docs/08 §2, docs/09 T22~T25).
 * Foundation 객체(사업자·판매계정·판매단위·연동·업무함·검색·내보내기)로 판정하며, 주문(M4)은 같은 엔진을 재사용한다.
 */
class AccessScopeTest extends IntegrationTest {
    Fixtures.Ws ws;
    UUID a;
    UUID b;
    UUID accA;
    UUID accB;

    @BeforeEach
    void setUp() {
        ws = fx.workspace();
        a = fx.businessEntity(ws.owner(), "사업자A");
        b = fx.businessEntity(ws.owner(), "사업자B");
        accA = fx.channelAccount(ws.owner(), a, "NAVER_SMARTSTORE", "A 네이버");
        accB = fx.channelAccount(ws.owner(), b, "NAVER_SMARTSTORE", "B 네이버 스토어");
    }

    @Test
    void T22F_sameWorkspaceOtherBusiness_readableByDefault() {
        Api staffOfA = fx.member(ws, "ORDER_MANAGER");
        assertThat(OrganizationStructureTest.ids(staffOfA.get("/api/v1/channel-accounts").data()))
                .containsExactlyInAnyOrder(accA.toString(), accB.toString());
        assertThat(staffOfA.get("/api/v1/channel-accounts/" + accB).status()).isEqualTo(200);
        assertThat(staffOfA.get("/api/v1/business-entities/" + b).status()).isEqualTo(200);
        assertThat(staffOfA.get("/api/v1/search?q=B 네이버").data().findValuesAsString("id")).contains(accB.toString());
    }

    @Test
    void T23F_explicitDenyOnB_blocksListDetailSearchFilterExportAndAudits() {
        Api user = fx.member(ws, "OPS_ADMIN");
        assertThat(fx.setPolicy(ws.owner(), user, null, List.of(deny("BUSINESS_ENTITY", b))).status()).isEqualTo(200);

        // 목록: B 및 B 소속 판매계정 제외
        assertThat(OrganizationStructureTest.ids(user.get("/api/v1/business-entities").data())).containsExactly(a.toString());
        assertThat(OrganizationStructureTest.ids(user.get("/api/v1/channel-accounts").data())).containsExactly(accA.toString());
        // 직접 ID 접근: 404 (존재 비노출)
        assertThat(user.get("/api/v1/business-entities/" + b).status()).isEqualTo(404);
        assertThat(user.get("/api/v1/channel-accounts/" + accB).status()).isEqualTo(404);
        assertThat(user.get("/api/v1/channel-accounts/" + accB + "/sales-units").status()).isEqualTo(404);
        // 수정 시도도 404
        assertThat(user.patch("/api/v1/channel-accounts/" + accB, Map.of("display_name", "x"), 1).status()).isEqualTo(404);
        // 명시 필터: 403
        Api.Res filtered = user.get("/api/v1/channel-accounts?business_entity_id=" + b);
        assertThat(filtered.status()).isEqualTo(403);
        assertThat(filtered.errorCode()).isEqualTo("ACCESS_SCOPE_DENIED");
        // 'all' 은 허용 범위 전체
        assertThat(OrganizationStructureTest.ids(user.get("/api/v1/channel-accounts?business_entity_id=all").data()))
                .containsExactly(accA.toString());
        // 검색 제외
        assertThat(user.get("/api/v1/search?q=B 네이버").data().findValuesAsString("id")).doesNotContain(accB.toString());
        assertThat(user.get("/api/v1/search?q=사업자").data().findValuesAsString("id")).doesNotContain(b.toString());
        // 내보내기 제외 + 명시 지정 시 403
        String csv = export(user, "CHANNEL_ACCOUNTS");
        assertThat(csv).contains(accA.toString()).doesNotContain(accB.toString());
        assertThat(user.post("/api/v1/exports", Map.of("export_type", "CHANNEL_ACCOUNTS", "business_entity_ids", List.of(b.toString())))
                .status()).isEqualTo(403);
        // 거부 이벤트 감사 기록(별도 트랜잭션으로 남음)
        int denied = jdbc.sql("select count(*) from audit_log where workspace_id = :ws and actor_member_id = :m and outcome = 'DENIED'")
                .param("ws", ws.id()).param("m", user.memberId).query(Integer.class).single();
        assertThat(denied).isGreaterThanOrEqualTo(5);
    }

    @Test
    void T25F_otherWorkspaceIds_alwaysNotFound_neverListed() {
        Fixtures.Ws other = fx.workspace();
        UUID foreignBe = fx.businessEntity(other.owner(), "타그룹 사업자");
        UUID foreignAcc = fx.channelAccount(other.owner(), foreignBe, "GMARKET", "타그룹 G마켓");
        UUID foreignShipper = fx.shipperAccount(other.owner(), foreignBe, "POOMGO", "X-1");

        Api owner = ws.owner();
        assertThat(owner.get("/api/v1/business-entities/" + foreignBe).status()).isEqualTo(404);
        assertThat(owner.get("/api/v1/channel-accounts/" + foreignAcc).status()).isEqualTo(404);
        assertThat(owner.get("/api/v1/shipper-accounts/" + foreignShipper).status()).isEqualTo(404);
        assertThat(owner.patch("/api/v1/business-entities/" + foreignBe, Map.of("display_name", "탈취"), 1).status()).isEqualTo(404);
        assertThat(owner.post("/api/v1/channel-accounts", Map.of("business_entity_id", foreignBe.toString(),
                "sales_channel_code", "GMARKET", "display_name", "x")).status()).isEqualTo(422);
        assertThat(owner.post("/api/v1/integrations/connections", Map.of("target_type", "CHANNEL_ACCOUNT",
                "target_id", foreignAcc.toString(), "connector_code", "mock-channel", "display_name", "x", "auth_type", "API_KEY"))
                .status()).isEqualTo(422);
        // 타 그룹 사업자를 범위 항목으로 지정할 수도 없다
        Api user = fx.member(ws, "VIEWER");
        assertThat(fx.setPolicy(owner, user, null, List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", foreignBe, "ALLOW")))
                .status()).isEqualTo(422);
        // 목록·검색에 나타나지 않음
        assertThat(owner.get("/api/v1/business-entities").data().findValuesAsString("id")).doesNotContain(foreignBe.toString());
        assertThat(owner.get("/api/v1/search?q=타그룹").data()).isEmpty();
        // 타 그룹 사업자 필터 → 403 (내 범위에 없는 사업자)
        assertThat(owner.get("/api/v1/channel-accounts?business_entity_id=" + foreignBe).status()).isEqualTo(403);
        // 다른 그룹의 토큰으로는 이 그룹의 사용자 정보도 보이지 않음
        assertThat(other.owner().get("/api/v1/users/" + owner.userId).status()).isEqualTo(404);
    }

    @Test
    void selectedScope_onlyAllowedBusinesses_andAutoIncludeNewEntities() {
        Api restricted = fx.member(ws, "VIEWER");
        Api autoIncluded = fx.member(ws, "VIEWER");
        fx.setPolicy(ws.owner(), restricted, null, List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", a, "ALLOW")));
        Map<String, Object> auto = new java.util.LinkedHashMap<>(scope("BUSINESS_ENTITY", "READ", "SELECTED", a, "ALLOW"));
        auto.put("auto_include_new", true);
        fx.setPolicy(ws.owner(), autoIncluded, null, List.of(auto));

        assertThat(OrganizationStructureTest.ids(restricted.get("/api/v1/business-entities").data())).containsExactly(a.toString());
        UUID c = fx.businessEntity(ws.owner(), "사업자C(신규)");
        assertThat(OrganizationStructureTest.ids(restricted.get("/api/v1/business-entities").data())).containsExactly(a.toString());
        assertThat(OrganizationStructureTest.ids(autoIncluded.get("/api/v1/business-entities").data()))
                .containsExactlyInAnyOrder(a.toString(), c.toString());
        // ALL 범위의 일반 사용자는 신규 사업자가 자동 포함
        Api open = fx.member(ws, "VIEWER");
        assertThat(OrganizationStructureTest.ids(open.get("/api/v1/business-entities").data())).hasSize(3);
    }

    @Test
    void readAllowedButWriteRestricted_returns403ForWrites() {
        Api md = fx.member(ws, "OPS_ADMIN");
        fx.setPolicy(ws.owner(), md, null, List.of(scope("BUSINESS_ENTITY", "WRITE", "SELECTED", a, "ALLOW")));
        assertThat(md.get("/api/v1/channel-accounts/" + accB).status()).isEqualTo(200);
        Api.Res w = md.patch("/api/v1/channel-accounts/" + accB, Map.of("display_name", "변경"), 1);
        assertThat(w.status()).isEqualTo(403);
        assertThat(w.errorCode()).isEqualTo("WRITE_SCOPE_DENIED");
        assertThat(md.patch("/api/v1/channel-accounts/" + accA, Map.of("display_name", "변경"), 1).status()).isEqualTo(200);
        Api.Res create = md.post("/api/v1/channel-accounts", Map.of("business_entity_id", b.toString(),
                "sales_channel_code", "AUCTION", "display_name", "옥션"));
        assertThat(create.status()).isEqualTo(403);
    }

    @Test
    void channelAccountDimension_restrictsAccountsWithinAllowedBusiness() {
        UUID accA2 = fx.channelAccount(ws.owner(), a, "COUPANG_MARKETPLACE", "A 쿠팡");
        Api user = fx.member(ws, "VIEWER");
        fx.setPolicy(ws.owner(), user, null, List.of(deny("CHANNEL_ACCOUNT", accA2)));
        assertThat(OrganizationStructureTest.ids(user.get("/api/v1/channel-accounts?business_entity_id=" + a).data()))
                .containsExactly(accA.toString());
        assertThat(user.get("/api/v1/channel-accounts/" + accA2).status()).isEqualTo(404);
        // 사업자 자체는 여전히 조회 가능
        assertThat(user.get("/api/v1/business-entities/" + a).status()).isEqualTo(200);
        Api.Res byFilter = user.get("/api/v1/integrations/connections?channel_account_id=" + accA2);
        assertThat(byFilter.status()).isEqualTo(403);
    }

    @Test
    void exportScopeSeparate_fromReadScope() {
        Api user = fx.member(ws, "ANALYST");
        fx.setPolicy(ws.owner(), user, null, List.of(scope("BUSINESS_ENTITY", "EXPORT", "SELECTED", a, "ALLOW")));
        assertThat(OrganizationStructureTest.ids(user.get("/api/v1/business-entities").data())).hasSize(2);
        String csv = export(user, "BUSINESS_ENTITIES");
        assertThat(csv).contains(a.toString()).doesNotContain(b.toString());
    }

    @Test
    void permissionDenyOverride_beatsRolePermission() {
        Api ops = fx.member(ws, "OPS_ADMIN");
        assertThat(ops.get("/api/v1/business-entities").status()).isEqualTo(200);
        fx.setPolicy(ws.owner(), ops, List.of(Map.of("permission_code", "business_entity.read", "effect", "DENY", "reason", "테스트")), null);
        Api.Res r = ops.get("/api/v1/business-entities");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.errorCode()).isEqualTo("PERMISSION_DENIED");
        List<String> perms = new java.util.ArrayList<>();
        ops.get("/api/v1/me").data().get("permissions").forEach(n -> perms.add(n.asString()));
        assertThat(perms).doesNotContain("business_entity.read").contains("business_entity.manage");
    }

    @Test
    void policyChange_appliesImmediatelyToExistingSession_andExportRechecksAtExecution() {
        Api user = fx.member(ws, "ANALYST");
        Api.Res accepted = user.post("/api/v1/exports", Map.of("export_type", "BUSINESS_ENTITIES"));
        assertThat(accepted.status()).isEqualTo(202);
        // 요청 후 실행 전에 B 를 제한
        fx.setPolicy(ws.owner(), user, null, List.of(deny("BUSINESS_ENTITY", b)));
        drainAsync();
        JsonNode job = user.get("/api/v1/jobs/" + accepted.data().get("job_id").asString()).data();
        String csv = new String(user.download("/api/v1/files/" + job.get("result").get("file_id").asString() + "/content"),
                StandardCharsets.UTF_8);
        assertThat(csv).contains(a.toString()).doesNotContain(b.toString());
        assertThat(user.get("/api/v1/business-entities/" + b).status()).isEqualTo(404);
    }

    @Test
    void workItemsAndAuditLogs_followScope() {
        UUID conn = IntegrationIsolationTest.connection(ws.owner(), accB, "MOCK_AUTH_EXPIRED", "AUTH_EXPIRED");
        ws.owner().post("/api/v1/integrations/" + conn + "/sync-jobs", Map.of());
        drainAsync();
        Api viewer = fx.member(ws, "OPS_ADMIN");
        assertThat(viewer.get("/api/v1/work-items").data()).hasSize(1);
        fx.setPolicy(ws.owner(), viewer, null, List.of(deny("BUSINESS_ENTITY", b)));
        assertThat(viewer.get("/api/v1/work-items").data()).isEmpty();
        JsonNode audits = viewer.get("/api/v1/audit-logs?target_type=CHANNEL_ACCOUNT").data();
        assertThat(audits.findValuesAsString("target_id")).doesNotContain(accB.toString()).contains(accA.toString());
    }

    private String export(Api user, String type) {
        Api.Res accepted = user.post("/api/v1/exports", Map.of("export_type", type));
        assertThat(accepted.status()).as(accepted.body().toString()).isEqualTo(202);
        drainAsync();
        JsonNode job = user.get("/api/v1/jobs/" + accepted.data().get("job_id").asString()).data();
        assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("SUCCEEDED");
        return new String(user.download("/api/v1/files/" + job.get("result").get("file_id").asString() + "/content"), StandardCharsets.UTF_8);
    }
}
