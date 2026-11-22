package com.commerceos.fulfillment;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** M4 O5: 출고 확정·송장(박스 1~N)·배송 단계(역행 금지)·판매채널 발송처리·송장 파일·3PL 출고상태 수신·이중 차감 금지·SLA·주문 완료. */
class ShipmentTest extends IntegrationTest {
    @Autowired
    ShipmentWatch watch;

    record Ctx(Fixtures.Ws ws, Api o, UUID be, UUID acc, UUID sku, UUID hs) {
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
        UUID hs = o.post("/api/v1/fulfillment-nodes", Map.of("code", "HS", "name", "HS 센터", "logistics_provider_code", "SELF_WMS", "cutoff_time", "16:00",
                "inventory_source", "INTERNAL")).id();
        return new Ctx(ws, o, be, acc, sku, hs);
    }

    void stock(Ctx c, UUID node, int qty) {
        Api.Res r = c.o().post("/api/v1/inventory/receipts", Map.of("business_entity_id", c.be().toString(), "sku_id", c.sku().toString(), "node_id", node.toString(),
                "quantity", qty));
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
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        drainAsync();
        return r.data().get("results").get(0).get("order_id").asString();
    }

    JsonNode fo(Ctx c, String orderId) {
        return c.o().get("/api/v1/fulfillment-orders?order_id=" + orderId).data().get(0);
    }

    JsonNode order(Ctx c, String orderId) {
        return c.o().get("/api/v1/orders/" + orderId).data();
    }

    JsonNode shipments(Ctx c, String orderId) {
        return c.o().get("/api/v1/shipments?order_id=" + orderId).data();
    }

    int onHand(Ctx c, UUID node) {
        for (JsonNode n : c.o().get("/api/v1/inventory/availability?business_entity_id=" + c.be() + "&sku_id=" + c.sku()).data().get("nodes")) {
            if (n.get("node_id").asString().equals(node.toString())) {
                return n.get("sellable_on_hand").asInt();
            }
        }
        return 0;
    }

    static Map<String, Object> ship(Instant at, Object... parcels) {
        return Map.of("shipped_at", at.toString(), "parcels", List.of(parcels));
    }

    @Test
    void selfWmsShip_consumesOnce_updatesOrder_duplicateIsIdempotent_channelDispatchManualWithoutCapability() {
        Ctx c = ctx();
        stock(c, c.hs(), 5);
        String o = importOrder(c, "ORD-1", 2);
        JsonNode f = fo(c, o);
        assertThat(f.get("status").asString()).isEqualTo("ACCEPTED");
        String foId = f.get("id").asString();
        Instant at = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);

