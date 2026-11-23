package com.commerceos.fulfillment;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M4 O7 자체 WMS 기본: 웨이브 버킷 계획(이중 계획 없음), 스캔 피킹 차단, 결품 재계획, 스캔 검수 포장, 피킹 버킷 그대로 출고, 로케이션 이동. */
class WmsTest extends IntegrationTest {
    static final String BARCODE = "8800000000301";

    record Ctx(Fixtures.Ws ws, Api o, UUID be, UUID acc, UUID sku, UUID hs) {
    }

    Ctx ctx() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        UUID be = fx.businessEntity(o, "에이상사");
        UUID acc = fx.channelAccount(o, be, "NAVER_SMARTSTORE", "A 스토어");
        UUID unit = UUID.fromString(o.get("/api/v1/channel-accounts/" + acc + "/sales-units").data().get(0).get("id").asString());
        UUID product = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "AMP", "name", "앰플")).data().get("product").get("id").asString());
        UUID sku = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "AMP-30", "sku_type", "NORMAL", "barcode", BARCODE)).id();
        Api.Res l = o.post("/api/v1/channel-listings", Map.of("sales_unit_id", unit.toString(), "external_product_id", "P-100", "external_option_id", "", "title", "앰플"));
        o.post("/api/v1/channel-listings/" + l.data().get("listing").get("id").asString() + "/mapping", Map.of("sku_id", sku.toString()));
        UUID hs = o.post("/api/v1/fulfillment-nodes", Map.of("code", "HS", "name", "HS 센터", "logistics_provider_code", "SELF_WMS", "cutoff_time", "16:00",
                "inventory_source", "INTERNAL")).id();
        for (String loc : List.of("A-01", "B-01", "C-01")) {
            assertThat(o.post("/api/v1/fulfillment-nodes/" + hs + "/locations", Map.of("code", loc, "zone", loc.substring(0, 1))).status()).isEqualTo(201);
        }
        Ctx c = new Ctx(ws, o, be, acc, sku, hs);
        receive(c, "A-01", 2);
        receive(c, "B-01", 3);
        return c;
    }

    void receive(Ctx c, String loc, int qty) {
        Api.Res r = c.o().post("/api/v1/inventory/receipts", Map.of("business_entity_id", c.be().toString(), "sku_id", c.sku().toString(), "node_id", c.hs().toString(),
                "quantity", qty, "location_code", loc));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    }

    String importOrder(Ctx c, String key, int qty) {
        Instant now = Instant.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("external_order_key", key);
        m.put("ordered_at", now.toString());
        m.put("paid_at", now.toString());
        m.put("external_updated_at", now.toString());
        m.put("total_amount", 10000 * qty);
        m.put("recipient", Map.of("name", "홍길동", "phone", "010-1234-5678", "address1", "서울특별시 강남구 테헤란로 152"));
        m.put("items", List.of(Map.of("external_item_key", key + "-1", "external_product_id", "P-100", "product_name", "앰플", "quantity", qty, "unit_price", 10000,
                "line_amount", 10000 * qty)));
        Api.Res r = c.o().post("/api/v1/orders/imports", Map.of("channel_account_id", c.acc().toString(), "orders", List.of(m)));
        drainAsync();
        return r.data().get("results").get(0).get("order_id").asString();
    }

    JsonNode fo(Ctx c, String orderId) {
        return c.o().get("/api/v1/fulfillment-orders?order_id=" + orderId).data().get(0);
    }

    int onHandAt(Ctx c, String loc) {
        int n = 0;
        for (JsonNode s : c.o().get("/api/v1/inventory/stocks?sku_id=" + c.sku()).data()) {
            if (loc.equals(s.get("location_code").asString(null)) && "SELLABLE".equals(s.get("quality_status").asString())) {
                n += s.get("on_hand").asInt();
            }
        }
        return n;
    }

    String stockAt(Ctx c, String loc) {
        for (JsonNode s : c.o().get("/api/v1/inventory/stocks?sku_id=" + c.sku()).data()) {
            if (loc.equals(s.get("location_code").asString(null)) && s.get("on_hand").asInt() > 0) {
                return s.get("id").asString();
            }
        }
        throw new AssertionError("no stock at " + loc);
    }

    Api.Res scan(Api api, String wave, String loc, String barcode, int qty) {
        return api.post("/api/v1/wms/waves/" + wave + "/picks", Map.of("location_code", loc, "barcode", barcode, "quantity", qty));
    }

    static Map<String, Object> ship(String tracking) {
        return Map.of("shipped_at", Instant.now().minusSeconds(5).toString(), "parcels", List.of(Map.of("carrier_code", "CJ", "tracking_no", tracking)));
    }

    @Test
    void wavePlansBucketsOnce_scanPickBlocksWrongInput_packVerifies_shipConsumesPickedBuckets() {
        Ctx c = ctx();
        Api wh = fx.member(c.ws(), "WAREHOUSE_WORKER");
        String o1 = importOrder(c, "W-1", 3);
        String o2 = importOrder(c, "W-2", 2);
        assertThat(wh.get("/api/v1/wms/candidates?node_id=" + c.hs()).data().get("count").asInt()).isEqualTo(2);
        Api.Res w1r = wh.post("/api/v1/wms/waves", Map.of("node_id", c.hs().toString(), "fulfillment_order_ids", List.of(fo(c, o1).get("id").asString())));
        assertThat(w1r.status()).as(w1r.body().toString()).isEqualTo(201);
        JsonNode w1 = w1r.data();
        // 유통기한 같으면 로케이션 순: A-01 ×2, B-01 ×1
        assertThat(w1.get("tasks")).hasSize(2);
        assertThat(w1.get("tasks").get(0).get("location_code").asString()).isEqualTo("A-01");
        assertThat(w1.get("tasks").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(w1.get("tasks").get(1).get("location_code").asString()).isEqualTo("B-01");
        JsonNode f1 = fo(c, o1);
        assertThat(f1.get("status").asString()).isEqualTo("PICKING");
        assertThat(f1.get("lines").get(0).get("reservation_state").asString()).isEqualTo("PICKING");
        // 두 번째 웨이브는 이미 계획된 실물을 다시 잡지 않는다 → B-01 남은 2
        JsonNode w2 = wh.post("/api/v1/wms/waves", Map.of("node_id", c.hs().toString())).data();
        assertThat(w2.get("tasks")).hasSize(1);
        assertThat(w2.get("tasks").get(0).get("location_code").asString()).isEqualTo("B-01");
        assertThat(w2.get("tasks").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(wh.post("/api/v1/wms/waves", Map.of("node_id", c.hs().toString())).errorCode()).isEqualTo("WMS_NOTHING_TO_PICK");

        String wave1 = w1.get("id").asString();
        assertThat(scan(wh, wave1, "A-01", "9999999", 1).errorCode()).isEqualTo("SCAN_REJECTED"); // 모르는 바코드
        assertThat(scan(wh, wave1, "C-01", BARCODE, 1).errorCode()).isEqualTo("SCAN_REJECTED"); // 다른 로케이션
        assertThat(scan(wh, wave1, "B-01", BARCODE, 2).errorCode()).isEqualTo("SCAN_REJECTED"); // 초과
        assertThat(jdbc.sql("select count(*) from wms_scan where workspace_id = :ws and result = 'REJECTED'").param("ws", c.ws().id()).query(Integer.class).single())
                .isEqualTo(3);
        // 포장 전 출고 확정 불가
        assertThat(c.o().post("/api/v1/fulfillment-orders/" + f1.get("id").asString() + "/ship", ship("600000000001")).errorCode()).isEqualTo("WMS_NOT_PACKED");
        assertThat(wh.post("/api/v1/wms/fulfillment-orders/" + f1.get("id").asString() + "/pack", Map.of("scans", List.of(Map.of("barcode", BARCODE, "quantity", 3))))
                .errorCode()).isEqualTo("WMS_PICK_INCOMPLETE");
        scan(wh, wave1, "a-01", BARCODE, 2);
        JsonNode done = scan(wh, wave1, "B-01", "AMP-30", 1).data();
        assertThat(done.get("status").asString()).isEqualTo("PICKED");
        // 검수: 수량이 정확해야
        assertThat(wh.post("/api/v1/wms/fulfillment-orders/" + f1.get("id").asString() + "/pack", Map.of("scans", List.of(Map.of("barcode", BARCODE, "quantity", 2))))
                .errorCode()).isEqualTo("SCAN_REJECTED");
        JsonNode packed = wh.post("/api/v1/wms/fulfillment-orders/" + f1.get("id").asString() + "/pack",
                Map.of("scans", List.of(Map.of("barcode", BARCODE, "quantity", 2), Map.of("barcode", BARCODE, "quantity", 1)))).data();
        assertThat(packed.get("status").asString()).isEqualTo("PACKING");
        Api.Res sh = c.o().post("/api/v1/fulfillment-orders/" + f1.get("id").asString() + "/ship", ship("600000000001"));
        assertThat(sh.status()).as(sh.body().toString()).isEqualTo(200);
        assertThat(onHandAt(c, "A-01")).isZero();
        assertThat(onHandAt(c, "B-01")).isEqualTo(2);
        assertThat(wh.get("/api/v1/wms/waves/" + wave1).data().get("status").asString()).isEqualTo("CLOSED");

        // 웨이브2: 계획된 B-01 은 옮길 수 없음 → 결품 처리 시 C-01 보충분으로 재계획 → 그 버킷에서 출고
        assertThat(wh.post("/api/v1/inventory/location-moves", Map.of("stock_id", stockAt(c, "B-01"), "quantity", 1, "to_location_code", "C-01")).errorCode())
                .isEqualTo("STOCK_PLANNED_FOR_PICK");
        receive(c, "C-01", 2);
        String wave2 = w2.get("id").asString();
        JsonNode afterShort = wh.post("/api/v1/wms/pick-tasks/" + w2.get("tasks").get(0).get("id").asString() + "/short", Map.of("reason", "로케이션 비어 있음")).data();
        assertThat(afterShort.get("tasks")).hasSize(2);
        assertThat(afterShort.get("tasks").toString()).contains("C-01");
        assertThat(c.o().get("/api/v1/work-items?item_type=WMS_LOCATION_DISCREPANCY&status=OPEN").data()).hasSize(1);
        scan(wh, wave2, "C-01", BARCODE, 2);
        String fo2 = fo(c, o2).get("id").asString();
        wh.post("/api/v1/wms/fulfillment-orders/" + fo2 + "/pack", Map.of("scans", List.of(Map.of("barcode", BARCODE, "quantity", 2))));
        assertThat(c.o().post("/api/v1/fulfillment-orders/" + fo2 + "/ship", ship("600000000002")).status()).isEqualTo(200);
        assertThat(onHandAt(c, "C-01")).isZero();
        assertThat(onHandAt(c, "B-01")).isEqualTo(2);
        // 계획이 풀린 버킷은 로케이션 이동 가능, 판매가능 수량 불변·원장 두 줄
        Api.Res mv = wh.post("/api/v1/inventory/location-moves", Map.of("stock_id", stockAt(c, "B-01"), "quantity", 2, "to_location_code", "A-01", "reason", "보충"));
        assertThat(mv.status()).as(mv.body().toString()).isEqualTo(201);
        assertThat(onHandAt(c, "A-01")).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from inventory_transaction where workspace_id = :ws and txn_type = 'LOCATION_MOVE'").param("ws", c.ws().id())
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(c.o().get("/api/v1/inventory/verification").data()).isEmpty();
    }

    @Test
    void concurrentWaves_neverPlanMoreThanBucketHolds() throws Exception {
        Ctx c = ctx();
        String a = fo(c, importOrder(c, "W-A", 2)).get("id").asString();
        String b = fo(c, importOrder(c, "W-B", 2)).get("id").asString();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Callable<Integer>> tasks = List.of(
                () -> c.o().post("/api/v1/wms/waves", Map.of("node_id", c.hs().toString(), "fulfillment_order_ids", List.of(a))).status(),
                () -> c.o().post("/api/v1/wms/waves", Map.of("node_id", c.hs().toString(), "fulfillment_order_ids", List.of(b))).status());
        for (java.util.concurrent.Future<Integer> f : pool.invokeAll(tasks)) {
            assertThat(f.get()).isEqualTo(201);
        }
        pool.shutdown();
        // 버킷별 계획 수량 ≤ 실물
        assertThat(jdbc.sql("""
                select count(*) from inventory_stock s where s.workspace_id = :ws
                   and (select coalesce(sum(t.quantity), 0) from wms_pick_task t where t.stock_id = s.id and t.status in ('OPEN', 'PICKED')) > s.on_hand
                """).param("ws", c.ws().id()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select coalesce(sum(quantity), 0) from wms_pick_task where workspace_id = :ws").param("ws", c.ws().id()).query(Integer.class).single())
                .isEqualTo(4);
    }

    @Test
    void wms_onlySelfNodes_nodeScopeEnforced() {
        Ctx c = ctx();
        UUID fs = c.o().post("/api/v1/fulfillment-nodes", Map.of("code", "FS", "name", "파스토", "logistics_provider_code", "FASSTO", "cutoff_time", "15:00")).id();
        assertThat(c.o().post("/api/v1/wms/waves", Map.of("node_id", fs.toString())).errorCode()).isEqualTo("WMS_NODE_NOT_SELF");
        importOrder(c, "W-S", 1);
        String wave = c.o().post("/api/v1/wms/waves", Map.of("node_id", c.hs().toString())).data().get("id").asString();
        Api logi = fx.member(c.ws(), "LOGISTICS_MANAGER");
        fx.setPolicy(c.o(), logi, null, List.of(scope("FULFILLMENT_NODE", "READ", "SELECTED", fs, "ALLOW")));
        assertThat(logi.get("/api/v1/wms/waves").data()).isEmpty();
        assertThat(logi.get("/api/v1/wms/waves/" + wave).status()).isIn(403, 404);
        assertThat(scan(logi, wave, "A-01", BARCODE, 1).status()).isIn(403, 404);
        assertThat(fx.workspace().owner().get("/api/v1/wms/waves/" + wave).status()).isEqualTo(404);
    }
}
