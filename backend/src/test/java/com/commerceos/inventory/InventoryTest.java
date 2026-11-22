package com.commerceos.inventory;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** M3 I1~I3: 출고거점·화주 분리(T05), 예약→할당→피킹→소진(T06), 동시 확보 원자성(T07), 원장·검증·권한. */
class InventoryTest extends IntegrationTest {
    @Autowired
    ReservationService reservationService;

    record Ctx(Fixtures.Ws ws, Api o, UUID beA, UUID beB, UUID self, UUID sku, UUID sku2, UUID lotSku, UUID bundle) {
    }

    Ctx ctx() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        UUID a = fx.businessEntity(o, "에이상사");
        UUID b = fx.businessEntity(o, "비상사");
        UUID self = node(o, "SELF-01", "SELF_WMS", null);
        UUID product = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "AMP", "name", "앰플")).data().get("product").get("id").asString());
        UUID sku = sku(o, product, "AMP-30", Map.of());
        UUID sku2 = sku(o, product, "AMP-50", Map.of());
        UUID lot = sku(o, product, "AMP-LOT", Map.of("lot_managed", true, "expiry_managed", true));
        UUID bundle = sku(o, product, "AMP-SET", Map.of("sku_type", "BUNDLE"));
        return new Ctx(ws, o, a, b, self, sku, sku2, lot, bundle);
    }

    UUID sku(Api o, UUID product, String code, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("product_id", product.toString(), "sku_code", code, "sku_type", "NORMAL"));
        body.putAll(extra);
        Api.Res r = o.post("/api/v1/skus", body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.id();
    }

    UUID node(Api o, String code, String provider, String source) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("code", code, "name", code + " 센터", "logistics_provider_code", provider, "cutoff_time", "15:00"));
        if (source != null) {
            body.put("inventory_source", source);
        }
        Api.Res r = o.post("/api/v1/fulfillment-nodes", body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.id();
    }

    Api.Res receive(Api api, UUID be, UUID sku, UUID node, int qty, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("business_entity_id", be.toString(), "sku_id", sku.toString(), "node_id", node.toString(), "quantity", qty));
        body.putAll(extra);
        return api.post("/api/v1/inventory/receipts", body);
    }

    Api.Res reserve(Api api, UUID be, UUID node, String ref, Object... skuQty) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < skuQty.length; i += 2) {
            lines.add(Map.of("sku_id", skuQty[i].toString(), "quantity", skuQty[i + 1]));
        }
        return api.post("/api/v1/inventory/reservations", Map.of("business_entity_id", be.toString(), "node_id", node.toString(), "demand_type", "ORDER_LINE",
                "demand_ref", ref, "lines", lines));
    }

    Api.Res act(Api api, String reservationId, String action, Map<String, Object> body) {
        return api.post("/api/v1/inventory/reservations/" + reservationId + "/" + action, body);
    }

    JsonNode availability(Api api, UUID be, UUID sku) {
        Api.Res r = api.get("/api/v1/inventory/availability?business_entity_id=" + be + "&sku_id=" + sku);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.data();
    }

    JsonNode nodeAvail(Api api, UUID be, UUID sku, UUID node) {
        for (JsonNode n : availability(api, be, sku).get("nodes")) {
            if (n.get("node_id").asString().equals(node.toString())) {
                return n;
            }
        }
        throw new AssertionError("no node availability");
    }

    void assertVerified(Api api) {
        Api.Res v = api.get("/api/v1/inventory/verification");
        assertThat(v.status()).isEqualTo(200);
        assertThat(v.data()).as(v.body().toString()).isEmpty();
    }

    @Test
    void T05_sameThirdPartyNode_ownersSeparatedByShipperAccount() {
        Ctx c = ctx();
        Api o = c.o();
        assertThat(o.post("/api/v1/fulfillment-nodes", Map.of("code", "NO-CUT", "name", "마감 없음", "logistics_provider_code", "FASSTO")).errorCode())
                .isEqualTo("VALIDATION_FAILED"); // 출고 마감 필수(DLG-040)
        UUID fassto = node(o, "FASSTO-ICN", "FASSTO", "INTERNAL");
        assertThat(o.get("/api/v1/fulfillment-nodes/" + fassto).data().get("cutoff_time").asString()).startsWith("15:00");
        UUID sa = fx.shipperAccount(o, c.beA(), "FASSTO", "SHIP-A");
        UUID sb = fx.shipperAccount(o, c.beB(), "FASSTO", "SHIP-B");
        UUID poomgo = fx.shipperAccount(o, c.beB(), "POOMGO", "PG-B");

        assertThat(o.post("/api/v1/fulfillment-nodes/" + fassto + "/shippers", Map.of("shipper_account_id", sa.toString())).status()).isEqualTo(200);
        assertThat(o.post("/api/v1/fulfillment-nodes/" + fassto + "/shippers", Map.of("shipper_account_id", sa.toString())).errorCode())
                .isEqualTo("NODE_SHIPPER_DUPLICATE");
        assertThat(o.post("/api/v1/fulfillment-nodes/" + fassto + "/shippers", Map.of("shipper_account_id", poomgo.toString())).errorCode())
                .isEqualTo("NODE_SHIPPER_PROVIDER_MISMATCH");
        assertThat(o.post("/api/v1/fulfillment-nodes/" + c.self() + "/shippers", Map.of("shipper_account_id", sa.toString())).errorCode())
                .isEqualTo("NODE_SHIPPER_NOT_APPLICABLE");

        // B 는 아직 이 센터에 화주계정이 없다 → 입고 불가(무단 혼용 금지)
        assertThat(receive(o, c.beB(), c.sku(), fassto, 5, Map.of()).errorCode()).isEqualTo("OWNER_NOT_ON_NODE");
        assertThat(o.post("/api/v1/fulfillment-nodes/" + fassto + "/shippers", Map.of("shipper_account_id", sb.toString())).status()).isEqualTo(200);
        assertThat(receive(o, c.beA(), c.sku(), fassto, 10, Map.of()).status()).isEqualTo(201);
        assertThat(receive(o, c.beB(), c.sku(), fassto, 5, Map.of()).status()).isEqualTo(201);

        assertThat(nodeAvail(o, c.beA(), c.sku(), fassto).get("atp").asInt()).isEqualTo(10);
        assertThat(nodeAvail(o, c.beB(), c.sku(), fassto).get("atp").asInt()).isEqualTo(5);
        // 같은 센터 합계 15 여도 A 는 자기 재고 10 까지만 확보할 수 있다
        Api.Res over = reserve(o, c.beA(), fassto, "ORD-1", c.sku(), 12);
        assertThat(over.errorCode()).isEqualTo("INSUFFICIENT_AVAILABLE_STOCK");
        assertThat(over.body().get("error").get("details").get("lines").get(0).get("available").asInt()).isEqualTo(10);
        assertThat(reserve(o, c.beA(), fassto, "ORD-1", c.sku(), 10).status()).isEqualTo(201);
        assertThat(nodeAvail(o, c.beB(), c.sku(), fassto).get("atp").asInt()).isEqualTo(5);

        // 물류사 재고가 기준인 거점(EXTERNAL)은 수동 입고를 받지 않는다(I4 동기화로만 반영)
        UUID ext = node(o, "FASSTO-EXT", "FASSTO", null);
        assertThat(o.get("/api/v1/fulfillment-nodes/" + ext).data().get("inventory_source").asString()).isEqualTo("EXTERNAL");
        o.post("/api/v1/fulfillment-nodes/" + ext + "/shippers", Map.of("shipper_account_id", sa.toString()));
        assertThat(receive(o, c.beA(), c.sku(), ext, 1, Map.of()).errorCode()).isEqualTo("NODE_INVENTORY_EXTERNAL");
        // 로케이션은 자체 WMS 거점에서만
        assertThat(o.post("/api/v1/fulfillment-nodes/" + fassto + "/locations", Map.of("code", "A-01")).errorCode()).isEqualTo("NODE_LOCATION_NOT_APPLICABLE");
        assertThat(o.post("/api/v1/fulfillment-nodes/" + c.self() + "/locations", Map.of("code", "A-01", "zone", "A")).status()).isEqualTo(201);
        assertThat(receive(o, c.beA(), c.sku(), c.self(), 1, Map.of("location_code", "Z-99")).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(receive(o, c.beA(), c.sku(), c.self(), 1, Map.of("location_code", "A-01")).data().get("location_code").asString()).isEqualTo("A-01");
        // 재고·진행 중 확보가 있는 거점은 닫지 못한다
        int v = o.get("/api/v1/fulfillment-nodes/" + fassto).data().get("version").asInt();
        assertThat(o.patch("/api/v1/fulfillment-nodes/" + fassto, Map.of("status", "CLOSED"), v).errorCode()).isEqualTo("NODE_HAS_STOCK");
        assertVerified(o);
    }

    @Test
    void T06_reserveAllocatePickConsume_keepsCommittedOnce_andNoDoubleAtpDeduction() {
        Ctx c = ctx();
        Api o = c.o();
        assertThat(receive(o, c.beA(), c.sku(), c.self(), 5, Map.of("reference_type", "PO", "reference_id", "PO-1")).status()).isEqualTo(201);
        Api.Res r = reserve(o, c.beA(), c.self(), "ORD-100:1", c.sku(), 2);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        String id = r.data().get(0).get("id").asString();
        JsonNode n = nodeAvail(o, c.beA(), c.sku(), c.self());
        assertThat(n.get("committed").asInt()).isEqualTo(2);
        assertThat(n.get("atp").asInt()).isEqualTo(3);

        assertThat(act(o, id, "pick", Map.of()).errorCode()).isEqualTo("RESERVATION_STATE_INVALID");
        assertThat(act(o, id, "consume", Map.of()).errorCode()).isEqualTo("RESERVATION_STATE_INVALID");
        assertThat(act(o, id, "allocate", Map.of()).data().get("state").asString()).isEqualTo("ALLOCATED");
        assertThat(act(o, id, "allocate", Map.of()).data().get("state").asString()).isEqualTo("ALLOCATED"); // 재시도는 그대로
        n = nodeAvail(o, c.beA(), c.sku(), c.self());
        assertThat(n.get("committed").asInt()).isEqualTo(2);
        assertThat(n.get("atp").asInt()).isEqualTo(3);
        assertThat(act(o, id, "pick", Map.of()).data().get("state").asString()).isEqualTo("PICKING");
        n = nodeAvail(o, c.beA(), c.sku(), c.self());
        assertThat(n.get("committed").asInt()).isEqualTo(2);
        assertThat(n.get("atp").asInt()).isEqualTo(3);
        assertThat(act(o, id, "consume", Map.of("reason", "출고 확정")).data().get("state").asString()).isEqualTo("CONSUMED");
        n = nodeAvail(o, c.beA(), c.sku(), c.self());
        assertThat(n.get("sellable_on_hand").asInt()).isEqualTo(3);
        assertThat(n.get("committed").asInt()).isEqualTo(0);
        assertThat(n.get("atp").asInt()).isEqualTo(3); // 소진 때 ATP 추가 변화 없음
        assertThat(act(o, id, "consume", Map.of()).data().get("state").asString()).isEqualTo("CONSUMED");
        assertThat(act(o, id, "release", Map.of()).errorCode()).isEqualTo("RESERVATION_STATE_INVALID");

        JsonNode txns = o.get("/api/v1/inventory/transactions?sku_id=" + c.sku()).data();
        List<String> types = new ArrayList<>();
        int committedSum = 0;
        int onHandSum = 0;
        for (JsonNode t : txns) {
            types.add(t.get("txn_type").asString());
            committedSum += t.get("committed_delta").asInt();
            onHandSum += t.get("on_hand_delta").asInt();
        }
        assertThat(types).containsExactly("CONSUME", "PICK", "ALLOCATE", "RESERVE", "RECEIPT");
        assertThat(committedSum).isZero();
        assertThat(onHandSum).isEqualTo(3);

        // 피킹 중 해제는 작업 복귀 확인 필요
        String id2 = reserve(o, c.beA(), c.self(), "ORD-101:1", c.sku(), 1).data().get(0).get("id").asString();
        act(o, id2, "allocate", Map.of());
        act(o, id2, "pick", Map.of());
        assertThat(act(o, id2, "release", Map.of()).errorCode()).isEqualTo("PICK_RETURN_CONFIRMATION_REQUIRED");
        assertThat(act(o, id2, "release", Map.of("pick_return_confirmed", true, "reason", "출고 중지")).data().get("state").asString()).isEqualTo("RELEASED");
        assertThat(nodeAvail(o, c.beA(), c.sku(), c.self()).get("atp").asInt()).isEqualTo(3);
        assertVerified(o);
    }

    @Test
    void T07_concurrentReservations_overAtp_onlyOneSucceeds() throws Exception {
        Ctx c = ctx();
        Api o = c.o();
        receive(o, c.beA(), c.sku(), c.self(), 10, Map.of());
        for (int round = 0; round < 3; round++) {
            int r0 = round;
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Callable<Api.Res>> tasks = List.of(
                    () -> { go.await(); return reserve(o, c.beA(), c.self(), "R" + r0 + "-A", c.sku(), 8); },
                    () -> { go.await(); return reserve(o, c.beA(), c.self(), "R" + r0 + "-B", c.sku(), 8); });
            List<Future<Api.Res>> fs = new ArrayList<>();
            for (Callable<Api.Res> t : tasks) {
                fs.add(pool.submit(t));
            }
            go.countDown();
            List<Integer> st = new ArrayList<>();
            String okId = null;
            for (Future<Api.Res> f : fs) {
                Api.Res res = f.get();
                st.add(res.status());
                if (res.status() == 201) {
                    okId = res.data().get(0).get("id").asString();
                } else {
                    assertThat(res.errorCode()).isEqualTo("INSUFFICIENT_AVAILABLE_STOCK");
                }
            }
            pool.shutdown();
            assertThat(st).containsExactlyInAnyOrder(201, 409);
            assertThat(nodeAvail(o, c.beA(), c.sku(), c.self()).get("committed").asInt()).isEqualTo(8);
            assertThat(act(o, okId, "release", Map.of()).status()).isEqualTo(200);
        }
        assertVerified(o);
    }

    @Test
    void multiLine_isAllOrNothing_safetyStockApplied_andDemandIdempotent() {
        Ctx c = ctx();
        Api o = c.o();
        receive(o, c.beA(), c.sku(), c.self(), 5, Map.of());
        receive(o, c.beA(), c.sku2(), c.self(), 1, Map.of());
        Api.Res fail = reserve(o, c.beA(), c.self(), "SET-1", c.sku(), 2, c.sku2(), 3);
        assertThat(fail.errorCode()).isEqualTo("INSUFFICIENT_AVAILABLE_STOCK");
        assertThat(fail.body().get("error").get("details").get("lines")).hasSize(1);
        assertThat(nodeAvail(o, c.beA(), c.sku(), c.self()).get("committed").asInt()).isZero(); // 부분 확보 없음
        assertThat(o.get("/api/v1/inventory/reservations?demand_ref=SET-1").data()).isEmpty();

        // 같은 수요 재요청: 내용이 같으면 기존 확보 그대로, 다르면 충돌
        Api.Res ok = reserve(o, c.beA(), c.self(), "SET-2", c.sku(), 2, c.sku2(), 1);
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.data()).hasSize(2);
        assertThat(ok.data().get(0).get("group_id").asString()).isEqualTo(ok.data().get(1).get("group_id").asString());
        Api.Res again = reserve(o, c.beA(), c.self(), "SET-2", c.sku(), 2, c.sku2(), 1);
        assertThat(again.status()).isEqualTo(201);
        assertThat(nodeAvail(o, c.beA(), c.sku(), c.self()).get("committed").asInt()).isEqualTo(2);
        assertThat(reserve(o, c.beA(), c.self(), "SET-2", c.sku(), 3).errorCode()).isEqualTo("RESERVATION_EXISTS");
        Api.Res rel = o.post("/api/v1/inventory/reservations/release-by-demand", Map.of("demand_type", "ORDER_LINE", "demand_ref", "SET-2", "reason", "주문 취소"));
        assertThat(rel.status()).as(rel.body().toString()).isEqualTo(200);
        assertThat(rel.data()).hasSize(2);
        assertThat(nodeAvail(o, c.beA(), c.sku(), c.self()).get("committed").asInt()).isZero();

        // 안전재고 3: 전체 ATP 5 − 3 = 2
        assertThat(o.put("/api/v1/business-entities/" + c.beA() + "/sku-policies/" + c.sku(), Map.of("safety_stock", 3), null).status()).isEqualTo(200);
        assertThat(availability(o, c.beA(), c.sku()).get("atp").asInt()).isEqualTo(2);
        Api.Res safe = reserve(o, c.beA(), c.self(), "SAFE-1", c.sku(), 3);
        assertThat(safe.errorCode()).isEqualTo("INSUFFICIENT_AVAILABLE_STOCK");
        assertThat(safe.body().get("error").get("details").get("lines").get(0).get("total_atp_after_safety_stock").asInt()).isEqualTo(2);
        assertThat(reserve(o, c.beA(), c.self(), "SAFE-2", c.sku(), 2).status()).isEqualTo(201);

        // 재고관리 대상이 아닌 가상묶음은 직접 확보하지 않는다(부품으로 확보)
        assertThat(reserve(o, c.beA(), c.self(), "BND-1", c.bundle(), 1).errorCode()).isEqualTo("SKU_NOT_INVENTORY_MANAGED");
        assertThat(reserve(o, c.beA(), c.self(), "DUP", c.sku(), 1, c.sku(), 1).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertVerified(o);
    }

    @Test
    void qualityChange_cannotTakeCommittedStock_lotFefoOnConsume() {
        Ctx c = ctx();
        Api o = c.o();
        String stock = receive(o, c.beA(), c.sku(), c.self(), 5, Map.of()).data().get("id").asString();
        reserve(o, c.beA(), c.self(), "Q-1", c.sku(), 4);
        Map<String, Object> move = new LinkedHashMap<>(Map.of("stock_id", stock, "quantity", 2, "to_quality_status", "DEFECTIVE", "reason", "외관 불량"));
        Api.Res blocked = o.post("/api/v1/inventory/quality-changes", move);
        assertThat(blocked.errorCode()).isEqualTo("STOCK_COMMITTED");
        assertThat(blocked.body().get("error").get("details").get("movable").asInt()).isEqualTo(1);
        move.put("quantity", 1);
        move.remove("reason");
        assertThat(o.post("/api/v1/inventory/quality-changes", move).errorCode()).isEqualTo("VALIDATION_FAILED"); // 사유 필수
        move.put("reason", "외관 불량");
        move.put("to_hold_reason", "supplier_claim");
        Api.Res moved = o.post("/api/v1/inventory/quality-changes", move);
        assertThat(moved.status()).as(moved.body().toString()).isEqualTo(201);
        assertThat(moved.data().get(1).get("quality_status").asString()).isEqualTo("DEFECTIVE");
        assertThat(moved.data().get(1).get("hold_reason").asString()).isEqualTo("SUPPLIER_CLAIM");
        JsonNode n = nodeAvail(o, c.beA(), c.sku(), c.self());
        assertThat(n.get("sellable_on_hand").asInt()).isEqualTo(4);
        assertThat(n.get("atp").asInt()).isZero();
        JsonNode pos = o.get("/api/v1/inventory/positions?sku_id=" + c.sku()).data().get(0);
        assertThat(pos.get("non_sellable_on_hand").asInt()).isEqualTo(1);
        // 판매가능에는 보류 사유 불가
        assertThat(receive(o, c.beA(), c.sku(), c.self(), 1, Map.of("hold_reason", "X1")).errorCode()).isEqualTo("VALIDATION_FAILED");

        // LOT·유통기한 관리 SKU: LOT·유통기한 필수, 같은 LOT 다른 유통기한 금지, 소진은 유통기한 빠른 LOT 부터
        assertThat(receive(o, c.beA(), c.lotSku(), c.self(), 2, Map.of()).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(receive(o, c.beA(), c.lotSku(), c.self(), 2, Map.of("lot_code", "L-LATE", "expires_on", "2027-12-31")).status()).isEqualTo(201);
        assertThat(receive(o, c.beA(), c.lotSku(), c.self(), 2, Map.of("lot_code", "L-EARLY", "expires_on", "2027-03-31")).status()).isEqualTo(201);
        assertThat(receive(o, c.beA(), c.lotSku(), c.self(), 1, Map.of("lot_code", "L-EARLY", "expires_on", "2027-04-30")).errorCode()).isEqualTo("LOT_EXPIRY_MISMATCH");
        assertThat(receive(o, c.beA(), c.sku(), c.self(), 1, Map.of("lot_code", "L-1")).errorCode()).isEqualTo("VALIDATION_FAILED");
        String rid = reserve(o, c.beA(), c.self(), "LOT-1", c.lotSku(), 3).data().get(0).get("id").asString();
        act(o, rid, "allocate", Map.of());
        act(o, rid, "pick", Map.of());
        assertThat(act(o, rid, "consume", Map.of()).status()).isEqualTo(200);
        Map<String, Integer> byLot = new LinkedHashMap<>();
        for (JsonNode s : o.get("/api/v1/inventory/stocks?sku_id=" + c.lotSku()).data()) {
            byLot.put(s.get("lot_code").asString(), s.get("on_hand").asInt());
        }
        assertThat(byLot).containsExactly(Map.entry("L-LATE", 1));
        assertVerified(o);
    }

    @Test
    void ledger_isAppendOnly_receiptIdempotent_andScopeEnforced() {
        Ctx c = ctx();
        Api o = c.o();
        Map<String, Object> body = Map.of("business_entity_id", c.beA().toString(), "sku_id", c.sku().toString(), "node_id", c.self().toString(), "quantity", 3);
        Api.Res first = o.postWithKey("/api/v1/inventory/receipts", body, "rcpt-key-0001");
        Api.Res replay = o.postWithKey("/api/v1/inventory/receipts", body, "rcpt-key-0001");
        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(nodeAvail(o, c.beA(), c.sku(), c.self()).get("sellable_on_hand").asInt()).isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from inventory_transaction where workspace_id = :ws").param("ws", c.ws().id()).query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("update inventory_transaction set on_hand_delta = 99 where workspace_id = :ws").param("ws", c.ws().id()).update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from inventory_transaction where workspace_id = :ws").param("ws", c.ws().id()).update())
                .rootCause().hasMessageContaining("append-only");
        // DB 최종 방어: 확보수량은 판매가능 실물을 넘을 수 없다
        assertThatThrownBy(() -> jdbc.sql("update inventory_position set committed = 4 where workspace_id = :ws").param("ws", c.ws().id()).update())
                .rootCause().hasMessageContaining("inventory_position");

        // 조회 전용(VIEWER)은 조회만
        Api viewer = fx.member(c.ws(), "VIEWER");
        assertThat(viewer.get("/api/v1/inventory/positions").status()).isEqualTo(200);
        assertThat(receive(viewer, c.beA(), c.sku(), c.self(), 1, Map.of()).errorCode()).isEqualTo("PERMISSION_DENIED");
        // 사업자 A 만 수정할 수 있는 물류 담당: B 재고 입고·확보 거부, A 는 허용
        Api logi = fx.member(c.ws(), "LOGISTICS_MANAGER");
        assertThat(fx.setPolicy(o, logi, null, List.of(scope("BUSINESS_ENTITY", "WRITE", "SELECTED", c.beA(), "ALLOW"))).status()).isEqualTo(200);
        assertThat(receive(logi, c.beB(), c.sku(), c.self(), 1, Map.of()).errorCode()).isEqualTo("WRITE_SCOPE_DENIED");
        assertThat(reserve(logi, c.beB(), c.self(), "S-1", c.sku(), 1).errorCode()).isEqualTo("WRITE_SCOPE_DENIED");
        assertThat(receive(logi, c.beA(), c.sku(), c.self(), 1, Map.of()).status()).isEqualTo(201);
        // 사업자 A 만 볼 수 있는 사용자: B 재고·원장·확보가 보이지 않고, B 를 지정하면 거부
        receive(o, c.beB(), c.sku(), c.self(), 7, Map.of());
        String bRes = reserve(o, c.beB(), c.self(), "B-1", c.sku(), 1).data().get(0).get("id").asString();
        Api onlyA = fx.member(c.ws(), "VIEWER");
        fx.setPolicy(o, onlyA, null, List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", c.beA(), "ALLOW")));
        for (JsonNode p : onlyA.get("/api/v1/inventory/positions").data()) {
            assertThat(p.get("business_entity_id").asString()).isEqualTo(c.beA().toString());
        }
        for (JsonNode t : onlyA.get("/api/v1/inventory/transactions").data()) {
            assertThat(t.get("business_entity_id").asString()).isEqualTo(c.beA().toString());
        }
        assertThat(onlyA.get("/api/v1/inventory/reservations").data()).isEmpty();
        assertThat(onlyA.get("/api/v1/inventory/reservations/" + bRes).status()).isEqualTo(404);
        assertThat(onlyA.get("/api/v1/inventory/positions?business_entity_id=" + c.beB()).errorCode()).isEqualTo("ACCESS_SCOPE_DENIED");
        assertThat(onlyA.get("/api/v1/inventory/availability?business_entity_id=" + c.beB() + "&sku_id=" + c.sku()).status()).isEqualTo(404);
        // 다른 운영그룹의 거점·재고는 존재하지 않는 것으로 보인다
        Fixtures.Ws other = fx.workspace();
        assertThat(other.owner().get("/api/v1/fulfillment-nodes/" + c.self()).status()).isEqualTo(404);
        assertThat(other.owner().get("/api/v1/inventory/positions").data()).isEmpty();
    }

    @Test
    void expiredReservedReservations_areReleasedAutomatically() throws Exception {
        Ctx c = ctx();
        Api o = c.o();
        receive(o, c.beA(), c.sku(), c.self(), 4, Map.of());
        Api.Res r = o.post("/api/v1/inventory/reservations", Map.of("business_entity_id", c.beA().toString(), "node_id", c.self().toString(),
                "demand_type", "CART_HOLD", "demand_ref", "CART-1", "expires_at", Instant.now().plusMillis(800).toString(),
                "lines", List.of(Map.of("sku_id", c.sku().toString(), "quantity", 3))));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        String allocated = reserve(o, c.beA(), c.self(), "KEEP", c.sku(), 1).data().get(0).get("id").asString();
        act(o, allocated, "allocate", Map.of());
        assertThat(nodeAvail(o, c.beA(), c.sku(), c.self()).get("atp").asInt()).isZero();
        Thread.sleep(1200);
        assertThat(reservationService.releaseExpired()).isGreaterThanOrEqualTo(1);
        assertThat(o.get("/api/v1/inventory/reservations/" + r.data().get(0).get("id").asString()).data().get("state").asString()).isEqualTo("RELEASED");
        assertThat(nodeAvail(o, c.beA(), c.sku(), c.self()).get("atp").asInt()).isEqualTo(3);
        JsonNode rel = o.get("/api/v1/inventory/transactions?txn_type=RELEASE").data();
        assertThat(rel.get(0).get("reason").asString()).isEqualTo("확보 만료 자동 해제");
        assertThat(rel.get(0).get("actor_name").isNull()).isTrue();
        assertVerified(o);
    }
}
