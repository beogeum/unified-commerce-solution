package com.commerceos.insight;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.finance.FinanceTestBase;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** X3 손익 시뮬레이션: 가격·탄력성·수수료율 변경의 공헌이익 영향, 고정비 가정, 손익분기, 검토 업무 저장, 권한. */
class SimulationTest extends FinanceTestBase {
    @Test
    void priceCutWithElasticity_commissionChange_breakeven_saveAsWorkItem() {
        Ctx c = ctx("시뮬상사");
        rule(c, "SELLING_FEE", "PER_UNIT", 1500, null, false);
        rule(c, "OUTBOUND_SHIPPING", "PER_SHIPMENT", 3200, null, true);
        shipOrder(c, order(c, "SIM-1", new Object[]{"P-100", 2, 33000, 0, 0, false}));
        c.o().postWithKey("/api/v1/finance/cost-entries", Map.of("business_entity_id", c.be().toString(), "category", "ADVERTISING", "basis", "ACTUAL",
                "supply_amount", 5000), "sim-adv-0001");
        Map<String, Object> s = new LinkedHashMap<>(Map.of("business_entity_id", c.be().toString(), "price_change_pct", -10, "elasticity", -2));
        JsonNode r = c.o().post("/api/v1/simulations/profit", s).data();
        assertThat(r.get("base").get("contribution").asInt()).isEqualTo(30800); // 60,000 − 18,000 − 3,000 − 3,200 − 5,000
        assertThat(new BigDecimal(r.get("volume_factor").asString())).isEqualByComparingTo("1.2");
        JsonNode sim = r.get("simulated");
        assertThat(sim.get("net_sales").asInt()).isEqualTo(64800);
        assertThat(sim.get("costs").get("PRODUCT_COGS").asInt()).isEqualTo(21600);
        assertThat(sim.get("costs").get("SELLING_FEE").asInt()).isEqualTo(3240);
        assertThat(sim.get("costs").get("OUTBOUND_SHIPPING").asInt()).isEqualTo(3840);
        assertThat(sim.get("costs").get("ADVERTISING").asInt()).isEqualTo(5000); // 고정 가정
        assertThat(sim.get("contribution").asInt()).isEqualTo(31120);
        assertThat(r.get("contribution_delta").asInt()).isEqualTo(320);
        assertThat(new BigDecimal(r.get("breakeven_volume_change_pct").asString())).isEqualByComparingTo("18.9");
        assertThat(r.get("assumptions").toString()).contains("인과 효과가 아닌");
        // 수수료율 +2%p
        s.put("commission_rate_pp", 2);
        assertThat(c.o().post("/api/v1/simulations/profit", s).data().get("simulated").get("costs").get("SELLING_FEE").asInt()).isEqualTo(4536);
        // 검토 업무로 남기기(자동 실행 없음)
        assertThat(c.o().post("/api/v1/simulations/profit/work-item", s).status()).isEqualTo(200);
        assertThat(c.o().get("/api/v1/work-items?item_type=SIMULATION_REVIEW&status=OPEN").data()).hasSize(1);
        // 검증·권한
        assertThat(c.o().post("/api/v1/simulations/profit", Map.of("business_entity_id", c.be().toString(), "elasticity", 20)).errorCode()).isEqualTo("VALIDATION_FAILED");
        java.util.UUID empty = fx.businessEntity(c.o(), "빈사업자");
        assertThat(c.o().post("/api/v1/simulations/profit", Map.of("business_entity_id", empty.toString())).errorCode()).isEqualTo("SIMULATION_NO_SALES");
        assertThat(fx.member(c.ws(), "CS_AGENT").post("/api/v1/simulations/profit", s).status()).isEqualTo(403);
    }
}
