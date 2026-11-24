package com.commerceos.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 단계별 입고: 입고예정 → 도착 → 검수 → 적치 → 완료|차이. 적치 전 판매불가, 이중 입고 방지, 초과 도착, 권한·범위·동시성. */
class InboundShipmentTest extends PurchaseTestBase {

    int atp(Ctx c) {
        JsonNode a = c.o().get("/api/v1/inventory/availability?business_entity_id=" + c.be() + "&sku_id=" + c.amp()).data();
        return a.get("atp").asInt();
    }

    Api.Res act(Api api, String id, String step, Object body) {
        JsonNode cur = api.get("/api/v1/inbound-shipments/" + id).data();
        return api.postIfMatch("/api/v1/inbound-shipments/" + id + "/" + step, body, cur == null ? 1 : cur.get("version").asInt());
    }

    @Test
    void stagedReceipt_arriveInspectPutaway_stockSellableOnlyAfterPutaway_discrepancyAndOverArrival() {
        Ctx c = ctx("단계입고");
        String sup = supplier(c, "단계 공급처");
        Api ops = fx.member(c.ws(), "OPS_ADMIN");
        String po = place(c, po(c, sup, 20), ops);
        String poLine = c.o().get("/api/v1/purchase-orders/" + po).data().get("lines").get(0).get("id").asString();
        assertThat(c.o().post("/api/v1/fulfillment-nodes/" + c.hs() + "/locations", Map.of("code", "A-01", "zone", "A")).status()).isEqualTo(201);
        int onHand0 = onHand(c);
        int atp0 = atp(c);

        // 입고예정: 잔량 초과 거부, 진행 중 입고예정이 잡은 수량은 다른 입고예정·즉시 입고에서 제외
        assertThat(c.o().post("/api/v1/purchase-orders/" + po + "/inbound-shipments", Map.of("lines", List.of(Map.of("po_line_id", poLine, "quantity", 21)))).errorCode())
                .isEqualTo("INBOUND_OVER_PLAN");
        Api.Res pr = c.o().post("/api/v1/purchase-orders/" + po + "/inbound-shipments", Map.of("carrier", "한진", "tracking_no", "5123-001",
                "lines", List.of(Map.of("po_line_id", poLine, "quantity", 15))));
        assertThat(pr.status()).as(pr.body().toString()).isEqualTo(201);
        JsonNode sh = pr.data();
        String id = sh.get("id").asString();
        assertThat(sh.get("shipment_no").asString()).startsWith("IB-");
        assertThat(sh.get("status").asString()).isEqualTo("EXPECTED");
        String line = sh.get("lines").get(0).get("id").asString();
        assertThat(c.o().post("/api/v1/purchase-orders/" + po + "/inbound-shipments", Map.of("lines", List.of(Map.of("po_line_id", poLine, "quantity", 6)))).errorCode())
                .isEqualTo("INBOUND_OVER_PLAN");
        assertThat(c.o().post("/api/v1/purchase-orders/" + po + "/receipts", Map.of("lines", List.of(Map.of("po_line_id", poLine, "good_qty", 1)))).errorCode())
                .isEqualTo("INBOUND_SHIPMENT_ACTIVE");
        assertThat(c.o().post("/api/v1/purchase-orders/" + po + "/cancel", Map.of("reason", "x")).errorCode()).isEqualTo("INBOUND_SHIPMENT_ACTIVE");

        // 도착: 모든 품목 수량 필요, 순서 위반·버전 충돌
        assertThat(act(c.o(), id, "inspect", Map.of("lines", List.of())).errorCode()).isEqualTo("INBOUND_INVALID_STATE");
        assertThat(c.o().postIfMatch("/api/v1/inbound-shipments/" + id + "/arrive", Map.of("lines", List.of(Map.of("line_id", line, "arrived_qty", 16))), 99).errorCode())
                .isEqualTo("VERSION_CONFLICT");
        Api.Res ar = act(c.o(), id, "arrive", Map.of("lines", List.of(Map.of("line_id", line, "arrived_qty", 16))));
        assertThat(ar.status()).as(ar.body().toString()).isEqualTo(200);
        assertThat(ar.data().get("status").asString()).isEqualTo("ARRIVED");
        assertThat(ar.data().get("lines").get(0).get("difference").asInt()).isEqualTo(1);
        assertThat(onHand(c)).isEqualTo(onHand0); // 도착은 재고 원장 미반영
        assertThat(act(c.o(), id, "start-inspection", Map.of()).data().get("status").asString()).isEqualTo("INSPECTING");

        // 검수: 정상+불량+초과 = 도착, 불량 사유 필수
        assertThat(act(c.o(), id, "inspect", Map.of("lines", List.of(Map.of("line_id", line, "good_qty", 14, "rejected_qty", 1)))).errorCode())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(act(c.o(), id, "inspect", Map.of("lines", List.of(Map.of("line_id", line, "good_qty", 14, "rejected_qty", 2)))).errorCode())
                .isEqualTo("VALIDATION_FAILED");
        Api.Res ir = act(c.o(), id, "inspect", Map.of("lines", List.of(Map.of("line_id", line, "good_qty", 14, "rejected_qty", 2, "unit_cost", 8200,
                "reject_reason", "라벨 오염"))));
        assertThat(ir.status()).as(ir.body().toString()).isEqualTo(200);
        JsonNode iv = ir.data();
        assertThat(iv.get("status").asString()).isEqualTo("PUTAWAY");
        assertThat(iv.get("has_discrepancy").asBoolean()).isTrue();
        assertThat(iv.get("receipt_no").asString()).startsWith("GR-");
        // 검수 후: 실물(원장) +14, 판매가능은 적치 전까지 그대로
        assertThat(onHand(c)).isEqualTo(onHand0 + 14);
        assertThat(atp(c)).isEqualTo(atp0);
        assertThat(jdbc.sql("select sum(on_hand) from inventory_stock where owner_business_entity_id = :b and sku_id = :s and quality_status = 'PENDING_INSPECTION' and hold_reason = 'PUTAWAY_PENDING'")
                .param("b", c.be()).param("s", c.amp()).query(Integer.class).single()).isEqualTo(14);
        JsonNode pov = c.o().get("/api/v1/purchase-orders/" + po).data();
        assertThat(pov.get("status").asString()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(pov.get("received_qty").asInt()).isEqualTo(14);
        assertThat(pov.get("rejected_qty").asInt()).isEqualTo(2);
        assertThat(inbound(c)).isEqualTo(4);
        assertThat(c.o().get("/api/v1/work-items?item_type=PURCHASE_RETURN_TO_SUPPLIER&status=OPEN").data()).hasSize(1);
        assertThat(c.o().get("/api/v1/work-items?item_type=INBOUND_DISCREPANCY&status=OPEN").data()).hasSize(1);

        // 적치: 부분(로케이션 A-01) → 나머지. 남은 수량 초과 거부, 없는 로케이션 거부(원자적으로 되돌림)
        assertThat(act(c.o(), id, "putaway", Map.of("lines", List.of(Map.of("line_id", line, "quantity", 15)))).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(act(c.o(), id, "putaway", Map.of("lines", List.of(Map.of("line_id", line, "quantity", 3, "location_code", "Z-99")))).errorCode())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(atp(c)).isEqualTo(atp0);
        JsonNode p1 = act(c.o(), id, "putaway", Map.of("lines", List.of(Map.of("line_id", line, "quantity", 10, "location_code", "A-01")))).data();
        assertThat(p1.get("status").asString()).isEqualTo("PUTAWAY");
        assertThat(p1.get("lines").get(0).get("putaway_remaining").asInt()).isEqualTo(4);
        assertThat(atp(c)).isEqualTo(atp0 + 10);
        assertThat(jdbc.sql("""
                select coalesce(sum(s.on_hand), 0) from inventory_stock s join node_location l on l.id = s.location_id
                 where s.owner_business_entity_id = :b and s.sku_id = :k and l.code = 'A-01' and s.quality_status = 'SELLABLE'
                """).param("b", c.be()).param("k", c.amp()).query(Integer.class).single()).isEqualTo(10);
        JsonNode done = act(c.o(), id, "putaway", Map.of("lines", List.of(Map.of("line_id", line)))).data();
        assertThat(done.get("status").asString()).isEqualTo("DISCREPANCY");
        assertThat(done.get("completed_at").isNull()).isFalse();
        assertThat(atp(c)).isEqualTo(atp0 + 14);
        assertThat(onHand(c)).isEqualTo(onHand0 + 14); // 적치는 실물을 다시 더하지 않음
        List<String> steps = new ArrayList<>();
        done.get("events").forEach(e -> steps.add(e.get("to_status").asString()));
        assertThat(steps).containsExactly("EXPECTED", "ARRIVED", "INSPECTING", "PUTAWAY", "PUTAWAY", "DISCREPANCY");
        assertThat(jdbc.sql("select count(*) from audit_log where target_type = 'INBOUND_SHIPMENT' and target_id = cast(:id as uuid)").param("id", id).query(Integer.class)
                .single()).isEqualTo(6);
        assertThat(act(c.o(), id, "cancel", Map.of("reason", "x")).errorCode()).isEqualTo("INBOUND_INVALID_STATE");

        // 두 번째 입고 건: 잔량 4 예정인데 6 도착 → 초과 2 는 미인수(반송 업무), 잔량 초과 인수는 거부
        String id2 = c.o().post("/api/v1/purchase-orders/" + po + "/inbound-shipments", Map.of("lines", List.of(Map.of("po_line_id", poLine, "quantity", 4)))).data()
                .get("id").asString();
        String line2 = c.o().get("/api/v1/inbound-shipments/" + id2).data().get("lines").get(0).get("id").asString();
        act(c.o(), id2, "arrive", Map.of("lines", List.of(Map.of("line_id", line2, "arrived_qty", 6))));
        act(c.o(), id2, "start-inspection", Map.of());
        assertThat(act(c.o(), id2, "inspect", Map.of("lines", List.of(Map.of("line_id", line2, "good_qty", 6)))).errorCode()).isEqualTo("PO_OVER_RECEIPT");
        JsonNode i2 = act(c.o(), id2, "inspect", Map.of("lines", List.of(Map.of("line_id", line2, "good_qty", 4, "over_qty", 2, "reject_reason", "발주 초과 도착")))).data();
        assertThat(i2.get("status").asString()).isEqualTo("PUTAWAY");
        assertThat(c.o().get("/api/v1/purchase-orders/" + po).data().get("status").asString()).isEqualTo("RECEIVED");
        assertThat(onHand(c)).isEqualTo(onHand0 + 18);
        assertThat(c.o().get("/api/v1/work-items?item_type=PURCHASE_RETURN_TO_SUPPLIER&status=OPEN").data()).hasSize(2);
        assertThat(act(c.o(), id2, "putaway", Map.of("lines", List.of(Map.of("line_id", line2, "location_code", "A-01")))).data().get("status").asString())
                .isEqualTo("DISCREPANCY");
        assertThat(c.o().get("/api/v1/inbound-shipments?status=OPEN").data()).isEmpty();
        assertThat(c.o().get("/api/v1/inbound-shipments?po_id=" + po).data()).hasSize(2);
    }

    @Test
    void permissionsScopeCancel_andConcurrentInspectionPostsOnce() throws Exception {
        Ctx c = ctx("입고권한");
        String sup = supplier(c, "권한 공급처");
        Api ops = fx.member(c.ws(), "OPS_ADMIN");
        String po = place(c, po(c, sup, 10), ops);
        String poLine = c.o().get("/api/v1/purchase-orders/" + po).data().get("lines").get(0).get("id").asString();
        Api cs = fx.member(c.ws(), "CS_AGENT");
        Map<String, Object> plan = Map.of("lines", List.of(Map.of("po_line_id", poLine, "quantity", 10)));
        assertThat(cs.post("/api/v1/purchase-orders/" + po + "/inbound-shipments", plan).status()).isEqualTo(403);
        String id = c.o().post("/api/v1/purchase-orders/" + po + "/inbound-shipments", plan).data().get("id").asString();
        String line = c.o().get("/api/v1/inbound-shipments/" + id).data().get("lines").get(0).get("id").asString();
        assertThat(cs.post("/api/v1/inbound-shipments/" + id + "/arrive", Map.of("lines", List.of(Map.of("line_id", line, "arrived_qty", 10)))).status()).isEqualTo(403);
        assertThat(fx.workspace().owner().get("/api/v1/inbound-shipments/" + id).status()).isEqualTo(404);
        assertThat(fx.workspace().owner().get("/api/v1/inbound-shipments").data()).isEmpty();

        // 취소: 사유 필수 → 취소 후 같은 수량을 다시 예정할 수 있고 즉시 입고도 가능
        assertThat(act(c.o(), id, "cancel", Map.of()).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(act(c.o(), id, "cancel", Map.of("reason", "운송사 변경")).data().get("status").asString()).isEqualTo("CANCELLED");
        String id2 = c.o().post("/api/v1/purchase-orders/" + po + "/inbound-shipments", plan).data().get("id").asString();
        String line2 = c.o().get("/api/v1/inbound-shipments/" + id2).data().get("lines").get(0).get("id").asString();
        act(c.o(), id2, "arrive", Map.of("lines", List.of(Map.of("line_id", line2, "arrived_qty", 10))));
        act(c.o(), id2, "start-inspection", Map.of());
        int before = onHand(c);
        Map<String, Object> body = Map.of("lines", List.of(Map.of("line_id", line2, "good_qty", 10)));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> c.o().post("/api/v1/inbound-shipments/" + id2 + "/inspect", body).status());
        }
        List<Integer> st = new ArrayList<>();
        for (java.util.concurrent.Future<Integer> f : pool.invokeAll(tasks)) {
            st.add(f.get());
        }
        pool.shutdown();
        assertThat(st).filteredOn(x -> x == 200).hasSize(1);
        assertThat(onHand(c)).isEqualTo(before + 10);
        assertThat(c.o().get("/api/v1/purchase-orders/" + po).data().get("received_qty").asInt()).isEqualTo(10);
        assertThat(c.o().get("/api/v1/inbound-shipments/" + id2).data().get("status").asString()).isEqualTo("PUTAWAY");
        assertThat(c.o().get("/api/v1/work-items?item_type=INBOUND_DISCREPANCY&status=OPEN").data()).isEmpty();
        assertThat(act(c.o(), id2, "putaway", Map.of("lines", List.of(Map.of("line_id", line2)))).data().get("status").asString()).isEqualTo("COMPLETED");
    }
}
