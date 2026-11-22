package com.commerceos.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M3 I5: 실사(블라인드·잠금·재실사 기준·승인자 분리, T40), 수동 조정 승인 기준(DLG-043), 거점 이동. */
class StockCountTest extends IntegrationTest {

    record Ctx(Fixtures.Ws ws, Api o, Api logi, Api worker1, Api worker2, Api approver, UUID be, UUID node, UUID amp, UUID box) {
    }

    Ctx ctx() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        UUID be = fx.businessEntity(o, "에이상사");
        UUID product = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "AMP", "name", "앰플")).data().get("product").get("id").asString());
        UUID amp = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "AMP-30", "sku_type", "NORMAL")).id();
        UUID box = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "BOX-S", "sku_type", "MATERIAL")).id();
        assertThat(o.post("/api/v1/business-entities/" + be + "/sku-costs/" + amp, Map.of("unit_cost", 9000, "reason", "기준 원가")).status()).isEqualTo(201);
        UUID node = o.post("/api/v1/fulfillment-nodes", Map.of("code", "HS", "name", "화성", "logistics_provider_code", "SELF_WMS", "cutoff_time", "16:00")).id();
        o.post("/api/v1/fulfillment-nodes/" + node + "/locations", Map.of("code", "A-01", "zone", "A"));
        o.post("/api/v1/fulfillment-nodes/" + node + "/locations", Map.of("code", "M-01", "zone", "M"));
        receive(o, be, amp, node, 400, "A-01");
        receive(o, be, box, node, 3800, "M-01");
        Api logi = fx.member(ws, "LOGISTICS_MANAGER");
        Api w1 = fx.member(ws, "WAREHOUSE_WORKER");
        Api w2 = fx.member(ws, "WAREHOUSE_WORKER");
        Api approver = fx.member(ws, "LOGISTICS_MANAGER");
        return new Ctx(ws, o, logi, w1, w2, approver, be, node, amp, box);
    }

    void receive(Api o, UUID be, UUID sku, UUID node, int qty, String loc) {
        Api.Res r = o.post("/api/v1/inventory/receipts", Map.of("business_entity_id", be.toString(), "sku_id", sku.toString(), "node_id", node.toString(),
                "quantity", qty, "location_code", loc));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    }

    Api.Res policy(Api api, UUID be, UUID node, String zone, Integer qty, Integer cost, String onExceed, String assignee) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("business_entity_id", be.toString());
        if (node != null) {
            b.put("node_id", node.toString());
        }
        if (zone != null) {
            b.put("zone", zone);
        }
        if (qty != null) {
            b.put("qty_threshold", qty);
        }
        if (cost != null) {
            b.put("cost_threshold", cost);
        }
        b.put("combine", "ANY");
        b.put("on_exceed", onExceed);
        b.put("recount_assignee", assignee);
        return api.post("/api/v1/inventory/count-policies", b);
    }

    JsonNode createCount(Ctx c, String scope, String zone, List<Api> counters, boolean blind) {
        Map<String, Object> b = new LinkedHashMap<>(Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(), "scope", scope, "blind", blind,
                "counter_member_ids", counters.stream().map(x -> x.memberId.toString()).toList()));
        if (zone != null) {
            b.put("zone", zone);
        }
        Api.Res r = c.logi().post("/api/v1/inventory/stock-counts", b);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data();
    }

    JsonNode line(JsonNode detail, String sku) {
        for (JsonNode l : detail.get("lines")) {
            if (l.get("sku_code").asString().equals(sku)) {
                return l;
            }
        }
        throw new AssertionError("no line " + sku);
    }

    Api.Res enter(Api api, JsonNode count, Map<String, Integer> qtyBySku) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (JsonNode l : count.get("lines")) {
            Integer q = qtyBySku.get(l.get("sku_code").asString());
            if (q != null) {
                entries.add(Map.of("line_id", l.get("id").asString(), "counted_qty", q));
            }
        }
        return api.post("/api/v1/inventory/stock-counts/" + count.get("count").get("id").asString() + "/entries", Map.of("entries", entries));
    }

    JsonNode ok(Api.Res r) {
        assertThat(r.status()).as(r.body().toString()).isBetween(200, 201);
        return r.data();
    }

    int sellable(Ctx c, UUID sku) {
        JsonNode a = c.o().get("/api/v1/inventory/availability?business_entity_id=" + c.be() + "&sku_id=" + sku).data();
        return a.get("sellable_on_hand").asInt();
    }

    int onHand(Ctx c, UUID sku) {
        int sum = 0;
        for (JsonNode s : c.o().get("/api/v1/inventory/stocks?sku_id=" + sku).data()) {
            sum += s.get("on_hand").asInt();
        }
        return sum;
    }

    @Test
    void policyRequired_versionFixedAtCreation_zoneOverrideWins() {
        Ctx c = ctx();
        Map<String, Object> noPolicy = Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(), "scope", "ALL",
                "counter_member_ids", List.of(c.worker1().memberId.toString()));
        assertThat(c.logi().post("/api/v1/inventory/stock-counts", noPolicy).errorCode()).isEqualTo("COUNT_POLICY_MISSING");
        // 조건이 하나도 없으면 저장 불가, 조정 권한 없으면 불가
        assertThat(policy(c.logi(), c.be(), null, null, null, null, "RECOUNT_REQUIRED", "DIFFERENT_COUNTER").errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(policy(c.worker1(), c.be(), null, null, 3, null, "RECOUNT_REQUIRED", "DIFFERENT_COUNTER").errorCode()).isEqualTo("PERMISSION_DENIED");
        JsonNode v1 = ok(policy(c.logi(), c.be(), c.node(), null, 3, 30000, "RECOUNT_REQUIRED", "DIFFERENT_COUNTER"));
        JsonNode zoneM = ok(policy(c.logi(), c.be(), c.node(), "M", 50, 100000, "RECOUNT_REQUIRED", "DIFFERENT_COUNTER"));
        assertThat(v1.get("version").asInt()).isEqualTo(1);
        JsonNode count = createCount(c, "ALL", null, List.of(c.worker1()), true);
        assertThat(count.get("count").get("policy_version").asInt()).isEqualTo(1);
        assertThat(line(count, "AMP-30").get("policy_id").asString()).isEqualTo(v1.get("id").asString());
        assertThat(line(count, "BOX-S").get("policy_id").asString()).isEqualTo(zoneM.get("id").asString()); // Zone 재정의 우선
        // 블라인드: 카운트 중에는 시스템 수량을 누구에게도 보여 주지 않는다
        assertThat(line(count, "AMP-30").get("system_qty").isNull()).isTrue();
        // 기준을 바꿔도 이미 만든 실사는 v1 유지
        JsonNode v2 = ok(policy(c.logi(), c.be(), c.node(), null, 100, null, "RECOUNT_RECOMMENDED", "SAME_ALLOWED"));
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        JsonNode again = c.logi().get("/api/v1/inventory/stock-counts/" + count.get("count").get("id").asString()).data();
        assertThat(line(again, "AMP-30").get("policy_version").asInt()).isEqualTo(1);
        assertThat(c.logi().get("/api/v1/inventory/count-policies?business_entity_id=" + c.be()).data()).hasSize(2);
        assertThatThrownBy(() -> jdbc.sql("update stock_count_policy set qty_threshold = 0 where workspace_id = :ws").param("ws", c.ws().id()).update())
                .rootCause().hasMessageContaining("append-only");
        // 겹치는 범위 실사 중복 생성 불가
        Map<String, Object> dup = Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(), "scope", "ZONE", "zone", "A",
                "counter_member_ids", List.of(c.worker1().memberId.toString()));
        assertThat(c.logi().post("/api/v1/inventory/stock-counts", dup).errorCode()).isEqualTo("STOCK_COUNT_OVERLAP");
    }

    @Test
    void T40_countLocksScope_submitDoesNotChangeStock_approverSeparation_recountRequired() {
        Ctx c = ctx();
        ok(policy(c.logi(), c.be(), null, null, 3, 30000, "RECOUNT_REQUIRED", "DIFFERENT_COUNTER"));
        String res = ok(c.o().post("/api/v1/inventory/reservations", Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(),
                "demand_type", "ORDER_LINE", "demand_ref", "ORD-1", "lines", List.of(Map.of("sku_id", c.amp().toString(), "quantity", 2))))).get(0).get("id").asString();
        JsonNode count = createCount(c, "ZONE", "A", List.of(c.worker1(), c.worker2()), true);
        String id = count.get("count").get("id").asString();
        assertThat(count.get("lines")).hasSize(1); // Zone A 의 AMP-30 만

        // 잠금(CONF-012): 범위 안 입고 적치·출고 배정·조정 불가, 범위 밖(Zone M)은 가능
        assertThat(c.o().post("/api/v1/inventory/receipts", Map.of("business_entity_id", c.be().toString(), "sku_id", c.amp().toString(),
                "node_id", c.node().toString(), "quantity", 1, "location_code", "A-01")).errorCode()).isEqualTo("STOCK_COUNT_LOCKED");
        assertThat(c.o().post("/api/v1/inventory/reservations/" + res + "/allocate", Map.of()).errorCode()).isEqualTo("STOCK_COUNT_LOCKED");
        assertThat(c.o().post("/api/v1/inventory/adjustments", Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(),
                "sku_id", c.amp().toString(), "location_code", "A-01", "mode", "INCREASE", "quantity", 1, "reason", "x")).errorCode()).isEqualTo("STOCK_COUNT_LOCKED");
        receive(c.o(), c.be(), c.box(), c.node(), 10, "M-01");

        // 카운트 담당자만 입력, 모든 행을 세야 제출 가능
        assertThat(enter(c.approver(), count, Map.of("AMP-30", 380)).errorCode()).isEqualTo("NOT_COUNT_ASSIGNEE");
        assertThat(c.worker1().post("/api/v1/inventory/stock-counts/" + id + "/submit", Map.of()).errorCode()).isEqualTo("COUNT_INCOMPLETE");
        ok(enter(c.worker1(), count, Map.of("AMP-30", 380)));
        JsonNode submitted = ok(c.worker1().post("/api/v1/inventory/stock-counts/" + id + "/submit", Map.of()));
        JsonNode l = line(submitted, "AMP-30");
        assertThat(l.get("system_qty").asInt()).isEqualTo(400);
        assertThat(l.get("diff").asInt()).isEqualTo(-20);
        assertThat(l.get("cost_impact").asString()).isEqualTo("-180000");
        assertThat(l.get("status").asString()).isEqualTo("RECOUNT_REQUIRED");
        // T40: 승인 전 실재고 불변, 잠금 해제
        assertThat(onHand(c, c.amp())).isEqualTo(400);
        assertThat(c.o().post("/api/v1/inventory/reservations/" + res + "/allocate", Map.of()).status()).isEqualTo(200);
        assertThat(c.o().get("/api/v1/work-items?item_type=STOCK_RECOUNT_REQUIRED&status=OPEN").data()).hasSize(1);

        // 기준 초과 행은 재실사 전 승인 불가 / 요청자·카운트 담당자는 승인 불가
        Map<String, Object> approve = Map.of("line_ids", List.of(l.get("id").asString()), "approve", true, "reason", "확인");
        assertThat(c.approver().post("/api/v1/inventory/stock-counts/" + id + "/decisions", approve).errorCode()).isEqualTo("RECOUNT_REQUIRED_BEFORE_APPROVAL");
        assertThat(c.logi().post("/api/v1/inventory/stock-counts/" + id + "/decisions", approve).errorCode()).isEqualTo("COUNT_APPROVER_CONFLICT");

        // 재실사: 첫 카운트 담당자는 다시 셀 수 없음(DIFFERENT_COUNTER)
        JsonNode recount = ok(c.logi().post("/api/v1/inventory/stock-counts/" + id + "/recount", Map.of("line_ids", List.of(l.get("id").asString()))));
        assertThat(recount.get("count").get("status").asString()).isEqualTo("COUNTING");
        assertThat(recount.get("count").get("round").asInt()).isEqualTo(2);
        assertThat(enter(c.worker1(), recount, Map.of("AMP-30", 382)).errorCode()).isEqualTo("RECOUNT_SAME_COUNTER");
        ok(enter(c.worker2(), recount, Map.of("AMP-30", 382)));
        JsonNode s2 = ok(c.worker2().post("/api/v1/inventory/stock-counts/" + id + "/submit", Map.of()));
        JsonNode l2 = line(s2, "AMP-30");
        assertThat(l2.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(l2.get("diff").asInt()).isEqualTo(-18);
        assertThat(c.worker2().post("/api/v1/inventory/stock-counts/" + id + "/decisions", approve).errorCode()).isIn("PERMISSION_DENIED", "COUNT_APPROVER_CONFLICT");
        assertThat(c.o().get("/api/v1/work-items?item_type=STOCK_RECOUNT_REQUIRED&status=OPEN").data()).isEmpty();

        // 다른 승인자 승인 → 조정 원장, 확보 2 는 유지
        JsonNode done = ok(c.approver().post("/api/v1/inventory/stock-counts/" + id + "/decisions", approve));
        assertThat(done.get("count").get("status").asString()).isEqualTo("COMPLETED");
        assertThat(onHand(c, c.amp())).isEqualTo(382);
        assertThat(sellable(c, c.amp())).isEqualTo(382);
        JsonNode txns = c.o().get("/api/v1/inventory/transactions?txn_type=ADJUSTMENT").data();
        assertThat(txns.get(0).get("on_hand_delta").asInt()).isEqualTo(-18);
        assertThat(txns.get(0).get("reference_type").asString()).isEqualTo("STOCK_COUNT");
        Api.Res v = c.o().get("/api/v1/inventory/verification");
        assertThat(v.data()).as(v.body().toString()).isEmpty();
    }

    @Test
    void recommendedRecount_needsReason_staleBucketRejected_rejectKeepsStock_visibleModeNeedsReason() {
        Ctx c = ctx();
        ok(policy(c.logi(), c.be(), null, null, 3, null, "RECOUNT_RECOMMENDED", "SAME_ALLOWED"));
        JsonNode count = createCount(c, "ALL", null, List.of(c.worker1()), false);
        String id = count.get("count").get("id").asString();
        assertThat(line(count, "AMP-30").get("system_qty").asInt()).isEqualTo(400); // 수량 표시 방식
        ok(enter(c.worker1(), count, Map.of("AMP-30", 390, "BOX-S", 3800)));
        JsonNode s = ok(c.worker1().post("/api/v1/inventory/stock-counts/" + id + "/submit", Map.of()));
        JsonNode amp = line(s, "AMP-30");
        assertThat(amp.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(amp.get("recount_recommended").asBoolean()).isTrue();
        assertThat(line(s, "BOX-S").get("status").asString()).isEqualTo("MATCHED");
        Map<String, Object> noReason = Map.of("line_ids", List.of(amp.get("id").asString()), "approve", true);
        assertThat(c.approver().post("/api/v1/inventory/stock-counts/" + id + "/decisions", noReason).errorCode()).isEqualTo("VALIDATION_FAILED");
        // 제출 후 재고가 바뀌면(잠금 해제됨) 승인하지 않는다
        receive(c.o(), c.be(), c.amp(), c.node(), 5, "A-01");
        Map<String, Object> withReason = Map.of("line_ids", List.of(amp.get("id").asString()), "approve", true, "reason", "현장 재확인 완료");
        assertThat(c.approver().post("/api/v1/inventory/stock-counts/" + id + "/decisions", withReason).errorCode()).isEqualTo("STOCK_COUNT_STALE");
        assertThat(onHand(c, c.amp())).isEqualTo(405);
        // 반려: 재고 불변, 실사 완료
        JsonNode rejected = ok(c.approver().post("/api/v1/inventory/stock-counts/" + id + "/decisions",
                Map.of("line_ids", List.of(amp.get("id").asString()), "approve", false, "reason", "재고 변동")));
        assertThat(line(rejected, "AMP-30").get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("count").get("status").asString()).isEqualTo("COMPLETED");
        assertThat(onHand(c, c.amp())).isEqualTo(405);
        // DB 최종 방어: 카운트한 사람이 결정자로 기록될 수 없다
        assertThatThrownBy(() -> jdbc.sql("update stock_count_line set decided_by = counted_by where workspace_id = :ws and counted_by is not null")
                .param("ws", c.ws().id()).update()).hasMessageContaining("stock_count_line");
    }

    @Test
    void foundStock_andCountCancel_andExternalNodeRejected() {
        Ctx c = ctx();
        ok(policy(c.logi(), c.be(), null, null, 1000, null, "RECOUNT_REQUIRED", "SAME_ALLOWED"));
        JsonNode count = createCount(c, "ZONE", "M", List.of(c.worker1()), true);
        String id = count.get("count").get("id").asString();
        // 실사 중 발견한 재고(시스템 0) 추가
        JsonNode withFound = ok(c.worker1().post("/api/v1/inventory/stock-counts/" + id + "/entries", Map.of("entries", List.of(Map.of("line_id",
                line(count, "BOX-S").get("id").asString(), "counted_qty", 3800)), "found_lines", List.of(Map.of("sku_id", c.amp().toString(),
                "location_code", "M-01", "counted_qty", 4)))));
        assertThat(withFound.get("lines")).hasSize(2);
        assertThat(c.worker1().post("/api/v1/inventory/stock-counts/" + id + "/entries", Map.of("found_lines", List.of(Map.of("sku_id", c.amp().toString(),
                "location_code", "A-01", "counted_qty", 1)))).errorCode()).isEqualTo("VALIDATION_FAILED"); // 범위 밖
        JsonNode s = ok(c.worker1().post("/api/v1/inventory/stock-counts/" + id + "/submit", Map.of()));
        JsonNode found = line(s, "AMP-30");
        assertThat(found.get("found_in_count").asBoolean()).isTrue();
        assertThat(found.get("diff").asInt()).isEqualTo(4);
        assertThat(found.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        ok(c.approver().post("/api/v1/inventory/stock-counts/" + id + "/decisions", Map.of("line_ids", List.of(found.get("id").asString()), "approve", true)));
        assertThat(onHand(c, c.amp())).isEqualTo(404);

        // 취소: 요청자 또는 조정 권한자만, 취소하면 잠금 해제
        JsonNode second = createCount(c, "ZONE", "A", List.of(c.worker1()), true);
        String sid = second.get("count").get("id").asString();
        assertThat(c.worker1().post("/api/v1/inventory/stock-counts/" + sid + "/cancel", Map.of("reason", "x")).errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(ok(c.logi().post("/api/v1/inventory/stock-counts/" + sid + "/cancel", Map.of("reason", "일정 변경"))).get("count").get("status").asString())
                .isEqualTo("CANCELLED");
        receive(c.o(), c.be(), c.amp(), c.node(), 1, "A-01");
        // SKU 범위(순환 실사): 그 SKU 의 모든 로케이션 버킷
        Map<String, Object> skuScope = Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(), "scope", "SKU",
                "sku_ids", List.of(c.amp().toString()), "counter_member_ids", List.of(c.worker1().memberId.toString()));
        JsonNode third = ok(c.logi().post("/api/v1/inventory/stock-counts", skuScope));
        assertThat(third.get("lines")).hasSize(2); // A-01, M-01
        // 물류사 재고 기준 거점은 내부 실사 대상이 아니다
        UUID ext = c.o().post("/api/v1/fulfillment-nodes", Map.of("code", "FS", "name", "파스토", "logistics_provider_code", "FASSTO", "cutoff_time", "15:00")).id();
        UUID shipper = fx.shipperAccount(c.o(), c.be(), "FASSTO", "S-1");
        c.o().post("/api/v1/fulfillment-nodes/" + ext + "/shippers", Map.of("shipper_account_id", shipper.toString()));
        assertThat(c.logi().post("/api/v1/inventory/stock-counts", Map.of("business_entity_id", c.be().toString(), "node_id", ext.toString(), "scope", "ALL",
                "counter_member_ids", List.of(c.worker1().memberId.toString()))).errorCode()).isEqualTo("NODE_INVENTORY_EXTERNAL");
    }

    @Test
    void manualAdjustment_belowThresholdApplies_aboveOrUnknownCostNeedsApproval_approverRules() {
        Ctx c = ctx();
        Map<String, Object> small = Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(), "sku_id", c.amp().toString(),
                "location_code", "A-01", "mode", "DECREASE", "quantity", 2, "reason", "파손 폐기 확인");
        assertThat(c.worker1().post("/api/v1/inventory/adjustments", small).errorCode()).isEqualTo("PERMISSION_DENIED");
        JsonNode applied = ok(c.logi().post("/api/v1/inventory/adjustments", small));
        assertThat(applied.get("status").asString()).isEqualTo("APPLIED");
        assertThat(applied.get("cost_impact").asString()).isEqualTo("18000");
        assertThat(onHand(c, c.amp())).isEqualTo(398);
        assertThat(c.logi().post("/api/v1/inventory/adjustments", Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(),
                "sku_id", c.amp().toString(), "location_code", "A-01", "mode", "DECREASE", "quantity", 1)).errorCode()).isEqualTo("VALIDATION_FAILED"); // 사유 필수

        // 원가 영향 9,000 × 20 = 180,000 ≥ 기준 100,000 → 승인 대기, 재고 불변
        JsonNode pending = ok(c.logi().post("/api/v1/inventory/adjustments", Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(),
                "sku_id", c.amp().toString(), "location_code", "A-01", "mode", "ABSOLUTE", "quantity", 378, "reason", "분실 확인")));
        assertThat(pending.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.get("delta").asInt()).isEqualTo(-20);
        assertThat(onHand(c, c.amp())).isEqualTo(398);
        String approvalId = pending.get("approval_id").asString();
        assertThat(c.logi().post("/api/v1/approvals/" + approvalId + "/decision", Map.of("decision", "APPROVE")).errorCode()).isIn("PERMISSION_DENIED", "SELF_APPROVAL_FORBIDDEN");
        // 승인 권한은 있지만 재고 조정 권한이 없는 운영관리자 → 반영 불가
        Api ops = fx.member(c.ws(), "OPS_ADMIN");
        assertThat(ops.post("/api/v1/approvals/" + approvalId + "/decision", Map.of("decision", "APPROVE")).errorCode()).isEqualTo("APPROVER_NEEDS_INVENTORY_ADJUST");
        assertThat(onHand(c, c.amp())).isEqualTo(398);
        assertThat(ok(c.o().post("/api/v1/approvals/" + approvalId + "/decision", Map.of("decision", "APPROVE", "note", "확인"))).get("status").asString())
                .isEqualTo("APPROVED");
        assertThat(onHand(c, c.amp())).isEqualTo(378);

        // 원가 미등록(BOX-S)은 영향을 판단할 수 없어 소량이라도 승인 대상
        JsonNode unknown = ok(c.logi().post("/api/v1/inventory/adjustments", Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(),
                "sku_id", c.box().toString(), "location_code", "M-01", "mode", "INCREASE", "quantity", 1, "reason", "발견")));
        assertThat(unknown.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        // 요청 이후 버킷이 바뀌면 승인하지 않고 요청 취소
        receive(c.o(), c.be(), c.box(), c.node(), 1, "M-01");
        assertThat(c.o().post("/api/v1/approvals/" + unknown.get("approval_id").asString() + "/decision", Map.of("decision", "APPROVE")).errorCode())
                .isEqualTo("APPROVAL_STALE");

        // 승인 기준 변경은 승인 권한자만(조정 담당자 불가), 확보된 수량은 줄일 수 없음
        assertThat(c.logi().put("/api/v1/inventory/adjustment-policies/" + c.be(), Map.of("approval_threshold", 1000000), null).errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(ok(ops.put("/api/v1/inventory/adjustment-policies/" + c.be(), Map.of("approval_threshold", 1000000), null)).get("approval_threshold").asString())
                .isEqualTo("1000000");
        c.o().post("/api/v1/inventory/reservations", Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(), "demand_type", "ORDER_LINE",
                "demand_ref", "R-1", "lines", List.of(Map.of("sku_id", c.amp().toString(), "quantity", 370))));
        assertThat(c.logi().post("/api/v1/inventory/adjustments", Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(),
                "sku_id", c.amp().toString(), "location_code", "A-01", "mode", "DECREASE", "quantity", 10, "reason", "x")).errorCode()).isEqualTo("STOCK_COMMITTED");
        Api.Res v = c.o().get("/api/v1/inventory/verification");
        assertThat(v.data()).as(v.body().toString()).isEmpty();
    }

    @Test
    void transfer_sameOwnerOnly_shipsUnreservedStock_receivesWithDiscrepancyWorkItem() {
        Ctx c = ctx();
        UUID node2 = c.o().post("/api/v1/fulfillment-nodes", Map.of("code", "GP", "name", "김포", "logistics_provider_code", "SELF_WMS", "cutoff_time", "15:00")).id();
        c.o().post("/api/v1/inventory/reservations", Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(), "demand_type", "ORDER_LINE",
                "demand_ref", "R-1", "lines", List.of(Map.of("sku_id", c.amp().toString(), "quantity", 350))));
        Map<String, Object> tooMuch = Map.of("business_entity_id", c.be().toString(), "from_node_id", c.node().toString(), "to_node_id", node2.toString(),
                "lines", List.of(Map.of("sku_id", c.amp().toString(), "quantity", 60)));
        Api.Res short1 = c.logi().post("/api/v1/inventory/transfers", tooMuch);
        assertThat(short1.errorCode()).isEqualTo("INSUFFICIENT_AVAILABLE_STOCK"); // 확보 350 → 이동 가능 50
        JsonNode t = ok(c.logi().post("/api/v1/inventory/transfers", Map.of("business_entity_id", c.be().toString(), "from_node_id", c.node().toString(),
                "to_node_id", node2.toString(), "lines", List.of(Map.of("sku_id", c.amp().toString(), "quantity", 40)), "note", "김포 보충")));
        assertThat(t.get("status").asString()).isEqualTo("IN_TRANSIT");
        assertThat(t.get("in_transit_quantity").asInt()).isEqualTo(40);
        assertThat(onHand(c, c.amp())).isEqualTo(360);
        // 받은 수량 37, 부족 3 → 차이 업무
        JsonNode r = ok(c.worker1().post("/api/v1/inventory/transfers/" + t.get("id").asString() + "/receive",
                Map.of("lines", List.of(Map.of("line_no", 1, "received_qty", 37)))));
        assertThat(r.get("status").asString()).isEqualTo("RECEIVED_WITH_DISCREPANCY");
        assertThat(onHand(c, c.amp())).isEqualTo(397);
        assertThat(c.o().get("/api/v1/work-items?item_type=INVENTORY_TRANSFER_DISCREPANCY&status=OPEN").data()).hasSize(1);
        assertThat(c.worker1().post("/api/v1/inventory/transfers/" + t.get("id").asString() + "/receive",
                Map.of("lines", List.of(Map.of("line_no", 1, "received_qty", 37)))).errorCode()).isEqualTo("TRANSFER_NOT_IN_TRANSIT");
        JsonNode types = c.o().get("/api/v1/inventory/transactions?sku_id=" + c.amp()).data();
        List<String> tt = new ArrayList<>();
        types.forEach(x -> tt.add(x.get("txn_type").asString()));
        assertThat(tt).contains("TRANSFER_OUT", "TRANSFER_IN");
        Api.Res v = c.o().get("/api/v1/inventory/verification");
        assertThat(v.data()).as(v.body().toString()).isEmpty();
    }
}
