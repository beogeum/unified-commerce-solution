package com.commerceos.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** FR-CAT-08 상품정보 품질 점검, docs/05 §7 추정 영업이익(고정비는 공헌이익 밖, 배부 가능). */
class QualityAndFixedCostTest extends FinanceTestBase {

    List<String> codes(JsonNode issues, String sku) {
        List<String> out = new ArrayList<>();
        for (JsonNode i : issues) {
            if (sku == null || (i.get("sku_code") != null && !i.get("sku_code").isNull() && i.get("sku_code").asString().equals(sku))) {
                out.add(i.get("code").asString());
            }
        }
        return out;
    }

    @Test
    void qualityChecks_costScopedByPermission() {
        Ctx c = ctx("품질상사");
        UUID nc = sku(c.o(), UUID.fromString(c.o().post("/api/v1/products", Map.of("product_code", "QNC", "name", "원가없음")).data().get("product").get("id").asString()),
                "QNC-1", "NORMAL");
        c.o().post("/api/v1/inventory/receipts", Map.of("business_entity_id", c.be().toString(), "sku_id", nc.toString(), "node_id", c.hs().toString(), "quantity", 3));
        UUID unit = UUID.fromString(c.o().get("/api/v1/channel-accounts/" + c.acc() + "/sales-units").data().get(0).get("id").asString());
        c.o().post("/api/v1/channel-listings", Map.of("sales_unit_id", unit.toString(), "external_product_id", "P-NEW", "external_option_id", "", "title", "새 상품"));
        shipOrder(c, order(c, "QC-1", new Object[]{"P-100", 1, 10000, 0, 0, false}));
        JsonNode r = c.o().get("/api/v1/catalog/quality").data();
        assertThat(codes(r.get("issues"), "QNC-1")).contains("SKU_NO_COST", "SKU_NO_BARCODE", "STOCK_NOT_LISTED");
        assertThat(codes(r.get("issues"), "AMP-50")).contains("STOCK_NOT_LISTED");
        assertThat(codes(r.get("issues"), "AMP-30")).contains("SELLING_WITHOUT_SUPPLIER").doesNotContain("SKU_NO_COST", "STOCK_NOT_LISTED");
        assertThat(r.get("counts").get("UNMAPPED_LISTING").asInt()).isEqualTo(1);
        assertThat(r.get("issues").get(0).get("severity").asString()).isEqualTo("HIGH");
        // 원가 조회 권한이 없으면 원가 점검은 보이지 않는다
        Api cs = fx.member(c.ws(), "CS_AGENT");
        assertThat(codes(cs.get("/api/v1/catalog/quality").data().get("issues"), null)).doesNotContain("SKU_NO_COST");
    }

    @Test
    void fixedCosts_excludedFromContribution_operatingProfitEstimate_allocatable() {
        Fixtures.Ws ws = fx.workspace();
        Ctx a = ctx(ws, "고정에이", "FA");
        Ctx b = ctx(ws, "고정비이", "FB");
        shipOrder(a, order(a, "FX-1", new Object[]{"P-100", 1, 33000, 0, 0, false}));
        int contribution = total(a.o(), a.be()).get("contribution").asInt();
        Api.Res rent = a.o().postWithKey("/api/v1/finance/cost-entries", Map.of("business_entity_id", a.be().toString(), "category", "FIXED_RENT", "basis", "ACTUAL",
                "supply_amount", 100000), "fixed-rent-0001");
        assertThat(rent.status()).as(rent.body().toString()).isEqualTo(201);
        JsonNode t = total(a.o(), a.be());
        assertThat(t.get("contribution").asInt()).isEqualTo(contribution); // 공헌이익 불변
        assertThat(t.get("fixed_total").asInt()).isEqualTo(100000);
        assertThat(t.get("operating_profit_estimate").asInt()).isEqualTo(contribution - 100000);
        assertThat(t.get("costs").has("FIXED_RENT")).isFalse();
        // 고정비도 사업자 간 배부 가능
        Api.Res al = a.o().postWithKey("/api/v1/finance/cost-allocations", Map.of("category", "FIXED_LABOR", "supply_amount", 30000, "method", "EQUAL",
                "targets", List.of(Map.of("business_entity_id", a.be().toString()), Map.of("business_entity_id", b.be().toString()))), "fixed-alloc-0001");
        assertThat(al.status()).as(al.body().toString()).isEqualTo(201);
        assertThat(total(a.o(), b.be()).get("fixed_total").asInt()).isEqualTo(15000);
        assertThat(total(a.o(), b.be()).get("contribution").asInt()).isZero();
    }
}
