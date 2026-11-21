package com.commerceos.catalog;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;

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

/** M2 C5: GWP 캠페인 등록·검증·상태·운영 중 변경 제한·미리보기(T13)·사업자 범위. */
class GwpTest extends IntegrationTest {

    record Ctx(Fixtures.Ws ws, UUID beA, UUID beB, UUID accA, UUID accB, UUID amp, UUID pouch, UUID box) {
    }

    Ctx ctx() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        UUID beA = fx.businessEntity(o, "A 코스메틱");
        UUID beB = fx.businessEntity(o, "B 리빙");
        UUID accA = fx.channelAccount(o, beA, "NAVER_SMARTSTORE", "A 공식스토어");
        UUID accB = fx.channelAccount(o, beB, "NAVER_SMARTSTORE", "B 스토어");
        UUID p = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "AMP", "name", "앰플")).data().get("product").get("id").asString());
        UUID amp = o.post("/api/v1/skus", Map.of("product_id", p.toString(), "sku_code", "AMP-30", "sku_type", "NORMAL")).id();
        UUID pouch = o.post("/api/v1/skus", Map.of("product_id", p.toString(), "sku_code", "GIFT-PCH", "sku_type", "GIFT")).id();
        UUID box = o.post("/api/v1/skus", Map.of("product_id", p.toString(), "sku_code", "BOX-S", "sku_type", "MATERIAL")).id();
        return new Ctx(ws, beA, beB, accA, accB, amp, pouch, box);
    }

    Map<String, Object> body(Ctx c, Map<String, Object> extra) {
        Map<String, Object> b = new HashMap<>(Map.of("business_entity_id", c.beA().toString(), "name", "앰플 2개 파우치 증정",
                "starts_at", Instant.now().minus(1, ChronoUnit.HOURS).toString(), "ends_at", Instant.now().plus(7, ChronoUnit.DAYS).toString(),
                "required_skus", List.of(Map.of("sku_id", c.amp().toString(), "min_quantity", 2)),
                "rewards", List.of(Map.of("gift_sku_id", c.pouch().toString(), "quantity", 1)),
                "channel_campaign_ref", "NAVER-PROMO-77"));
        b.putAll(extra);
        return b;
    }

    Map<String, Object> preview(Ctx c, UUID account, int qty, List<Map<String, Object>> gifts) {
        return Map.of("business_entity_id", c.beA().toString(), "channel_account_id", account.toString(),
                "lines", List.of(Map.of("sku_id", c.amp().toString(), "quantity", qty, "amount", 33000 * qty)), "channel_gifts", gifts);
    }

    @Test
    void lifecycle_validation_andActiveCampaignIsLocked() {
        Ctx c = ctx();
        Api o = c.ws().owner();
        assertThat(o.post("/api/v1/gwp-campaigns", body(c, Map.of("rewards", List.of(Map.of("gift_sku_id", c.box().toString()))))).errorCode())
                .isEqualTo("GWP_GIFT_INVALID");
        assertThat(o.post("/api/v1/gwp-campaigns", body(c, Map.of("channel_account_ids", List.of(c.accB().toString())))).errorCode())
                .isEqualTo("CHANNEL_ACCOUNT_NOT_IN_BUSINESS");
        assertThat(o.post("/api/v1/gwp-campaigns", body(c, Map.of("ends_at", Instant.now().minus(2, ChronoUnit.DAYS).toString()))).errorCode())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(o.post("/api/v1/gwp-campaigns", body(c, Map.of("excluded_sku_ids", List.of(c.amp().toString())))).errorCode())
                .isEqualTo("GWP_SKU_BOTH_REQUIRED_AND_EXCLUDED");

        JsonNode draft = o.post("/api/v1/gwp-campaigns", body(c, Map.of("total_limit", 100, "channel_account_ids", List.of(c.accA().toString())))).data();
        assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.get("channel_overlap_policy").asString()).isEqualTo("SKIP_INTERNAL");
        assertThat(draft.get("condition_mode").asString()).isEqualTo("ALL");
        String path = "/api/v1/gwp-campaigns/" + draft.get("id").asString();
        JsonNode active = o.patch(path, Map.of("status", "ACTIVE"), draft.get("version").asInt()).data();
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");

        assertThat(o.put(path, body(c, Map.of("name", "변경")), active.get("version").asInt()).errorCode()).isEqualTo("GWP_NOT_DRAFT");
        assertThat(o.patch(path, Map.of("total_limit", 50), active.get("version").asInt()).errorCode()).isEqualTo("GWP_LIMIT_DECREASE_FORBIDDEN");
        assertThat(o.patch(path, Map.of("ends_at", Instant.now().plus(1, ChronoUnit.DAYS).toString()), active.get("version").asInt()).errorCode())
                .isEqualTo("GWP_END_SHORTEN_FORBIDDEN");
        JsonNode extended = o.patch(path, Map.of("ends_at", Instant.now().plus(14, ChronoUnit.DAYS).toString(), "total_limit", 200),
                active.get("version").asInt()).data();
        assertThat(extended.get("total_limit").asInt()).isEqualTo(200);
        JsonNode ended = o.patch(path, Map.of("status", "ENDED"), extended.get("version").asInt()).data();
        assertThat(o.patch(path, Map.of("status", "ACTIVE"), ended.get("version").asInt()).errorCode()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(jdbc.sql("select count(*) from audit_log where target_id = :id and action = 'GWP_CAMPAIGN_STATUS_CHANGED'")
                .param("id", UUID.fromString(draft.get("id").asString())).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void T13_preview_skipsInternalGiftWhenChannelAlreadyGaveIt() {
        Ctx c = ctx();
        Api o = c.ws().owner();
        JsonNode d = o.post("/api/v1/gwp-campaigns", body(c, Map.of())).data();
        o.patch("/api/v1/gwp-campaigns/" + d.get("id").asString(), Map.of("status", "ACTIVE"), d.get("version").asInt());

        JsonNode awarded = o.post("/api/v1/gwp-campaigns/preview", preview(c, c.accA(), 2, List.of())).data();
        assertThat(awarded.get("awards")).hasSize(1);
        assertThat(awarded.get("awards").get(0).get("gift_sku_id").asString()).isEqualTo(c.pouch().toString());

        JsonNode dup = o.post("/api/v1/gwp-campaigns/preview", preview(c, c.accA(), 2, List.of(Map.of("channel_campaign_ref", "NAVER-PROMO-77")))).data();
        assertThat(dup.get("awards")).isEmpty();
        assertThat(dup.get("skipped").get(0).get("reason").asString()).isEqualTo("CHANNEL_GIFT_DUPLICATE");

        JsonNode notMet = o.post("/api/v1/gwp-campaigns/preview", preview(c, c.accA(), 1, List.of())).data();
        assertThat(notMet.get("skipped").get(0).get("reason").asString()).isEqualTo("CONDITION_NOT_MET");
    }

    @Test
    void businessScope_appliesToCampaigns() {
        Ctx c = ctx();
        Api o = c.ws().owner();
        JsonNode d = o.post("/api/v1/gwp-campaigns", body(c, Map.of())).data();
        Api md = fx.member(c.ws(), "MD");
        assertThat(o.put("/api/v1/users/" + md.userId + "/access-policy", Map.of("permission_overrides", List.of(),
                "scopes", List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", c.beB(), "ALLOW"))), 1).status()).isEqualTo(200);
        assertThat(md.get("/api/v1/gwp-campaigns/" + d.get("id").asString()).status()).isEqualTo(404);
        assertThat(md.get("/api/v1/gwp-campaigns").data()).isEmpty();
        assertThat(md.post("/api/v1/gwp-campaigns", body(c, Map.of())).status()).isEqualTo(404);
        assertThat(fx.member(c.ws(), "VIEWER").post("/api/v1/gwp-campaigns", body(c, Map.of())).errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(ctx().ws().owner().get("/api/v1/gwp-campaigns/" + d.get("id").asString()).status()).isEqualTo(404);
    }
}
