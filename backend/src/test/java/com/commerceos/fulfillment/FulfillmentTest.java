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

/** M4 O4: 자동 출고 배정(우선순위·한 거점 충족·원자적 확보), 출고지시 전송(자체 WMS·3PL API·수동), 타임아웃 사후조회(T11), 취소. */
class FulfillmentTest extends IntegrationTest {

    record Ctx(Fixtures.Ws ws, Api o, UUID be, UUID acc, UUID unit, UUID sku, UUID hs, UUID gp) {
    }

    Ctx ctx() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        UUID be = fx.businessEntity(o, "에이상사");
        UUID acc = fx.channelAccount(o, be, "NAVER_SMARTSTORE", "A 스토어");
        UUID unit = UUID.fromString(o.get("/api/v1/channel-accounts/" + acc + "/sales-units").data().get(0).get("id").asString());
        UUID product = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "AMP", "name", "앰플")).data().get("product").get("id").asString());
        UUID sku = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "AMP-30", "sku_type", "NORMAL")).id();
        Api.Res l = o.post("/api/v1/channel-listings", Map.of("sales_unit_id", unit.toString(), "external_product_id", "P-100", "external_option_id", "", "title", "앰플"));
        o.post("/api/v1/channel-listings/" + l.data().get("listing").get("id").asString() + "/mapping", Map.of("sku_id", sku.toString()));
        UUID hs = node(o, "HS", "SELF_WMS", 100);
        UUID gp = node(o, "GP", "SELF_WMS", 10);
        return new Ctx(ws, o, be, acc, unit, sku, hs, gp);
    }

    UUID node(Api o, String code, String provider, int priority) {
        UUID id = o.post("/api/v1/fulfillment-nodes", Map.of("code", code, "name", code + " 센터", "logistics_provider_code", provider, "cutoff_time", "16:00",
                "inventory_source", "INTERNAL")).id();
        jdbc.sql("update fulfillment_node set routing_priority = :p where id = :id").param("p", priority).param("id", id).update();
        return id;
    }

    void stock(Ctx c, UUID node, int qty) {
        Api.Res r = c.o().post("/api/v1/inventory/receipts", Map.of("business_entity_id", c.be().toString(), "sku_id", c.sku().toString(), "node_id", node.toString(),
                "quantity", qty));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    }

    static Map<String, Object> order(String key, int qty) {
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
        return m;
    }

    String importOrder(Ctx c, String key, int qty) {
        Api.Res r = c.o().post("/api/v1/orders/imports", Map.of("channel_account_id", c.acc().toString(), "orders", List.of(order(key, qty))));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        drainAsync();
        return r.data().get("results").get(0).get("order_id").asString();
    }

    JsonNode fo(Ctx c, String orderId) {
        JsonNode list = c.o().get("/api/v1/fulfillment-orders?order_id=" + orderId).data();
        return list.isEmpty() ? null : list.get(0);
    }

    int committed(Ctx c, UUID node) {
        for (JsonNode n : c.o().get("/api/v1/inventory/availability?business_entity_id=" + c.be() + "&sku_id=" + c.sku()).data().get("nodes")) {
            if (n.get("node_id").asString().equals(node.toString())) {
                return n.get("committed").asInt();
            }
        }
        return 0;
    }

    @Test
    void autoAllocation_highestPriorityNodeThatFillsWholeOrder_atomicReservation_selfWmsAcceptedInternally() {
        Ctx c = ctx();
        stock(c, c.hs(), 5);
        String o1 = importOrder(c, "ORD-1", 2);
        JsonNode f = fo(c, o1);
        assertThat(f.get("node_id").asString()).isEqualTo(c.hs().toString()); // GP 가 우선이지만 재고 없음
        assertThat(f.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(f.get("dispatch_mode").asString()).isEqualTo("INTERNAL");
        assertThat(f.get("lines").get(0).get("reservation_state").asString()).isEqualTo("ALLOCATED");
        assertThat(committed(c, c.hs())).isEqualTo(2);
        JsonNode od = c.o().get("/api/v1/orders/" + o1).data();
        assertThat(od.get("order").get("status").asString()).isEqualTo("PROCESSING");
        // 우선순위 높은 GP 에 재고가 생기면 다음 주문은 GP
        stock(c, c.gp(), 5);
        assertThat(fo(c, importOrder(c, "ORD-2", 1)).get("node_id").asString()).isEqualTo(c.gp().toString());
        // 같은 주문 다시 배정 → 이미 다 담겨 있음
        assertThat(c.o().post("/api/v1/orders/" + o1 + "/allocate", Map.of()).data().get("outcome").asString()).isEqualTo("NOTHING_TO_ALLOCATE");
        // 시스템 자동 처리로 남은 감사로그
        assertThat(jdbc.sql("select count(*) from audit_log where workspace_id = :ws and action = 'FULFILLMENT_ALLOCATED' and actor_type = 'SYSTEM'")
                .param("ws", c.ws().id()).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void noSingleNodeCanFill_noSplit_failsWithWorkItem_manualAllocateAfterStock() {
        Ctx c = ctx();
        stock(c, c.hs(), 1);
        stock(c, c.gp(), 1);
        String o = importOrder(c, "ORD-3", 2);
        assertThat(fo(c, o)).isNull();
        JsonNode od = c.o().get("/api/v1/orders/" + o).data();
        assertThat(od.get("order").get("status").asString()).isEqualTo("CONFIRMED");
        List<JsonNode> items = new java.util.ArrayList<>();
        c.o().get("/api/v1/work-items?item_type=ORDER_ALLOCATION_FAILED&status=OPEN").data().forEach(items::add);
        assertThat(items).hasSize(1);
        assertThat(committed(c, c.hs()) + committed(c, c.gp())).isZero(); // 부분 확보 없음
        stock(c, c.hs(), 1);
        JsonNode r = c.o().post("/api/v1/orders/" + o + "/allocate", Map.of()).data();
        assertThat(r.get("outcome").asString()).isEqualTo("ALLOCATED");
        assertThat(c.o().get("/api/v1/work-items?item_type=ORDER_ALLOCATION_FAILED&status=OPEN").data()).isEmpty();
        // 권한: 조회 전용은 배정 불가
        Api viewer = fx.member(c.ws(), "VIEWER");
        assertThat(viewer.post("/api/v1/orders/" + o + "/allocate", Map.of()).errorCode()).isEqualTo("PERMISSION_DENIED");
    }

    UUID thirdPartyNode(Ctx c, boolean capability) {
        UUID node = node(c.o(), "FS", "FASSTO", 1);
        UUID shipper = fx.shipperAccount(c.o(), c.be(), "FASSTO", "SHIP-" + UUID.randomUUID().toString().substring(0, 6));
        c.o().post("/api/v1/fulfillment-nodes/" + node + "/shippers", Map.of("shipper_account_id", shipper.toString()));
        Api.Res conn = c.o().post("/api/v1/integrations/connections", Map.of("target_type", "SHIPPER_ACCOUNT", "target_id", shipper.toString(),
                "connector_code", "mock-3pl", "display_name", "파스토 모의", "auth_type", "API_KEY", "credential", Map.of("api_key", "k")));
        UUID connId = conn.id();
        if (capability) {
            assertThat(c.o().put("/api/v1/integrations/connections/" + connId + "/capabilities/OUTBOUND_REQUEST/WRITE", Map.of("support_level", "OFFICIAL_DOCUMENTED",
                    "account_verification", "VERIFIED", "evidence_ref", "테스트 계정 출고 요청 응답"), 1).status()).isEqualTo(200);
        }
        c.o().post("/api/v1/integrations/" + connId + "/sync-jobs", Map.of());
        drainAsync();
        stock(c, node, 10);
        lastConnection = connId;
        return node;
    }

    UUID lastConnection;

    @Test
    void thirdPartyDispatch_viaApi_recordsExternalAck_T11TimeoutAfterAcceptIsCheckedNotResent() {
        Ctx c = ctx();
        UUID fs = thirdPartyNode(c, true);
        String o1 = importOrder(c, "ORD-API-1", 2);
        JsonNode f1 = fo(c, o1);
        assertThat(f1.get("node_id").asString()).isEqualTo(fs.toString());
        assertThat(f1.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(f1.get("dispatch_mode").asString()).isEqualTo("API");
        assertThat(f1.get("external_ref").asString()).startsWith("MOCK-OUT-");
        String res = f1.get("lines").get(0).get("reservation_id").asString();
        assertThat(c.o().get("/api/v1/inventory/reservations/" + res).data().get("external_ack_at").isNull()).isFalse();

        // T11: 물류사가 접수했는데 응답이 끊김 → 재시도는 사후조회로 확인하고 다시 보내지 않는다
        mockControl.timeoutAfterAcceptOnce(lastConnection);
        String o2 = importOrder(c, "ORD-API-2", 1);
        JsonNode f2 = fo(c, o2);
        assertThat(f2.get("status").asString()).isEqualTo("REQUESTED");
        jdbc.sql("update async_job set available_at = now() where workspace_id = :ws and status = 'RETRY_WAIT'").param("ws", c.ws().id()).update();
        drainAsync();
        f2 = fo(c, o2);
        assertThat(f2.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(f2.get("attempts").asInt()).isEqualTo(2);
        assertThat(mockControl.outboundRequests(lastConnection, f2.get("dispatch_key").asString())).isEqualTo(1);
    }

    @Test
    void noDispatchCapability_manualWorkItem_manualAccept_cancelReleasesReservation() {
        Ctx c = ctx();
        UUID fs = thirdPartyNode(c, false);
        String o = importOrder(c, "ORD-M", 3);
        JsonNode f = fo(c, o);
        assertThat(f.get("node_id").asString()).isEqualTo(fs.toString());
        assertThat(f.get("status").asString()).isEqualTo("ALLOCATED");
        assertThat(f.get("dispatch_mode").asString()).isEqualTo("MANUAL");
        assertThat(c.o().get("/api/v1/work-items?item_type=FULFILLMENT_DISPATCH_MANUAL&status=OPEN").data()).hasSize(1);
        assertThat(committed(c, fs)).isEqualTo(3);

        // 배정만 된 건은 바로 취소 → 확보 해제, 주문은 다시 배정 대기
        JsonNode cancelled = c.o().post("/api/v1/fulfillment-orders/" + f.get("id").asString() + "/cancel", Map.of("reason", "고객 주소 확인")).data();
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(committed(c, fs)).isZero();
        JsonNode od = c.o().get("/api/v1/orders/" + o).data();
        assertThat(od.get("order").get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(c.o().get("/api/v1/work-items?item_type=FULFILLMENT_DISPATCH_MANUAL&status=OPEN").data()).isEmpty();

        // 다시 배정 → 수동 전달 후 접수 기록 → 접수 이후 취소는 물류사 확인 필요
        c.o().post("/api/v1/orders/" + o + "/allocate", Map.of());
        drainAsync();
        JsonNode f2 = fo(c, o);
        assertThat(c.o().post("/api/v1/fulfillment-orders/" + f2.get("id").asString() + "/manual-accept", Map.of("external_ref", "")).errorCode())
                .isEqualTo("VALIDATION_FAILED");
        JsonNode accepted = c.o().post("/api/v1/fulfillment-orders/" + f2.get("id").asString() + "/manual-accept",
                Map.of("external_ref", "FS-OUT-9001", "accepted_at", Instant.now().toString())).data();
        assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
        JsonNode req = c.o().post("/api/v1/fulfillment-orders/" + f2.get("id").asString() + "/cancel", Map.of("reason", "고객 취소")).data();
        assertThat(req.get("status").asString()).isEqualTo("CANCEL_REQUESTED");
        assertThat(committed(c, fs)).isEqualTo(3); // 물류사 확인 전 확보 유지
        JsonNode done = c.o().post("/api/v1/fulfillment-orders/" + f2.get("id").asString() + "/cancel",
                Map.of("reason", "물류사 출고 중지 확인", "pick_return_confirmed", true)).data();
        assertThat(done.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(committed(c, fs)).isZero();
    }

    @Test
    void concurrentAllocation_ofScarceStock_exactlyOneOrderWins_noDoubleCommit() throws Exception {
        Ctx c = ctx();
        List<String> orders = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            orders.add(importOrder(c, "ORD-RACE-" + i, 1)); // 재고 없음 → 모두 배정 실패
        }
        stock(c, c.hs(), 2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        List<java.util.concurrent.Callable<String>> tasks = new java.util.ArrayList<>();
        for (String o : orders) {
            tasks.add(() -> c.o().post("/api/v1/orders/" + o + "/allocate", Map.of()).data().get("outcome").asString());
        }
        List<String> outcomes = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<String> f : pool.invokeAll(tasks)) {
            outcomes.add(f.get());
        }
        pool.shutdown();
        assertThat(outcomes).filteredOn("ALLOCATED"::equals).hasSize(2);
        assertThat(outcomes).filteredOn("FAILED"::equals).hasSize(4);
        assertThat(committed(c, c.hs())).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from fulfillment_order where workspace_id = :ws").param("ws", c.ws().id()).query(Integer.class).single()).isEqualTo(2);

        // 같은 주문을 동시에 여러 번 배정해도 출고지시는 하나(주문 행 잠금)
        stock(c, c.hs(), 1);
        String failed = orders.stream().filter(o -> c.o().get("/api/v1/fulfillment-orders?order_id=" + o).data().isEmpty()).findFirst().orElseThrow();
        java.util.concurrent.ExecutorService pool2 = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Callable<String>> same = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            same.add(() -> c.o().post("/api/v1/orders/" + failed + "/allocate", Map.of()).data().get("outcome").asString());
        }
        List<String> sameOutcomes = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<String> f : pool2.invokeAll(same)) {
            sameOutcomes.add(f.get());
        }
        pool2.shutdown();
        assertThat(sameOutcomes).filteredOn("ALLOCATED"::equals).hasSize(1);
        assertThat(sameOutcomes).filteredOn("NOTHING_TO_ALLOCATE"::equals).hasSize(3);
        assertThat(committed(c, c.hs())).isEqualTo(3);
    }

    @Test
    void systemAutomation_inConsecutiveWorkspaces_doesNotReuseOtherWorkspaceScope() {
        // 작업 스레드를 재사용해도 시스템 자동 처리 주체의 범위는 워크스페이스마다 새로 계산된다
        Ctx a = ctx();
        Ctx b = ctx();
        stock(a, a.hs(), 3);
        stock(b, b.gp(), 3);
        assertThat(fo(a, importOrder(a, "ORD-W1", 1)).get("node_id").asString()).isEqualTo(a.hs().toString());
        assertThat(fo(b, importOrder(b, "ORD-W2", 1)).get("node_id").asString()).isEqualTo(b.gp().toString());
        assertThat(fo(a, importOrder(a, "ORD-W3", 1))).isNotNull();
    }

    @Test
    void fulfillmentOrders_respectNodeAndBusinessScope() {
        Ctx c = ctx();
        stock(c, c.hs(), 5);
        stock(c, c.gp(), 5);
        String a = importOrder(c, "ORD-S1", 1); // GP
        jdbc.sql("update fulfillment_node set routing_priority = 1 where id = :id").param("id", c.hs()).update();
        String b = importOrder(c, "ORD-S2", 1); // HS
        Api logi = fx.member(c.ws(), "LOGISTICS_MANAGER");
        fx.setPolicy(c.o(), logi, null, List.of(scope("FULFILLMENT_NODE", "READ", "SELECTED", c.hs(), "ALLOW")));
        JsonNode visible = logi.get("/api/v1/fulfillment-orders").data();
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).get("order_id").asString()).isEqualTo(b);
        assertThat(logi.get("/api/v1/fulfillment-orders/" + fo(c, a).get("id").asString()).status()).isEqualTo(404);
        assertThat(fx.workspace().owner().get("/api/v1/fulfillment-orders/" + fo(c, a).get("id").asString()).status()).isEqualTo(404);
    }
}
