package com.commerceos.finance;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** 재무 통합 테스트 공통 준비(사업자·판매계정·SKU·원가·입고·주문·출고). */
abstract class FinanceTestBase extends IntegrationTest {
    @Autowired
    PostingService posting;
    @Autowired
    PlatformTransactionManager tm;

    record Ctx(Fixtures.Ws ws, Api o, UUID be, UUID acc, UUID amp, UUID amp50, UUID gift, UUID hs) {
    }

    Ctx ctx(String beName) {
        Fixtures.Ws ws = fx.workspace();
        return ctx(ws, beName, "AMP");
    }

    Ctx ctx(Fixtures.Ws ws, String beName, String prefix) {
        Api o = ws.owner();
        UUID be = fx.businessEntity(o, beName);
        UUID acc = fx.channelAccount(o, be, "NAVER_SMARTSTORE", beName + " 스토어");
        UUID unit = UUID.fromString(o.get("/api/v1/channel-accounts/" + acc + "/sales-units").data().get(0).get("id").asString());
        UUID product = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", prefix, "name", "앰플")).data().get("product").get("id").asString());
        UUID amp = sku(o, product, prefix + "-30", "NORMAL");
        UUID amp50 = sku(o, product, prefix + "-50", "NORMAL");
        UUID gift = sku(o, product, prefix + "-GIFT", "GIFT");
        map(o, unit, "P-100", amp);
        map(o, unit, "P-GIFT", gift);
        UUID hs = o.post("/api/v1/fulfillment-nodes", Map.of("code", prefix + "HS", "name", prefix + " 센터", "logistics_provider_code", "SELF_WMS", "cutoff_time", "16:00",
                "inventory_source", "INTERNAL")).id();
        Ctx c = new Ctx(ws, o, be, acc, amp, amp50, gift, hs);
        cost(c, amp, 9000);
        cost(c, amp50, 12000);
        cost(c, gift, 1200);
        for (UUID s : List.of(amp, amp50, gift)) {
            assertThat(o.post("/api/v1/inventory/receipts", Map.of("business_entity_id", be.toString(), "sku_id", s.toString(), "node_id", hs.toString(), "quantity", 10))
                    .status()).isEqualTo(201);
        }
        return c;
    }

    UUID sku(Api o, UUID product, String code, String type) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("product_id", product.toString(), "sku_code", code, "sku_type", type));
        if ("GIFT".equals(type)) {
            body.put("sellable", true);
        }
        Api.Res r = o.post("/api/v1/skus", body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.id();
    }

    void map(Api o, UUID unit, String product, UUID sku) {
        Api.Res l = o.post("/api/v1/channel-listings", Map.of("sales_unit_id", unit.toString(), "external_product_id", product, "external_option_id", "", "title", product));
        o.post("/api/v1/channel-listings/" + l.data().get("listing").get("id").asString() + "/mapping", Map.of("sku_id", sku.toString()));
    }

    void cost(Ctx c, UUID sku, int unitCost) {
        Api.Res r = c.o().post("/api/v1/business-entities/" + c.be() + "/sku-costs/" + sku, Map.of("unit_cost", unitCost, "reason", "기준 원가"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    }

    void rule(Ctx c, String category, String method, Integer amount, Integer bps, boolean node) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("business_entity_id", c.be().toString());
        if (node) {
            b.put("node_id", c.hs().toString());
        }
        b.put("category", category);
        b.put("method", method);
        if (amount != null) {
            b.put("amount", amount);
        }
        if (bps != null) {
            b.put("rate_bps", bps);
        }
        b.put("effective_from", Instant.now().minusSeconds(3600).toString());
        Api.Res r = c.o().post("/api/v1/finance/cost-rules", b);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    }

    /** items: (상품번호, 수량, 단가, 판매자할인, 채널할인, 사은품) */
    String order(Ctx c, String key, Object[]... items) {
        Instant now = Instant.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("external_order_key", key);
        m.put("ordered_at", now.toString());
        m.put("paid_at", now.toString());
        m.put("external_updated_at", now.toString());
        List<Map<String, Object>> list = new ArrayList<>();
        int total = 0;
        int n = 1;
        for (Object[] it : items) {
            int q = (Integer) it[1];
            int price = (Integer) it[2];
            int sd = (Integer) it[3];
            int cd = (Integer) it[4];
            Map<String, Object> line = new LinkedHashMap<>(Map.of("external_item_key", key + "-" + n++, "external_product_id", it[0], "product_name", "앰플", "quantity", q,
                    "unit_price", price, "line_amount", price * q, "seller_discount", sd, "channel_discount", cd));
            if (Boolean.TRUE.equals(it[5])) {
                line.put("gift", true);
            }
            list.add(line);
            total += price * q - sd - cd;
        }
        m.put("total_amount", total);
        m.put("recipient", Map.of("name", "홍길동", "phone", "010-1234-5678", "address1", "서울특별시 강남구 테헤란로 152"));
        m.put("items", list);
        Api.Res r = c.o().post("/api/v1/orders/imports", Map.of("channel_account_id", c.acc().toString(), "orders", List.of(m)));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        drainAsync();
        return r.data().get("results").get(0).get("order_id").asString();
    }

    String shipOrder(Ctx c, String orderId) {
        JsonNode f = c.o().get("/api/v1/fulfillment-orders?order_id=" + orderId).data().get(0);
        Api.Res r = c.o().post("/api/v1/fulfillment-orders/" + f.get("id").asString() + "/ship", Map.of("shipped_at", Instant.now().minusSeconds(5).toString(),
                "parcels", List.of(Map.of("carrier_code", "CJ", "tracking_no", "6" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000000000L)))));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        drainAsync();
        return f.get("id").asString();
    }

    JsonNode total(Api api, UUID be) {
        Api.Res r = api.get("/api/v1/finance/profit?business_entity_id=" + be);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.data().get("total");
    }

    static int cost(JsonNode row, String category) {
        JsonNode v = row.get("costs").get(category);
        return v == null ? 0 : v.asInt();
    }

    String itemId(Ctx c, String orderId, String product) {
        for (JsonNode i : c.o().get("/api/v1/orders/" + orderId).data().get("items")) {
            if (i.get("external_product_id").asString().equals(product)) {
                return i.get("id").asString();
            }
        }
        throw new AssertionError();
    }

    String returnAndInspect(Ctx c, String orderId, String type, String condition, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("order_id", orderId, "claim_type", type, "reason_code", "CHANGE_OF_MIND",
                "items", List.of(Map.of("order_item_id", itemId(c, orderId, "P-100"), "quantity", 1))));
        body.putAll(extra);
        Api.Res cl = c.o().post("/api/v1/claims", body);
        assertThat(cl.status()).as(cl.body().toString()).isEqualTo(201);
        String id = cl.data().get("id").asString();
        c.o().post("/api/v1/claims/" + id + "/approve", Map.of());
        drainAsync();
        JsonNode rc = c.o().post("/api/v1/returns/receipts", Map.of("node_id", c.hs().toString(), "claim_id", id, "lines",
                List.of(Map.of("sku_id", c.amp().toString(), "quantity", 1)))).data();
        drainAsync();
        c.o().post("/api/v1/returns/receipts/" + rc.get("id").asString() + "/inspections", Map.of("lines", List.of(Map.of("receipt_line_id",
                rc.get("lines").get(0).get("id").asString(), "decisions", List.of(Map.of("condition", condition, "quantity", 1))))));
        drainAsync();
        return id;
    }
}
