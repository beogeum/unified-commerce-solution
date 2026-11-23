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

/** M5 관리 손익 원장: 출고 매출·원가·예상 비용, 반품·교환·폐기, 예상→실제 대체(T26), 할인 부담 주체(T27), 계산 사례 A(T28), 합산 이익률(T36). */
class FinanceTest extends FinanceTestBase {
    @Test
    void T28_caseA_initialAndAfterNormalReturn_contributionMatchesSpec_postingIdempotent() {
        Ctx c = ctx("에이상사");
        rule(c, "SELLING_FEE", "PER_UNIT", 1500, null, false);
        rule(c, "OUTBOUND_SHIPPING", "PER_SHIPMENT", 3200, null, true);
        rule(c, "RETURN_SHIPPING", "PER_RETURN", 3000, null, false);
        String o = order(c, "A-1", new Object[]{"P-100", 2, 33000, 6600, 0, false}, new Object[]{"P-GIFT", 1, 0, 0, 0, true});
        String fo = shipOrder(c, o);
        JsonNode t = total(c.o(), c.be());
        assertThat(t.get("net_sales").asInt()).isEqualTo(54000);
        assertThat(cost(t, "PRODUCT_COGS")).isEqualTo(18000);
        assertThat(cost(t, "SELLING_FEE")).isEqualTo(3000);
        assertThat(cost(t, "OUTBOUND_SHIPPING")).isEqualTo(3200);
        assertThat(cost(t, "GWP")).isEqualTo(1200); // CHG-005: 출고 시 판촉비
        assertThat(t.get("contribution").asInt()).isEqualTo(28600);
        assertThat(t.get("margin_pct").asString()).isEqualTo("52.96");
        // 같은 출고 사건 재처리 → 원장 중복 없음
        int before = jdbc.sql("select count(*) from financial_entry where workspace_id = :ws").param("ws", c.ws().id()).query(Integer.class).single();
        new TransactionTemplate(tm).executeWithoutResult(s -> posting.onShipment(c.ws().id(), UUID.fromString(fo)));
        assertThat(jdbc.sql("select count(*) from financial_entry where workspace_id = :ws").param("ws", c.ws().id()).query(Integer.class).single()).isEqualTo(before);

        // 1개 정상 반품(T15): 순매출·원가 절반, 수수료 환급 1,500, 회수비 3,000, 사은품 원가 유지
        String claim = returnAndInspect(c, o, "RETURN", "SELLABLE", Map.of());
        t = total(c.o(), c.be());
        assertThat(t.get("net_sales").asInt()).isEqualTo(27000);
        assertThat(cost(t, "PRODUCT_COGS")).isEqualTo(9000);
        assertThat(cost(t, "SELLING_FEE")).isEqualTo(1500);
        assertThat(cost(t, "RETURN_SHIPPING")).isEqualTo(3000);
        assertThat(cost(t, "GWP")).isEqualTo(1200);
        assertThat(t.get("contribution").asInt()).isEqualTo(9100);
        assertThat(t.get("margin_pct").asString()).isEqualTo("33.70");
        // 환불액 29,700(현금)은 손익에 따로 잡지 않는다(반품 매출 차감으로 한 번만)
        JsonNode cl = c.o().get("/api/v1/claims/" + claim).data();
        assertThat(cl.get("expected_refund_amount").asInt()).isEqualTo(29700);
        String rid = c.o().postWithKey("/api/v1/claims/" + claim + "/refund-requests", Map.of("amount", 29700), "rf-a").data().get("refunds").get(0).get("id").asString();
        c.o().post("/api/v1/refunds/" + rid + "/confirm", Map.of("confirmed_amount", 29700));
        drainAsync();
        assertThat(total(c.o(), c.be()).get("contribution").asInt()).isEqualTo(9100);
        // 드릴다운: 반품 매출 차감 원장
        JsonNode entries = c.o().get("/api/v1/finance/entries?order_id=" + o + "&category=SALES_RETURN").data();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("supply_amount").asInt()).isEqualTo(-30000);
        assertThat(entries.get(0).get("vat_amount").asInt()).isEqualTo(-3000);
    }

    @Test
    void T26_estimatedShippingReplacedByActual_onlyActualCounted_varianceReviewed_T27_discountByBearer() {
        Ctx c = ctx("비상사");
        rule(c, "OUTBOUND_SHIPPING", "PER_SHIPMENT", 3000, null, true);
        String o = order(c, "B-1", new Object[]{"P-100", 1, 10000, 1000, 2000, false});
        String fo = shipOrder(c, o);
        assertThat(cost(total(c.o(), c.be()), "OUTBOUND_SHIPPING")).isEqualTo(3000);
        Map<String, Object> actual = Map.of("business_entity_id", c.be().toString(), "category", "OUTBOUND_SHIPPING", "basis", "ACTUAL", "supply_amount", 3200,
                "scope", Map.of("fulfillment_order_id", fo), "source_ref", "CJ 청구서 2026-11");
        Api.Res r = c.o().postWithKey("/api/v1/finance/cost-entries", actual, "inv-1");
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        assertThat(r.data().get("variance").asInt()).isEqualTo(200);
        assertThat(r.data().get("review_required").asBoolean()).isTrue();
        assertThat(c.o().postWithKey("/api/v1/finance/cost-entries", actual, "inv-1").status()).isEqualTo(201); // 같은 청구 재등록 → 중복 없음
        JsonNode t = total(c.o(), c.be());
        assertThat(cost(t, "OUTBOUND_SHIPPING")).isEqualTo(3200);
        assertThat(t.get("estimated_cost").asInt()).isZero();
        assertThat(c.o().get("/api/v1/work-items?item_type=COST_VARIANCE_REVIEW&status=OPEN").data()).hasSize(1);
        // T27: 판매자 할인만 매출 감액, 채널 부담 할인은 메모(정산 보전 대상)로 따로
        assertThat(t.get("net_sales").asInt()).isEqualTo(8181); // (10,000 − 1,000) 부가세 제외: 9,091 − 910
        JsonNode memo = c.o().get("/api/v1/finance/entries?order_id=" + o + "&category=CHANNEL_DISCOUNT").data();
        assertThat(memo).hasSize(1);
        assertThat(memo.get(0).get("entry_class").asString()).isEqualTo("MEMO");
        assertThat(memo.get(0).get("supply_amount").asInt() + memo.get(0).get("vat_amount").asInt()).isEqualTo(2000);
        // 원가 미등록 SKU 출고 → 0원 예상 원가 + 업무함
        UUID nocost = sku(c.o(), UUID.fromString(c.o().post("/api/v1/products", Map.of("product_code", "NC", "name", "원가없음")).data().get("product").get("id").asString()),
                "NC-1", "NORMAL");
        c.o().post("/api/v1/inventory/receipts", Map.of("business_entity_id", c.be().toString(), "sku_id", nocost.toString(), "node_id", c.hs().toString(), "quantity", 3));
        UUID unit = UUID.fromString(c.o().get("/api/v1/channel-accounts/" + c.acc() + "/sales-units").data().get(0).get("id").asString());
        map(c.o(), unit, "P-NC", nocost);
        shipOrder(c, order(c, "B-2", new Object[]{"P-NC", 1, 5000, 0, 0, false}));
        assertThat(total(c.o(), c.be()).get("missing_cost_lines").asInt()).isEqualTo(1);
        assertThat(c.o().get("/api/v1/work-items?item_type=FINANCE_COST_MISSING&status=OPEN").data()).hasSize(1);
    }

    @Test
    void T17_defectiveReturnZeroBook_thenDisposalNoSecondLoss_impairmentThenDisposalNoDouble_movingAverage() {
        Ctx c = ctx("씨상사");
        String o = order(c, "C-1", new Object[]{"P-100", 1, 30000, 0, 0, false});
        shipOrder(c, o);
        returnAndInspect(c, o, "RETURN", "DEFECTIVE", Map.of("reason_code", "DEFECT"));
        JsonNode t = total(c.o(), c.be());
        assertThat(cost(t, "PRODUCT_COGS")).isEqualTo(9000); // 불량 회수: 원가 환입 없음(매출원가에 남음)
        String defective = stockId(c, "DEFECTIVE");
        // 폐기 승인 → 장부가 0 이라 폐기손실 0
        Api wh = fx.member(c.ws(), "WAREHOUSE_WORKER");
        String ap = wh.post("/api/v1/inventory/disposals", Map.of("stock_id", defective, "quantity", 1, "reason", "불량 폐기")).data().get("approval_id").asString();
        c.o().post("/api/v1/approvals/" + ap + "/decision", Map.of("decision", "APPROVE"));
        drainAsync();
        assertThat(cost(total(c.o(), c.be()), "INVENTORY_IMPAIRMENT")).isZero();
        // 창고에서 발견한 파손 2개: 평가손실 18,000 → 그중 2개 폐기는 추가 손실 0, 평가 안 한 1개 폐기는 9,000
        String sellable = stockId(c, "SELLABLE");
        Api.Res qc = c.o().postWithKey("/api/v1/inventory/quality-changes", Map.of("stock_id", sellable, "quantity", 3, "to_quality_status", "DAMAGED", "reason", "낙하 파손"),
                "qc-000001");
        assertThat(qc.status()).as(qc.body().toString()).isIn(200, 201);
        String damaged = stockId(c, "DAMAGED");
        Api fin = fx.member(c.ws(), "FINANCE");
        Api.Res imp = fin.post("/api/v1/finance/impairments", Map.of("stock_id", damaged, "quantity", 2, "reason", "파손 — 판매 불가"));
        assertThat(imp.status()).as(imp.body().toString()).isEqualTo(201);
        assertThat(imp.data().get("loss").asInt()).isEqualTo(18000);
        assertThat(fin.post("/api/v1/finance/impairments", Map.of("stock_id", damaged, "quantity", 2, "reason", "중복")).errorCode()).isEqualTo("ALREADY_IMPAIRED");
        String ap2 = wh.post("/api/v1/inventory/disposals", Map.of("stock_id", damaged, "quantity", 3, "reason", "파손 폐기")).data().get("approval_id").asString();
        c.o().post("/api/v1/approvals/" + ap2 + "/decision", Map.of("decision", "APPROVE"));
        drainAsync();
        assertThat(cost(total(c.o(), c.be()), "INVENTORY_IMPAIRMENT")).isEqualTo(18000 + 9000);
        // 이동평균: 남은 실물 6개 @9,000(원가 등록) + 입고 3개 @12,000 → (54,000 + 36,000) / 9 = 10,000
        c.o().post("/api/v1/inventory/receipts", Map.of("business_entity_id", c.be().toString(), "sku_id", c.amp().toString(), "node_id", c.hs().toString(), "quantity", 3,
                "unit_cost", 12000));
        String o2 = order(c, "C-2", new Object[]{"P-100", 1, 30000, 0, 0, false});
        shipOrder(c, o2);
        JsonNode cogs = c.o().get("/api/v1/finance/entries?order_id=" + o2 + "&category=PRODUCT_COGS").data();
        assertThat(cogs.get(0).get("supply_amount").asInt()).isEqualTo(10000);
        // 권한: CS 는 손익 조회 불가
        assertThat(fx.member(c.ws(), "CS_AGENT").get("/api/v1/finance/profit").errorCode()).isEqualTo("PERMISSION_DENIED");
    }

    String stockId(Ctx c, String quality) {
        for (JsonNode s : c.o().get("/api/v1/inventory/stocks?sku_id=" + c.amp()).data()) {
            if (quality.equals(s.get("quality_status").asString()) && s.get("on_hand").asInt() > 0) {
                return s.get("id").asString();
            }
        }
        throw new AssertionError("no " + quality);
    }

    @Test
    void T18_T19_exchangeKeepsOriginalRevenue_replacementCost_priceDifferenceSeparate() {
        Ctx c = ctx("디상사");
        String o = order(c, "D-1", new Object[]{"P-100", 1, 33000, 0, 0, false});
        shipOrder(c, o);
        returnAndInspect(c, o, "EXCHANGE", "SELLABLE", Map.of("reason_code", "DEFECT"));
        shipOrder(c, o); // 교환품 출고
        JsonNode t = total(c.o(), c.be());
        assertThat(t.get("net_sales").asInt()).isEqualTo(30000); // 원판매 매출 유지(T18)
        assertThat(cost(t, "PRODUCT_COGS")).isEqualTo(9000); // 원출고 9,000 + 교환품 9,000 − 정상 회수 환입 9,000
        // T19: 다른 SKU(원가 12,000) + 차액 5,000 → 교환 차액 매출 4,546(공급가), 새 SKU 원가
        String o2 = order(c, "D-2", new Object[]{"P-100", 1, 33000, 0, 0, false});
        shipOrder(c, o2);
        Map<String, Object> body = new LinkedHashMap<>(Map.of("order_id", o2, "claim_type", "EXCHANGE", "reason_code", "CHANGE_OF_MIND", "price_difference", 5000,
                "items", List.of(Map.of("order_item_id", itemId(c, o2, "P-100"), "quantity", 1, "replacement_sku_id", c.amp50().toString(), "replacement_quantity", 1))));
        String claim = c.o().post("/api/v1/claims", body).data().get("id").asString();
        c.o().post("/api/v1/claims/" + claim + "/approve", Map.of());
        drainAsync();
        JsonNode rc = c.o().post("/api/v1/returns/receipts", Map.of("node_id", c.hs().toString(), "claim_id", claim, "lines",
                List.of(Map.of("sku_id", c.amp().toString(), "quantity", 1)))).data();
        drainAsync();
        c.o().post("/api/v1/returns/receipts/" + rc.get("id").asString() + "/inspections", Map.of("lines", List.of(Map.of("receipt_line_id",
                rc.get("lines").get(0).get("id").asString(), "decisions", List.of(Map.of("condition", "SELLABLE", "quantity", 1))))));
        drainAsync();
        shipOrder(c, o2);
        assertThat(c.o().get("/api/v1/claims/" + claim).data().get("status").asString()).isEqualTo("COMPLETED");
        JsonNode diff = c.o().get("/api/v1/finance/entries?claim_id=" + claim + "&category=EXCHANGE_PRICE_DIFFERENCE").data();
        assertThat(diff).hasSize(1);
        assertThat(diff.get(0).get("supply_amount").asInt()).isEqualTo(4546);
        JsonNode cogs = c.o().get("/api/v1/finance/entries?order_id=" + o2 + "&category=PRODUCT_COGS").data();
        int sum = 0;
        for (JsonNode e : cogs) {
            sum += e.get("supply_amount").asInt();
        }
        assertThat(sum).isEqualTo(9000 + 12000 - 9000);
    }

    @Test
    void T36_groupMarginIsSumOverSum_notAverage_andScopeLimitsBusinessEntities() {
        Fixtures.Ws ws = fx.workspace();
        Ctx a = ctx(ws, "에이상사", "AA");
        Ctx b = ctx(ws, "비상사", "BB");
        shipOrder(a, order(a, "G-1", new Object[]{"P-100", 1, 22000, 0, 0, false})); // 순매출 20,000, 원가 9,000 → 11,000(55%)
        shipOrder(b, order(b, "G-2", new Object[]{"P-100", 5, 11000, 0, 0, false})); // 순매출 50,000, 원가 45,000 → 5,000(10%)
        JsonNode p = a.o().get("/api/v1/finance/profit?group_by=business_entity").data();
        assertThat(p.get("rows")).hasSize(2);
        JsonNode t = p.get("total");
        assertThat(t.get("net_sales").asInt()).isEqualTo(70000);
        assertThat(t.get("contribution").asInt()).isEqualTo(16000);
        assertThat(t.get("margin_pct").asString()).isEqualTo("22.86"); // 16,000 / 70,000 — 단순 평균 32.5% 아님
        Api fin = fx.member(ws, "FINANCE");
        fx.setPolicy(a.o(), fin, null, List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", b.be(), "ALLOW")));
        JsonNode only = fin.get("/api/v1/finance/profit?group_by=business_entity").data();
        assertThat(only.get("rows")).hasSize(1);
        assertThat(only.get("total").get("net_sales").asInt()).isEqualTo(50000);
        assertThat(fin.get("/api/v1/finance/entries?business_entity_id=" + a.be()).status()).isIn(403, 200);
        assertThat(fx.workspace().owner().get("/api/v1/finance/profit").data().get("rows")).isEmpty();
    }
}
