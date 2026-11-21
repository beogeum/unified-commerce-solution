package com.commerceos.catalog;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerceos.shared.db.TenantContext;
import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** M2 C3: 채널상품 식별·매핑 이력·충돌·미연결 업무함·외부 ID 변경(FR-CAT-03, CLAUDE.md 판매계정+판매단위+외부키). */
class ChannelListingTest extends IntegrationTest {
    @Autowired
    ChannelListingService listings;

    record Ctx(Fixtures.Ws ws, UUID beA, UUID beB, UUID naverA, UUID unitA, UUID naverB, UUID unitB, UUID cafe24, UUID shop1, UUID shop2,
               UUID amp, UUID clf) {
    }

    UUID unit(Api api, UUID account, int index) {
        return UUID.fromString(api.get("/api/v1/channel-accounts/" + account + "/sales-units").data().get(index).get("id").asString());
    }

    Ctx ctx() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        UUID beA = fx.businessEntity(o, "A 코스메틱");
        UUID beB = fx.businessEntity(o, "B 리빙");
        UUID naverA = fx.channelAccount(o, beA, "NAVER_SMARTSTORE", "A 공식스토어");
        UUID naverB = fx.channelAccount(o, beB, "NAVER_SMARTSTORE", "B 스토어");
        UUID cafe24 = fx.channelAccount(o, beB, "CAFE24", "B 카페24", "mallB", Map.of("external_unit_key", "1", "display_name", "기본몰"));
        o.post("/api/v1/channel-accounts/" + cafe24 + "/sales-units", Map.of("external_unit_key", "2", "display_name", "영문몰"));
        UUID product = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "AMP", "name", "비타 앰플"))
                .data().get("product").get("id").asString());
        UUID amp = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "AMP-30", "sku_type", "NORMAL",
                "barcode", "8801234567001")).id();
        UUID clf = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "CLF-150", "sku_type", "NORMAL")).id();
        JsonNode cafeUnits = o.get("/api/v1/channel-accounts/" + cafe24 + "/sales-units").data();
        UUID shop1 = null;
        UUID shop2 = null;
        for (JsonNode u : cafeUnits) {
            if ("1".equals(u.get("external_unit_key").asString())) {
                shop1 = UUID.fromString(u.get("id").asString());
            } else {
                shop2 = UUID.fromString(u.get("id").asString());
            }
        }
        return new Ctx(ws, beA, beB, naverA, unit(o, naverA, 0), naverB, unit(o, naverB, 0), cafe24, shop1, shop2, amp, clf);
    }

    Api.Res listing(Api api, UUID unit, String product, String option, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("sales_unit_id", unit.toString(), "external_product_id", product,
                "external_option_id", option, "title", "비타 앰플 30ml"));
        body.putAll(extra);
        return api.post("/api/v1/channel-listings", body);
    }

    Api.Res map(Api api, UUID listing, UUID sku, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("sku_id", sku.toString()));
        body.putAll(extra);
        return api.post("/api/v1/channel-listings/" + listing + "/mapping", body);
    }

    int openUnmappedItems(UUID ws) {
        return jdbc.sql("select count(*) from work_item where workspace_id = :ws and item_type = 'CHANNEL_LISTING_UNMAPPED' and status = 'OPEN'")
                .param("ws", ws).query(Integer.class).single();
    }

    @Test
    void sameExternalIds_inDifferentAccountsOrSalesUnits_areDifferentListings() {
        Ctx c = ctx();
        Api o = c.ws().owner();
        assertThat(listing(o, c.unitA(), "9921", "2289101", Map.of()).status()).isEqualTo(201);
        assertThat(listing(o, c.unitB(), "9921", "2289101", Map.of()).status()).isEqualTo(201); // 다른 사업자의 같은 채널 계정
        assertThat(listing(o, c.shop1(), "P000041", "", Map.of()).status()).isEqualTo(201);
        assertThat(listing(o, c.shop2(), "P000041", "", Map.of()).status()).isEqualTo(201); // 같은 계정의 다른 판매단위(T03)
        Api.Res dup = listing(o, c.unitA(), "9921", "2289101", Map.of());
        assertThat(dup.errorCode()).isEqualTo("CHANNEL_LISTING_DUPLICATE");
        assertThat(dup.body().get("error").get("details").get("existing_id").asString()).isNotBlank();
        // 옵션 없는 상품(빈 옵션 키)과 옵션 있는 상품은 구분
        assertThat(listing(o, c.unitA(), "9921", "", Map.of()).status()).isEqualTo(201);
        assertThat(o.get("/api/v1/channel-listings?channel_account_id=" + c.naverA()).data()).hasSize(2);
        // 판매단위-판매계정 소속은 DB 가 강제
        assertThatThrownBy(() -> jdbc.sql("""
                insert into channel_listing (id, workspace_id, channel_account_id, sales_unit_id, external_product_id, title, source)
                values (gen_random_uuid(), :ws, :ca, :su, 'X', 'x', 'MANUAL')
                """).param("ws", c.ws().id()).param("ca", c.naverA()).param("su", c.unitB()).update())
                .rootCause().hasMessageContaining("does not belong");
    }

    @Test
    void mapping_keepsHistory_remapRequiresConfirmation_andResolvesByTime() throws Exception {
        Ctx c = ctx();
        Api o = c.ws().owner();
        UUID l = UUID.fromString(listing(o, c.unitA(), "9921", "2289101", Map.of()).data().get("listing").get("id").asString());
        assertThat(openUnmappedItems(c.ws().id())).isEqualTo(1);

        Api.Res first = map(o, l, c.amp(), Map.of());
        assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
        assertThat(first.data().get("warnings").get(0).get("code").asString()).isEqualTo("SKU_NOT_ENABLED_FOR_BUSINESS");
        assertThat(openUnmappedItems(c.ws().id())).isZero(); // 모두 연결되면 자동 완료
        Instant afterFirst = Instant.now();
        // 같은 연결 반복은 이력을 늘리지 않음(멱등)
        map(o, l, c.amp(), Map.of());
        Thread.sleep(20);

        Api.Res remap = map(o, l, c.clf(), Map.of());
        assertThat(remap.errorCode()).isEqualTo("MAPPING_REMAP_CONFIRMATION_REQUIRED"); // 처음 바꿀 때도 확인 — 이미 AMP-30 이력이 있음
        assertThat(map(o, l, c.clf(), Map.of("confirm_remap", true)).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(map(o, l, c.clf(), Map.of("confirm_remap", true, "reason", "옵션 착오 정정", "quantity", 2)).status()).isEqualTo(200);

        JsonNode detail = o.get("/api/v1/channel-listings/" + l).data();
        assertThat(detail.get("history")).hasSize(2);
        assertThat(detail.get("listing").get("current_mapping").get("sku_code").asString()).isEqualTo("CLF-150");
        assertThat(detail.get("listing").get("distinct_sku_count").asInt()).isEqualTo(2);
        assertThat(o.get("/api/v1/channel-listings?mapping=REMAPPED").data()).hasSize(1);

        // 과거 시점 주문은 그때의 SKU 로 해석(M4 주문 수집용)
        TenantContext.setWorkspace(c.ws().id());
        try {
            var asOwner = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    actorFor(c.ws().id(), o.memberId), null, List.of());
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(asOwner);
            assertThat(listings.resolve(c.unitA(), "9921", "2289101", afterFirst).orElseThrow().skuId()).isEqualTo(c.amp());
            var now = listings.resolve(c.unitA(), "9921", "2289101", Instant.now().plusSeconds(1)).orElseThrow();
            assertThat(now.skuId()).isEqualTo(c.clf());
            assertThat(now.quantity()).isEqualTo(2);
            assertThat(listings.resolve(c.unitB(), "9921", "2289101", Instant.now())).isEmpty(); // 다른 계정은 별개
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
        // 이력 불변·겹침 금지(DB)
        assertThatThrownBy(() -> jdbc.sql("update channel_listing_mapping set sku_id = :s where listing_id = :l").param("s", c.amp()).param("l", l).update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from channel_listing_mapping where listing_id = :l").param("l", l).update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("""
                insert into channel_listing_mapping (id, workspace_id, listing_id, sku_id, valid_from, valid_to, match_basis)
                values (gen_random_uuid(), :ws, :l, :s, now() - interval '1 second', now() + interval '1 hour', 'MANUAL')
                """).param("ws", c.ws().id()).param("l", l).param("s", c.amp()).update()).rootCause().hasMessageContaining("exclu");
    }

    com.commerceos.identity.access.CurrentActor actorFor(UUID ws, UUID member) {
        return new com.commerceos.identity.access.CurrentActor(ws, member, null, "test", java.util.Set.of(), java.util.Set.of(), 1, null, "NOT_REQUIRED");
    }

    @Test
    void unmappedWorkItem_isOnePerSalesUnit_countsOccurrences_andReopensOnUnmap() {
        Ctx c = ctx();
        Api o = c.ws().owner();
        UUID l1 = UUID.fromString(listing(o, c.unitA(), "1", "", Map.of()).data().get("listing").get("id").asString());
        UUID l2 = UUID.fromString(listing(o, c.unitA(), "2", "", Map.of()).data().get("listing").get("id").asString());
        JsonNode items = o.get("/api/v1/work-items?item_type=CHANNEL_LISTING_UNMAPPED").data();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("occurrence_count").asInt()).isEqualTo(2);
        assertThat(items.get(0).get("related").get("unmapped_count").asInt()).isEqualTo(1); // 첫 등록 시점 값(관련 정보는 최초 기록)
        map(o, l1, c.amp(), Map.of());
        assertThat(openUnmappedItems(c.ws().id())).isEqualTo(1); // 아직 1건 남음
        map(o, l2, c.clf(), Map.of());
        assertThat(openUnmappedItems(c.ws().id())).isZero();
        assertThat(o.delete("/api/v1/channel-listings/" + l2 + "/mapping?reason=" + java.net.URLEncoder.encode("판매 종료", java.nio.charset.StandardCharsets.UTF_8))
                .status()).isEqualTo(200);
        assertThat(openUnmappedItems(c.ws().id())).isEqualTo(1);
        assertThat(o.get("/api/v1/channel-listings?mapping=UNMAPPED").data()).hasSize(1);
    }

    @Test
    void suggestions_rankBarcodeAndExternalSkuCodeAboveName() {
        Ctx c = ctx();
        Api o = c.ws().owner();
        UUID byBarcode = UUID.fromString(listing(o, c.unitA(), "1", "", Map.of("barcode", "8801234567001", "title", "아무 이름")).data().get("listing").get("id").asString());
        JsonNode s1 = o.get("/api/v1/channel-listings/" + byBarcode + "/suggestions").data();
        assertThat(s1.get(0).get("sku_code").asString()).isEqualTo("AMP-30");
        assertThat(s1.get(0).get("basis").asString()).isEqualTo("BARCODE");
        assertThat(s1.get(0).get("confidence").asString()).isEqualTo("HIGH");
        UUID byCode = UUID.fromString(listing(o, c.unitA(), "2", "", Map.of("external_sku_code", "clf-150", "title", "클렌징폼")).data().get("listing").get("id").asString());
        assertThat(o.get("/api/v1/channel-listings/" + byCode + "/suggestions").data().get(0).get("sku_code").asString()).isEqualTo("CLF-150");
        UUID byName = UUID.fromString(listing(o, c.unitA(), "3", "", Map.of("title", "[특가] 비타 앰플 30ml")).data().get("listing").get("id").asString());
        JsonNode s3 = o.get("/api/v1/channel-listings/" + byName + "/suggestions").data();
        assertThat(s3.get(0).get("confidence").asString()).isEqualTo("LOW");
    }

    @Test
    void externalIdChange_movesMapping_andKeepsOldListingAndHistory() {
        Ctx c = ctx();
        Api o = c.ws().owner();
        UUID old = UUID.fromString(listing(o, c.unitA(), "RG-1", "OPT-1", Map.of()).data().get("listing").get("id").asString());
        map(o, old, c.amp(), Map.of("quantity", 2));
        Api.Res changed = o.post("/api/v1/channel-listings/" + old + "/external-id-change",
                Map.of("external_product_id", "RG-1", "external_option_id", "OPT-90011", "reason", "채널 옵션ID 변경 공지"));
        assertThat(changed.status()).as(changed.body().toString()).isEqualTo(200);
        JsonNode now = changed.data();
        assertThat(now.get("listing").get("external_option_id").asString()).isEqualTo("OPT-90011");
        assertThat(now.get("listing").get("current_mapping").get("sku_code").asString()).isEqualTo("AMP-30");
        assertThat(now.get("listing").get("current_mapping").get("quantity").asInt()).isEqualTo(2);
        assertThat(now.get("listing").get("current_mapping").get("match_basis").asString()).isEqualTo("ID_CHANGE");
        assertThat(now.get("previous_listing_id").asString()).isEqualTo(old.toString());
        JsonNode prev = o.get("/api/v1/channel-listings/" + old).data();
        assertThat(prev.get("listing").get("status").asString()).isEqualTo("REPLACED");
        assertThat(prev.get("listing").get("current_mapping").isNull()).isTrue();
        assertThat(prev.get("history")).hasSize(1);
        assertThat(map(o, old, c.clf(), Map.of()).errorCode()).isEqualTo("CHANNEL_LISTING_NOT_ACTIVE");
        assertThat(openUnmappedItems(c.ws().id())).isZero();
    }

    @Test
    void scope_permissions_andSkuEligibility() throws Exception {
        Ctx c = ctx();
        Api o = c.ws().owner();
        UUID la = UUID.fromString(listing(o, c.unitA(), "A1", "", Map.of()).data().get("listing").get("id").asString());
        UUID lb = UUID.fromString(listing(o, c.unitB(), "B1", "", Map.of()).data().get("listing").get("id").asString());
        Api md = fx.member(c.ws(), "MD");
        assertThat(o.put("/api/v1/users/" + md.userId + "/access-policy", Map.of("permission_overrides", List.of(),
                "scopes", List.of(scope("CHANNEL_ACCOUNT", "READ", "SELECTED", c.naverA(), "ALLOW"))), 1).status()).isEqualTo(200);
        assertThat(md.get("/api/v1/channel-listings").data()).hasSize(1);
        assertThat(md.get("/api/v1/channel-listings/" + lb).status()).isEqualTo(404);
        assertThat(md.get("/api/v1/channel-listings?channel_account_id=" + c.naverB()).errorCode()).isEqualTo("ACCESS_SCOPE_DENIED");
        assertThat(map(md, lb, c.amp(), Map.of()).status()).isEqualTo(404);
        assertThat(map(md, la, c.amp(), Map.of()).status()).isEqualTo(200);
        Api viewer = fx.member(c.ws(), "VIEWER");
        assertThat(map(viewer, lb, c.amp(), Map.of()).errorCode()).isEqualTo("PERMISSION_DENIED");

        // 판매 불가·중지 SKU 에는 연결 불가
        UUID product = UUID.fromString(o.get("/api/v1/skus/" + c.amp()).data().get("product_id").asString());
        UUID gift = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", "GIFT-1", "sku_type", "GIFT")).id();
        assertThat(map(o, lb, gift, Map.of()).errorCode()).isEqualTo("SKU_NOT_MAPPABLE");
        assertThat(map(o, lb, c.amp(), Map.of("quantity", 0)).errorCode()).isEqualTo("VALIDATION_FAILED");

        // 같은 미연결 채널상품을 동시에 다른 SKU 로 연결 → 하나만 성공, 나머지는 재연결 확인 요구
        UUID race = UUID.fromString(listing(o, c.unitB(), "RACE", "", Map.of()).data().get("listing").get("id").asString());
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID target = i % 2 == 0 ? c.amp() : c.clf();
            tasks.add(() -> map(o, race, target, Map.of()).status());
        }
        List<Integer> st = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            st.add(f.get());
        }
        pool.shutdown();
        assertThat(jdbc.sql("select count(*) from channel_listing_mapping where listing_id = :l").param("l", race).query(Integer.class).single())
                .isEqualTo(1);
        assertThat(st).contains(200).doesNotContain(500);
        // 다른 운영그룹
        assertThat(ctx().ws().owner().get("/api/v1/channel-listings/" + la).status()).isEqualTo(404);
    }
}
