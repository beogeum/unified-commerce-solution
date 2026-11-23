package com.commerceos.insight;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.finance.UnitCosts;
import com.commerceos.support.Api;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** M6 1차: 운영 리포트(재고·판매·물류 KPI), 규칙 기반 인사이트 카드(원인 분해·근거·미확인 표시·사람 판단·자동 해결·범위). */
class InsightTest extends com.commerceos.finance.FinanceTestBase {
    @Autowired
    UnitCosts unitCosts;

    void entry(UUID ws, UUID be, UUID ca, String cls, String cat, String basis, int supply, LocalDate d) {
        jdbc.sql("""
                insert into financial_entry (id, workspace_id, business_entity_id, channel_account_id, entry_class, category, basis, supply_amount, period_date, source_type, source_key)
                values (gen_random_uuid(), :ws, :be, :ca, :cls, :cat, :b, :s, :d, 'TEST', :k)
                """).param("ws", ws).param("be", be).param("ca", ca).param("cls", cls).param("cat", cat).param("b", basis).param("s", supply).param("d", d)
                .param("k", "T:" + UUID.randomUUID()).update();
    }

    List<JsonNode> cards(Api api, String query) {
        Api.Res r = api.get("/api/v1/insights" + query);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        List<JsonNode> out = new ArrayList<>();
        r.data().forEach(out::add);
        return out;
    }

    static JsonNode byRule(List<JsonNode> cards, String rule) {
        return cards.stream().filter(c -> c.get("rule").asString().equals(rule)).findFirst().orElse(null);
    }

    @Test
    void reports_inventoryCoverSalesRatesLogistics_withDenominators() {
        Ctx c = ctx("리포트상사");
        String o = order(c, "R-1", new Object[]{"P-100", 9, 11000, 0, 0, false});
        shipOrder(c, o);
        order(c, "R-2", new Object[]{"P-100", 1, 11000, 0, 0, false}); // 미출고(확보만)
        returnAndInspect(c, o, "RETURN", "SELLABLE", Map.of());
        JsonNode inv = c.o().get("/api/v1/analytics/inventory").data();
        JsonNode amp = null;
        for (JsonNode r : inv.get("rows")) {
            if (r.get("sku_id").asString().equals(c.amp().toString())) {
                amp = r;
            }
        }
        assertThat(amp).isNotNull();
        assertThat(amp.get("sold28d").asInt()).isEqualTo(9);
        assertThat(amp.get("sellable_on_hand").asInt()).isEqualTo(2); // 10 − 9 + 반품 정상 1
        assertThat(amp.get("atp").asInt()).isEqualTo(1);             // R-2 확보 1
        assertThat(new BigDecimal(amp.get("days_of_cover").asString())).isEqualByComparingTo("3.1");
        assertThat(amp.get("stockout_risk").asBoolean()).isTrue();
        assertThat(new BigDecimal(amp.get("book_value").asString())).isEqualByComparingTo("18000");
        // 판매가 없는 SKU 의 재고일수는 계산불가(null)
        for (JsonNode r : inv.get("rows")) {
            if (r.get("sku_id").asString().equals(c.amp50().toString())) {
                assertThat(r.get("days_of_cover").isNull()).isTrue();
            }
        }
        JsonNode s = c.o().get("/api/v1/analytics/sales?group_by=channel_account").data();
        JsonNode t = s.get("total");
        assertThat(t.get("orders").asInt()).isEqualTo(2);
        assertThat(t.get("original_units").asInt()).isEqualTo(10);
        assertThat(t.get("shipped_units").asInt()).isEqualTo(9);
        assertThat(t.get("returned_units").asInt()).isEqualTo(1);
        assertThat(t.get("net_units").asInt()).isEqualTo(9);
        assertThat(new BigDecimal(t.get("return_rate_pct").asString())).isEqualByComparingTo("11.11"); // 1 / 출고 9
        assertThat(t.get("cancel_rate_pct").asString()).isEqualTo("0.00");
        JsonNode l = c.o().get("/api/v1/analytics/logistics").data();
        assertThat(l.get("rows").get(0).get("fulfillment_orders").asInt()).isEqualTo(1);
        assertThat(l.get("rows").get(0).get("parcels").asInt()).isEqualTo(1);
        assertThat(l.get("cost_visible").asBoolean()).isTrue();
        // 원가 권한 없는 사용자에게는 물류비 숨김, 주문 조회 권한만으로 판매 KPI
        Api cs = fx.member(c.ws(), "CS_AGENT");
        JsonNode l2 = cs.get("/api/v1/analytics/logistics").data();
        assertThat(l2.get("cost_visible").asBoolean()).isFalse();
        assertThat(l2.get("rows").get(0).get("shipping_cost").isNull()).isTrue();
        assertThat(fx.workspace().owner().get("/api/v1/analytics/sales").data().get("rows")).isEmpty();
    }

