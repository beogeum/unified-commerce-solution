package com.commerceos.claim;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M4 O6: 취소·반품·교환 클레임, 반품 입고·검수, 환불, 폐기 승인, 판매채널 클레임 수집·처리 반영(T14~T20, T37). */
class ClaimTest extends IntegrationTest {

    record Ctx(Fixtures.Ws ws, Api o, UUID be, UUID acc, UUID a30, UUID a50, UUID hs) {
    }

    Ctx ctx() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        UUID be = fx.businessEntity(o, "에이상사");
        UUID acc = fx.channelAccount(o, be, "NAVER_SMARTSTORE", "A 스토어");
        UUID unit = UUID.fromString(o.get("/api/v1/channel-accounts/" + acc + "/sales-units").data().get(0).get("id").asString());
        UUID product = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "AMP", "name", "앰플")).data().get("product").get("id").asString());
        UUID a30 = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "AMP-30", "sku_type", "NORMAL")).id();
        UUID a50 = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "AMP-50", "sku_type", "NORMAL")).id();
        map(o, unit, "P-100", a30);
        map(o, unit, "P-200", a50);
        UUID hs = o.post("/api/v1/fulfillment-nodes", Map.of("code", "HS", "name", "HS 센터", "logistics_provider_code", "SELF_WMS", "cutoff_time", "16:00",
                "inventory_source", "INTERNAL")).id();
        Ctx c = new Ctx(ws, o, be, acc, a30, a50, hs);
        stock(c, a30, 5);
        stock(c, a50, 5);
        return c;
    }

    void map(Api o, UUID unit, String product, UUID sku) {
        Api.Res l = o.post("/api/v1/channel-listings", Map.of("sales_unit_id", unit.toString(), "external_product_id", product, "external_option_id", "", "title", product));
        o.post("/api/v1/channel-listings/" + l.data().get("listing").get("id").asString() + "/mapping", Map.of("sku_id", sku.toString()));
    }

    void stock(Ctx c, UUID sku, int qty) {
        Api.Res r = c.o().post("/api/v1/inventory/receipts", Map.of("business_entity_id", c.be().toString(), "sku_id", sku.toString(), "node_id", c.hs().toString(),
                "quantity", qty));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    }

    /** items: (상품번호, 수량, 취소수량) */
    Map<String, Object> order(String key, Instant updated, Object[]... items) {
        Instant now = Instant.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("external_order_key", key);
        m.put("ordered_at", now.toString());
        m.put("paid_at", now.toString());
        m.put("external_updated_at", updated.toString());
        int total = 0;
        List<Map<String, Object>> list = new ArrayList<>();
        int n = 1;
        for (Object[] it : items) {
            int q = (Integer) it[1];
            total += 10000 * q;
            list.add(Map.of("external_item_key", key + "-" + n++, "external_product_id", it[0], "product_name", "앰플", "quantity", q, "canceled_quantity", it[2],
                    "unit_price", 10000, "line_amount", 10000 * q));
        }
        m.put("total_amount", total);
        m.put("recipient", Map.of("name", "홍길동", "phone", "010-1234-5678", "address1", "서울특별시 강남구 테헤란로 152"));
        m.put("items", list);
        return m;
    }

    String importOrder(Ctx c, Map<String, Object> order) {
        Api.Res r = c.o().post("/api/v1/orders/imports", Map.of("channel_account_id", c.acc().toString(), "orders", List.of(order)));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        drainAsync();
        return r.data().get("results").get(0).get("order_id").asString();
    }

    JsonNode order(Ctx c, String id) {
        return c.o().get("/api/v1/orders/" + id).data();
    }

    String itemId(Ctx c, String orderId, String product) {
        for (JsonNode i : order(c, orderId).get("items")) {
            if (i.get("external_product_id").asString().equals(product) && "ORIGINAL".equals(i.get("item_kind").asString())) {
                return i.get("id").asString();
            }
        }
        throw new AssertionError("no item " + product);
    }

    List<JsonNode> fos(Ctx c, String orderId) {
        List<JsonNode> out = new ArrayList<>();
        c.o().get("/api/v1/fulfillment-orders?order_id=" + orderId).data().forEach(out::add);
        return out;
    }

    JsonNode activeFo(Ctx c, String orderId) {
        return fos(c, orderId).stream().filter(f -> !"CANCELLED".equals(f.get("status").asString()) && !"SHIPPED".equals(f.get("status").asString())).findFirst()
                .orElse(null);
    }

    void shipAll(Ctx c, String orderId) {
        JsonNode f = activeFo(c, orderId);
        Api.Res r = c.o().post("/api/v1/fulfillment-orders/" + f.get("id").asString() + "/ship", Map.of("shipped_at", Instant.now().minusSeconds(5).toString(),
                "parcels", List.of(Map.of("carrier_code", "CJ", "tracking_no", "6" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000000000L)))));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        drainAsync();
    }

    int sellable(Ctx c, UUID sku) {
        for (JsonNode n : c.o().get("/api/v1/inventory/availability?business_entity_id=" + c.be() + "&sku_id=" + sku).data().get("nodes")) {
            if (n.get("node_id").asString().equals(c.hs().toString())) {
                return n.get("sellable_on_hand").asInt();
            }
        }
        return 0;
    }

    JsonNode claim(Api api, Map<String, Object> body) {
        Api.Res r = api.post("/api/v1/claims", body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data();
    }

    static Map<String, Object> newClaim(String orderId, String type, String reason, Object... itemQty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order_id", orderId);
        m.put("claim_type", type);
        m.put("reason_code", reason);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < itemQty.length; i += 2) {
            items.add(Map.of("order_item_id", itemQty[i], "quantity", itemQty[i + 1]));
        }
        m.put("items", items);
        return m;
    }

    JsonNode refundAndConfirm(Ctx c, JsonNode claim, int amount) {
        String id = claim.get("id").asString();
        Api.Res req = c.o().postWithKey("/api/v1/claims/" + id + "/refund-requests", Map.of("amount", amount, "executor", "CHANNEL"), "rf-" + id);
        assertThat(req.status()).as(req.body().toString()).isEqualTo(200);
        // 같은 키 재시도 → 환불 요청 하나
        assertThat(c.o().postWithKey("/api/v1/claims/" + id + "/refund-requests", Map.of("amount", amount, "executor", "CHANNEL"), "rf-" + id).data().get("refunds"))
                .hasSize(1);
        String refundId = req.data().get("refunds").get(0).get("id").asString();
        Api.Res ok = c.o().post("/api/v1/refunds/" + refundId + "/confirm", Map.of("confirmed_amount", amount, "external_ref", "PAY-" + id.substring(0, 6)));
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        return ok.data();
    }

    @Test
    void T14_partialCancelAfterDispatch_waitsForLogistics_thenRestShips_refundSeparately() {
        Ctx c = ctx();
        String o = importOrder(c, order("ORD-14", Instant.now(), new Object[]{"P-100", 1, 0}, new Object[]{"P-200", 1, 0}));
        JsonNode f = activeFo(c, o);
        assertThat(f.get("status").asString()).isEqualTo("ACCEPTED"); // 자체 WMS 작업지시로 전달됨
        String itemA = itemId(c, o, "P-100");
        Api cs = fx.member(c.ws(), "CS_AGENT");
        JsonNode cl = claim(cs, newClaim(o, "CANCEL", "CHANGE_OF_MIND", itemA, 1));
        assertThat(cl.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(cl.get("expected_refund_amount").asInt()).isEqualTo(10000);
        assertThat(order(c, o).get("items").get(0).get("canceled_qty").asInt()).isZero(); // 요청만으로 수량 불변
        // 2개 초과 취소 요청은 거부
        assertThat(cs.post("/api/v1/claims", newClaim(o, "CANCEL", "CHANGE_OF_MIND", itemA, 1)).errorCode()).isEqualTo("CLAIM_QUANTITY_EXCEEDED");
        cl = cs.post("/api/v1/claims/" + cl.get("id").asString() + "/approve", Map.of("note", "고객 요청")).data();
        assertThat(cl.get("status").asString()).isEqualTo("PROCESSING");
        assertThat(cl.get("cancel_applied").asBoolean()).isFalse(); // 물류 확인 대기
        assertThat(activeFo(c, o).get("status").asString()).isEqualTo("CANCEL_REQUESTED");
        // 창고가 출고 중지 확인 → 취소 반영 + 남은 상품 재배정
        c.o().post("/api/v1/fulfillment-orders/" + f.get("id").asString() + "/cancel", Map.of("reason", "창고 출고 중지 확인", "pick_return_confirmed", true));
        drainAsync();
        cl = c.o().get("/api/v1/claims/" + cl.get("id").asString()).data();
        assertThat(cl.get("cancel_applied").asBoolean()).isTrue();
        JsonNode nf = activeFo(c, o);
        assertThat(nf.get("lines")).hasSize(1);
        assertThat(nf.get("lines").get(0).get("sku_code").asString()).isEqualTo("AMP-50");
        shipAll(c, o);
        JsonNode od = order(c, o);
        for (JsonNode i : od.get("items")) {
            if (i.get("id").asString().equals(itemA)) {
                assertThat(i.get("canceled_qty").asInt()).isEqualTo(1);
                assertThat(i.get("shipped_qty").asInt()).isZero();
            } else {
                assertThat(i.get("shipped_qty").asInt()).isEqualTo(1);
            }
        }
        assertThat(od.get("shipping_status").asString()).isEqualTo("SHIPPED");
        assertThat(sellable(c, c.a30())).isEqualTo(5);
        assertThat(sellable(c, c.a50())).isEqualTo(4);
        // 환불: CS 는 권한 없음, 환불 권한자가 요청·확정
        assertThat(cs.postWithKey("/api/v1/claims/" + cl.get("id").asString() + "/refund-requests", Map.of("amount", 10000), "x").errorCode())
                .isEqualTo("PERMISSION_DENIED");
        assertThat(c.o().postWithKey("/api/v1/claims/" + cl.get("id").asString() + "/refund-requests", Map.of("amount", 10001), "y").errorCode())
                .isEqualTo("REFUND_EXCEEDS_EXPECTED");
        JsonNode done = refundAndConfirm(c, cl, 10000);
        assertThat(done.get("refund_status").asString()).isEqualTo("CONFIRMED");
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(order(c, o).get("order").get("payment_status").asString()).isEqualTo("PARTIALLY_REFUNDED");
        // 출고된 상품은 취소가 아니라 반품
        assertThat(cs.post("/api/v1/claims", newClaim(o, "CANCEL", "CHANGE_OF_MIND", itemId(c, o, "P-200"), 1)).errorCode()).isEqualTo("CLAIM_QUANTITY_EXCEEDED");
    }

    @Test
    void T15_T16_returnOneOfTwo_pendingInspectionNotSellable_inspectedSellable_returnedQty_refundMinusReturnFee() {
        Ctx c = ctx();
        String o = importOrder(c, order("ORD-15", Instant.now(), new Object[]{"P-100", 2, 0}));
        shipAll(c, o);
        assertThat(sellable(c, c.a30())).isEqualTo(3);
        String item = itemId(c, o, "P-100");
        Map<String, Object> body = newClaim(o, "RETURN", "CHANGE_OF_MIND", item, 1);
        body.put("return_shipping_fee", 3000);
        JsonNode cl = claim(c.o(), body);
        assertThat(cl.get("item_refund_amount").asInt()).isEqualTo(10000);
        assertThat(cl.get("expected_refund_amount").asInt()).isEqualTo(7000);
        assertThat(cl.get("fault_party").asString()).isEqualTo("CUSTOMER");
        String id = cl.get("id").asString();
        c.o().post("/api/v1/claims/" + id + "/approve", Map.of());
        assertThat(c.o().post("/api/v1/claims/" + id + "/return-shipment", Map.of("carrier_code", "CJ", "tracking_no", "5000-1111-2222")).data()
                .get("return_status").asString()).isEqualTo("IN_TRANSIT");
        // 남은 1개만 더 반품 가능
        assertThat(c.o().post("/api/v1/claims", newClaim(o, "RETURN", "CHANGE_OF_MIND", item, 2)).errorCode()).isEqualTo("CLAIM_QUANTITY_EXCEEDED");
        Api wh = fx.member(c.ws(), "WAREHOUSE_WORKER");
        JsonNode rc = receive(wh, Map.of("node_id", c.hs().toString(), "claim_id", id, "lines", List.of(Map.of("sku_id", c.a30().toString(), "quantity", 1))));
        assertThat(rc.get("identification").asString()).isEqualTo("MATCHED");
        assertThat(sellable(c, c.a30())).isEqualTo(3); // T16: 검수 전 판매가능 아님
        assertThat(c.o().get("/api/v1/claims/" + id).data().get("return_status").asString()).isEqualTo("RECEIVED");
        String line = rc.get("lines").get(0).get("id").asString();
        assertThat(wh.post("/api/v1/returns/receipts/" + rc.get("id").asString() + "/inspections", Map.of("lines", List.of(Map.of("receipt_line_id", line,
                "decisions", List.of(Map.of("condition", "SELLABLE", "quantity", 2)))))).errorCode()).isEqualTo("INSPECTION_QUANTITY_MISMATCH");
        JsonNode in = wh.post("/api/v1/returns/receipts/" + rc.get("id").asString() + "/inspections", Map.of("lines", List.of(Map.of("receipt_line_id", line,
                "decisions", List.of(Map.of("condition", "SELLABLE", "quantity", 1)))))).data();
        assertThat(in.get("status").asString()).isEqualTo("INSPECTED");
        assertThat(sellable(c, c.a30())).isEqualTo(4);
        JsonNode od = order(c, o);
        assertThat(od.get("items").get(0).get("returned_qty").asInt()).isEqualTo(1);
        cl = c.o().get("/api/v1/claims/" + id).data();
        assertThat(cl.get("return_status").asString()).isEqualTo("INSPECTED");
        assertThat(cl.get("status").asString()).isEqualTo("PROCESSING"); // 환불 확정 전
        JsonNode done = refundAndConfirm(c, cl, 7000);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        // 같은 줄 재검수 불가
        assertThat(wh.post("/api/v1/returns/receipts/" + rc.get("id").asString() + "/inspections", Map.of("lines", List.of(Map.of("receipt_line_id", line,
                "decisions", List.of(Map.of("condition", "SELLABLE", "quantity", 1)))))).errorCode()).isEqualTo("RETURN_LINE_INSPECTED");
    }

    JsonNode receive(Api api, Map<String, Object> body) {
        Api.Res r = api.post("/api/v1/returns/receipts", body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        drainAsync();
        return r.data();
    }

    @Test
    void T17_defectiveReturn_disposalNeedsApproval_noSelfApproval_ledgerDisposalOnce() {
        Ctx c = ctx();
        String o = importOrder(c, order("ORD-17", Instant.now(), new Object[]{"P-100", 1, 0}));
        shipAll(c, o);
        JsonNode cl = claim(c.o(), newClaim(o, "RETURN", "DEFECT", itemId(c, o, "P-100"), 1));
        assertThat(cl.get("expected_refund_amount").asInt()).isEqualTo(10000); // 판매자 귀책: 반품 배송비 없음
        c.o().post("/api/v1/claims/" + cl.get("id").asString() + "/approve", Map.of());
        Api wh = fx.member(c.ws(), "WAREHOUSE_WORKER");
        JsonNode rc = receive(wh, Map.of("node_id", c.hs().toString(), "claim_id", cl.get("id").asString(), "lines",
                List.of(Map.of("sku_id", c.a30().toString(), "quantity", 1))));
        wh.post("/api/v1/returns/receipts/" + rc.get("id").asString() + "/inspections", Map.of("lines", List.of(Map.of("receipt_line_id",
                rc.get("lines").get(0).get("id").asString(), "decisions", List.of(Map.of("condition", "DEFECTIVE", "quantity", 1, "reason", "펌프 파손"))))));
        assertThat(sellable(c, c.a30())).isEqualTo(4); // 불량은 판매가능 아님
        String defective = null;
        String sellableStock = null;
        for (JsonNode s : c.o().get("/api/v1/inventory/stocks?sku_id=" + c.a30()).data()) {
            if ("DEFECTIVE".equals(s.get("quality_status").asString())) {
                defective = s.get("id").asString();
            } else if ("SELLABLE".equals(s.get("quality_status").asString())) {
                sellableStock = s.get("id").asString();
            }
        }
        assertThat(defective).isNotNull();
        assertThat(wh.post("/api/v1/inventory/disposals", Map.of("stock_id", sellableStock, "quantity", 1, "reason", "x")).errorCode()).isEqualTo("STOCK_NOT_DISPOSABLE");
        Api.Res req = wh.post("/api/v1/inventory/disposals", Map.of("stock_id", defective, "quantity", 1, "reason", "불량 반품 폐기", "evidence_ref", "사진 3장"));
        assertThat(req.status()).as(req.body().toString()).isEqualTo(202);
        String approval = req.data().get("approval_id").asString();
        assertThat(jdbc.sql("select count(*) from inventory_transaction where workspace_id = :ws and txn_type = 'DISPOSAL'").param("ws", c.ws().id()).query(Integer.class)
                .single()).isZero(); // 승인 전 불변
        Api logi = fx.member(c.ws(), "LOGISTICS_MANAGER");
        assertThat(logi.post("/api/v1/approvals/" + approval + "/decision", Map.of("decision", "APPROVE")).errorCode())
                .isIn("APPROVER_NEEDS_INVENTORY_DISPOSE", "PERMISSION_DENIED");
        assertThat(c.o().post("/api/v1/approvals/" + approval + "/decision", Map.of("decision", "APPROVE", "note", "확인")).status()).isEqualTo(200);
        assertThat(jdbc.sql("select count(*) from inventory_transaction where workspace_id = :ws and txn_type = 'DISPOSAL'").param("ws", c.ws().id()).query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(c.o().get("/api/v1/inventory/verification").data()).isEmpty();
    }

    @Test
    void T18_T19_exchange_sameSkuFreeNoNewRevenue_differentSkuPriceDifferenceSeparated() {
        Ctx c = ctx();
        String o = importOrder(c, order("ORD-18", Instant.now(), new Object[]{"P-100", 1, 0}));
        shipAll(c, o);
        String item = itemId(c, o, "P-100");
        JsonNode cl = claim(c.o(), newClaim(o, "EXCHANGE", "DEFECT", item, 1));
        assertThat(cl.get("expected_refund_amount").asInt()).isZero();
        assertThat(cl.get("reship_status").asString()).isEqualTo("PENDING");
        String id = cl.get("id").asString();
        c.o().post("/api/v1/claims/" + id + "/approve", Map.of());
        JsonNode od = order(c, o);
        assertThat(od.get("items")).hasSize(2);
        JsonNode repl = od.get("items").get(1);
        assertThat(repl.get("line_amount").asInt()).isZero(); // 원판매 매출 중복 없음
        assertThat(repl.get("product_name").asString()).startsWith("[교환]");
        assertThat(od.get("items_amount").asInt()).isEqualTo(10000);
        assertThat(fos(c, o).stream().filter(f -> !"CANCELLED".equals(f.get("status").asString())).count()).isEqualTo(1); // 회수 전 재출고 없음
        // 회수품 도착 → 대체품 출고 배정 → 출고 → 재출고 완료, 회수품 불량 검수 → 완료
        JsonNode rc = receive(c.o(), Map.of("node_id", c.hs().toString(), "claim_id", id, "lines", List.of(Map.of("sku_id", c.a30().toString(), "quantity", 1))));
        JsonNode rf = fos(c, o).stream().filter(f -> "ACCEPTED".equals(f.get("status").asString())).findFirst().orElseThrow();
        assertThat(rf.get("lines").get(0).get("sku_code").asString()).isEqualTo("AMP-30");
        shipAll(c, o);
        assertThat(c.o().get("/api/v1/claims/" + id).data().get("reship_status").asString()).isEqualTo("SHIPPED");
        c.o().post("/api/v1/returns/receipts/" + rc.get("id").asString() + "/inspections", Map.of("lines", List.of(Map.of("receipt_line_id",
                rc.get("lines").get(0).get("id").asString(), "decisions", List.of(Map.of("condition", "DEFECTIVE", "quantity", 1))))));
        cl = c.o().get("/api/v1/claims/" + id).data();
        assertThat(cl.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(cl.get("refund_status").asString()).isEqualTo("NOT_REQUIRED");

        // T19: 다른 SKU 교환 + 차액 5,000원(고객 추가 결제), 선출고 → 승인 즉시 새 SKU 배정
        String o2 = importOrder(c, order("ORD-19", Instant.now(), new Object[]{"P-100", 1, 0}));
        shipAll(c, o2);
        Map<String, Object> body = newClaim(o2, "EXCHANGE", "CHANGE_OF_MIND", itemId(c, o2, "P-100"), 1);
        body.put("items", List.of(Map.of("order_item_id", itemId(c, o2, "P-100"), "quantity", 1, "replacement_sku_id", c.a50().toString(), "replacement_quantity", 1)));
        body.put("price_difference", 5000);
        body.put("ship_before_return", true);
        JsonNode c2 = claim(c.o(), body);
        assertThat(c2.get("additional_payment_amount").asInt()).isEqualTo(5000);
        assertThat(c2.get("expected_refund_amount").asInt()).isZero();
        c.o().post("/api/v1/claims/" + c2.get("id").asString() + "/approve", Map.of());
        drainAsync();
        JsonNode nf = fos(c, o2).stream().filter(f -> "ACCEPTED".equals(f.get("status").asString())).findFirst().orElseThrow();
        assertThat(nf.get("lines").get(0).get("sku_code").asString()).isEqualTo("AMP-50");
        // 차액 환불(−5,000) 은 환불 예정액으로
        Map<String, Object> b3 = new LinkedHashMap<>(body);
        b3.put("order_id", o);
        b3.put("items", List.of(Map.of("order_item_id", item, "quantity", 1, "replacement_sku_id", c.a50().toString())));
        b3.put("price_difference", -5000);
        b3.remove("ship_before_return");
        assertThat(c.o().post("/api/v1/claims", b3).errorCode()).isEqualTo("CLAIM_QUANTITY_EXCEEDED"); // 이미 교환 1개 접수됨
    }

    @Test
    void T37_T20_unidentifiedAndMismatchedReturnsQuarantined_thirdPartyInspectionUndeterminedManualOnly() {
        Ctx c = ctx();
        Api wh = fx.member(c.ws(), "WAREHOUSE_WORKER");
        // 원주문 없는 반품
        assertThat(wh.post("/api/v1/returns/receipts", Map.of("node_id", c.hs().toString(), "lines", List.of(Map.of("sku_id", c.a30().toString(), "quantity", 1))))
                .errorCode()).isEqualTo("VALIDATION_FAILED");
        JsonNode un = receive(wh, Map.of("node_id", c.hs().toString(), "business_entity_id", c.be().toString(), "note", "송장 없이 도착",
                "lines", List.of(Map.of("sku_id", c.a30().toString(), "quantity", 1))));
        assertThat(un.get("identification").asString()).isEqualTo("UNIDENTIFIED");
        assertThat(sellable(c, c.a30())).isEqualTo(5);
        assertThat(c.o().get("/api/v1/work-items?item_type=UNIDENTIFIED_RETURN&status=OPEN").data()).hasSize(1);
        boolean quarantined = false;
        for (JsonNode s : c.o().get("/api/v1/inventory/stocks?sku_id=" + c.a30()).data()) {
            quarantined |= "UNDETERMINED".equals(s.get("quality_status").asString()) && "UNIDENTIFIED_RETURN".equals(s.get("hold_reason").asString());
        }
        assertThat(quarantined).isTrue();
        // 클레임과 다른 SKU 가 온 경우
        String o = importOrder(c, order("ORD-37", Instant.now(), new Object[]{"P-100", 1, 0}));
        shipAll(c, o);
        JsonNode cl = claim(c.o(), newClaim(o, "RETURN", "WRONG_ITEM", itemId(c, o, "P-100"), 1));
        c.o().post("/api/v1/claims/" + cl.get("id").asString() + "/approve", Map.of());
        JsonNode mm = receive(wh, Map.of("node_id", c.hs().toString(), "claim_id", cl.get("id").asString(), "lines",
                List.of(Map.of("sku_id", c.a50().toString(), "quantity", 1))));
        assertThat(mm.get("identification").asString()).isEqualTo("MISMATCH");
        assertThat(mm.get("lines").get(0).get("matched").asBoolean()).isFalse();
        assertThat(wh.post("/api/v1/returns/receipts/" + mm.get("id").asString() + "/inspections", Map.of("lines", List.of(Map.of("receipt_line_id",
                mm.get("lines").get(0).get("id").asString(), "decisions", List.of(Map.of("condition", "SELLABLE", "quantity", 1)))))).errorCode())
                .isEqualTo("RETURN_LINE_UNIDENTIFIED");
        assertThat(sellable(c, c.a50())).isEqualTo(5);

        // T20: 3PL(외부 재고) 거점 반품 — 검수 API 미지원 → 미확정·수동 검수, 외부 재고 덮어쓰지 않음
        UUID fs = c.o().post("/api/v1/fulfillment-nodes", Map.of("code", "FS", "name", "파스토", "logistics_provider_code", "FASSTO", "cutoff_time", "15:00")).id();
        UUID shipper = fx.shipperAccount(c.o(), c.be(), "FASSTO", "SHIP-A");
        c.o().post("/api/v1/fulfillment-nodes/" + fs + "/shippers", Map.of("shipper_account_id", shipper.toString()));
        JsonNode ext = receive(c.o(), Map.of("node_id", fs.toString(), "claim_id", cl.get("id").asString(), "lines",
                List.of(Map.of("sku_id", c.a30().toString(), "quantity", 1))));
        assertThat(ext.get("external_inventory").asBoolean()).isTrue();
        assertThat(ext.get("inspection_support").asString()).isEqualTo("MANUAL_ONLY");
        assertThat(ext.get("lines").get(0).get("stock_id").isNull()).isTrue();
        JsonNode ins = c.o().post("/api/v1/returns/receipts/" + ext.get("id").asString() + "/inspections", Map.of("lines", List.of(Map.of("receipt_line_id",
                ext.get("lines").get(0).get("id").asString(), "decisions", List.of(Map.of("condition", "UNDETERMINED", "quantity", 1, "reason", "물류사 화면 확인 전")))))).data();
        assertThat(ins.get("lines").get(0).get("inspections").get(0).get("applied_to_stock").asBoolean()).isFalse();
        assertThat(c.o().get("/api/v1/claims/" + cl.get("id").asString()).data().get("return_status").asString()).isEqualTo("INSPECTED");
    }

    UUID connection(Api o, UUID acc, Map<String, String> caps) {
        UUID conn = o.post("/api/v1/integrations/connections", Map.of("target_type", "CHANNEL_ACCOUNT", "target_id", acc.toString(), "connector_code", "mock-channel",
                "display_name", "모의 채널", "auth_type", "API_KEY", "credential", Map.of("api_key", "k"))).id();
        caps.forEach((f, op) -> assertThat(o.put("/api/v1/integrations/connections/" + conn + "/capabilities/" + f + "/" + op, Map.of("support_level",
                "OFFICIAL_DOCUMENTED", "account_verification", "VERIFIED", "evidence_ref", "테스트 계정 응답"), 1).status()).as(f).isEqualTo(200));
        o.post("/api/v1/integrations/" + conn + "/sync-jobs", Map.of());
        drainAsync();
        return conn;
    }

    void fetchClaims(Api o, UUID conn) {
        Api.Res job = o.post("/api/v1/integrations/" + conn + "/sync-jobs", Map.of("job_type", "CLAIM_FETCH"));
        assertThat(job.status()).as(job.body().toString()).isEqualTo(202);
        drainAsync();
        JsonNode j = o.get("/api/v1/jobs/" + job.data().get("job_id").asString()).data();
        assertThat(j.get("status").asString()).as(j.toString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void channelClaims_collected_userDecisionWrittenBackOnce_channelRefundConfirmed_staleIgnored_noCapabilityManual() {
        Ctx c = ctx();
        String o = importOrder(c, order("ORD-CH", Instant.now(), new Object[]{"P-100", 2, 0}));
        shipAll(c, o);
        UUID conn = connection(c.o(), c.acc(), Map.of("CLAIM_RETURN", "READ"));
        Instant t1 = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> row = new LinkedHashMap<>(Map.of("external_claim_key", "RET-1", "external_order_key", "ORD-CH", "claim_type", "RETURN", "status", "REQUESTED",
                "reason_code", "CHANGE_OF_MIND", "items", List.of(Map.of("external_item_key", "ORD-CH-1", "quantity", 1)), "updated_at", t1.toString(),
                "return_address", Map.of("name", "홍길동")));
        mockControl.setClaims(conn, List.of(row));
        fetchClaims(c.o(), conn);
        JsonNode list = c.o().get("/api/v1/claims?order_id=" + o).data();
        assertThat(list).hasSize(1);
        JsonNode cl = list.get(0);
        assertThat(cl.get("source").asString()).isEqualTo("API");
        assertThat(cl.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(jdbc.sql("select count(*) from claim_raw where workspace_id = :ws and payload::text like '%홍길동%'").param("ws", c.ws().id()).query(Integer.class)
                .single()).isZero();
        fetchClaims(c.o(), conn); // 같은 원본 반복 → 중복 없음
        assertThat(c.o().get("/api/v1/claims?order_id=" + o).data()).hasSize(1);
        // 쓰기 기능 미확인 → 승인은 판매자센터 수동 처리 업무
        String id = cl.get("id").asString();
        c.o().post("/api/v1/claims/" + id + "/approve", Map.of());
        drainAsync();
        assertThat(c.o().get("/api/v1/claims/" + id).data().get("channel_action_status").asString()).isEqualTo("MANUAL_REQUIRED");
        assertThat(c.o().get("/api/v1/work-items?item_type=CLAIM_CHANNEL_ACTION_MANUAL&status=OPEN").data()).hasSize(1);
        assertThat(c.o().post("/api/v1/claims/" + id + "/channel-action/manual-done", Map.of()).data().get("channel_action_status").asString()).isEqualTo("MANUAL_DONE");
        // 채널이 환불 완료 → 확정 환불 한 번만, 늦게 온 옛 원본은 무시
        Map<String, Object> done = new LinkedHashMap<>(row);
        done.put("status", "COMPLETED");
        done.put("refund_amount", 10000);
        done.put("refunded_at", Instant.now().toString());
        done.put("refund_ref", "NV-REF-1");
        done.put("updated_at", t1.plus(5, ChronoUnit.MINUTES).toString());
        Map<String, Object> stale = new LinkedHashMap<>(row);
        stale.put("status", "REJECTED");
        stale.put("updated_at", t1.minus(5, ChronoUnit.MINUTES).toString());
        mockControl.setClaims(conn, List.of(done, stale));
        fetchClaims(c.o(), conn);
        cl = c.o().get("/api/v1/claims/" + id).data();
        assertThat(cl.get("refunds")).hasSize(1);
        assertThat(cl.get("refunds").get(0).get("executor").asString()).isEqualTo("CHANNEL");
        assertThat(cl.get("refund_status").asString()).isEqualTo("CONFIRMED");
        assertThat(cl.get("status").asString()).isIn("PROCESSING"); // 회수 검수 전
        fetchClaims(c.o(), conn);
        assertThat(c.o().get("/api/v1/claims/" + id).data().get("refunds")).hasSize(1);

        // 쓰기 기능 확인된 연결 → 거부가 채널에 한 번 반영
        Ctx d = ctx();
        String o2 = importOrder(d, order("ORD-CH2", Instant.now(), new Object[]{"P-100", 1, 0}));
        shipAll(d, o2);
        UUID conn2 = connection(d.o(), d.acc(), Map.of("CLAIM_RETURN", "READ"));
        assertThat(d.o().put("/api/v1/integrations/connections/" + conn2 + "/capabilities/CLAIM_RETURN/WRITE", Map.of("support_level", "OFFICIAL_DOCUMENTED",
                "account_verification", "VERIFIED", "evidence_ref", "테스트 계정 응답"), 1).status()).isEqualTo(200);
        mockControl.setClaims(conn2, List.of(Map.of("external_claim_key", "RET-9", "external_order_key", "ORD-CH2", "claim_type", "RETURN", "status", "REQUESTED",
                "items", List.of(Map.of("external_item_key", "ORD-CH2-1", "quantity", 1)), "updated_at", t1.toString())));
        fetchClaims(d.o(), conn2);
        String id2 = d.o().get("/api/v1/claims?order_id=" + o2).data().get(0).get("id").asString();
        assertThat(d.o().post("/api/v1/claims/" + id2 + "/reject", Map.of()).errorCode()).isEqualTo("VALIDATION_FAILED");
        d.o().post("/api/v1/claims/" + id2 + "/reject", Map.of("note", "반품 기간 경과"));
        drainAsync();
        JsonNode r2 = d.o().get("/api/v1/claims/" + id2).data();
        assertThat(r2.get("status").asString()).isEqualTo("REJECTED");
        assertThat(r2.get("channel_action_status").asString()).isEqualTo("SENT");
        assertThat(mockControl.claimActions(conn2, r2.get("claim_no").asString() + ":REJECT")).isEqualTo(1);
    }

    @Test
    void externalCancelFromOrderFeed_reconcilesAllocatedFulfillment_andChannelCancelClaimDoesNotDoubleCount() {
        Ctx c = ctx();
        // 수동 전달 3PL 거점(배정 상태 유지)을 최우선으로
        UUID fs = c.o().post("/api/v1/fulfillment-nodes", Map.of("code", "FS", "name", "파스토", "logistics_provider_code", "FASSTO", "cutoff_time", "15:00",
                "inventory_source", "INTERNAL")).id();
        jdbc.sql("update fulfillment_node set routing_priority = 1 where id = :id").param("id", fs).update();
        UUID shipper = fx.shipperAccount(c.o(), c.be(), "FASSTO", "SHIP-A");
        c.o().post("/api/v1/fulfillment-nodes/" + fs + "/shippers", Map.of("shipper_account_id", shipper.toString()));
        c.o().post("/api/v1/inventory/receipts", Map.of("business_entity_id", c.be().toString(), "sku_id", c.a30().toString(), "node_id", fs.toString(), "quantity", 5));
        Instant t0 = Instant.now().minus(10, ChronoUnit.MINUTES);
        String o = importOrder(c, order("ORD-X", t0, new Object[]{"P-100", 2, 0}));
        JsonNode f = activeFo(c, o);
        assertThat(f.get("status").asString()).isEqualTo("ALLOCATED");
        assertThat(f.get("lines").get(0).get("quantity").asInt()).isEqualTo(2);
        // 판매채널에서 1개 취소된 주문이 다시 수집됨
        importOrder(c, order("ORD-X", t0.plus(1, ChronoUnit.MINUTES), new Object[]{"P-100", 2, 1}));
        JsonNode nf = activeFo(c, o);
        assertThat(nf.get("id").asString()).isNotEqualTo(f.get("id").asString());
        assertThat(nf.get("lines").get(0).get("quantity").asInt()).isEqualTo(1);
        // 같은 취소를 채널 클레임으로도 수집 → 취소 수량은 그대로 1
        UUID conn = connection(c.o(), c.acc(), Map.of("CLAIM_CANCEL", "READ"));
        mockControl.setClaims(conn, List.of(Map.of("external_claim_key", "CAN-1", "external_order_key", "ORD-X", "claim_type", "CANCEL", "status", "APPROVED",
                "items", List.of(Map.of("external_item_key", "ORD-X-1", "quantity", 1)), "updated_at", Instant.now().toString())));
        fetchClaims(c.o(), conn);
        JsonNode od = order(c, o);
        assertThat(od.get("items").get(0).get("canceled_qty").asInt()).isEqualTo(1);
        assertThat(activeFo(c, o).get("lines").get(0).get("quantity").asInt()).isEqualTo(1);
    }

    @Test
    void claims_respectBusinessScope_andWorkspaceIsolation_withdrawBeforeProgress() {
        Ctx c = ctx();
        String o = importOrder(c, order("ORD-S", Instant.now(), new Object[]{"P-100", 1, 0}));
        shipAll(c, o);
        JsonNode cl = claim(c.o(), newClaim(o, "RETURN", "CHANGE_OF_MIND", itemId(c, o, "P-100"), 1));
        UUID other = fx.businessEntity(c.o(), "비상사");
        Api cs = fx.member(c.ws(), "CS_AGENT");
        fx.setPolicy(c.o(), cs, null, List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", other, "ALLOW")));
        assertThat(cs.get("/api/v1/claims").data()).isEmpty();
        assertThat(cs.get("/api/v1/claims/" + cl.get("id").asString()).status()).isEqualTo(404);
        assertThat(fx.workspace().owner().get("/api/v1/claims/" + cl.get("id").asString()).status()).isEqualTo(404);
        assertThat(fx.workspace().owner().post("/api/v1/claims/" + cl.get("id").asString() + "/approve", Map.of()).status()).isEqualTo(404);
        JsonNode w = c.o().post("/api/v1/claims/" + cl.get("id").asString() + "/withdraw", Map.of("note", "고객 철회")).data();
        assertThat(w.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(w.get("refund_status").asString()).isEqualTo("CANCELLED");
        // 철회한 수량은 다시 반품 접수 가능
        assertThat(c.o().post("/api/v1/claims", newClaim(o, "RETURN", "CHANGE_OF_MIND", itemId(c, o, "P-100"), 1)).status()).isEqualTo(201);
    }
}
