package com.commerceos.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** M2 C4: BOM 유형 규칙·순환 금지·버전·적용기간·불변·원가 합(FR-CAT-05, docs/13 §2). */
class BomTest extends IntegrationTest {

    record Ctx(Fixtures.Ws ws, UUID product, Map<String, UUID> sku) {
    }

    Ctx ctx() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        UUID product = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "SET", "name", "세트")).data().get("product").get("id").asString());
        Map<String, UUID> s = new HashMap<>();
        for (String[] d : new String[][] {{"AMP-30", "NORMAL"}, {"CLF-150", "NORMAL"}, {"GIFT-PCH", "GIFT"}, {"BOX-S", "MATERIAL"}, {"TAPE", "MATERIAL"},
                {"SET-A", "BUNDLE"}, {"SET-B", "BUNDLE"}, {"SET-C", "BUNDLE"}, {"KIT-1", "KIT"}}) {
            Api.Res r = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", d[0], "sku_type", d[1]));
            assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
            s.put(d[0], r.id());
        }
        return new Ctx(ws, product, s);
    }

    List<Map<String, Object>> comps(Ctx c, Object... codeQty) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (int i = 0; i < codeQty.length; i += 2) {
            l.add(Map.of("sku_id", c.sku().get((String) codeQty[i]).toString(), "quantity", codeQty[i + 1]));
        }
        return l;
    }

    Api.Res draft(Api api, String type, UUID parent, List<Map<String, Object>> components) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("bom_type", type, "components", components));
        if (parent != null) {
            body.put("parent_sku_id", parent.toString());
        }
        return api.post("/api/v1/boms", body);
    }

    Api.Res publish(Api api, JsonNode bom, Instant from) {
        Map<String, Object> body = from == null ? Map.of() : Map.of("valid_from", from.toString());
        return api.postIfMatch("/api/v1/boms/" + bom.get("id").asString() + "/publish", body, bom.get("version_lock").asInt());
    }

    @Test
    void typeRules_parentComponentsSelfReferenceAndQuantity() {
        Ctx c = ctx();
        Api o = c.ws().owner();
        assertThat(draft(o, "SALES_BUNDLE", c.sku().get("AMP-30"), comps(c, "CLF-150", 1)).errorCode()).isEqualTo("BOM_PARENT_INVALID");
        assertThat(draft(o, "ASSEMBLY", c.sku().get("SET-A"), comps(c, "AMP-30", 1)).errorCode()).isEqualTo("BOM_PARENT_INVALID");
        assertThat(draft(o, "SALES_BUNDLE", c.sku().get("SET-A"), comps(c, "BOX-S", 1)).errorCode()).isEqualTo("BOM_COMPONENT_TYPE_INVALID");
        assertThat(draft(o, "SALES_BUNDLE", c.sku().get("SET-A"), comps(c, "SET-A", 1)).errorCode()).isEqualTo("BOM_SELF_REFERENCE");
        assertThat(draft(o, "SALES_BUNDLE", c.sku().get("SET-A"), comps(c, "AMP-30", 1, "AMP-30", 2)).errorCode()).isEqualTo("BOM_COMPONENT_DUPLICATE");
        assertThat(draft(o, "SALES_BUNDLE", c.sku().get("SET-A"), comps(c, "AMP-30", 0)).errorCode()).isEqualTo("VALIDATION_FAILED");
        // 포장 BOM: 포장 프로필 + 부자재만
        assertThat(o.post("/api/v1/boms", Map.of("bom_type", "PACKAGING", "packaging_profile_code", "DEFAULT", "components", comps(c, "AMP-30", 1)))
                .errorCode()).isEqualTo("BOM_COMPONENT_TYPE_INVALID");
        Api.Res pack = o.post("/api/v1/boms", Map.of("bom_type", "PACKAGING", "packaging_profile_code", "DEFAULT", "components", comps(c, "BOX-S", 1, "TAPE", 1)));
        assertThat(pack.status()).as(pack.body().toString()).isEqualTo(201);
        assertThat(publish(o, pack.data(), null).status()).isEqualTo(200);
        assertThat(draft(o, "ASSEMBLY", c.sku().get("KIT-1"), comps(c, "AMP-30", 2, "BOX-S", 1)).status()).isEqualTo(201); // 실물 세트 조립은 부자재 포함 가능
        assertThat(draft(fx.member(c.ws(), "VIEWER"), "SALES_BUNDLE", c.sku().get("SET-A"), comps(c, "AMP-30", 1)).errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(publish(o, draft(o, "SALES_BUNDLE", c.sku().get("SET-C"), List.of()).data(), null).errorCode()).isEqualTo("BOM_EMPTY");
    }

    @Test
    void cycles_areRejected_includingIndirectAndConcurrent() throws Exception {
        Ctx c = ctx();
        Api o = c.ws().owner();
        assertThat(draft(o, "SALES_BUNDLE", c.sku().get("SET-A"), comps(c, "SET-B", 1, "AMP-30", 1)).status()).isEqualTo(201);
        assertThat(draft(o, "SALES_BUNDLE", c.sku().get("SET-B"), comps(c, "SET-C", 1)).status()).isEqualTo(201);
        Api.Res cyc = draft(o, "SALES_BUNDLE", c.sku().get("SET-C"), comps(c, "SET-A", 1));
        assertThat(cyc.errorCode()).isEqualTo("BOM_CYCLE");
        assertThat(cyc.body().get("error").get("details").get("cycle").toString()).contains("SET-C", "SET-A", "SET-B");

        // 동시에 서로를 구성품으로 넣는 두 요청 → 둘 다 성공할 수 없다(그래프 잠금)
        Ctx d = ctx();
        Api o2 = d.ws().owner();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Integer>> tasks = List.of(
                () -> draft(o2, "SALES_BUNDLE", d.sku().get("SET-A"), comps(d, "SET-B", 1)).status(),
                () -> draft(o2, "SALES_BUNDLE", d.sku().get("SET-B"), comps(d, "SET-A", 1)).status());
        List<Integer> st = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            st.add(f.get());
        }
        pool.shutdown();
        assertThat(st).containsExactlyInAnyOrder(201, 422);
    }

    @Test
    void versions_closePreviousPeriod_resolveByTime_andArePublishedImmutable() throws Exception {
        Ctx c = ctx();
        Api o = c.ws().owner();
        JsonNode v1 = draft(o, "SALES_BUNDLE", c.sku().get("SET-A"), comps(c, "AMP-30", 1, "GIFT-PCH", 1)).data();
        JsonNode p1 = publish(o, v1, null).data();
        assertThat(p1.get("status").asString()).isEqualTo("ACTIVE");
        Instant duringV1 = Instant.now();
        Thread.sleep(20);
        JsonNode v2 = draft(o, "SALES_BUNDLE", c.sku().get("SET-A"), comps(c, "AMP-30", 1, "CLF-150", 1)).data();
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        JsonNode p2 = publish(o, v2, null).data();
        JsonNode closed = o.get("/api/v1/boms/" + v1.get("id").asString()).data();
        assertThat(closed.get("valid_to").asString()).isEqualTo(p2.get("valid_from").asString());

        String sku = c.sku().get("SET-A").toString();
        assertThat(o.get("/api/v1/skus/" + sku + "/boms/effective?bom_type=SALES_BUNDLE&at=" + duringV1).data().get("version").asInt()).isEqualTo(1);
        assertThat(o.get("/api/v1/skus/" + sku + "/boms/effective?bom_type=SALES_BUNDLE").data().get("components").toString()).contains("CLF-150");

        // 게시본 불변(API·DB)
        assertThat(o.put("/api/v1/boms/" + p2.get("id").asString() + "/components", Map.of("components", comps(c, "AMP-30", 9)),
                p2.get("version_lock").asInt()).errorCode()).isEqualTo("BOM_PUBLISHED_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.sql("update bom_component set quantity = 9 where bom_id = :b").param("b", UUID.fromString(p2.get("id").asString())).update())
                .rootCause().hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("delete from bom where id = :b").param("b", UUID.fromString(p2.get("id").asString())).update())
                .rootCause().hasMessageContaining("immutable");
        assertThat(o.delete("/api/v1/boms/" + p2.get("id").asString()).errorCode()).isEqualTo("BOM_PUBLISHED_IMMUTABLE");

        // 소급 게시 금지, 미래 예약 게시는 가능하고 겹치는 예약은 거부
        JsonNode v3 = draft(o, "SALES_BUNDLE", c.sku().get("SET-A"), comps(c, "AMP-30", 2)).data();
        assertThat(publish(o, v3, Instant.now().minus(1, ChronoUnit.DAYS)).errorCode()).isEqualTo("BOM_BACKDATE_FORBIDDEN");
        Instant later = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        assertThat(publish(o, o.get("/api/v1/boms/" + v3.get("id").asString()).data(), later).status()).isEqualTo(200);
        JsonNode v4 = draft(o, "SALES_BUNDLE", c.sku().get("SET-A"), comps(c, "AMP-30", 3)).data();
        assertThat(publish(o, v4, later.minus(1, ChronoUnit.HOURS)).errorCode()).isEqualTo("BOM_PERIOD_OVERLAP");
        // 지금은 여전히 v2
        assertThat(o.get("/api/v1/skus/" + sku + "/boms/effective?bom_type=SALES_BUNDLE").data().get("version").asInt()).isEqualTo(2);
        // 초안은 삭제 가능
        assertThat(o.delete("/api/v1/boms/" + v4.get("id").asString()).status()).isEqualTo(204);
    }

    @Test
    void retire_endsEffectivePeriod_andCostRollupFlagsMissingCosts() {
        Ctx c = ctx();
        Api o = c.ws().owner();
        UUID be = fx.businessEntity(o, "A 코스메틱");
        JsonNode b = publish(o, draft(o, "SALES_BUNDLE", c.sku().get("SET-A"), comps(c, "AMP-30", 2, "GIFT-PCH", 1)).data(), null).data();
        o.post("/api/v1/business-entities/" + be + "/sku-costs/" + c.sku().get("AMP-30"), Map.of("unit_cost", 9000, "reason", "원가"));
        JsonNode cost = o.get("/api/v1/boms/" + b.get("id").asString() + "/cost?business_entity_id=" + be).data();
        assertThat(cost.get("total_cost").isNull()).isTrue(); // 증정품 원가 미등록 → 합계 계산 불가
        assertThat(cost.get("missing_cost_sku_codes").toString()).contains("GIFT-PCH");
        o.post("/api/v1/business-entities/" + be + "/sku-costs/" + c.sku().get("GIFT-PCH"), Map.of("unit_cost", 1200, "reason", "원가"));
        assertThat(o.get("/api/v1/boms/" + b.get("id").asString() + "/cost?business_entity_id=" + be).data().get("total_cost").asString())
                .isEqualTo("19200");
        assertThat(fx.member(c.ws(), "ORDER_MANAGER").get("/api/v1/boms/" + b.get("id").asString() + "/cost?business_entity_id=" + be).status())
                .isEqualTo(403);

        assertThat(o.postIfMatch("/api/v1/boms/" + b.get("id").asString() + "/retire", Map.of("reason", "행사 종료"), b.get("version_lock").asInt())
                .data().get("status").asString()).isEqualTo("RETIRED");
        assertThat(o.get("/api/v1/skus/" + c.sku().get("SET-A") + "/boms/effective?bom_type=SALES_BUNDLE&at=" + Instant.now().plusSeconds(1)).status())
                .isEqualTo(404);
        assertThat(ctx().ws().owner().get("/api/v1/boms/" + b.get("id").asString()).status()).isEqualTo(404);
    }
}