    @Test
    void insights_marginDropDecomposedWithUnconfirmed_stockout_expiry_settlementMissing_undeposited_actionsAndAutoResolve() {
        Ctx c = ctx("인사이트상사");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        UUID ws = c.ws().id();
        // 공헌이익률(별도 판매계정): 직전 30일 55% → 최근 30일 42%(광고비 +10%p, 수수료 +3%p 예상)
        UUID ca2 = fx.channelAccount(c.o(), c.be(), "COUPANG_MARKETPLACE", "인사이트 쿠팡");
        entry(ws, c.be(), ca2, "REVENUE", "SALES", "ACTUAL", 100000, today.minusDays(40));
        entry(ws, c.be(), ca2, "COST", "PRODUCT_COGS", "ACTUAL", 40000, today.minusDays(40));
        entry(ws, c.be(), ca2, "COST", "ADVERTISING", "ACTUAL", 5000, today.minusDays(40));
        entry(ws, c.be(), ca2, "REVENUE", "SALES", "ACTUAL", 100000, today.minusDays(5));
        entry(ws, c.be(), ca2, "COST", "PRODUCT_COGS", "ACTUAL", 40000, today.minusDays(5));
        entry(ws, c.be(), ca2, "COST", "ADVERTISING", "ACTUAL", 15000, today.minusDays(5));
        entry(ws, c.be(), ca2, "COST", "SELLING_FEE", "ESTIMATED", 3000, today.minusDays(5));
        // 품절 위험: 10개 중 9개 판매
        String o = order(c, "I-1", new Object[]{"P-100", 9, 11000, 0, 0, false});
        shipOrder(c, o);
        // 유통기한 임박 LOT
        UUID product = UUID.fromString(c.o().post("/api/v1/products", Map.of("product_code", "EXP", "name", "크림")).data().get("product").get("id").asString());
        UUID cream = c.o().post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "EXP-1", "sku_type", "NORMAL", "lot_managed", true,
                "expiry_managed", true)).id();
        Api.Res rc = c.o().post("/api/v1/inventory/receipts", Map.of("business_entity_id", c.be().toString(), "sku_id", cream.toString(), "node_id", c.hs().toString(),
                "quantity", 5, "lot_code", "L2611", "expires_on", today.plusDays(10).toString()));
        assertThat(rc.status()).as(rc.body().toString()).isEqualTo(201);
        // 정산 누락 의심: 40일 전 출고된 주문, 이 판매계정에 정산은 있지만 그 주문은 없음 / 미입금: 지급예정일 10일 지남
        jdbc.sql("update shipment set shipped_at = now() - interval '40 days' where order_id = :o").param("o", UUID.fromString(o)).update();
        Map<String, Object> run = new LinkedHashMap<>(Map.of("channel_account_id", c.acc().toString(), "settlement_key", "ST-OLD", "payout_date",
                today.minusDays(10).toString(), "lines", List.of(Map.of("line_type", "SALE", "amount", 5000))));
        String runId = c.o().post("/api/v1/finance/settlements", run).data().get("id").asString();

        Api.Res rf = c.o().post("/api/v1/insights/refresh", Map.of());
        assertThat(rf.status()).as(rf.body().toString()).isEqualTo(200);
        int detected = rf.data().get("detected").asInt();
        List<JsonNode> open = cards(c.o(), "");
        JsonNode margin = byRule(open, "MARGIN_DROP");
        assertThat(margin).isNotNull();
        assertThat(margin.get("severity").asString()).isEqualTo("HIGH");
        assertThat(new BigDecimal(margin.get("metric").get("delta_pp").asString())).isEqualByComparingTo("-13.00");
        assertThat(margin.get("causes").get(0).get("code").asString()).isEqualTo("ADVERTISING");
        assertThat(new BigDecimal(margin.get("causes").get(0).get("delta_pp").asString())).isEqualByComparingTo("10.00");
        assertThat(margin.get("causes").get(1).get("code").asString()).isEqualTo("SELLING_FEE");
        assertThat(margin.get("unconfirmed").get(0).asString()).contains("예상 비용 3000원");
        assertThat(margin.get("evidence").get("certainty").asString()).contains("인과로 단정하지 않습니다");
        JsonNode stockout = byRule(open, "STOCKOUT_RISK");
        assertThat(stockout).isNotNull();
        assertThat(new BigDecimal(stockout.get("metric").get("days_of_cover").asString())).isEqualByComparingTo("3.1");
        JsonNode exp = byRule(open, "EXPIRY_RISK");
        assertThat(exp.get("severity").asString()).isEqualTo("HIGH");
        assertThat(exp.get("metric").get("days_left").asInt()).isEqualTo(10);
        JsonNode missing = byRule(open, "SETTLEMENT_MISSING");
        assertThat(missing.get("metric").get("orders").asInt()).isEqualTo(1);
        JsonNode undep = byRule(open, "UNDEPOSITED");
        assertThat(undep.get("metric").get("undeposited_amount").asInt()).isEqualTo(5000);

        // 다시 계산해도 카드는 그대로(중복 없음)
        JsonNode again = c.o().post("/api/v1/insights/refresh", Map.of()).data();
        assertThat(again.get("detected").asInt()).isEqualTo(detected);
        assertThat(again.get("opened").asInt()).isZero();
        assertThat(cards(c.o(), "")).hasSize(open.size());

        // 사람 판단: 검토 업무 생성(자동 실행 없음) · 무시(사유 필수)
        JsonNode acted = c.o().post("/api/v1/insights/" + stockout.get("id").asString() + "/work-item", Map.of()).data();
        assertThat(acted.get("status").asString()).isEqualTo("ACTIONED");
        assertThat(c.o().get("/api/v1/work-items?item_type=INSIGHT_REVIEW&status=OPEN").data()).hasSize(1);
        assertThat(c.o().post("/api/v1/insights/" + exp.get("id").asString() + "/dismiss", Map.of()).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(c.o().post("/api/v1/insights/" + exp.get("id").asString() + "/dismiss", Map.of("reason", "할인 행사 예정")).data().get("status").asString())
                .isEqualTo("DISMISSED");
        assertThat(c.o().post("/api/v1/insights/" + exp.get("id").asString() + "/dismiss", Map.of("reason", "x")).errorCode()).isEqualTo("INSIGHT_NOT_OPEN");
        c.o().post("/api/v1/insights/refresh", Map.of());
        assertThat(c.o().get("/api/v1/insights/" + exp.get("id").asString()).data().get("status").asString()).isEqualTo("DISMISSED"); // 무시는 유지

        // 원인 해소 → 자동 해결: 입금 연결, 입고로 재고 보충(조치 업무도 해결)
        String dep = c.o().postWithKey("/api/v1/finance/deposits", Map.of("business_entity_id", c.be().toString(), "deposited_on", today.toString(), "amount", 5000),
                "ins-dep-0001").data().get("id").asString();
        c.o().post("/api/v1/finance/deposits/" + dep + "/matches", Map.of("run_id", runId));
        c.o().post("/api/v1/inventory/receipts", Map.of("business_entity_id", c.be().toString(), "sku_id", c.amp().toString(), "node_id", c.hs().toString(), "quantity", 50));
        JsonNode r3 = c.o().post("/api/v1/insights/refresh", Map.of()).data();
        assertThat(r3.get("resolved").asInt()).isEqualTo(2);
        assertThat(c.o().get("/api/v1/insights/" + undep.get("id").asString()).data().get("status").asString()).isEqualTo("RESOLVED");
        assertThat(c.o().get("/api/v1/insights/" + stockout.get("id").asString()).data().get("status").asString()).isEqualTo("RESOLVED");
        assertThat(c.o().get("/api/v1/work-items?item_type=INSIGHT_REVIEW&status=OPEN").data()).isEmpty();

        // 범위: 물류관리자(재고 조회만)는 재고 카드만, 손익·정산 카드 불가. 다른 워크스페이스는 존재도 모름
        Api logi = fx.member(c.ws(), "LOGISTICS_MANAGER");
        List<JsonNode> seen = cards(logi, "?status=ALL");
        assertThat(seen).isNotEmpty();
        assertThat(seen).allMatch(x -> List.of("STOCKOUT_RISK", "SLOW_MOVING", "EXPIRY_RISK").contains(x.get("rule").asString()));
        assertThat(logi.get("/api/v1/insights/" + margin.get("id").asString()).status()).isEqualTo(403);
        assertThat(logi.post("/api/v1/insights/" + margin.get("id").asString() + "/work-item", Map.of()).status()).isIn(403, 404);
        assertThat(fx.workspace().owner().get("/api/v1/insights/" + margin.get("id").asString()).status()).isEqualTo(404);
    }

    @Test
    void slowMovingRule_detectsStockWithoutSalesBeyondThreshold_bookValueOrUnconfirmed() {
        Ctx c = ctx("장기재고상사");
        InsightEngine engine = new InsightEngine(jdbc, unitCosts, new BigDecimal("3"), new BigDecimal("50000"), 30, 7, 0, 60, 30, 7);
        List<InsightEngine.Detection> d = com.commerceos.shared.db.TenantContext.asSystem(() -> engine.stock(c.ws().id(), LocalDate.now(ZoneId.of("Asia/Seoul"))));
        InsightEngine.Detection amp50 = d.stream().filter(x -> x.rule().equals("SLOW_MOVING") && x.skuId().equals(c.amp50())).findFirst().orElseThrow();
        assertThat(amp50.metric().get("book_value")).isEqualTo(new BigDecimal("120000")); // 10 × 12,000
        assertThat(amp50.unconfirmed()).isEmpty();
    }
}
