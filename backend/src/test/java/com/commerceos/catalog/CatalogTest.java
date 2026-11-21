package com.commerceos.catalog;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M2 C1: 상품·옵션·SKU(FR-CAT-01·02, docs/03 §3, docs/13 §1). */
class CatalogTest extends IntegrationTest {

    record Ctx(Fixtures.Ws ws, UUID brand, UUID category) {
    }

    Ctx ctx() {
        Fixtures.Ws ws = fx.workspace();
        UUID brand = ws.owner().post("/api/v1/brands", Map.of("name", "루미에르")).id();
        UUID category = ws.owner().post("/api/v1/categories", Map.of("name", "스킨케어")).id();
        return new Ctx(ws, brand, category);
    }

    UUID product(Api api, String code, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("product_code", code, "name", "비타 앰플 " + code));
        body.putAll(extra);
        Api.Res r = api.post("/api/v1/products", body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return UUID.fromString(r.data().get("product").get("id").asString());
    }

    Api.Res sku(Api api, UUID product, String code, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("product_id", product.toString(), "sku_code", code, "sku_type", "NORMAL"));
        body.putAll(extra);
        return api.post("/api/v1/skus", body);
    }

    int version(Api api, String path) {
        JsonNode d = api.get(path).data();
        return (d.has("product") ? d.get("product") : d).get("version").asInt();
    }

    @Test
    void catalogIsSharedMaster_readableRegardlessOfBusinessScope_writableOnlyWithManagePermission() {
        Ctx c = ctx();
        UUID beA = fx.businessEntity(c.ws().owner(), "A 코스메틱");
        fx.businessEntity(c.ws().owner(), "B 리빙");
        UUID p = product(c.ws().owner(), "AMP", Map.of("brand_id", c.brand().toString()));
        sku(c.ws().owner(), p, "AMP-30", Map.of());

        // 사업자 A 만 볼 수 있는 사용자도 공통 상품·SKU 는 조회한다(CONF-004, 사업자별 원가·재고는 C2·M3 에서 분리)
        Api viewer = fx.member(c.ws(), "VIEWER");
        assertThat(c.ws().owner().put("/api/v1/users/" + viewer.userId + "/access-policy", Map.of("permission_overrides", List.of(),
                "scopes", List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", beA, "ALLOW"))), 1).status()).isEqualTo(200);
        assertThat(viewer.get("/api/v1/skus?q=AMP-30").data()).hasSize(1);
        assertThat(viewer.get("/api/v1/products/" + p).status()).isEqualTo(200);
        Api.Res denied = viewer.post("/api/v1/products", Map.of("product_code", "X1", "name", "x"));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.errorCode()).isEqualTo("PERMISSION_DENIED");

        Api md = fx.member(c.ws(), "MD");
        assertThat(md.post("/api/v1/products", Map.of("product_code", "CLF", "name", "클렌징폼")).status()).isEqualTo(201);
        Api worker = fx.member(c.ws(), "WAREHOUSE_WORKER");
        assertThat(worker.get("/api/v1/skus").status()).isEqualTo(200);
    }

    @Test
    void publish_requiresBrandCategoryTaxTypeAndSellableSku_andTransitionsAreGuarded() {
        Ctx c = ctx();
        Api api = c.ws().owner();
        UUID p = product(api, "AMP", Map.of());
        Api.Res r = api.patch("/api/v1/products/" + p, Map.of("status", "ACTIVE"), version(api, "/api/v1/products/" + p));
        assertThat(r.errorCode()).isEqualTo("PUBLISH_REQUIREMENTS_NOT_MET");
        assertThat(r.body().get("error").get("details").get("missing").toString())
                .contains("brand", "category", "tax_type", "active_sellable_sku");

        assertThat(api.patch("/api/v1/products/" + p, Map.of("brand_id", c.brand().toString()), null).status()).isEqualTo(428);
        assertThat(api.patch("/api/v1/products/" + p, Map.of("brand_id", c.brand().toString()), 99).errorCode()).isEqualTo("VERSION_CONFLICT");
        api.patch("/api/v1/products/" + p, Map.of("brand_id", c.brand().toString(), "category_id", c.category().toString(),
                "tax_type", "TAXABLE"), version(api, "/api/v1/products/" + p));
        // 판매 불가 SKU(증정품)만으로는 게시 불가
        assertThat(sku(api, p, "AMP-GIFT", Map.of("sku_type", "GIFT")).status()).isEqualTo(201);
        assertThat(api.patch("/api/v1/products/" + p, Map.of("status", "ACTIVE"), version(api, "/api/v1/products/" + p)).errorCode())
                .isEqualTo("PUBLISH_REQUIREMENTS_NOT_MET");
        UUID s = sku(api, p, "AMP-30", Map.of()).id();
        Api.Res published = api.patch("/api/v1/products/" + p, Map.of("status", "ACTIVE"), version(api, "/api/v1/products/" + p));
        assertThat(published.status()).as(published.body().toString()).isEqualTo(200);
        assertThat(published.data().get("product").get("status").asString()).isEqualTo("ACTIVE");

        assertThat(api.patch("/api/v1/products/" + p, Map.of("status", "DRAFT"), version(api, "/api/v1/products/" + p)).errorCode())
                .isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(api.patch("/api/v1/products/" + p, Map.of("status", "ARCHIVED"), version(api, "/api/v1/products/" + p)).errorCode())
                .isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(api.patch("/api/v1/skus/" + s, Map.of("status", "INACTIVE"), version(api, "/api/v1/skus/" + s)).errorCode())
                .isEqualTo("PRODUCT_NEEDS_ACTIVE_SKU");
        assertThat(api.patch("/api/v1/products/" + p, Map.of("product_code", "OTHER"), version(api, "/api/v1/products/" + p)).errorCode())
                .isEqualTo("PRODUCT_CODE_IMMUTABLE");

        // DB 도 게시 필수값을 강제한다
        assertThatThrownBy(() -> jdbc.sql("update product set brand_id = null where id = :id").param("id", p).update())
                .rootCause().hasMessageContaining("check");
        // 상태 변경 이력
        assertThat(jdbc.sql("select count(*) from audit_log where target_id = :id and action = 'PRODUCT_STATUS_CHANGED'")
                .param("id", p).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void skuCode_uniqueCaseInsensitivePerWorkspace_andIdentityImmutable() {
        Ctx c = ctx();
        Api api = c.ws().owner();
        UUID p = product(api, "AMP", Map.of());
        UUID s = sku(api, p, "AMP-30", Map.of()).id();
        Api.Res dup = sku(api, p, "amp-30", Map.of());
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.errorCode()).isEqualTo("SKU_CODE_DUPLICATE");

        Ctx other = ctx();
        assertThat(sku(other.ws().owner(), product(other.ws().owner(), "AMP", Map.of()), "AMP-30", Map.of()).status()).isEqualTo(201);

        assertThat(api.patch("/api/v1/skus/" + s, Map.of("sku_code", "AMP-31"), version(api, "/api/v1/skus/" + s)).errorCode())
                .isEqualTo("SKU_IDENTITY_IMMUTABLE");
        assertThat(api.patch("/api/v1/skus/" + s, Map.of("sku_type", "KIT"), version(api, "/api/v1/skus/" + s)).errorCode())
                .isEqualTo("SKU_TYPE_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.sql("update sku set sku_code = 'X' where id = :id").param("id", s).update())
                .rootCause().hasMessageContaining("immutable");
    }

    @Test
    void skuTypeAndFlags_areIndependentButTypeConstraintsHold() {
        Ctx c = ctx();
        Api api = c.ws().owner();
        UUID p = product(api, "SET", Map.of());
        JsonNode bundle = sku(api, p, "SET-BUNDLE", Map.of("sku_type", "BUNDLE")).data();
        assertThat(bundle.get("inventory_managed").asBoolean()).isFalse();
        assertThat(bundle.get("sellable").asBoolean()).isTrue();
        JsonNode gift = sku(api, p, "GIFT-PCH", Map.of("sku_type", "GIFT")).data();
        assertThat(gift.get("sellable").asBoolean()).isFalse();
        assertThat(gift.get("inventory_managed").asBoolean()).isTrue();
        // 증정품도 별도 판매로 바꿀 수 있다(독립 속성)
        assertThat(sku(api, p, "GIFT-SELL", Map.of("sku_type", "GIFT", "sellable", true)).status()).isEqualTo(201);

        for (Map<String, Object> bad : List.of(
                Map.<String, Object>of("sku_type", "SERVICE", "inventory_managed", true),
                Map.<String, Object>of("sku_type", "KIT", "inventory_managed", false),
                Map.<String, Object>of("sku_type", "MATERIAL", "sellable", true),
                Map.<String, Object>of("sku_type", "NORMAL", "expiry_managed", true),
                Map.<String, Object>of("sku_type", "NORMAL", "lot_managed", true, "expiry_managed", true, "min_remaining_shelf_days", -1))) {
            Api.Res r = sku(api, p, "BAD-" + UUID.randomUUID().toString().substring(0, 6), bad);
            assertThat(r.errorCode()).as(bad.toString()).isEqualTo("SKU_TYPE_FLAGS_INVALID");
        }
        assertThat(sku(api, p, "BAD-W", Map.of("weight_g", -1)).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(sku(api, p, "bad code", Map.of()).errorCode()).isEqualTo("VALIDATION_FAILED");
        Api.Res food = sku(api, p, "GRN-500", Map.of("lot_managed", true, "expiry_managed", true, "min_remaining_shelf_days", 30));
        assertThat(food.status()).isEqualTo(201);
        assertThat(food.data().get("min_remaining_shelf_days").asInt()).isEqualTo(30);
        // DB CHECK 도 같은 규칙
        assertThatThrownBy(() -> jdbc.sql("update sku set inventory_managed = true where id = :id")
                .param("id", UUID.fromString(bundle.get("id").asString())).update()).rootCause().hasMessageContaining("check");
    }

    @Test
    void optionCombinations_mustBeCompleteValidAndUnique() {
        Ctx c = ctx();
        Api api = c.ws().owner();
        Api.Res created = api.post("/api/v1/products", Map.of("product_code", "RUG", "name", "러그",
                "options", List.of(Map.of("name", "색상", "values", List.of("그레이", "베이지")), Map.of("name", "크기", "values", List.of("160", "200")))));
        UUID p = UUID.fromString(created.data().get("product").get("id").asString());
        Map<String, String> v = new HashMap<>();
        created.data().get("options").forEach(o -> o.get("values").forEach(x -> v.put(o.get("name").asString() + ":" + x.get("value").asString(),
                x.get("id").asString())));
        UUID other = product(api, "OTHER", Map.of("options", List.of(Map.of("name", "색상", "values", List.of("레드")))));
        String foreign = api.get("/api/v1/products/" + other).data().get("options").get(0).get("values").get(0).get("id").asString();

        assertThat(sku(api, p, "RUG-GR", Map.of("option_value_ids", List.of(v.get("색상:그레이")))).errorCode()).isEqualTo("SKU_OPTION_INCOMPLETE");
        assertThat(sku(api, p, "RUG-X", Map.of("option_value_ids", List.of(v.get("색상:그레이"), foreign))).errorCode()).isEqualTo("SKU_OPTION_INVALID");
        assertThat(sku(api, p, "RUG-Y", Map.of("option_value_ids", List.of(v.get("색상:그레이"), v.get("색상:베이지")))).errorCode())
                .isEqualTo("SKU_OPTION_INVALID");
        assertThat(sku(api, p, "RUG-Z", Map.of()).errorCode()).isEqualTo("SKU_OPTION_INCOMPLETE");

        Api.Res ok = sku(api, p, "RUG-GR-160", Map.of("option_value_ids", List.of(v.get("크기:160"), v.get("색상:그레이"))));
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.data().get("options")).hasSize(2);
        assertThat(sku(api, p, "RUG-GR-160B", Map.of("option_value_ids", List.of(v.get("색상:그레이"), v.get("크기:160")))).errorCode())
                .isEqualTo("SKU_OPTION_COMBINATION_DUPLICATE");
        assertThat(sku(api, p, "RUG-GR-200", Map.of("option_value_ids", List.of(v.get("색상:그레이"), v.get("크기:200")))).status()).isEqualTo(201);

        // SKU 가 생긴 뒤에는 새 옵션 축 추가 불가, 값 추가는 가능
        assertThat(api.post("/api/v1/products/" + p + "/options", Map.of("name", "소재", "values", List.of("면"))).errorCode())
                .isEqualTo("PRODUCT_HAS_SKUS");
        String sizeOption = created.data().get("options").get(1).get("id").asString();
        assertThat(api.post("/api/v1/products/" + p + "/options/" + sizeOption + "/values", Map.of("value", "240")).status()).isEqualTo(201);
        assertThat(api.post("/api/v1/products/" + p + "/options/" + sizeOption + "/values", Map.of("value", "240")).errorCode())
                .isEqualTo("PRODUCT_OPTION_VALUE_DUPLICATE");
    }

    @Test
    void duplicateBarcode_isWarningNotError() {
        Ctx c = ctx();
        Api api = c.ws().owner();
        UUID p = product(api, "RUG", Map.of());
        sku(api, p, "RUG-160", Map.of("barcode", "8801234567909"));
        Api.Res second = sku(api, p, "RUG-200", Map.of("barcode", "8801234567909"));
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.data().get("warnings").get(0).get("code").asString()).isEqualTo("BARCODE_DUPLICATE");
        assertThat(second.data().get("warnings").get(0).get("details").get("sku_codes").toString()).contains("RUG-160");
    }

    @Test
    void concurrentCreatesOfSameSkuCode_exactlyOneWins() throws Exception {
        Ctx c = ctx();
        Api api = c.ws().owner();
        UUID p = product(api, "AMP", Map.of());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> sku(api, p, "RACE-1", Map.of()).status());
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(7);
    }

    @Test
    void crossWorkspace_objectsAreInvisibleAndUnusable() {
        Ctx a = ctx();
        Ctx b = ctx();
        UUID p = product(a.ws().owner(), "AMP", Map.of());
        UUID s = sku(a.ws().owner(), p, "AMP-30", Map.of()).id();
        assertThat(b.ws().owner().get("/api/v1/products/" + p).status()).isEqualTo(404);
        assertThat(b.ws().owner().get("/api/v1/skus/" + s).status()).isEqualTo(404);
        assertThat(sku(b.ws().owner(), p, "STEAL", Map.of()).status()).isEqualTo(404);
        assertThat(b.ws().owner().post("/api/v1/products", Map.of("product_code", "X", "name", "x", "brand_id", a.brand().toString()))
                .errorCode()).isEqualTo("BRAND_NOT_AVAILABLE");
        assertThat(b.ws().owner().get("/api/v1/skus?q=AMP-30").data()).isEmpty();
    }

    @Test
    void searchAndExport_includeSkus_andDuplicateBrandRejected() {
        Ctx c = ctx();
        Api api = c.ws().owner();
        UUID p = product(api, "AMP", Map.of());
        sku(api, p, "AMP-30", Map.of("name", "비타 앰플 30ml"));
        assertThat(api.post("/api/v1/brands", Map.of("name", " 루미에르 ")).errorCode()).isEqualTo("BRAND_DUPLICATE");

        String hits = api.get("/api/v1/search?q=AMP-30").data().toString();
        assertThat(hits).contains("\"type\":\"SKU\"").contains("AMP-30");

        Api.Res accepted = api.post("/api/v1/exports", Map.of("export_type", "SKUS"));
        assertThat(accepted.status()).as(accepted.body().toString()).isEqualTo(202);
        drainAsync();
        JsonNode job = api.get("/api/v1/jobs/" + accepted.data().get("job_id").asString()).data();
        String csv = new String(api.download("/api/v1/files/" + job.get("result").get("file_id").asString() + "/content"), StandardCharsets.UTF_8);
        assertThat(csv).contains("SKU 코드").contains("AMP-30").contains("NORMAL");
    }
}
