package com.commerceos.order;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M4 O1~O2: 주문 식별·귀속 스냅샷(T01), 중복·역순(T10), 상품 연결·검증·보류, 개인정보 보호, 연동 수집. */
class OrderTest extends IntegrationTest {

    record Ctx(Fixtures.Ws ws, Api o, UUID beA, UUID beB, UUID accA, UUID accB, UUID unitA, UUID sku) {
    }

    Ctx ctx() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        UUID a = fx.businessEntity(o, "에이상사");
        UUID b = fx.businessEntity(o, "비상사");
        UUID accA = fx.channelAccount(o, a, "NAVER_SMARTSTORE", "A 스마트스토어");
        UUID accB = fx.channelAccount(o, b, "NAVER_SMARTSTORE", "B 스마트스토어");
        UUID unitA = UUID.fromString(o.get("/api/v1/channel-accounts/" + accA + "/sales-units").data().get(0).get("id").asString());
        UUID product = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "AMP", "name", "앰플")).data().get("product").get("id").asString());
        UUID sku = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "AMP-30", "sku_type", "NORMAL")).id();
        return new Ctx(ws, o, a, b, accA, accB, unitA, sku);
    }

    static Map<String, Object> order(String key, Instant orderedAt, Instant updatedAt, int qty, int canceled, String productId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("external_item_key", key + "-1");
        item.put("external_product_id", productId);
        item.put("external_option_id", "");
        item.put("product_name", "비타 앰플 30ml");
        item.put("quantity", qty);
        item.put("canceled_quantity", canceled);
        item.put("unit_price", 10000);
        item.put("line_amount", 10000 * qty);
        item.put("seller_discount", 1000);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("external_order_key", key);
        m.put("ordered_at", orderedAt.toString());
        m.put("paid_at", orderedAt.plusSeconds(5).toString());
        m.put("external_status", canceled == qty ? "CANCELED" : "PAYED");
        m.put("external_updated_at", updatedAt.toString());
        m.put("shipping_fee", 3000);
        m.put("total_amount", 10000 * qty - 1000 + 3000);
        m.put("buyer", Map.of("name", "홍길동", "phone", "010-1234-5678"));
        m.put("recipient", Map.of("name", "홍길동", "phone", "010-1234-5678", "postal_code", "06236", "address1", "서울특별시 강남구 테헤란로 152", "address2", "10층"));
        m.put("items", List.of(item));
        return m;
    }

    JsonNode importOrders(Api api, UUID account, Map<String, Object>... orders) {
        Api.Res r = api.post("/api/v1/orders/imports", Map.of("channel_account_id", account.toString(), "orders", List.of(orders)));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.data();
    }

    String created(JsonNode result) {
        assertThat(result.get("results").get(0).get("outcome").asString()).as(result.toString()).isIn("CREATED", "UPDATED");
        return result.get("results").get(0).get("order_id").asString();
    }

    void mapListing(Ctx c, String productId) {
        Api.Res l = c.o().post("/api/v1/channel-listings", Map.of("sales_unit_id", c.unitA().toString(), "external_product_id", productId, "external_option_id", "",
                "title", "비타 앰플 30ml"));
        assertThat(l.status()).as(l.body().toString()).isEqualTo(201);
        String id = l.data().get("listing").get("id").asString();
        Api.Res m = c.o().post("/api/v1/channel-listings/" + id + "/mapping", Map.of("sku_id", c.sku().toString()));
        assertThat(m.status()).as(m.body().toString()).isEqualTo(200);
    }

    @Test
    void T01_sameExternalKeyOnTwoAccounts_separateOrders_attributionSnapshotNotRecalculated_scope() {
        Ctx c = ctx();
        mapListing(c, "P-100");
        Instant now = Instant.now();
        String a1 = created(importOrders(c.o(), c.accA(), order("ORD-1", now, now, 1, 0, "P-100")));
        String b1 = created(importOrders(c.o(), c.accB(), order("ORD-1", now, now, 1, 0, "P-100")));
        assertThat(a1).isNotEqualTo(b1); // 외부 주문번호가 같아도 판매계정이 다르면 다른 주문
        JsonNode da = c.o().get("/api/v1/orders/" + a1).data();
        assertThat(da.get("order").get("business_entity_id").asString()).isEqualTo(c.beA().toString());
        assertThat(da.get("order").get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(da.get("order").get("on_hold").asBoolean()).isFalse();
        assertThat(da.get("items").get(0).get("sku_code").asString()).isEqualTo("AMP-30");
        assertThat(c.o().get("/api/v1/orders/" + b1).data().get("order").get("business_entity_id").asString()).isEqualTo(c.beB().toString());

        // 판매계정 A 를 사업자 B 로 넘겨도 기존 주문은 A, 이후 주문은 B
        int v = c.o().get("/api/v1/channel-accounts/" + c.accA()).data().get("version").asInt();
        Api.Res moved = c.o().postIfMatch("/api/v1/channel-accounts/" + c.accA() + "/attribution-changes",
                Map.of("business_entity_id", c.beB().toString(), "reason", "사업 양수도"), v);
        assertThat(moved.status()).as(moved.body().toString()).isEqualTo(200);
        Instant later = Instant.now().plusSeconds(1);
        String a2 = created(importOrders(c.o(), c.accA(), order("ORD-2", later, later, 1, 0, "P-100")));
        assertThat(c.o().get("/api/v1/orders/" + a1).data().get("order").get("business_entity_id").asString()).isEqualTo(c.beA().toString());
        assertThat(c.o().get("/api/v1/orders/" + a2).data().get("order").get("business_entity_id").asString()).isEqualTo(c.beB().toString());
        assertThatThrownBy(() -> jdbc.sql("update sales_order set business_entity_id = :b where id = :id").param("b", c.beB()).param("id", UUID.fromString(a1)).update())
                .rootCause().hasMessageContaining("immutable");

        // 사업자 A 만 보는 사용자: A 귀속 주문만, B 주문 직접 조회는 404, B 지정은 거부
        Api viewer = fx.member(c.ws(), "VIEWER");
        fx.setPolicy(c.o(), viewer, null, List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", c.beA(), "ALLOW")));
        JsonNode list = viewer.get("/api/v1/orders").data();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asString()).isEqualTo(a1);
        assertThat(viewer.get("/api/v1/orders/" + b1).status()).isEqualTo(404);
        assertThat(viewer.get("/api/v1/orders?business_entity_id=" + c.beB()).errorCode()).isEqualTo("ACCESS_SCOPE_DENIED");
        // 판매계정 범위: B 계정 제외
        Api ops = fx.member(c.ws(), "ORDER_MANAGER");
        fx.setPolicy(c.o(), ops, null, List.of(Fixtures.deny("CHANNEL_ACCOUNT", c.accB())));
        List<String> ids = new ArrayList<>();
        ops.get("/api/v1/orders").data().forEach(n -> ids.add(n.get("id").asString()));
        assertThat(ids).containsExactlyInAnyOrder(a1, a2);
        // 다른 운영그룹에는 보이지 않는다
        assertThat(fx.workspace().owner().get("/api/v1/orders/" + a1).status()).isEqualTo(404);
    }

    @Test
    void T10_duplicateIgnored_staleIgnored_cancelOnlyIncreases_fullCancelCancelsOrder() {
        Ctx c = ctx();
        mapListing(c, "P-100");
        Instant t0 = Instant.now();
        Map<String, Object> first = order("ORD-9", t0, t0, 2, 0, "P-100");
        String id = created(importOrders(c.o(), c.accA(), first));
        JsonNode dup = importOrders(c.o(), c.accA(), first);
        assertThat(dup.get("duplicate").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from order_raw where order_id = :o").param("o", UUID.fromString(id)).query(Integer.class).single()).isEqualTo(1);

        // 1개 취소(더 새로운 변경)
        JsonNode upd = importOrders(c.o(), c.accA(), order("ORD-9", t0, t0.plusSeconds(60), 2, 1, "P-100"));
        assertThat(upd.get("updated").asInt()).isEqualTo(1);
        JsonNode d = c.o().get("/api/v1/orders/" + id).data();
        assertThat(d.get("items").get(0).get("canceled_qty").asInt()).isEqualTo(1);
        assertThat(d.get("items").get(0).get("active_qty").asInt()).isEqualTo(1);
        // 늦게 도착한 오래된 원본(취소 0) → 보관만
        JsonNode stale = importOrders(c.o(), c.accA(), order("ORD-9", t0, t0.plusSeconds(30), 2, 0, "P-100"));
        assertThat(stale.get("stale").asInt()).isEqualTo(1);
        assertThat(c.o().get("/api/v1/orders/" + id).data().get("items").get(0).get("canceled_qty").asInt()).isEqualTo(1);
        // 더 새로운데 취소가 줄어든 값 → 반영하지 않고 확인 업무
        importOrders(c.o(), c.accA(), order("ORD-9", t0, t0.plusSeconds(90), 2, 0, "P-100"));
        assertThat(c.o().get("/api/v1/orders/" + id).data().get("items").get(0).get("canceled_qty").asInt()).isEqualTo(1);
        assertThat(c.o().get("/api/v1/work-items?item_type=ORDER_EXTERNAL_ANOMALY&status=OPEN").data()).hasSize(1);
        // 전체 취소 → 주문 취소
        importOrders(c.o(), c.accA(), order("ORD-9", t0, t0.plusSeconds(120), 2, 2, "P-100"));
        d = c.o().get("/api/v1/orders/" + id).data();
        assertThat(d.get("order").get("status").asString()).isEqualTo("CANCELLED");
        List<String> events = new ArrayList<>();
        d.get("events").forEach(e -> events.add(e.get("event_type").asString()));
        assertThat(events).contains("ORDER_CREATED", "ITEM_CANCELED", "RAW_IGNORED_STALE", "STATUS_CHANGED");
        assertThat(d.get("raws")).hasSize(5); // 생성·취소1·역순·취소감소·전체취소(중복 1건은 저장 안 함)
        assertThatThrownBy(() -> jdbc.sql("update order_item set canceled_qty = 3 where order_id = :o").param("o", UUID.fromString(id)).update())
                .rootCause().hasMessageContaining("order_item");
    }

    @Test
    void unmappedItemHolds_listingAutoCreated_mappingInChannelListingsReleasesHold_amountMismatch_manualHold() {
        Ctx c = ctx();
        Instant now = Instant.now();
        String id = created(importOrders(c.o(), c.accA(), order("ORD-5", now, now, 1, 0, "P-NEW")));
        JsonNode d = c.o().get("/api/v1/orders/" + id).data();
        assertThat(d.get("order").get("on_hold").asBoolean()).isTrue();
        assertThat(d.get("order").get("hold_reasons").get(0).asString()).isEqualTo("UNMAPPED_ITEM");
        assertThat(d.get("items").get(0).get("mapping_status").asString()).isEqualTo("UNMAPPED");
        assertThat(c.o().get("/api/v1/work-items?item_type=ORDER_ON_HOLD&status=OPEN").data()).hasSize(1);
        assertThat(c.o().get("/api/v1/orders?on_hold=true&hold_reason=UNMAPPED_ITEM").data()).hasSize(1);
        // 주문에서 채널상품이 미연결로 만들어졌다 → 채널상품 화면에서 SKU 연결 → 자동 재검증
        JsonNode listings = c.o().get("/api/v1/channel-listings?channel_account_id=" + c.accA() + "&mapping=UNMAPPED").data();
        assertThat(listings).hasSize(1);
        c.o().post("/api/v1/channel-listings/" + listings.get(0).get("id").asString() + "/mapping", Map.of("sku_id", c.sku().toString()));
        drainAsync();
        d = c.o().get("/api/v1/orders/" + id).data();
        assertThat(d.get("order").get("on_hold").asBoolean()).isFalse();
        assertThat(d.get("items").get(0).get("sku_code").asString()).isEqualTo("AMP-30");
        assertThat(c.o().get("/api/v1/work-items?item_type=ORDER_ON_HOLD&status=OPEN").data()).isEmpty();

        // 금액 불일치 → 보류(HIGH)
        Map<String, Object> bad = order("ORD-6", now, now, 1, 0, "P-NEW");
        bad.put("total_amount", 99999);
        String badId = created(importOrders(c.o(), c.accA(), bad));
        assertThat(c.o().get("/api/v1/orders/" + badId).data().get("order").get("hold_reasons").get(0).asString()).isEqualTo("AMOUNT_MISMATCH");
        // 시스템 보류는 직접 풀 수 없다
        int v = c.o().get("/api/v1/orders/" + badId).data().get("version").asInt();
        assertThat(c.o().postIfMatch("/api/v1/orders/" + badId + "/release-hold", Map.of(), v).errorCode()).isEqualTo("ORDER_HOLD_NOT_MANUAL");
        // 직접 보류·해제(사유 필수, 버전 확인)
        v = c.o().get("/api/v1/orders/" + id).data().get("version").asInt();
        assertThat(c.o().postIfMatch("/api/v1/orders/" + id + "/hold", Map.of("note", " "), v).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(c.o().postIfMatch("/api/v1/orders/" + id + "/hold", Map.of("note", "고객 요청 — 주소 확인"), v - 1).status()).isEqualTo(409);
        JsonNode held = c.o().postIfMatch("/api/v1/orders/" + id + "/hold", Map.of("note", "고객 요청 — 주소 확인"), v).data();
        assertThat(held.get("order").get("hold_reasons").get(0).asString()).isEqualTo("MANUAL");
        // 재검증해도 사람이 건 보류는 유지
        JsonNode reval = c.o().post("/api/v1/orders/" + id + "/revalidate", Map.of()).data();
        assertThat(reval.get("order").get("on_hold").asBoolean()).isTrue();
        JsonNode released = c.o().postIfMatch("/api/v1/orders/" + id + "/release-hold", Map.of(), reval.get("version").asInt()).data();
        assertThat(released.get("order").get("on_hold").asBoolean()).isFalse();
        // 권한: 조회 전용은 보류 불가
        Api viewer = fx.member(c.ws(), "VIEWER");
        assertThat(viewer.postIfMatch("/api/v1/orders/" + id + "/hold", Map.of("note", "x"), released.get("version").asInt()).errorCode()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void personalData_maskedByDefault_encryptedAtRest_revealNeedsPermissionReasonAndAudit_rejectedRawKept() {
        Ctx c = ctx();
        mapListing(c, "P-100");
        Instant now = Instant.now();
        String id = created(importOrders(c.o(), c.accA(), order("ORD-7", now, now, 1, 0, "P-100")));
        JsonNode d = c.o().get("/api/v1/orders/" + id).data();
        assertThat(d.get("order").get("recipient_name_masked").asString()).isEqualTo("홍*동");
        assertThat(d.get("recipient_phone_masked").asString()).isEqualTo("010-****-5678");
        assertThat(d.get("address_masked").asString()).isEqualTo("서울특별시 강남구 ***");
        assertThat(d.get("raws").get(0).get("payload_masked").toString()).doesNotContain("1234-5678").doesNotContain("테헤란로");
        String stored = jdbc.sql("select encode(payload_cipher, 'escape') from order_raw where order_id = :o").param("o", UUID.fromString(id)).query(String.class).single();
        assertThat(stored).doesNotContain("테헤란로");
        Api md = fx.member(c.ws(), "MD");
        assertThat(md.post("/api/v1/orders/" + id + "/pii", Map.of("reason", "배송 문의")).errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(c.o().post("/api/v1/orders/" + id + "/pii", Map.of("reason", "")).errorCode()).isEqualTo("VALIDATION_FAILED");
        JsonNode pii = c.o().post("/api/v1/orders/" + id + "/pii", Map.of("reason", "배송지 확인 문의(CS-11)")).data();
        assertThat(pii.get("recipient").get("address1").asString()).isEqualTo("서울특별시 강남구 테헤란로 152");
        assertThat(jdbc.sql("select count(*) from audit_log where workspace_id = :ws and action = 'ORDER_PII_REVEALED'").param("ws", c.ws().id())
                .query(Integer.class).single()).isEqualTo(1);

        // 형식 오류·없는 판매단위·귀속 없는 시각 → 거절 원본 보관 + 업무함, 주문은 만들지 않음
        Map<String, Object> noUnit = order("ORD-8", now, now, 1, 0, "P-100");
        noUnit.put("sales_unit_key", "SHOP-99");
        JsonNode rej = importOrders(c.o(), c.accA(), noUnit, order("ORD-OLD", now.minus(30, ChronoUnit.DAYS), now, 1, 0, "P-100"));
        assertThat(rej.get("rejected").asInt()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from order_raw where workspace_id = :ws and result = 'REJECTED'").param("ws", c.ws().id()).query(Integer.class).single())
                .isEqualTo(2);
        assertThat(c.o().get("/api/v1/work-items?item_type=ORDER_INGEST_REJECTED&status=OPEN").data()).hasSize(2);
    }

    @Test
    void orderFetchJob_viaMockConnector_paginates_isIdempotent_tracksWatermark_requiresCapability() {
        Ctx c = ctx();
        mapListing(c, "P-100");
        Api.Res conn = c.o().post("/api/v1/integrations/connections", Map.of("target_type", "CHANNEL_ACCOUNT", "target_id", c.accA().toString(),
                "connector_code", "mock-channel", "display_name", "네이버 모의", "auth_type", "API_KEY", "credential", Map.of("api_key", "k")));
        UUID connId = conn.id();
        assertThat(c.o().post("/api/v1/integrations/" + connId + "/sync-jobs", Map.of("job_type", "ORDER_FETCH")).errorCode()).isEqualTo("CAPABILITY_NOT_AVAILABLE");
        c.o().put("/api/v1/integrations/connections/" + connId + "/capabilities/ORDER/READ", Map.of("support_level", "OFFICIAL_DOCUMENTED",
                "account_verification", "VERIFIED", "evidence_ref", "테스트 계정 주문 조회 응답"), 1);
        c.o().post("/api/v1/integrations/" + connId + "/sync-jobs", Map.of());
        drainAsync();
        Instant now = Instant.now();
        mockControl.setOrders(connId, List.of(order("API-1", now, now, 1, 0, "P-100"), order("API-2", now, now, 2, 0, "P-100"),
                order("API-3", now, now, 1, 0, "P-NEW")));
        for (int round = 0; round < 2; round++) {
            Api.Res job = c.o().post("/api/v1/integrations/" + connId + "/sync-jobs", Map.of("job_type", "ORDER_FETCH"));
            assertThat(job.status()).as(job.body().toString()).isEqualTo(202);
            drainAsync();
            JsonNode j = c.o().get("/api/v1/jobs/" + job.data().get("job_id").asString()).data();
            assertThat(j.get("status").asString()).as(j.toString()).isEqualTo("SUCCEEDED");
            assertThat(j.get("result").get("row_count").asInt()).isEqualTo(3);
            assertThat(j.get("result").get("outcomes").get(round == 0 ? "CREATED" : "DUPLICATE").asInt()).isEqualTo(3);
        }
        assertThat(c.o().get("/api/v1/orders?channel_account_id=" + c.accA()).data()).hasSize(3);
        assertThat(c.o().get("/api/v1/orders?on_hold=true").data()).hasSize(1);
        assertThat(jdbc.sql("select watermark from integration_sync_state where connection_id = :c").param("c", connId).query(Object.class).optional()).isPresent();
        JsonNode raw = c.o().get("/api/v1/orders/" + c.o().get("/api/v1/orders?q=API-1").data().get(0).get("id").asString()).data().get("raws").get(0);
        assertThat(raw.get("source").asString()).isEqualTo("API");
        assertThat(raw.get("mapping_version").asString()).isEqualTo("mock-order-mapping-v1");
    }
}
