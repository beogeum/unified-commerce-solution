package com.commerceos.order;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** M4 O3: 주문 구성 확정(BOM 버전 고정, T12 기반)·사은품 지급(채널 사은품 중복 방지 T13·선착순·재고 보류) + 개인정보 보관(DLG-120). */
class OrderCompositionTest extends IntegrationTest {
    @Autowired
    OrderPrivacyService privacy;

    record Ctx(Fixtures.Ws ws, Api o, UUID be, UUID acc, UUID unit, UUID node, Map<String, UUID> sku) {
    }

    Ctx ctx() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        UUID be = fx.businessEntity(o, "에이상사");
        UUID acc = fx.channelAccount(o, be, "NAVER_SMARTSTORE", "A 스토어");
        UUID unit = UUID.fromString(o.get("/api/v1/channel-accounts/" + acc + "/sales-units").data().get(0).get("id").asString());
        UUID product = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "AMP", "name", "앰플")).data().get("product").get("id").asString());
        Map<String, UUID> sku = new HashMap<>();
        for (String[] d : new String[][]{{"AMP-30", "NORMAL"}, {"CLF-150", "NORMAL"}, {"SET-A", "BUNDLE"}}) {
            sku.put(d[0], o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", d[0], "sku_type", d[1])).id());
        }
        // 채널에 증정 상품으로 올리는 사은품은 판매 가능(채널상품 연결 대상)
        sku.put("GIFT-PCH", o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "GIFT-PCH", "sku_type", "GIFT", "sellable", true)).id());
        UUID node = o.post("/api/v1/fulfillment-nodes", Map.of("code", "HS", "name", "화성", "logistics_provider_code", "SELF_WMS", "cutoff_time", "16:00")).id();
        return new Ctx(ws, o, be, acc, unit, node, sku);
    }

    void listing(Ctx c, String productId, String skuCode) {
        Api.Res l = c.o().post("/api/v1/channel-listings", Map.of("sales_unit_id", c.unit().toString(), "external_product_id", productId, "external_option_id", "",
                "title", skuCode));
        assertThat(c.o().post("/api/v1/channel-listings/" + l.data().get("listing").get("id").asString() + "/mapping",
                Map.of("sku_id", c.sku().get(skuCode).toString())).status()).isEqualTo(200);
    }

    void publishBom(Ctx c, Object... skuQty) {
        List<Map<String, Object>> comps = new ArrayList<>();
        for (int i = 0; i < skuQty.length; i += 2) {
            comps.add(Map.of("sku_id", c.sku().get((String) skuQty[i]).toString(), "quantity", skuQty[i + 1]));
        }
        Api.Res d = c.o().post("/api/v1/boms", Map.of("bom_type", "SALES_BUNDLE", "parent_sku_id", c.sku().get("SET-A").toString(), "components", comps));
        assertThat(d.status()).as(d.body().toString()).isEqualTo(201);
        Api.Res p = c.o().postIfMatch("/api/v1/boms/" + d.data().get("id").asString() + "/publish", Map.of(), d.data().get("version_lock").asInt());
        assertThat(p.status()).as(p.body().toString()).isEqualTo(200);
    }

    JsonNode importOne(Ctx c, Map<String, Object> order) {
        Api.Res r = c.o().post("/api/v1/orders/imports", Map.of("channel_account_id", c.acc().toString(), "orders", List.of(order)));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return c.o().get("/api/v1/orders/" + r.data().get("results").get(0).get("order_id").asString()).data();
    }

    Map<String, Integer> components(JsonNode d) {
        Map<String, Integer> m = new LinkedHashMap<>();
        d.get("components").forEach(x -> m.merge(x.get("sku_code").asString(), x.get("required_quantity").asInt(), Integer::sum));
        return m;
    }

    UUID campaign(Ctx c, Map<String, Object> extra) {
        Map<String, Object> b = new HashMap<>(Map.of("business_entity_id", c.be().toString(), "name", "앰플 구매 파우치 증정",
                "starts_at", Instant.now().minus(1, ChronoUnit.HOURS).toString(), "ends_at", Instant.now().plus(7, ChronoUnit.DAYS).toString(),
                "required_skus", List.of(Map.of("sku_id", c.sku().get("AMP-30").toString(), "min_quantity", 1)),
                "rewards", List.of(Map.of("gift_sku_id", c.sku().get("GIFT-PCH").toString(), "quantity", 1)), "channel_campaign_ref", "NV-EVT-1"));
        b.putAll(extra);
        JsonNode d = c.o().post("/api/v1/gwp-campaigns", b).data();
        c.o().patch("/api/v1/gwp-campaigns/" + d.get("id").asString(), Map.of("status", "ACTIVE"), d.get("version").asInt());
        return UUID.fromString(d.get("id").asString());
    }

    void stock(Ctx c, String sku, int qty) {
        assertThat(c.o().post("/api/v1/inventory/receipts", Map.of("business_entity_id", c.be().toString(), "sku_id", c.sku().get(sku).toString(),
                "node_id", c.node().toString(), "quantity", qty)).status()).isEqualTo(201);
    }

    @Test
    void bundleExpandedWithBomVersionAtOrderTime_laterBomChangeDoesNotAlterFixedOrder_missingBomHoldsUntilPublished() {
        Ctx c = ctx();
        listing(c, "P-SET", "SET-A");
        // BOM 없는 가상묶음 → 보류
        Instant t0 = Instant.now();
        JsonNode noBom = importOne(c, OrderTest.order("ORD-0", t0, t0, 1, 0, "P-SET"));
        assertThat(noBom.get("order").get("hold_reasons").get(0).asString()).isEqualTo("BOM_MISSING");
        assertThat(noBom.get("composition_status").asString()).isEqualTo("PENDING");

        publishBom(c, "AMP-30", 1, "CLF-150", 2);
        // 주문 시각에는 BOM 이 없었다 → 지금 유효한 v1 으로 확정
        JsonNode re = c.o().post("/api/v1/orders/" + noBom.get("order").get("id").asString() + "/revalidate", Map.of()).data();
        assertThat(re.get("order").get("on_hold").asBoolean()).isFalse();
        assertThat(components(re)).containsExactly(Map.entry("AMP-30", 1), Map.entry("CLF-150", 2));
        Instant t1 = Instant.now().plusSeconds(1);
        JsonNode v1 = importOne(c, OrderTest.order("ORD-1", t1, t1, 2, 0, "P-SET"));
        assertThat(v1.get("composition_status").asString()).isEqualTo("FIXED");
        assertThat(components(v1)).containsExactly(Map.entry("AMP-30", 2), Map.entry("CLF-150", 4));
        assertThat(v1.get("components").get(0).get("bom_version").asInt()).isEqualTo(1);

        publishBom(c, "AMP-30", 3);
        Instant t2 = Instant.now().plusSeconds(2);
        JsonNode v2 = importOne(c, OrderTest.order("ORD-2", t2, t2, 1, 0, "P-SET"));
        assertThat(components(v2)).containsExactly(Map.entry("AMP-30", 3));
        assertThat(v2.get("components").get(0).get("bom_version").asInt()).isEqualTo(2);
        // 이미 확정한 주문은 그대로(BOM 버전 고정)
        JsonNode again = c.o().get("/api/v1/orders/" + v1.get("order").get("id").asString()).data();
        assertThat(components(again)).containsExactly(Map.entry("AMP-30", 2), Map.entry("CLF-150", 4));
        assertThatThrownBy(() -> jdbc.sql("update order_item_component set quantity_per_unit = 9 where workspace_id = :ws").param("ws", c.ws().id()).update())
                .rootCause().hasMessageContaining("append-only");
        // ORD-0 도 v2 게시 후 그대로 v1
        assertThat(components(c.o().get("/api/v1/orders/" + noBom.get("order").get("id").asString()).data())).containsExactly(Map.entry("AMP-30", 1),
                Map.entry("CLF-150", 2));
    }

    @Test
    void T13_channelGiftOfSameCampaign_skipsInternalGift_otherwiseGiftAwardedAt0Won_firstComeLimit_outOfStockHold() {
        Ctx c = ctx();
        listing(c, "P-AMP", "AMP-30");
        listing(c, "P-GIFT", "GIFT-PCH");
        stock(c, "GIFT-PCH", 10);
        campaign(c, Map.of("total_limit", 2));
        Instant now = Instant.now();

        // 채널이 같은 행사 사은품을 이미 넣어 줌 → 내부 사은품 미지급
        Map<String, Object> withChannelGift = OrderTest.order("ORD-CH", now, now, 1, 0, "P-AMP");
        List<Map<String, Object>> items = new ArrayList<>((List<Map<String, Object>>) withChannelGift.get("items"));
        items.add(new LinkedHashMap<>(Map.of("external_item_key", "ORD-CH-G", "external_product_id", "P-GIFT", "product_name", "파우치(증정)", "quantity", 1,
                "unit_price", 0, "line_amount", 0, "gift", true, "channel_campaign_ref", "NV-EVT-1")));
        withChannelGift.put("items", items);
        JsonNode ch = importOne(c, withChannelGift);
        assertThat(ch.get("composition_status").asString()).isEqualTo("FIXED");
        long internal = 0;
        for (JsonNode i : ch.get("items")) {
            if ("INTERNAL".equals(i.get("gift_source").asString())) {
                internal++;
            }
        }
        assertThat(internal).isZero();
        assertThat(ch.get("gwp_result").get("skipped").get(0).get("reason").asString()).isEqualTo("CHANNEL_GIFT_DUPLICATE");
        assertThat(components(ch)).containsEntry("GIFT-PCH", 1); // 채널 사은품도 출고 구성에 포함

        // 채널 사은품 없음 → 내부 사은품 0원 지급
        JsonNode in1 = importOne(c, OrderTest.order("ORD-IN1", now, now, 1, 0, "P-AMP"));
        JsonNode gift = null;
        for (JsonNode i : in1.get("items")) {
            if ("INTERNAL".equals(i.get("gift_source").asString())) {
                gift = i;
            }
        }
        assertThat(gift).isNotNull();
        assertThat(gift.get("line_amount").asString()).isEqualTo("0");
        assertThat(gift.get("sku_code").asString()).isEqualTo("GIFT-PCH");
        assertThat(in1.get("order").get("total_amount").asString()).isEqualTo("12000"); // 결제 금액 불변
        assertThat(in1.get("order").get("on_hold").asBoolean()).isFalse();
        // 재검증해도 중복 지급 없음
        JsonNode re = c.o().post("/api/v1/orders/" + in1.get("order").get("id").asString() + "/revalidate", Map.of()).data();
        assertThat(re.get("items")).hasSize(2);
        // 선착순 2: 두 번째 지급 후 세 번째는 한도 소진
        importOne(c, OrderTest.order("ORD-IN2", now, now, 1, 0, "P-AMP"));
        JsonNode in3 = importOne(c, OrderTest.order("ORD-IN3", now, now, 1, 0, "P-AMP"));
        assertThat(in3.get("items")).hasSize(1);
        assertThat(in3.get("gwp_result").get("skipped").get(0).get("reason").asString()).isEqualTo("LIMIT_EXHAUSTED");
    }

    @Test
    void giftOutOfStockWithHoldPolicy_holdsUntilStockThenAwards() {
        Ctx c = ctx();
        listing(c, "P-AMP", "AMP-30");
        campaign(c, Map.of("out_of_stock_policy", "HOLD_ORDER", "channel_campaign_ref", "OTHER"));
        Instant now = Instant.now();
        JsonNode held = importOne(c, OrderTest.order("ORD-H", now, now, 1, 0, "P-AMP"));
        assertThat(held.get("order").get("hold_reasons").get(0).asString()).isEqualTo("GIFT_OUT_OF_STOCK");
        assertThat(held.get("composition_status").asString()).isEqualTo("PENDING");
        stock(c, "GIFT-PCH", 5);
        JsonNode re = c.o().post("/api/v1/orders/" + held.get("order").get("id").asString() + "/revalidate", Map.of()).data();
        assertThat(re.get("order").get("on_hold").asBoolean()).isFalse();
        assertThat(re.get("composition_status").asString()).isEqualTo("FIXED");
        assertThat(components(re)).containsEntry("GIFT-PCH", 1).containsEntry("AMP-30", 1);
    }

    @Test
    void privacyRetention_archiveAfterActivePeriod_purgeAfterLegalRetention_revealRules() {
        Ctx c = ctx();
        listing(c, "P-AMP", "AMP-30");
        Instant now = Instant.now();
        JsonNode d = importOne(c, OrderTest.order("ORD-P", now, now, 1, 0, "P-AMP"));
        String id = d.get("order").get("id").asString();
        assertThat(d.get("pii_state").asString()).isEqualTo("ACTIVE");
        // 기본값: 운영 열람 90일, 보존 5년. 정책 변경은 접근 관리 권한자만
        assertThat(c.o().get("/api/v1/orders/privacy-policies/" + c.be()).data().get("active_days").asInt()).isEqualTo(90);
        Api om = fx.member(c.ws(), "ORDER_MANAGER");
        assertThat(om.put("/api/v1/orders/privacy-policies/" + c.be(), Map.of("active_days", 60, "retention_years", 5), null).errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(c.o().put("/api/v1/orders/privacy-policies/" + c.be(), Map.of("active_days", 20, "retention_years", 5), null).errorCode()).isEqualTo("VALIDATION_FAILED");

        // 종료되지 않은 주문은 분리보관하지 않는다
        privacy.runAll(now.plus(200, ChronoUnit.DAYS));
        assertThat(c.o().get("/api/v1/orders/" + id).data().get("pii_state").asString()).isEqualTo("ACTIVE");
        importOne(c, OrderTest.order("ORD-P", now, now.plusSeconds(60), 1, 1, "P-AMP")); // 전체 취소 → 종료
        privacy.runAll(now.plus(91, ChronoUnit.DAYS));
        assertThat(c.o().get("/api/v1/orders/" + id).data().get("pii_state").asString()).isEqualTo("ARCHIVED");
        assertThat(c.o().post("/api/v1/orders/" + id + "/pii", Map.of("reason", "문의")).errorCode()).isEqualTo("PII_ARCHIVED");
        assertThat(om.post("/api/v1/orders/" + id + "/pii", Map.of("reason", "분쟁", "purpose", "DISPUTE")).errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(c.o().post("/api/v1/orders/" + id + "/pii", Map.of("reason", "소비자원 분쟁 조정 자료", "purpose", "DISPUTE")).data()
                .get("recipient").get("name").asString()).isEqualTo("홍길동");

        // 법정 보존기간 경과 → 원문 파기(거래 기록·마스킹 값은 유지)
        privacy.runAll(now.plus(5 * 366, ChronoUnit.DAYS));
        JsonNode purged = c.o().get("/api/v1/orders/" + id).data();
        assertThat(purged.get("pii_state").asString()).isEqualTo("PURGED");
        assertThat(purged.get("order").get("recipient_name_masked").asString()).isEqualTo("홍*동");
        assertThat(purged.get("order").get("total_amount").asString()).isEqualTo("12000");
        assertThat(c.o().post("/api/v1/orders/" + id + "/pii", Map.of("reason", "x", "purpose", "DISPUTE")).errorCode()).isEqualTo("PII_PURGED");
        assertThat(jdbc.sql("select count(*) from order_raw where order_id = :o and payload_cipher is not null").param("o", UUID.fromString(id)).query(Integer.class)
                .single()).isZero();
        // 원본은 파기 외 변경 불가
        assertThatThrownBy(() -> jdbc.sql("update order_raw set result = 'CREATED' where order_id = :o").param("o", UUID.fromString(id)).update())
                .rootCause().hasMessageContaining("append-only");
    }
}
