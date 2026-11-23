package com.commerceos.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.finance.FinanceTestBase;
import com.commerceos.support.Api;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** X5 발주 제안: 판매 속도·리드타임·목표 일수·가용·입고예정·MOQ 반영, 제안으로 작성 중 발주 만들기. */
class ReorderTest extends FinanceTestBase {
    @Test
    void suggestionAccountsForLeadTimeInboundAndMoq_thenDraftPo() {
        Ctx c = ctx("제안상사");
        String sup = c.o().post("/api/v1/suppliers", Map.of("business_entity_id", c.be().toString(), "name", "제안 공급처", "lead_time_days", 10)).data().get("id").asString();
        c.o().put("/api/v1/suppliers/" + sup + "/skus", Map.of("sku_id", c.amp().toString(), "unit_cost", 8000, "moq", 10, "order_multiple", 5), null);
        shipOrder(c, order(c, "RO-1", new Object[]{"P-100", 8, 11000, 0, 0, false})); // 28일 8개, 가용 2
        JsonNode s = c.o().get("/api/v1/purchase-orders/suggestions?cover_days=14").data();
        assertThat(s).hasSize(1);
        JsonNode x = s.get(0);
        assertThat(x.get("atp").asInt()).isEqualTo(2);
        assertThat(x.get("target_days").asInt()).isEqualTo(24);
        assertThat(x.get("need").asInt()).isEqualTo(5);           // ⌈8/28 × 24⌉ = 7 − 2
        assertThat(x.get("suggested_qty").asInt()).isEqualTo(10); // MOQ 10
        assertThat(x.get("amount").asInt()).isEqualTo(80000);
        assertThat(x.get("reason").asString()).contains("리드타임 10일");
        // 제안으로 작성 중 발주
        Api.Res d = c.o().post("/api/v1/purchase-orders/from-suggestions", Map.of("node_id", c.hs().toString(),
                "items", List.of(Map.of("supplier_sku_id", x.get("supplier_sku_id").asString(), "quantity", 10))));
        assertThat(d.status()).as(d.body().toString()).isEqualTo(201);
        JsonNode po = d.data().get(0);
        assertThat(po.get("status").asString()).isEqualTo("DRAFT");
        assertThat(po.get("ordered_qty").asInt()).isEqualTo(10);
        // 승인되어 입고예정이 생기면 제안이 사라진다
        String ap = c.o().post("/api/v1/purchase-orders/" + po.get("id").asString() + "/submit", Map.of()).data().get("approval_id").asString();
        fx.member(c.ws(), "OPS_ADMIN").post("/api/v1/approvals/" + ap + "/decision", Map.of("decision", "APPROVE"));
        assertThat(c.o().get("/api/v1/purchase-orders/suggestions?cover_days=14").data()).isEmpty();
    }
}
