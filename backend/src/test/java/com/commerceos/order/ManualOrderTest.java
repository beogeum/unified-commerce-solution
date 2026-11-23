package com.commerceos.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.finance.FinanceTestBase;
import com.commerceos.support.Api;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** FR-ORD-04 수동·B2B·샘플 주문: 같은 주문 경로(매핑·확보·출고 배정·손익), 샘플 원가는 판촉비, 채널 발송처리 없음, 개인정보 마스킹, 멱등·권한. */
class ManualOrderTest extends FinanceTestBase {
    static final Map<String, Object> RECIPIENT = Map.of("name", "김납품", "phone", "010-2222-3333", "address1", "경기도 성남시 분당구 판교역로 1");

    Api.Res manual(Api api, Ctx c, String kind, Object... lines) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("channel_account_id", c.acc().toString());
        b.put("kind", kind);
        if ("B2B".equals(kind)) {
            b.put("customer_name", "올리브 유통");
        }
        b.put("recipient", RECIPIENT);
        List<Map<String, Object>> ls = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i += 3) {
            ls.add(Map.of("sku_id", lines[i].toString(), "quantity", lines[i + 1], "unit_price", lines[i + 2]));
        }
        b.put("lines", ls);
        return api.postWithKey("/api/v1/orders/manual", b, "manual-" + java.util.UUID.randomUUID());
    }

    @Test
    void b2bOrderFollowsStandardPipeline_sampleCostIsPromotion_noChannelDispatch_validationAndPermission() {
        Ctx c = ctx("수동주문상사");
        Api.Res r = manual(c.o(), c, "B2B", c.amp(), 3, 20000);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        String id = r.data().get("order_id").asString();
        assertThat(r.data().get("external_order_key").asString()).startsWith("M-");
        drainAsync();
        JsonNode o = c.o().get("/api/v1/orders/" + id).data();
        assertThat(o.get("order").get("source_type").asString()).isEqualTo("MANUAL");
        assertThat(o.get("order").get("order_kind").asString()).isEqualTo("B2B");
        assertThat(o.get("items").get(0).get("mapping_status").asString()).isEqualTo("MAPPED");
        assertThat(o.get("items").get(0).get("sku_id").asString()).isEqualTo(c.amp().toString());
        // 개인정보는 채널 주문처럼 마스킹
        assertThat(o.toString()).doesNotContain("010-2222-3333").doesNotContain("판교역로 1");
        JsonNode list = c.o().get("/api/v1/orders?q=" + r.data().get("order_no").asString()).data();
        assertThat(list.get(0).get("order_kind").asString()).isEqualTo("B2B");
        assertThat(list.get(0).get("customer_name").asString()).isEqualTo("올리브 유통");
        // 출고 배정 → 출고 확정 → 손익(매출 60,000 → 공급가 54,546), 채널 발송처리 대상 아님
        shipOrder(c, id);
        JsonNode t = total(c.o(), c.be());
        assertThat(t.get("net_sales").asInt()).isEqualTo(54546);
        assertThat(cost(t, "PRODUCT_COGS")).isEqualTo(27000);
        JsonNode sh = c.o().get("/api/v1/shipments?order_id=" + id).data();
        assertThat(sh.get(0).get("channel_dispatch_status").asString()).isEqualTo("NOT_REQUIRED");

        // 샘플: 금액 0 만, 원가는 판촉비(GWP)
        assertThat(manual(c.o(), c, "SAMPLE", c.amp50(), 1, 1000).errorCode()).isEqualTo("VALIDATION_FAILED");
        Api.Res s = manual(c.o(), c, "SAMPLE", c.amp50(), 2, 0);
        assertThat(s.status()).as(s.body().toString()).isEqualTo(201);
        drainAsync();
        shipOrder(c, s.data().get("order_id").asString());
        t = total(c.o(), c.be());
        assertThat(cost(t, "GWP")).isEqualTo(24000); // 12,000 × 2
        assertThat(cost(t, "PRODUCT_COGS")).isEqualTo(27000);
        assertThat(t.get("net_sales").asInt()).isEqualTo(54546);

        // 검증: B2B 거래처명, 받는 분, 판매 불가 SKU
        Map<String, Object> noCustomer = new LinkedHashMap<>(Map.of("channel_account_id", c.acc().toString(), "kind", "B2B", "recipient", RECIPIENT,
                "lines", List.of(Map.of("sku_id", c.amp().toString(), "quantity", 1, "unit_price", 1000))));
        assertThat(c.o().postWithKey("/api/v1/orders/manual", noCustomer, "manual-nocust-01").errorCode()).isEqualTo("VALIDATION_FAILED");
        Map<String, Object> noRecipient = new LinkedHashMap<>(Map.of("channel_account_id", c.acc().toString(), "kind", "MANUAL",
                "lines", List.of(Map.of("sku_id", c.amp().toString(), "quantity", 1, "unit_price", 1000))));
        assertThat(c.o().postWithKey("/api/v1/orders/manual", noRecipient, "manual-norcp-01").errorCode()).isEqualTo("VALIDATION_FAILED");

        // 멱등: 같은 키 재전송 → 같은 주문
        Map<String, Object> body = new LinkedHashMap<>(Map.of("channel_account_id", c.acc().toString(), "kind", "MANUAL", "recipient", RECIPIENT,
                "lines", List.of(Map.of("sku_id", c.amp().toString(), "quantity", 1, "unit_price", 15000))));
        String first = c.o().postWithKey("/api/v1/orders/manual", body, "manual-idem-0001").data().get("order_id").asString();
        assertThat(c.o().postWithKey("/api/v1/orders/manual", body, "manual-idem-0001").data().get("order_id").asString()).isEqualTo(first);

        // 권한: 주문 변경 권한 없는 창고 작업자 불가, 다른 운영그룹 판매계정 불가
        Api wh = fx.member(c.ws(), "WAREHOUSE_WORKER");
        assertThat(manual(wh, c, "MANUAL", c.amp(), 1, 1000).status()).isEqualTo(403);
        Api other = fx.workspace().owner();
        assertThat(manual(other, c, "MANUAL", c.amp(), 1, 1000).status()).isEqualTo(404);
    }
}
