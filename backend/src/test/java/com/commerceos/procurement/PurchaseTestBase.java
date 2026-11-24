package com.commerceos.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.finance.FinanceTestBase;
import com.commerceos.support.Api;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** 발주·입고 테스트 공용 도우미(공급처·발주 작성·승인·입고예정·재고). */
public abstract class PurchaseTestBase extends FinanceTestBase {

    String supplier(Ctx c, String name) {
        Api.Res r = c.o().post("/api/v1/suppliers", Map.of("business_entity_id", c.be().toString(), "name", name, "lead_time_days", 10, "payment_terms", "월말 마감 익월 10일"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        String id = r.data().get("id").asString();
        Api.Res s = c.o().put("/api/v1/suppliers/" + id + "/skus", Map.of("sku_id", c.amp().toString(), "unit_cost", 8000, "moq", 10, "order_multiple", 5), null);
        assertThat(s.status()).as(s.body().toString()).isEqualTo(200);
        return id;
    }

    JsonNode po(Ctx c, String supplier, int qty) {
        Api.Res r = c.o().post("/api/v1/purchase-orders", Map.of("supplier_id", supplier, "node_id", c.hs().toString(), "expected_on", "2026-12-10",
                "lines", List.of(Map.of("sku_id", c.amp().toString(), "quantity", qty))));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data();
    }

    String place(Ctx c, JsonNode po, Api approver) {
        JsonNode s = c.o().post("/api/v1/purchase-orders/" + po.get("id").asString() + "/submit", Map.of()).data();
        assertThat(s.get("status").asString()).isEqualTo("APPROVAL_PENDING");
        Api.Res d = approver.post("/api/v1/approvals/" + s.get("approval_id").asString() + "/decision", Map.of("decision", "APPROVE"));
        assertThat(d.status()).as(d.body().toString()).isEqualTo(200);
        return po.get("id").asString();
    }

    int inbound(Ctx c) {
        JsonNode r = c.o().get("/api/v1/purchase-orders/inbound?sku_id=" + c.amp()).data();
        return r.isEmpty() ? 0 : r.get(0).get("inbound_qty").asInt();
    }

    int onHand(Ctx c) {
        return jdbc.sql("select coalesce(sum(on_hand), 0) from inventory_stock where owner_business_entity_id = :b and sku_id = :s").param("b", c.be()).param("s", c.amp())
                .query(Integer.class).single();
    }
}
