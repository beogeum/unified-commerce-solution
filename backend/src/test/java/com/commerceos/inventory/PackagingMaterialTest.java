package com.commerceos.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.finance.FinanceTestBase;
import com.commerceos.support.Api;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 포장 부자재 출고 차감: 거점 포장 프로필 → 유효 포장 BOM × 박스 수 차감, 부족분 업무, 원가는 차감 기록에서만(MATERIAL 규칙 중복 없음), 권한·외부 거점. */
class PackagingMaterialTest extends FinanceTestBase {

    int onHand(Ctx c, UUID sku) {
        return jdbc.sql("select coalesce(sum(on_hand), 0) from inventory_stock where owner_business_entity_id = :b and sku_id = :s").param("b", c.be()).param("s", sku)
                .query(Integer.class).single();
    }

    @Test
    void shipmentConsumesPackagingBom_shortfallFlagged_materialCostFromConsumptionOnly() throws Exception {
        Ctx c = ctx("부자재상사");
        Api o = c.o();
        UUID product = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "PKG", "name", "포장재")).data().get("product").get("id").asString());
        UUID box = sku(o, product, "BOX-M", "MATERIAL");
        UUID tape = sku(o, product, "TAPE-1", "MATERIAL");
        cost(c, box, 400);
        cost(c, tape, 50);
        assertThat(o.post("/api/v1/inventory/receipts", Map.of("business_entity_id", c.be().toString(), "sku_id", box.toString(), "node_id", c.hs().toString(), "quantity", 10))
                .status()).isEqualTo(201);
        assertThat(o.post("/api/v1/inventory/receipts", Map.of("business_entity_id", c.be().toString(), "sku_id", tape.toString(), "node_id", c.hs().toString(), "quantity", 1))
                .status()).isEqualTo(201);
        // 포장 BOM 없는 프로필은 지정 불가
        assertThat(o.put("/api/v1/fulfillment-nodes/" + c.hs() + "/packaging", Map.of("packaging_profile_code", "STD"), null).errorCode()).isEqualTo("PACKAGING_BOM_NOT_FOUND");
        Api.Res b = o.post("/api/v1/boms", Map.of("bom_type", "PACKAGING", "packaging_profile_code", "STD", "components",
                List.of(Map.of("sku_id", box.toString(), "quantity", 1), Map.of("sku_id", tape.toString(), "quantity", 2))));
        assertThat(b.status()).as(b.body().toString()).isEqualTo(201);
        Api.Res pub = o.postIfMatch("/api/v1/boms/" + b.data().get("id").asString() + "/publish", Map.of(),
                b.data().get("version_lock").asInt());
        assertThat(pub.status()).as(pub.body().toString()).isEqualTo(200);
        assertThat(fx.member(c.ws(), "CS_AGENT").put("/api/v1/fulfillment-nodes/" + c.hs() + "/packaging", Map.of("packaging_profile_code", "STD"), null).status())
                .isEqualTo(403);
        JsonNode pk = o.put("/api/v1/fulfillment-nodes/" + c.hs() + "/packaging", Map.of("packaging_profile_code", "std"), null).data();
        assertThat(pk.get("packaging_profile_code").asString()).isEqualTo("STD");
        assertThat(pk.get("effective_bom_version").asInt()).isEqualTo(1);
        // 부자재 규칙이 있어도 차감 기록이 있으면 규칙은 쓰지 않는다
        rule(c, "MATERIAL", "PER_ORDER", 999, null, false);

        Thread.sleep(6000); // 출고 시각(테스트 도우미는 5초 전)에 BOM 이 유효하도록 — 과거 소급 게시는 금지
        String order = order(c, "PKG-1", new Object[]{"P-100", 1, 33000, 0, 0, false});
        String fo = shipOrder(c, order);
        JsonNode m = o.get("/api/v1/fulfillment-orders/" + fo + "/materials").data();
        assertThat(m).hasSize(2);
        assertThat(onHand(c, box)).isEqualTo(9);
        assertThat(onHand(c, tape)).isZero();
        JsonNode t = null;
        for (JsonNode x : m) {
            if (x.get("sku_code").asString().equals("TAPE-1")) {
                t = x;
            }
        }
        assertThat(t.get("required_qty").asInt()).isEqualTo(2);
        assertThat(t.get("consumed_qty").asInt()).isEqualTo(1);
        assertThat(t.get("shortfall_qty").asInt()).isEqualTo(1);
        assertThat(o.get("/api/v1/work-items?item_type=MATERIAL_STOCK_SHORT&status=OPEN").data()).hasSize(1);
        // 손익: 부자재 = 400(실제) + 50 × 2(부족분 포함 예상), 규칙 999 없음
        List<BigDecimal> mats = jdbc.sql("select supply_amount from financial_entry where fo_id = cast(:f as uuid) and category = 'MATERIAL' order by supply_amount")
                .param("f", fo).query(BigDecimal.class).list();
        assertThat(mats).usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("100"), new BigDecimal("400"));
        // 원장: 부자재 차감은 CONSUME(참조 FO_MATERIAL), 판매 상품 확보와 별개
        assertThat(jdbc.sql("select count(*) from inventory_transaction where reference_type = 'FO_MATERIAL' and reference_id = :f and txn_type = 'CONSUME'").param("f", fo)
                .query(Integer.class).single()).isEqualTo(2);
        // 물류사 재고 기준 거점에는 지정 불가, 해제는 가능
        UUID ext = o.post("/api/v1/fulfillment-nodes", Map.of("code", "EXT1", "name", "외부 센터", "logistics_provider_code", "FASSTO", "cutoff_time", "15:00",
                "inventory_source", "EXTERNAL")).id();
        assertThat(o.put("/api/v1/fulfillment-nodes/" + ext + "/packaging", Map.of("packaging_profile_code", "STD"), null).errorCode()).isEqualTo("NODE_INVENTORY_EXTERNAL");
        assertThat(o.put("/api/v1/fulfillment-nodes/" + c.hs() + "/packaging", Map.of(), null).data().get("packaging_profile_code").isNull()).isTrue();
    }
}