        assertThat(c.o().post("/api/v1/fulfillment-orders/" + foId + "/ship", ship(at, Map.of("carrier_code", "NOPE", "tracking_no", "123456789"))).errorCode())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(c.o().post("/api/v1/fulfillment-orders/" + foId + "/ship", ship(Instant.now().plus(1, ChronoUnit.HOURS),
                Map.of("carrier_code", "CJ", "tracking_no", "123456789"))).errorCode()).isEqualTo("VALIDATION_FAILED");
        Api.Res r = c.o().post("/api/v1/fulfillment-orders/" + foId + "/ship", ship(at, Map.of("carrier_code", "CJ", "tracking_no", "6012-3456-7890")));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.data().get("status").asString()).isEqualTo("SHIPPED");
        assertThat(r.data().get("lines").get(0).get("reservation_state").asString()).isEqualTo("CONSUMED");
        assertThat(onHand(c, c.hs())).isEqualTo(3);
        JsonNode od = order(c, o);
        assertThat(od.get("items").get(0).get("shipped_qty").asInt()).isEqualTo(2);
        assertThat(od.get("shipping_status").asString()).isEqualTo("SHIPPED");
        JsonNode s = shipments(c, o).get(0);
        assertThat(s.get("tracking_no").asString()).isEqualTo("601234567890");
        assertThat(s.get("status").asString()).isEqualTo("LABEL_CREATED"); // 송장 발급 ≠ 배송완료
        assertThat(java.time.OffsetDateTime.parse(s.get("shipped_at").asString()).toInstant()).isEqualTo(at);

        // 같은 송장 재전송: 중복 없음 / 다른 송장: 거부
        assertThat(c.o().post("/api/v1/fulfillment-orders/" + foId + "/ship", ship(at, Map.of("carrier_code", "CJ", "tracking_no", "601234567890"))).status())
                .isEqualTo(200);
        assertThat(c.o().post("/api/v1/fulfillment-orders/" + foId + "/ship", ship(at, Map.of("carrier_code", "CJ", "tracking_no", "999999999"))).errorCode())
                .isEqualTo("FULFILLMENT_ALREADY_SHIPPED");
        assertThat(onHand(c, c.hs())).isEqualTo(3);
        assertThat(shipments(c, o)).hasSize(1);
        assertThat(jdbc.sql("select count(*) from inventory_transaction where workspace_id = :ws and txn_type = 'CONSUME'").param("ws", c.ws().id()).query(Integer.class).single())
                .isEqualTo(1);

        // 판매채널 연결 없음 → 수동 발송처리 업무 → 완료 기록
        drainAsync();
        s = shipments(c, o).get(0);
        assertThat(s.get("channel_dispatch_status").asString()).isEqualTo("MANUAL_REQUIRED");
        assertThat(c.o().get("/api/v1/work-items?item_type=CHANNEL_DISPATCH_MANUAL&status=OPEN").data()).hasSize(1);
        JsonNode done = c.o().post("/api/v1/shipments/" + s.get("id").asString() + "/channel-dispatch/manual-done", Map.of("note", "판매자센터 발송처리")).data();
        assertThat(done.get("channel_dispatch_status").asString()).isEqualTo("MANUAL_DONE");
        assertThat(c.o().get("/api/v1/work-items?item_type=CHANNEL_DISPATCH_MANUAL&status=OPEN").data()).isEmpty();
        // 취소된 출고지시는 출고 확정 불가
        String o2 = importOrder(c, "ORD-2", 1);
        String fo2 = fo(c, o2).get("id").asString();
        c.o().post("/api/v1/fulfillment-orders/" + fo2 + "/cancel", Map.of("reason", "x", "pick_return_confirmed", true));
        assertThat(c.o().post("/api/v1/fulfillment-orders/" + fo2 + "/ship", ship(at, Map.of("carrier_code", "CJ", "tracking_no", "111122223333"))).errorCode())
                .isEqualTo("FULFILLMENT_CANCELLED");
        // 권한
        Api viewer = fx.member(c.ws(), "VIEWER");
        assertThat(viewer.post("/api/v1/fulfillment-orders/" + foId + "/ship", ship(at, Map.of("carrier_code", "CJ", "tracking_no", "111122223333"))).errorCode())
                .isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void multiParcel_itemsMustSumToFulfillmentOrder_extraBoxWithoutItemsNeedsNoChannelDispatch() {
        Ctx c = ctx();
        stock(c, c.hs(), 5);
        String o = importOrder(c, "ORD-M", 3);
        String foId = fo(c, o).get("id").asString();
        Instant at = Instant.now().minus(2, ChronoUnit.MINUTES);
        assertThat(c.o().post("/api/v1/fulfillment-orders/" + foId + "/ship", ship(at,
                Map.of("carrier_code", "CJ", "tracking_no", "AAA11111", "items", List.of(Map.of("sku_id", c.sku().toString(), "quantity", 2)))))
                .errorCode()).isEqualTo("PARCEL_ITEMS_MISMATCH");
        assertThat(c.o().post("/api/v1/fulfillment-orders/" + foId + "/ship", ship(at,
                Map.of("carrier_code", "CJ", "tracking_no", "AAA11111"), Map.of("carrier_code", "CJ", "tracking_no", "aaa-11111"))).errorCode())
                .isEqualTo("VALIDATION_FAILED");
        Api.Res r = c.o().post("/api/v1/fulfillment-orders/" + foId + "/ship", ship(at,
                Map.of("carrier_code", "CJ", "tracking_no", "AAA11111"), Map.of("carrier_code", "HANJIN", "tracking_no", "BBB22222")));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        drainAsync();
        JsonNode list = shipments(c, o);
        assertThat(list).hasSize(2);
        for (JsonNode s : list) {
            if (s.get("parcel_no").asInt() == 1) {
                assertThat(s.get("items").get(0).get("quantity").asInt()).isEqualTo(3);
                assertThat(s.get("channel_dispatch_status").asString()).isEqualTo("MANUAL_REQUIRED");
            } else {
                assertThat(s.get("items")).isEmpty();
                assertThat(s.get("channel_dispatch_status").asString()).isEqualTo("NOT_REQUIRED");
            }
        }
    }

    @Test
    void trackingNeverRegresses_lateEventsKept_exceptionWorkItem_deliveredThenOrderCompletesAfterWithdrawalPeriod() {
        Ctx c = ctx();
        stock(c, c.hs(), 5);
        String o = importOrder(c, "ORD-T", 1);
        String foId = fo(c, o).get("id").asString();
        Instant t0 = Instant.now().minus(50, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        c.o().post("/api/v1/fulfillment-orders/" + foId + "/ship", ship(t0, Map.of("carrier_code", "LOGEN", "tracking_no", "40000000001")));
        String sid = shipments(c, o).get(0).get("id").asString();
        String path = "/api/v1/shipments/" + sid + "/tracking-events";
        assertThat(c.o().post(path, Map.of("status", "IN_TRANSIT", "occurred_at", t0.plus(10, ChronoUnit.MINUTES).toString())).data().get("shipment").get("status").asString())
                .isEqualTo("IN_TRANSIT");
        // 늦게 기록한 이전 단계: 상태는 그대로, 이력에는 남는다
        assertThat(c.o().post(path, Map.of("status", "PICKED_UP", "occurred_at", t0.plus(5, ChronoUnit.MINUTES).toString())).data().get("result").asString())
                .isEqualTo("IGNORED_STALE");
        JsonNode s = c.o().get("/api/v1/shipments/" + sid).data();
        assertThat(s.get("status").asString()).isEqualTo("IN_TRANSIT");
        assertThat(s.get("events")).hasSize(3);
        // 예외 → 업무함, 이후 배송완료 → 해소 + 주문 배송완료
        c.o().post(path, Map.of("status", "EXCEPTION", "occurred_at", t0.plus(15, ChronoUnit.MINUTES).toString(), "description", "수취인 부재"));
        assertThat(c.o().get("/api/v1/work-items?item_type=SHIPMENT_EXCEPTION&status=OPEN").data()).hasSize(1);
        Instant delivered = t0.plus(20, ChronoUnit.MINUTES);
        assertThat(c.o().post(path, Map.of("status", "DELIVERED", "occurred_at", delivered.toString())).data().get("shipment").get("status").asString())
                .isEqualTo("DELIVERED");
        assertThat(c.o().get("/api/v1/work-items?item_type=SHIPMENT_EXCEPTION&status=OPEN").data()).isEmpty();
        assertThat(c.o().post(path, Map.of("status", "IN_TRANSIT", "occurred_at", Instant.now().toString())).data().get("result").asString())
                .isEqualTo("IGNORED_STALE"); // 배송완료는 최종
        JsonNode od = order(c, o);
        assertThat(od.get("shipping_status").asString()).isEqualTo("DELIVERED");
        assertThat(od.get("order").get("status").asString()).isEqualTo("PROCESSING"); // 배송완료 ≠ 주문완료
        watch.completeDelivered(Instant.now().plus(7, ChronoUnit.DAYS));
        assertThat(order(c, o).get("order").get("status").asString()).isEqualTo("PROCESSING");
        watch.completeDelivered(Instant.now().plus(9, ChronoUnit.DAYS));
        assertThat(order(c, o).get("order").get("status").asString()).isEqualTo("COMPLETED");
    }

    UUID mockConnection(Api o, String targetType, UUID target, String connector, Map<String, String> caps) {
        UUID conn = o.post("/api/v1/integrations/connections", Map.of("target_type", targetType, "target_id", target.toString(), "connector_code", connector,
                "display_name", "모의 " + connector, "auth_type", "API_KEY", "credential", Map.of("api_key", "k"))).id();
        caps.forEach((feature, op) -> assertThat(o.put("/api/v1/integrations/connections/" + conn + "/capabilities/" + feature + "/" + op,
                Map.of("support_level", "OFFICIAL_DOCUMENTED", "account_verification", "VERIFIED", "evidence_ref", "테스트 계정 응답 캡처"), 1).status())
                .as(feature).isEqualTo(200));
        o.post("/api/v1/integrations/" + conn + "/sync-jobs", Map.of());
        drainAsync();
        return conn;
    }

    @Test
    void thirdPartyOutboundStatusAndTracking_viaSync_onHandSnapshotAfterShipmentIsNotDeductedTwice_channelDispatchRetriedOnce() {
        Ctx c = ctx();
        // 3PL 거점(외부 재고, 수량 의미 = 총재고) + 출고요청·출고상태·송장 지원 기능
        UUID fs = c.o().post("/api/v1/fulfillment-nodes", Map.of("code", "FS", "name", "파스토", "logistics_provider_code", "FASSTO", "cutoff_time", "15:00")).id();
        int v = c.o().get("/api/v1/fulfillment-nodes/" + fs).data().get("version").asInt();
        c.o().patch("/api/v1/fulfillment-nodes/" + fs, Map.of("quantity_semantic", "ON_HAND", "quantity_semantic_evidence", "물류사 API 문서", "routing_priority", 1), v);
        UUID shipper = fx.shipperAccount(c.o(), c.be(), "FASSTO", "SHIP-A");
        c.o().post("/api/v1/fulfillment-nodes/" + fs + "/shippers", Map.of("shipper_account_id", shipper.toString()));
        UUID lconn = mockConnection(c.o(), "SHIPPER_ACCOUNT", shipper, "mock-3pl", Map.of("OUTBOUND_REQUEST", "WRITE", "OUTBOUND_STATUS", "READ", "TRACKING", "READ"));
        UUID cconn = mockConnection(c.o(), "CHANNEL_ACCOUNT", c.acc(), "mock-channel", Map.of("SHIPMENT_DISPATCH", "WRITE"));
        Instant t0 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        assertThat(c.o().post("/api/v1/inventory/external-snapshots", Map.of("node_id", fs.toString(), "business_entity_id", c.be().toString(), "source_key", "S1",
                "as_of", t0.toString(), "complete", false, "lines", List.of(Map.of("external_sku_code", "AMP-30", "quantity", 10, "sellable", true)))).status())
                .isEqualTo(201);
        String o = importOrder(c, "ORD-3PL", 2);
        JsonNode f = fo(c, o);
        assertThat(f.get("node_id").asString()).isEqualTo(fs.toString());
        assertThat(f.get("status").asString()).isEqualTo("ACCEPTED");
        String key = f.get("dispatch_key").asString();

        Instant picked = Instant.now().minus(40, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        Instant shippedAt = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        mockControl.addOutboundStatus(lconn, Map.of("dispatch_key", key, "status", "PICKING", "occurred_at", picked.toString()));
        mockControl.addOutboundStatus(lconn, Map.of("dispatch_key", key, "status", "SHIPPED", "occurred_at", shippedAt.toString(),
                "parcels", List.of(Map.of("carrier_code", "CJ", "tracking_no", "5555-6666-7777")), "recipient", Map.of("name", "홍길동")));
        mockControl.addTracking(lconn, Map.of("carrier_code", "CJ", "tracking_no", "555566667777", "status", "IN_TRANSIT",
                "occurred_at", shippedAt.plus(20, ChronoUnit.MINUTES).toString(), "location", "옥천HUB"));
        mockControl.addTracking(lconn, Map.of("carrier_code", "CJ", "tracking_no", "555566667777", "status", "PICKED_UP",
                "occurred_at", shippedAt.plus(5, ChronoUnit.MINUTES).toString()));
        mockControl.failChannelDispatch(cconn, 1);
        Api.Res job = c.o().post("/api/v1/integrations/" + lconn + "/sync-jobs", Map.of("job_type", "SHIPMENT_SYNC"));
        assertThat(job.status()).as(job.body().toString()).isEqualTo(202);
        drainAsync();
        JsonNode jr = c.o().get("/api/v1/jobs/" + job.data().get("job_id").asString()).data();
        assertThat(jr.get("status").asString()).as(jr.toString()).isEqualTo("SUCCEEDED");
        f = fo(c, o);
        assertThat(f.get("status").asString()).isEqualTo("SHIPPED");
        String res = f.get("lines").get(0).get("reservation_id").asString();
        assertThat(Instant.parse(c.o().get("/api/v1/inventory/reservations/" + res).data().get("consumed_at").asString())).isEqualTo(shippedAt);
        JsonNode s = shipments(c, o).get(0);
        assertThat(s.get("source").asString()).isEqualTo("API");
        assertThat(s.get("status").asString()).isEqualTo("IN_TRANSIT"); // 늦게 온 집하(PICKED_UP)는 역행시키지 않음
        // 원본 보관: 개인정보 키 제거
        assertThat(jdbc.sql("select count(*) from logistics_raw where workspace_id = :ws and payload::text like '%홍길동%'").param("ws", c.ws().id())
                .query(Integer.class).single()).isZero();

        // 같은 응답 다시 수신 → 중복 반영 없음
        c.o().post("/api/v1/integrations/" + lconn + "/sync-jobs", Map.of("job_type", "SHIPMENT_SYNC"));
        drainAsync();
        assertThat(shipments(c, o)).hasSize(1);
        assertThat(c.o().get("/api/v1/shipments/" + s.get("id").asString()).data().get("events")).hasSize(3);

        // 출고 후 기준시각의 총재고 스냅샷(이미 2개 빠진 8) → 내부에서 다시 빼지 않는다
        assertThat(onHand(c, fs)).isEqualTo(8);
        c.o().post("/api/v1/inventory/external-snapshots", Map.of("node_id", fs.toString(), "business_entity_id", c.be().toString(), "source_key", "S2",
                "as_of", shippedAt.plus(10, ChronoUnit.MINUTES).toString(), "complete", false,
                "lines", List.of(Map.of("external_sku_code", "AMP-30", "quantity", 8, "sellable", true))));
        assertThat(onHand(c, fs)).isEqualTo(8); // 출고 시각 대신 반영 시각을 썼다면 6

        // 판매채널 발송처리: 첫 시도 일시 오류 → 재시도 1회로 한 번만 반영
        jdbc.sql("update async_job set available_at = now() where workspace_id = :ws and status = 'RETRY_WAIT'").param("ws", c.ws().id()).update();
        drainAsync();
        s = c.o().get("/api/v1/shipments/" + s.get("id").asString()).data();
        assertThat(s.get("channel_dispatch_status").asString()).isEqualTo("SENT");
        assertThat(s.get("channel_dispatch_mode").asString()).isEqualTo("API");
        assertThat(mockControl.channelDispatches(cconn, s.get("shipment_no").asString())).isEqualTo(1);

        // 지원 기능이 없는 연결은 수신 작업을 받지 않는다(T39)
        UUID noCap = mockConnection(c.o(), "SHIPPER_ACCOUNT", fx.shipperAccount(c.o(), fx.businessEntity(c.o(), "비상사"), "FASSTO", "SHIP-B"), "mock-3pl", Map.of());
        assertThat(c.o().post("/api/v1/integrations/" + noCap + "/sync-jobs", Map.of("job_type", "SHIPMENT_SYNC")).errorCode()).isEqualTo("CAPABILITY_NOT_AVAILABLE");
    }

    @Test
    void shipmentFileUpload_appliesPerFulfillmentOrder_reportsBadRows_reuploadIsDuplicate() {
        Ctx c = ctx();
        stock(c, c.hs(), 10);
        String o1 = importOrder(c, "ORD-F1", 1);
        String o2 = importOrder(c, "ORD-F2", 2);
        String fo1 = fo(c, o1).get("fo_no").asString();
        String fo2 = fo(c, o2).get("fo_no").asString();
        String kst = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul")).minusMinutes(5).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String csv = "출고번호,택배사,송장번호,출고일시\r\n"
                + fo1 + ",CJ대한통운,6000-1111-2222," + kst + "\r\n"
                + fo2 + ",한진택배,7000111122,\r\n"
                + fo2 + ",한진택배,7000111133,\r\n"
                + "F-NOPE-1,CJ대한통운,6000111199,\r\n"
                + fo1 + ",모르는택배,6000111188,\r\n";
        JsonNode r = upload(c, csv);
        assertThat(r.get("shipped").asInt()).as(r.toString()).isEqualTo(3);
        assertThat(r.get("failed").asInt()).isEqualTo(2);
        assertThat(fo(c, o1).get("status").asString()).isEqualTo("SHIPPED");
        assertThat(shipments(c, o2)).hasSize(2);
        assertThat(shipments(c, o1).get(0).get("source").asString()).isEqualTo("FILE");
        JsonNode again = upload(c, csv);
        assertThat(again.get("duplicate").asInt()).isEqualTo(3);
        assertThat(onHand(c, c.hs())).isEqualTo(7);
        assertThat(upload(c, "주문번호,송장\r\nX,1\r\n")).isNull();
    }

    JsonNode upload(Ctx c, String csv) {
        Api.Res r = c.o().perform(multipart("/api/v1/shipments/imports")
                .file(new MockMultipartFile("file", "invoices.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8))));
        if (r.status() != 200) {
            assertThat(r.errorCode()).isEqualTo("TEMPLATE_COLUMN_MISSING");
            return null;
        }
        return r.data();
    }

    @Test
    void trackingSla_raisesWorkItem_resolvedByShipment_shipDuringCancelRequestIsRecorded() {
        Ctx c = ctx();
        stock(c, c.hs(), 5);
        String o = importOrder(c, "ORD-SLA", 1);
        String foId = fo(c, o).get("id").asString();
        jdbc.sql("update fulfillment_order set accepted_at = now() - interval '30 hours' where id = :id").param("id", UUID.fromString(foId)).update();
        assertThat(watch.scanTrackingSla(Instant.now())).isGreaterThanOrEqualTo(1);
        assertThat(c.o().get("/api/v1/work-items?item_type=FULFILLMENT_TRACKING_MISSING&status=OPEN").data()).hasSize(1);
        // 취소 요청 중에 실물이 나갔다 → 출고 사실 반영 + 반품 안내 업무
        c.o().post("/api/v1/fulfillment-orders/" + foId + "/cancel", Map.of("reason", "고객 취소"));
        Api.Res r = c.o().post("/api/v1/fulfillment-orders/" + foId + "/ship", ship(Instant.now().minus(1, ChronoUnit.MINUTES),
                Map.of("carrier_code", "EPOST", "tracking_no", "6865012345678")));
        assertThat(r.data().get("status").asString()).isEqualTo("SHIPPED");
        assertThat(c.o().get("/api/v1/work-items?item_type=FULFILLMENT_TRACKING_MISSING&status=OPEN").data()).isEmpty();
        assertThat(c.o().get("/api/v1/work-items?item_type=FULFILLMENT_SHIPPED_DURING_CANCEL&status=OPEN").data()).hasSize(1);
        assertThat(c.o().get("/api/v1/work-items?item_type=FULFILLMENT_CANCEL_CONFIRM&status=OPEN").data()).isEmpty();
    }

    @Test
    void shipments_respectNodeScope_andWorkspaceIsolation() {
        Ctx c = ctx();
        stock(c, c.hs(), 5);
        UUID gp = c.o().post("/api/v1/fulfillment-nodes", Map.of("code", "GP", "name", "GP 센터", "logistics_provider_code", "SELF_WMS", "cutoff_time", "16:00",
                "inventory_source", "INTERNAL")).id();
        String a = importOrder(c, "ORD-A", 1); // HS
        jdbc.sql("update fulfillment_node set routing_priority = 1 where id = :id").param("id", gp).update();
        stock(c, gp, 5);
        String b = importOrder(c, "ORD-B", 1); // GP
        Instant at = Instant.now().minus(1, ChronoUnit.MINUTES);
        c.o().post("/api/v1/fulfillment-orders/" + fo(c, a).get("id").asString() + "/ship", ship(at, Map.of("carrier_code", "CJ", "tracking_no", "10000001")));
        c.o().post("/api/v1/fulfillment-orders/" + fo(c, b).get("id").asString() + "/ship", ship(at, Map.of("carrier_code", "CJ", "tracking_no", "10000002")));
        Api logi = fx.member(c.ws(), "LOGISTICS_MANAGER");
        fx.setPolicy(c.o(), logi, null, List.of(scope("FULFILLMENT_NODE", "READ", "SELECTED", c.hs(), "ALLOW")));
        JsonNode visible = logi.get("/api/v1/shipments").data();
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).get("order_id").asString()).isEqualTo(a);
        String hidden = shipments(c, b).get(0).get("id").asString();
        assertThat(logi.get("/api/v1/shipments/" + hidden).status()).isEqualTo(404);
        assertThat(fx.workspace().owner().get("/api/v1/shipments/" + hidden).status()).isEqualTo(404);
        assertThat(fx.workspace().owner().post("/api/v1/shipments/" + hidden + "/tracking-events", Map.of("status", "DELIVERED")).status()).isEqualTo(404);
    }
}
