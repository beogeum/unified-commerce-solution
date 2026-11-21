package com.commerceos.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.identity.workspace.WorkspaceProvisioningService;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** 가상 사업자 A/B·계정·사용자 테스트 데이터(docs/09: 가상 데이터만 사용). */
@TestComponent
public class Fixtures {
    public static final String PASSWORD = "Passw0rd-test-2026";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    WorkspaceProvisioningService provisioning;

    public record Ws(UUID id, String code, Api owner) {
    }

    public Ws workspace() {
        String code = "ws-" + UUID.randomUUID().toString().substring(0, 12);
        String email = "owner-" + UUID.randomUUID() + "@example.test";
        WorkspaceProvisioningService.Provisioned p = provisioning.provision(code, "테스트 운영그룹", email, "최고관리자", PASSWORD);
        return new Ws(p.workspaceId(), code, login(code, email, PASSWORD));
    }

    public Api login(String code, String email, String password) {
        Api anon = new Api(mvc, mapper, null, null, null);
        Api.Res r = anon.call(HttpMethod.POST, "/api/v1/auth/sessions",
                Map.of("workspace_code", code, "email", email, "password", password), null, null);
        assertThat(r.status()).as("login %s: %s", email, r.body()).isEqualTo(201);
        return new Api(mvc, mapper, r.data().get("access_token").asString(), UUID.fromString(r.data().get("user_id").asString()),
                UUID.fromString(r.data().get("member_id").asString()));
    }

    public Api member(Ws ws, String... roles) {
        String email = "user-" + UUID.randomUUID() + "@example.test";
        Api.Res r = ws.owner().post("/api/v1/users", Map.of("email", email, "display_name", "직원", "initial_password", PASSWORD,
                "role_codes", List.of(roles)));
        assertThat(r.status()).as("create member: %s", r.body()).isEqualTo(201);
        return login(ws.code(), email, PASSWORD);
    }

    public UUID businessEntity(Api api, String name) {
        Api.Res r = api.post("/api/v1/business-entities", Map.of("display_name", name, "legal_name", name + " 주식회사",
                "legal_type", "CORPORATION", "business_registration_no", validBrn(), "tax_type", "GENERAL"));
        assertThat(r.status()).as("create BE: %s", r.body()).isEqualTo(201);
        return r.id();
    }

    public UUID channelAccount(Api api, UUID be, String channel, String name) {
        return channelAccount(api, be, channel, name, "ext-" + UUID.randomUUID().toString().substring(0, 8), null);
    }

    public UUID channelAccount(Api api, UUID be, String channel, String name, String extKey, Map<String, Object> defaultUnit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("business_entity_id", be.toString());
        body.put("sales_channel_code", channel);
        body.put("display_name", name);
        body.put("external_account_key", extKey);
        if (defaultUnit != null) {
            body.put("default_sales_unit", defaultUnit);
        }
        Api.Res r = api.post("/api/v1/channel-accounts", body);
        assertThat(r.status()).as("create channel account: %s", r.body()).isEqualTo(201);
        return r.id();
    }

    public UUID shipperAccount(Api api, UUID be, String provider, String extKey) {
        Api.Res r = api.post("/api/v1/shipper-accounts", Map.of("business_entity_id", be.toString(), "logistics_provider_code", provider,
                "display_name", "화주 " + extKey, "external_shipper_key", extKey));
        assertThat(r.status()).as("create shipper: %s", r.body()).isEqualTo(201);
        return r.id();
    }

    /** 접근정책 전체 교체. scopes 는 (dimension, kind, mode, entries...) 맵 목록. */
    public Api.Res setPolicy(Api admin, Api target, List<Map<String, Object>> overrides, List<Map<String, Object>> scopes) {
        Api.Res cur = admin.get("/api/v1/users/" + target.userId + "/access-policy");
        assertThat(cur.status()).isEqualTo(200);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("permission_overrides", overrides == null ? List.of() : overrides);
        body.put("scopes", scopes == null ? List.of() : scopes);
        return admin.put("/api/v1/users/" + target.userId + "/access-policy", body, cur.data().get("access_policy_version").asInt());
    }

    public static Map<String, Object> scope(String dimension, String kind, String mode, Object... entries) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < entries.length; i += 2) {
            list.add(Map.of("target_id", entries[i].toString(), "effect", entries[i + 1]));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dimension", dimension);
        m.put("access_kind", kind);
        m.put("mode", mode);
        m.put("auto_include_new", false);
        m.put("entries", list);
        return m;
    }

    public static Map<String, Object> deny(String dimension, UUID target) {
        return scope(dimension, "READ", "ALL", target, "DENY");
    }

    /** 검증번호가 맞는 가상 사업자등록번호. */
    public static String validBrn() {
        int[] w = {1, 3, 7, 1, 3, 7, 1, 3, 5};
        int[] d = new int[10];
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            d[i] = RANDOM.nextInt(10);
            sum += d[i] * w[i];
        }
        sum += (d[8] * 5) / 10;
        d[9] = (10 - (sum % 10)) % 10;
        StringBuilder sb = new StringBuilder();
        for (int x : d) {
            sb.append(x);
        }
        return sb.toString();
    }
}
