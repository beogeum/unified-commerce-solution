package com.commerceos.catalog;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M2 C2: 사업자별 SKU 정책·원가 이력·최저판매가 승인(T04, CONF-004, DLG-030·035·043). */
class BusinessSkuPolicyTest extends IntegrationTest {

    record Ctx(Fixtures.Ws ws, UUID beA, UUID beB, UUID sku, UUID gift) {
    }

    Ctx ctx() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        UUID beA = fx.businessEntity(o, "A 코스메틱");
        UUID beB = fx.businessEntity(o, "B 리빙");
        UUID brand = o.post("/api/v1/brands", Map.of("name", "루미에르")).id();
        UUID cat = o.post("/api/v1/categories", Map.of("name", "스킨케어")).id();
        Api.Res p = o.post("/api/v1/products", Map.of("product_code", "AMP", "name", "비타 앰플", "brand_id", brand.toString(),
                "category_id", cat.toString(), "tax_type", "TAXABLE"));
        UUID product = UUID.fromString(p.data().get("product").get("id").asString());
        UUID sku = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "AMP-30", "sku_type", "NORMAL")).id();
        UUID gift = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "GIFT-PCH", "sku_type", "GIFT")).id();
        return new Ctx(ws, beA, beB, sku, gift);
    }

    String path(UUID be, UUID sku) {
        return "/api/v1/business-entities/" + be + "/sku-policies/" + sku;
    }

    Api.Res put(Api api, UUID be, UUID sku, Integer version, Map<String, Object> body) {
        return api.put(path(be, sku), body, version);
    }

    int version(Api api, UUID be, UUID sku) {
        return api.get(path(be, sku)).data().get("version").asInt();
    }

    Api restricted(Fixtures.Ws ws, UUID be, String... roles) {
        Api u = fx.member(ws, roles);
        Map<String, Object> body = new HashMap<>();
        body.put("permission_overrides", List.of());
        body.put("scopes", List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", be, "ALLOW"), scope("BUSINESS_ENTITY", "WRITE", "SELECTED", be, "ALLOW")));
        assertThat(ws.owner().put("/api/v1/users/" + u.userId + "/access-policy", body, 1).status()).isEqualTo(200);
        return u;
    }

    @Test
    void T04_sameSkuForTwoBusinesses_sharesProductButSeparatesPriceAndCost() {
        Ctx c = ctx();
        Api o = c.ws().owner();
        assertThat(put(o, c.beA(), c.sku(), null, Map.of("base_price", 33000, "safety_stock", 5)).status()).isEqualTo(200);
        assertThat(put(o, c.beB(), c.sku(), null, Map.of("base_price", 35000)).status()).isEqualTo(200);
        o.post("/api/v1/business-entities/" + c.beA() + "/sku-costs/" + c.sku(), Map.of("unit_cost", 9000, "reason", "초기 원가"));
        o.post("/api/v1/business-entities/" + c.beB() + "/sku-costs/" + c.sku(), Map.of("unit_cost", 9400, "reason", "초기 원가"));

        JsonNode rows = o.get("/api/v1/skus/" + c.sku() + "/business-policies").data();
        assertThat(rows).hasSize(2);
        Map<String, JsonNode> byName = new HashMap<>();
        rows.forEach(r -> byName.put(r.get("business_entity_name").asString(), r));
        assertThat(byName.get("A 코스메틱").get("base_price").asString()).isEqualTo("33000");
        // 금액은 JSON 문자열(부동소수 변환 방지)
        assertThat(byName.get("A 코스메틱").get("base_price").getNodeType()).isEqualTo(tools.jackson.databind.node.JsonNodeType.STRING);
        assertThat(byName.get("A 코스메틱").get("base_price_supply").asString()).isEqualTo("30000");
        assertThat(byName.get("A 코스메틱").get("base_price_vat").asString()).isEqualTo("3000");
        assertThat(byName.get("A 코스메틱").get("current_cost").asString()).isEqualTo("9000");
        assertThat(byName.get("B 리빙").get("base_price").asString()).isEqualTo("35000");
        assertThat(byName.get("B 리빙").get("current_cost").asString()).isEqualTo("9400");
        // 상품 정보는 하나(공유)
        assertThat(o.get("/api/v1/skus/" + c.sku()).data().get("product_code").asString()).isEqualTo("AMP");

        // A 만 볼 수 있는 사용자: B 정책은 존재 여부도 모름, 비교 보기에도 A 만
        Api a = restricted(c.ws(), c.beA(), "MD");
        assertThat(a.get(path(c.beB(), c.sku())).status()).isEqualTo(404);
        assertThat(a.get("/api/v1/skus/" + c.sku() + "/business-policies").data()).hasSize(1);
        assertThat(put(a, c.beB(), c.sku(), version(o, c.beB(), c.sku()), Map.of("base_price", 1000)).status()).isIn(403, 404);
        assertThat(o.get(path(c.beB(), c.sku())).data().get("base_price").asString()).isEqualTo("35000");
    }

    @Test
    void cost_requiresPermission_isAppendOnly_andCannotBeBackdated() {
        Ctx c = ctx();
        Api o = c.ws().owner();
        String costs = "/api/v1/business-entities/" + c.beA() + "/sku-costs/" + c.sku();
        assertThat(o.post(costs, Map.of("unit_cost", 9050, "reason", "10월 매입")).status()).isEqualTo(201);
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        assertThat(o.post(costs, Map.of("unit_cost", 9000, "reason", "11월 단가", "effective_from", future.toString())).status()).isEqualTo(201);
        JsonNode history = o.get(costs).data();
        assertThat(history).hasSize(2);
        // 미래 적용분은 아직 현재 원가가 아니다
        assertThat(o.get(path(c.beA(), c.sku())).data().get("current_cost").asString()).isEqualTo("9050");
        assertThat(history.get(1).get("current").asBoolean()).isTrue();

        assertThat(o.post(costs, Map.of("unit_cost", 1, "reason", "소급", "effective_from", Instant.now().minus(1, ChronoUnit.DAYS).toString()))
                .errorCode()).isEqualTo("COST_BACKDATE_FORBIDDEN");
        assertThat(o.post(costs, Map.of("unit_cost", 10.5, "reason", "소수")).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(o.post(costs, Map.of("unit_cost", -1, "reason", "음수")).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(o.post(costs, Map.of("unit_cost", 100)).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThatThrownBy(() -> jdbc.sql("update sku_cost set unit_cost = 1 where sku_id = :s").param("s", c.sku()).update())
                .rootCause().hasMessageContaining("append-only");

        // 원가 조회 권한 없는 주문담당: 정책은 보이되 원가는 숨김, 원가 이력·등록 거부
        Api orders = fx.member(c.ws(), "ORDER_MANAGER");
        JsonNode policy = orders.get(path(c.beA(), c.sku())).data();
        assertThat(policy.get("cost_visible").asBoolean()).isFalse();
        assertThat(policy.get("current_cost").isNull()).isTrue();
        assertThat(orders.get(costs).status()).isEqualTo(403);
        // 상품/MD 는 원가 조회는 되지만 등록 권한(cost.manage)은 재무 역할
        Api md = fx.member(c.ws(), "MD");
        assertThat(md.get(costs).status()).isEqualTo(200);
        assertThat(md.post(costs, Map.of("unit_cost", 1, "reason", "x")).errorCode()).isEqualTo("PERMISSION_DENIED");
        Api finance = fx.member(c.ws(), "FINANCE");
        assertThat(finance.post(costs, Map.of("unit_cost", 9100, "reason", "재무 등록", "effective_from",
                Instant.now().plus(2, ChronoUnit.HOURS).toString())).status()).isEqualTo(201);
        // 가상묶음 등 재고 원가 없는 유형은 거부
    }

    @Test
    void minPriceChange_requiresApprovalByAnotherPerson_andProtectsBasePrice() {
        Ctx c = ctx();
        Api md = fx.member(c.ws(), "MD");
        Api ops = fx.member(c.ws(), "OPS_ADMIN");
        Api.Res first = put(md, c.beA(), c.sku(), null, Map.of("base_price", 33000, "min_price", 29700, "min_price_reason", "최저가 보호 설정"));
        assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
        assertThat(first.data().get("min_price").isNull()).isTrue(); // 승인 전에는 바뀌지 않음
        String approvalId = first.data().get("pending_min_price").get("approval_id").asString();
        assertThat(first.data().get("pending_min_price").get("requested_min_price").asString()).isEqualTo("29700");

        // 같은 대상 중복 요청 불가
        assertThat(put(md, c.beA(), c.sku(), version(md, c.beA(), c.sku()), Map.of("min_price", 28000)).errorCode())
                .isEqualTo("APPROVAL_ALREADY_PENDING");
        // 요청자는 승인 권한도 없고, 권한이 있어도 자기 요청은 승인 불가
        assertThat(md.post("/api/v1/approvals/" + approvalId + "/decision", Map.of("decision", "APPROVE")).errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(md.get("/api/v1/approvals?mine=true").data()).hasSize(1);

        Api.Res approved = ops.post("/api/v1/approvals/" + approvalId + "/decision", Map.of("decision", "APPROVE", "note", "확인"));
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);
        assertThat(approved.data().get("status").asString()).isEqualTo("APPROVED");
        assertThat(md.get(path(c.beA(), c.sku())).data().get("min_price").asString()).isEqualTo("29700");
        assertThat(ops.post("/api/v1/approvals/" + approvalId + "/decision", Map.of("decision", "APPROVE")).errorCode()).isEqualTo("APPROVAL_NOT_PENDING");

        // 최저판매가 보호
        Api.Res low = put(md, c.beA(), c.sku(), version(md, c.beA(), c.sku()), Map.of("base_price", 28900));
        assertThat(low.errorCode()).isEqualTo("PRICE_BELOW_MIN_PRICE");
        assertThatThrownBy(() -> jdbc.sql("update business_sku_policy set base_price = 1 where sku_id = :s").param("s", c.sku()).update())
                .rootCause().hasMessageContaining("check");

        // 자기 승인 금지는 최고관리자에게도 적용(DB CHECK 포함)
        Api o = c.ws().owner();
        Api.Res own = put(o, c.beA(), c.sku(), version(o, c.beA(), c.sku()), Map.of("min_price", 30000));
        String ownId = own.data().get("pending_min_price").get("approval_id").asString();
        assertThat(o.post("/api/v1/approvals/" + ownId + "/decision", Map.of("decision", "APPROVE")).errorCode()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        assertThat(ops.post("/api/v1/approvals/" + ownId + "/decision", Map.of("decision", "REJECT", "note", "근거 부족")).data().get("status").asString())
                .isEqualTo("REJECTED");
        assertThat(o.get(path(c.beA(), c.sku())).data().get("min_price").asString()).isEqualTo("29700");
        assertThat(jdbc.sql("select count(*) from audit_log where target_id = :s and action = 'SKU_MIN_PRICE_CHANGED'").param("s", c.sku())
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void staleApproval_isCancelledInsteadOfApplied() {
        Ctx c = ctx();
        Api md = fx.member(c.ws(), "MD");
        Api ops = fx.member(c.ws(), "OPS_ADMIN");
        String id = put(md, c.beA(), c.sku(), null, Map.of("base_price", 33000, "min_price", 30000)).data()
                .get("pending_min_price").get("approval_id").asString();
        // 승인 전에 기준 판매가가 새 최저판매가 아래로 내려감 → 승인하면 보호 규칙 위반
        jdbc.sql("update business_sku_policy set base_price = 29000 where sku_id = :s").param("s", c.sku()).update();
        Api.Res r = ops.post("/api/v1/approvals/" + id + "/decision", Map.of("decision", "APPROVE"));
        assertThat(r.errorCode()).isEqualTo("APPROVAL_STALE");
        assertThat(ops.get("/api/v1/approvals/" + id).data().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(md.get(path(c.beA(), c.sku())).data().get("min_price").isNull()).isTrue();
    }

    @Test
    void policyRules_versionSellabilityAndScope() {
        Ctx c = ctx();
        Api o = c.ws().owner();
        assertThat(put(o, c.beA(), c.gift(), null, Map.of("sell_enabled", true)).errorCode()).isEqualTo("SKU_NOT_SELLABLE");
        JsonNode created = put(o, c.beA(), c.sku(), null, Map.of("base_price", 33000)).data();
        assertThat(created.get("sell_enabled").asBoolean()).isTrue();
        assertThat(put(o, c.beA(), c.sku(), null, Map.of("base_price", 34000)).status()).isEqualTo(428);
        assertThat(put(o, c.beA(), c.sku(), 99, Map.of("base_price", 34000)).errorCode()).isEqualTo("VERSION_CONFLICT");
        assertThat(put(o, c.beA(), c.sku(), created.get("version").asInt(), Map.of("base_price", 33000.5)).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(put(o, c.beA(), c.sku(), created.get("version").asInt(), Map.of("safety_stock", -1)).errorCode()).isEqualTo("VALIDATION_FAILED");

        // 조회만 가능한 사용자는 정책을 바꿀 수 없다
        Api viewer = fx.member(c.ws(), "VIEWER");
        assertThat(put(viewer, c.beA(), c.sku(), created.get("version").asInt(), Map.of("base_price", 1)).errorCode()).isEqualTo("PERMISSION_DENIED");
        // 수정 범위 밖 사업자
        Api md = fx.member(c.ws(), "MD");
        assertThat(c.ws().owner().put("/api/v1/users/" + md.userId + "/access-policy", Map.of("permission_overrides", List.of(),
                "scopes", List.of(scope("BUSINESS_ENTITY", "WRITE", "SELECTED", c.beB(), "ALLOW"))), 1).status()).isEqualTo(200);
        assertThat(put(md, c.beA(), c.sku(), created.get("version").asInt(), Map.of("base_price", 1)).errorCode()).isEqualTo("WRITE_SCOPE_DENIED");
        // 미설정 SKU 는 기본값으로 보인다
        JsonNode unconfigured = o.get(path(c.beB(), c.sku())).data();
        assertThat(unconfigured.get("configured").asBoolean()).isFalse();
        assertThat(o.get("/api/v1/business-entities/" + c.beA() + "/sku-policies").data()).hasSize(1);
        // 다른 운영그룹
        Ctx other = ctx();
        assertThat(other.ws().owner().get(path(c.beA(), c.sku())).status()).isEqualTo(404);
    }
}
