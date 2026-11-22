package com.commerceos.inventory;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** M3 I6: 재고 검증센터(감지·근거·중복 없는 추적·확인·재검증 자동 해결)와 출고거점 범위(조회·쓰기 제한). */
class VerificationTest extends IntegrationTest {
    @Autowired
    VerificationService verification;
    @Autowired
    ReservationService reservations;

    record Ctx(Fixtures.Ws ws, Api o, UUID be, UUID n1, UUID n2, UUID sku) {
    }

    Ctx ctx() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        UUID be = fx.businessEntity(o, "에이상사");
        UUID product = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "AMP", "name", "앰플")).data().get("product").get("id").asString());
        UUID sku = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "AMP-30", "sku_type", "NORMAL")).id();
        o.post("/api/v1/business-entities/" + be + "/sku-costs/" + sku, Map.of("unit_cost", 9000, "reason", "기준 원가"));
        UUID n1 = o.post("/api/v1/fulfillment-nodes", Map.of("code", "HS", "name", "화성", "logistics_provider_code", "SELF_WMS", "cutoff_time", "16:00")).id();
        UUID n2 = o.post("/api/v1/fulfillment-nodes", Map.of("code", "GP", "name", "김포", "logistics_provider_code", "SELF_WMS", "cutoff_time", "16:00")).id();
        receive(o, be, sku, n1, 10);
        receive(o, be, sku, n2, 20);
        return new Ctx(ws, o, be, n1, n2, sku);
    }

    Api.Res receive(Api api, UUID be, UUID sku, UUID node, int qty) {
        return api.post("/api/v1/inventory/receipts", Map.of("business_entity_id", be.toString(), "sku_id", sku.toString(), "node_id", node.toString(), "quantity", qty));
    }

    List<JsonNode> issues(Api api, String query) {
        List<JsonNode> out = new ArrayList<>();
        api.get("/api/v1/inventory/checks/issues" + query).data().forEach(out::add);
        return out;
    }

    @Test
    void detectsMismatch_withEvidenceAndCost_tracksOnce_acknowledge_recheckResolves() {
        Ctx c = ctx();
        JsonNode clean = c.o().post("/api/v1/inventory/checks/runs", Map.of()).data();
        assertThat(clean.get("found_issues").asInt()).isZero();
        assertThat(clean.get("checked_positions").asInt()).isEqualTo(2);

        // 원장 없이 버킷을 직접 바꾼 상황(장애·수동 DB 수정)을 흉내 낸다
        jdbc.sql("update inventory_stock set on_hand = on_hand + 5 where node_id = :n").param("n", c.n1()).update();
        JsonNode run = c.o().post("/api/v1/inventory/checks/runs", Map.of()).data();
        assertThat(run.get("new_issues").asInt()).isEqualTo(3);
        List<String> codes = issues(c.o(), "").stream().map(i -> i.get("check_code").asString()).sorted().toList();
        assertThat(codes).containsExactly("BUCKETS_VS_LEDGER", "BUCKET_VS_LEDGER", "POSITION_VS_BUCKETS");
        JsonNode bucket = issues(c.o(), "?check_code=BUCKET_VS_LEDGER").get(0);
        assertThat(bucket.get("severity").asString()).isEqualTo("HIGH");
        assertThat(bucket.get("expected").asLong()).isEqualTo(10);
        assertThat(bucket.get("actual").asLong()).isEqualTo(15);
        assertThat(bucket.get("cost_impact").asString()).isEqualTo("45000");
        assertThat(bucket.get("evidence").get("recent_ledger").get(0).get("txn_type").asString()).isEqualTo("RECEIPT");
        assertThat(bucket.get("node_id").asString()).isEqualTo(c.n1().toString());
        assertThat(c.o().get("/api/v1/work-items?item_type=INVENTORY_CHECK_FAILED&status=OPEN").data()).hasSize(1);

        // 다시 검사해도 같은 이상은 한 건(발견 횟수만 증가)
        c.o().post("/api/v1/inventory/checks/runs", Map.of());
        assertThat(issues(c.o(), "")).hasSize(3);
        assertThat(issues(c.o(), "?check_code=BUCKET_VS_LEDGER").get(0).get("detection_count").asInt()).isEqualTo(2);

        // 확인 처리: 사유 필수, 조회 전용 불가
        String id = bucket.get("id").asString();
        assertThat(c.o().post("/api/v1/inventory/checks/issues/" + id + "/acknowledge", Map.of("reason", " ")).errorCode()).isEqualTo("VALIDATION_FAILED");
        Api viewer = fx.member(c.ws(), "VIEWER");
        assertThat(viewer.post("/api/v1/inventory/checks/issues/" + id + "/acknowledge", Map.of("reason", "x")).errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(viewer.post("/api/v1/inventory/checks/runs", Map.of()).errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(viewer.get("/api/v1/inventory/checks/issues").data()).hasSize(3);
        JsonNode ack = c.o().post("/api/v1/inventory/checks/issues/" + id + "/acknowledge", Map.of("reason", "실사 예정 — 원인 조사 중")).data();
        assertThat(ack.get("status").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(c.o().get("/api/v1/inventory/checks/summary").data().get("open_high").asInt()).isEqualTo(2);

        // 원인 해소 후 재검증 → 자동 해결, 업무함 종료
        jdbc.sql("update inventory_stock set on_hand = on_hand - 5 where node_id = :n").param("n", c.n1()).update();
        JsonNode re = c.o().post("/api/v1/inventory/checks/issues/" + id + "/recheck", Map.of()).data();
        assertThat(re.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(issues(c.o(), "")).isEmpty();
        assertThat(issues(c.o(), "?status=RESOLVED")).hasSize(3);
        assertThat(c.o().get("/api/v1/work-items?item_type=INVENTORY_CHECK_FAILED&status=OPEN").data()).isEmpty();
        assertThat(c.o().get("/api/v1/inventory/checks/runs").data().size()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void operationalChecks_expiredReservation_restriction_transferOverdue_andScheduledScan() throws Exception {
        Ctx c = ctx();
        Api.Res r = c.o().post("/api/v1/inventory/reservations", Map.of("business_entity_id", c.be().toString(), "node_id", c.n1().toString(),
                "demand_type", "CART_HOLD", "demand_ref", "CART-1", "expires_at", Instant.now().plusSeconds(1).toString(),
                "lines", List.of(Map.of("sku_id", c.sku().toString(), "quantity", 2))));
        assertThat(r.status()).isEqualTo(201);
        Thread.sleep(1200);
        jdbc.sql("update inventory_reservation set expires_at = now() - interval '1 hour' where workspace_id = :ws").param("ws", c.ws().id()).update();
        UUID transfer = UUID.fromString(c.o().post("/api/v1/inventory/transfers", Map.of("business_entity_id", c.be().toString(), "from_node_id", c.n2().toString(),
                "to_node_id", c.n1().toString(), "lines", List.of(Map.of("sku_id", c.sku().toString(), "quantity", 3)))).data().get("id").asString());
        jdbc.sql("update inventory_transfer set shipped_at = now() - interval '10 days' where id = :id").param("id", transfer).update();

        assertThat(verification.scanAll()).isGreaterThanOrEqualTo(1);
        List<String> codes = issues(c.o(), "").stream().map(i -> i.get("check_code").asString()).sorted().toList();
        assertThat(codes).containsExactly("RESERVATION_EXPIRED_UNRELEASED", "TRANSFER_OVERDUE");
        assertThat(issues(c.o(), "?severity=MEDIUM")).hasSize(2);

        reservations.releaseExpired();
        c.o().post("/api/v1/inventory/transfers/" + transfer + "/receive", Map.of("lines", List.of(Map.of("line_no", 1, "received_qty", 3))));
        c.o().post("/api/v1/inventory/checks/runs", Map.of());
        assertThat(issues(c.o(), "")).isEmpty();
    }

    @Test
    void fulfillmentNodeScope_limitsReadsAndWrites_andAutoIncludesNewNodes() {
        Ctx c = ctx();
        Api logi = fx.member(c.ws(), "LOGISTICS_MANAGER");
        Map<String, Object> readN1 = new LinkedHashMap<>(scope("FULFILLMENT_NODE", "READ", "SELECTED", c.n1(), "ALLOW"));
        Api.Res set = fx.setPolicy(c.o(), logi, null, List.of(readN1));
        assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
        assertThat(set.data().get("effective").get("fulfillment_nodes").get("READ")).hasSize(1);

        // 조회: 허용 거점만, 범위 밖 거점 지정은 거부, 범위 밖 객체는 없는 것처럼
        JsonNode positions = logi.get("/api/v1/inventory/positions").data();
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).get("node_id").asString()).isEqualTo(c.n1().toString());
        assertThat(logi.get("/api/v1/inventory/positions?node_id=" + c.n2()).errorCode()).isEqualTo("ACCESS_SCOPE_DENIED");
        assertThat(logi.get("/api/v1/fulfillment-nodes").data()).hasSize(1);
        assertThat(logi.get("/api/v1/fulfillment-nodes/" + c.n2()).status()).isEqualTo(404);
        JsonNode avail = logi.get("/api/v1/inventory/availability?business_entity_id=" + c.be() + "&sku_id=" + c.sku()).data();
        assertThat(avail.get("nodes")).hasSize(1);
        assertThat(avail.get("sellable_on_hand").asInt()).isEqualTo(10);
        for (JsonNode t : logi.get("/api/v1/inventory/transactions").data()) {
            assertThat(t.get("node_id").asString()).isEqualTo(c.n1().toString());
        }
        String n2Reservation = c.o().post("/api/v1/inventory/reservations", Map.of("business_entity_id", c.be().toString(), "node_id", c.n2().toString(),
                "demand_type", "ORDER_LINE", "demand_ref", "O-2", "lines", List.of(Map.of("sku_id", c.sku().toString(), "quantity", 1)))).data().get(0).get("id").asString();
        assertThat(logi.get("/api/v1/inventory/reservations").data()).isEmpty();
        assertThat(logi.get("/api/v1/inventory/reservations/" + n2Reservation).status()).isEqualTo(404);

        // 쓰기: 범위 밖 거점 입고·확보 전환 거부, 범위 안은 허용
        assertThat(receive(logi, c.be(), c.sku(), c.n2(), 1).status()).isEqualTo(404);
        assertThat(logi.post("/api/v1/inventory/reservations/" + n2Reservation + "/allocate", Map.of()).status()).isEqualTo(404);
        assertThat(receive(logi, c.be(), c.sku(), c.n1(), 1).status()).isEqualTo(201);
        // 조회는 둘 다, 쓰기는 화성만
        Map<String, Object> writeN1 = new LinkedHashMap<>(scope("FULFILLMENT_NODE", "WRITE", "SELECTED", c.n1(), "ALLOW"));
        assertThat(fx.setPolicy(c.o(), logi, null, List.of(writeN1)).status()).isEqualTo(200);
        assertThat(logi.get("/api/v1/inventory/positions").data()).hasSize(2);
        assertThat(receive(logi, c.be(), c.sku(), c.n2(), 1).errorCode()).isEqualTo("WRITE_SCOPE_DENIED");
        assertThat(logi.post("/api/v1/inventory/reservations/" + n2Reservation + "/allocate", Map.of()).errorCode()).isEqualTo("WRITE_SCOPE_DENIED");
        assertThat(logi.post("/api/v1/inventory/transfers", Map.of("business_entity_id", c.be().toString(), "from_node_id", c.n2().toString(),
                "to_node_id", c.n1().toString(), "lines", List.of(Map.of("sku_id", c.sku().toString(), "quantity", 1)))).errorCode()).isEqualTo("WRITE_SCOPE_DENIED");

        // 새 거점 자동 포함: 설정한 사용자에게만
        Map<String, Object> auto = new LinkedHashMap<>(scope("FULFILLMENT_NODE", "READ", "SELECTED", c.n1(), "ALLOW"));
        auto.put("auto_include_new", true);
        assertThat(fx.setPolicy(c.o(), logi, null, List.of(auto)).status()).isEqualTo(200);
        UUID n3 = c.o().post("/api/v1/fulfillment-nodes", Map.of("code", "IC", "name", "인천", "logistics_provider_code", "SELF_WMS", "cutoff_time", "16:00")).id();
        List<String> visible = new ArrayList<>();
        logi.get("/api/v1/fulfillment-nodes").data().forEach(n -> visible.add(n.get("id").asString()));
        assertThat(visible).containsExactlyInAnyOrder(c.n1().toString(), n3.toString());
        // 본인 범위 밖 거점은 남에게 허용할 수 없다(권한 상승 방지)
        Api ops = fx.member(c.ws(), "OPS_ADMIN");
        assertThat(fx.setPolicy(c.o(), ops, List.of(Map.of("permission_code", "access.manage", "effect", "ALLOW")),
                List.of(new LinkedHashMap<>(scope("FULFILLMENT_NODE", "READ", "SELECTED", c.n1(), "ALLOW")))).status()).isEqualTo(200);
        Api other = fx.member(c.ws(), "WAREHOUSE_WORKER");
        assertThat(fx.setPolicy(ops, other, null, List.of(new LinkedHashMap<>(scope("FULFILLMENT_NODE", "READ", "SELECTED", c.n2(), "ALLOW")))).errorCode())
                .isEqualTo("PRIVILEGE_ESCALATION");
    }
}
