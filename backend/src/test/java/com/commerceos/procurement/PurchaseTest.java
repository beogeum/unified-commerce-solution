package com.commerceos.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** P1 발주·매입: 공급처·공급처 SKU, MOQ, 승인, 부분 입고(실매입 단가·불량 반송), 입고예정, 잔량 종료, 부대비용 연결, 권한·동시성. */
class PurchaseTest extends PurchaseTestBase {

    @Test
    void purchaseFlow_moq_approval_partialReceiptActualCostAndRejects_inbound_closeShort_landedCostByPo() {
        Ctx c = ctx("발주상사");
        String sup = supplier(c, "에이원 화장품");
        assertThat(c.o().post("/api/v1/suppliers", Map.of("business_entity_id", c.be().toString(), "name", "에이원 화장품")).errorCode()).isEqualTo("SUPPLIER_DUPLICATE");
        // MOQ·발주단위, 미등록 공급 SKU
        Map<String, Object> small = Map.of("supplier_id", sup, "node_id", c.hs().toString(), "lines", List.of(Map.of("sku_id", c.amp().toString(), "quantity", 7)));
        Api.Res moq = c.o().post("/api/v1/purchase-orders", small);
        assertThat(moq.errorCode()).isEqualTo("MOQ_NOT_MET");
        assertThat(c.o().post("/api/v1/purchase-orders", Map.of("supplier_id", sup, "node_id", c.hs().toString(), "lines",
                List.of(Map.of("sku_id", c.amp().toString(), "quantity", 7, "moq_override_reason", "샘플 소량 발주")))).status()).isEqualTo(201);
        assertThat(c.o().post("/api/v1/purchase-orders", Map.of("supplier_id", sup, "node_id", c.hs().toString(), "lines",
                List.of(Map.of("sku_id", c.amp50().toString(), "quantity", 10)))).errorCode()).isEqualTo("SUPPLIER_SKU_MISSING");

        JsonNode po = po(c, sup, 20);
        assertThat(po.get("status").asString()).isEqualTo("DRAFT");
        assertThat(po.get("supply_total").asInt()).isEqualTo(160000);
        assertThat(po.get("vat_total").asInt()).isEqualTo(16000);
        assertThat(po.get("po_no").asString()).startsWith("PO-");
        assertThat(c.o().patch("/api/v1/purchase-orders/" + po.get("id").asString(), Map.of("memo", "x"), 99).errorCode()).isEqualTo("VERSION_CONFLICT");
        assertThat(inbound(c)).isZero(); // 작성 중은 입고예정 아님

        // 승인: 본인 불가, 발주 권한 없는 승인자 불가(MD 는 승인 권한 자체가 없음), 운영관리자 승인
        JsonNode s = c.o().post("/api/v1/purchase-orders/" + po.get("id").asString() + "/submit", Map.of()).data();
        assertThat(c.o().post("/api/v1/approvals/" + s.get("approval_id").asString() + "/decision", Map.of("decision", "APPROVE")).errorCode())
                .isEqualTo("SELF_APPROVAL_FORBIDDEN");
        Api ops = fx.member(c.ws(), "OPS_ADMIN");
        assertThat(ops.post("/api/v1/approvals/" + s.get("approval_id").asString() + "/decision", Map.of("decision", "APPROVE")).status()).isEqualTo(200);
        String id = po.get("id").asString();
        assertThat(c.o().get("/api/v1/purchase-orders/" + id).data().get("status").asString()).isEqualTo("PLACED");
        assertThat(inbound(c)).isEqualTo(20);
        assertThat(onHand(c)).isEqualTo(10); // 입고예정은 재고가 아님
        assertThat(c.o().post("/api/v1/purchase-orders/" + id + "/supplier-confirm", Map.of("expected_on", "2026-12-05", "supplier_ref", "SO-889")).data()
                .get("status").asString()).isEqualTo("SUPPLIER_CONFIRMED");

        // 부분 입고: 정상 12개 실매입 8,500(발주 8,000), 불량 2개 반송
        String line = c.o().get("/api/v1/purchase-orders/" + id).data().get("lines").get(0).get("id").asString();
        assertThat(c.o().post("/api/v1/purchase-orders/" + id + "/receipts", Map.of("lines", List.of(Map.of("po_line_id", line, "good_qty", 12, "rejected_qty", 2))))
                .errorCode()).isEqualTo("VALIDATION_FAILED"); // 불량 사유 필수
        Api.Res rc = c.o().post("/api/v1/purchase-orders/" + id + "/receipts", Map.of("note", "1차 입고", "lines", List.of(Map.of("po_line_id", line, "good_qty", 12,
                "rejected_qty", 2, "unit_cost", 8500, "reject_reason", "용기 파손"))));
        assertThat(rc.status()).as(rc.body().toString()).isEqualTo(201);
        JsonNode v = rc.data();
        assertThat(v.get("status").asString()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(v.get("received_qty").asInt()).isEqualTo(12);
        assertThat(v.get("rejected_qty").asInt()).isEqualTo(2);
        assertThat(v.get("price_variance").asInt()).isEqualTo(6000); // (8,500 − 8,000) × 12
        assertThat(v.get("receipts").get(0).get("receipt_no").asString()).startsWith("GR-");
        assertThat(onHand(c)).isEqualTo(22);
        assertThat(jdbc.sql("select avg_unit_cost from sku_moving_cost where business_entity_id = :b and sku_id = :s").param("b", c.be()).param("s", c.amp())
                .query(BigDecimal.class).single()).isEqualByComparingTo("8727.2727"); // (9,000 × 10 + 8,500 × 12) / 22
        assertThat(c.o().get("/api/v1/work-items?item_type=PURCHASE_RETURN_TO_SUPPLIER&status=OPEN").data()).hasSize(1);
        assertThat(inbound(c)).isEqualTo(6);
        JsonNode invRow = null;
        for (JsonNode r : c.o().get("/api/v1/analytics/inventory").data().get("rows")) {
            if (r.get("sku_id").asString().equals(c.amp().toString())) {
                invRow = r;
            }
        }
        assertThat(invRow.get("inbound_qty").asInt()).isEqualTo(6);
        // 잔량 초과 입고 거부, 입고 시작 후 취소 불가 → 잔량 종료
        assertThat(c.o().post("/api/v1/purchase-orders/" + id + "/receipts", Map.of("lines", List.of(Map.of("po_line_id", line, "good_qty", 7)))).errorCode())
                .isEqualTo("PO_OVER_RECEIPT");
        assertThat(c.o().post("/api/v1/purchase-orders/" + id + "/cancel", Map.of("reason", "x")).errorCode()).isEqualTo("PO_INVALID_STATE");
        assertThat(c.o().post("/api/v1/purchase-orders/" + id + "/close", Map.of()).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(c.o().post("/api/v1/purchase-orders/" + id + "/close", Map.of("reason", "공급처 단종으로 잔량 미입고")).data().get("status").asString()).isEqualTo("CLOSED");
        assertThat(inbound(c)).isZero();

        // 입고 부대비용을 발주 단위로(입고분 재고 원장 행에 배부)
        Api.Res lc = c.o().post("/api/v1/finance/landed-costs", Map.of("business_entity_id", c.be().toString(), "cost_type", "FREIGHT", "supply_amount", 1200,
                "source_ref", "FRT-" + id, "purchase_order_id", id));
        assertThat(lc.status()).as(lc.body().toString()).isEqualTo(201);
        assertThat(lc.data().get("capitalized").asInt()).isEqualTo(1200);
        assertThat(lc.data().get("lines").get(0).get("quantity").asInt()).isEqualTo(12);

        // 승인 후 입고 전 취소는 사유 필요
        JsonNode po2 = po(c, sup, 10);
        String id2 = place(c, po2, ops);
        assertThat(c.o().post("/api/v1/purchase-orders/" + id2 + "/cancel", Map.of()).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(c.o().post("/api/v1/purchase-orders/" + id2 + "/cancel", Map.of("reason", "판매 계획 변경")).data().get("status").asString()).isEqualTo("CANCELLED");
        // 승인 대기 중 취소는 승인 요청도 취소
        JsonNode po3 = po(c, sup, 10);
        String ap = c.o().post("/api/v1/purchase-orders/" + po3.get("id").asString() + "/submit", Map.of()).data().get("approval_id").asString();
        c.o().post("/api/v1/purchase-orders/" + po3.get("id").asString() + "/cancel", Map.of("reason", "중복 작성"));
        assertThat(ops.post("/api/v1/approvals/" + ap + "/decision", Map.of("decision", "APPROVE")).errorCode()).isEqualTo("APPROVAL_NOT_PENDING");

        // 권한: 발주 권한 없는 사용자 작성 불가, 원가 권한 없는 창고 작업자는 단가 숨김, 다른 운영그룹은 존재도 모름
        Api cs = fx.member(c.ws(), "CS_AGENT");
        assertThat(cs.post("/api/v1/purchase-orders", Map.of("supplier_id", sup, "node_id", c.hs().toString(), "lines",
                List.of(Map.of("sku_id", c.amp().toString(), "quantity", 10)))).status()).isEqualTo(403);
        Api wh = fx.member(c.ws(), "WAREHOUSE_WORKER");
        JsonNode masked = wh.get("/api/v1/purchase-orders/" + id).data();
        assertThat(masked.get("cost_visible").asBoolean()).isFalse();
        assertThat(masked.get("lines").get(0).get("unit_cost").isNull()).isTrue();
        assertThat(fx.workspace().owner().get("/api/v1/purchase-orders/" + id).status()).isEqualTo(404);
    }

    @Test
    void concurrentReceipts_neverExceedOrderedQuantity() throws Exception {
        Ctx c = ctx("동시입고");
        String sup = supplier(c, "동시 공급처");
        Api ops = fx.member(c.ws(), "OPS_ADMIN");
        String id = place(c, po(c, sup, 10), ops);
        String line = c.o().get("/api/v1/purchase-orders/" + id).data().get("lines").get(0).get("id").asString();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> c.o().post("/api/v1/purchase-orders/" + id + "/receipts", Map.of("lines", List.of(Map.of("po_line_id", line, "good_qty", 6)))).status());
        }
        List<Integer> st = new ArrayList<>();
        for (java.util.concurrent.Future<Integer> f : pool.invokeAll(tasks)) {
            st.add(f.get());
        }
        pool.shutdown();
        assertThat(st).filteredOn(x -> x == 201).hasSize(1);
        assertThat(onHand(c)).isEqualTo(16);
        assertThat(c.o().get("/api/v1/purchase-orders/" + id).data().get("received_qty").asInt()).isEqualTo(6);
    }
}
