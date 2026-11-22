package com.commerceos.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** M3 I4: 외부 재고 스냅샷 — 수량 의미·워터마크·반영 확보 재차감 금지(T08)·판단 불가 시 보수적 제한(T09)·비가용 대사(T38). */
class ExternalInventoryTest extends IntegrationTest {
    @Autowired
    ExternalInventoryWatch watch;

    record Ctx(Fixtures.Ws ws, Api o, UUID be, UUID node, UUID sku, UUID sku2, UUID bundle, UUID shipper) {
    }

    Ctx ctx(String semantic) {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        UUID be = fx.businessEntity(o, "에이상사");
        UUID product = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "AMP", "name", "앰플")).data().get("product").get("id").asString());
        UUID sku = sku(o, product, "AMP-30", "NORMAL");
        UUID sku2 = sku(o, product, "AMP-50", "NORMAL");
        UUID bundle = sku(o, product, "AMP-SET", "BUNDLE");
        Api.Res n = o.post("/api/v1/fulfillment-nodes", Map.of("code", "FASSTO-YI", "name", "파스토 용인", "logistics_provider_code", "FASSTO", "cutoff_time", "15:00"));
        assertThat(n.status()).as(n.body().toString()).isEqualTo(201);
        UUID shipper = fx.shipperAccount(o, be, "FASSTO", "SHIP-A");
        assertThat(o.post("/api/v1/fulfillment-nodes/" + n.id() + "/shippers", Map.of("shipper_account_id", shipper.toString())).status()).isEqualTo(200);
        if (semantic != null) {
            setSemantic(o, n.id(), semantic);
        }
        return new Ctx(ws, o, be, n.id(), sku, sku2, bundle, shipper);
    }

    UUID sku(Api o, UUID product, String code, String type) {
        Api.Res r = o.post("/api/v1/skus", Map.of("product_id", product.toString(), "sku_code", code, "sku_type", type));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.id();
    }

    void setSemantic(Api o, UUID node, String semantic) {
        int v = o.get("/api/v1/fulfillment-nodes/" + node).data().get("version").asInt();
        Api.Res r = o.patch("/api/v1/fulfillment-nodes/" + node, Map.of("quantity_semantic", semantic, "quantity_semantic_evidence", "물류사 Open API 가이드·테스트 계정 응답 캡처"), v);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    }

    void patchNode(Api o, UUID node, Map<String, Object> body) {
        int v = o.get("/api/v1/fulfillment-nodes/" + node).data().get("version").asInt();
        Api.Res r = o.patch("/api/v1/fulfillment-nodes/" + node, body, v);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    }

    static Map<String, Object> line(String code, int qty, boolean sellable) {
        return Map.of("external_sku_code", code, "quantity", qty, "sellable", sellable);
    }

    Api.Res snapshot(Ctx c, String key, Instant asOf, boolean complete, List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("node_id", c.node().toString());
        body.put("business_entity_id", c.be().toString());
        body.put("source_key", key);
        if (asOf != null) {
            body.put("as_of", asOf.toString());
        }
        body.put("complete", complete);
        body.put("lines", lines);
        return c.o().post("/api/v1/inventory/external-snapshots", body);
    }

    JsonNode applied(Ctx c, String key, Instant asOf, List<Map<String, Object>> lines) {
        Api.Res r = snapshot(c, key, asOf, false, lines);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data();
    }

    JsonNode at(Ctx c, UUID sku) {
        JsonNode a = c.o().get("/api/v1/inventory/availability?business_entity_id=" + c.be() + "&sku_id=" + sku).data();
        for (JsonNode n : a.get("nodes")) {
            if (n.get("node_id").asString().equals(c.node().toString())) {
                return n;
            }
        }
        throw new AssertionError("no position");
    }

    JsonNode result(JsonNode detail, UUID sku) {
        for (JsonNode r : detail.get("results")) {
            if (r.get("sku_id").asString().equals(sku.toString())) {
                return r;
            }
        }
        throw new AssertionError("no result");
    }

    String reserve(Ctx c, String ref, UUID sku, int qty) {
        Api.Res r = c.o().post("/api/v1/inventory/reservations", Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(),
                "demand_type", "ORDER_LINE", "demand_ref", ref, "lines", List.of(Map.of("sku_id", sku.toString(), "quantity", qty))));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data().get(0).get("id").asString();
    }

    void act(Ctx c, String id, String action, Map<String, Object> body) {
        Api.Res r = c.o().post("/api/v1/inventory/reservations/" + id + "/" + action, body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    }

    List<JsonNode> items(Api o, String type, String status) {
        List<JsonNode> out = new ArrayList<>();
        o.get("/api/v1/work-items?item_type=" + type + (status == null ? "" : "&status=" + status)).data().forEach(out::add);
        return out;
    }

    void assertVerified(Api o) {
        Api.Res v = o.get("/api/v1/inventory/verification");
        assertThat(v.data()).as(v.body().toString()).isEmpty();
    }

    @Test
    void T08_availableSemantic_reservationAlreadyReflected_isNotDeductedAgain() {
        Ctx c = ctx(null);
        // 근거 없이 수량 의미를 '확인됨'으로 둘 수 없다
        int v = c.o().get("/api/v1/fulfillment-nodes/" + c.node()).data().get("version").asInt();
        assertThat(c.o().patch("/api/v1/fulfillment-nodes/" + c.node(), Map.of("quantity_semantic", "AVAILABLE"), v).errorCode()).isEqualTo("VALIDATION_FAILED");
        setSemantic(c.o(), c.node(), "AVAILABLE");
        assertThat(c.o().get("/api/v1/fulfillment-nodes/" + c.node()).data().get("quantity_semantic_verified_at").isNull()).isFalse();

        Instant t0 = Instant.now().minus(30, ChronoUnit.MINUTES);
        JsonNode s1 = applied(c, "S1", t0, List.of(line("AMP-30", 10, true)));
        assertThat(s1.get("snapshot").get("status").asString()).isEqualTo("APPLIED");
        assertThat(at(c, c.sku()).get("atp").asInt()).isEqualTo(10);

        String ord1 = reserve(c, "ORD-1", c.sku(), 2);
        act(c, ord1, "allocate", Map.of());
        act(c, ord1, "external-ack", Map.of("acknowledged_at", Instant.now().minus(20, ChronoUnit.MINUTES).toString(), "external_ref", "FASSTO-OUT-1"));
        assertThat(c.o().post("/api/v1/inventory/reservations/" + ord1 + "/external-ack",
                Map.of("acknowledged_at", Instant.now().minus(19, ChronoUnit.MINUTES).toString())).errorCode()).isEqualTo("EXTERNAL_ACK_EXISTS");
        // 물류사는 출고지시를 받은 ORD-1 2개를 가용에서 이미 뺐다: 가용 8
        JsonNode s2 = applied(c, "S2", Instant.now().minus(15, ChronoUnit.MINUTES), List.of(line("AMP-30", 8, true)));
        JsonNode r2 = result(s2, c.sku());
        assertThat(r2.get("reflected_quantity").asInt()).isEqualTo(2);
        assertThat(r2.get("sellable_after").asInt()).isEqualTo(10);
        assertThat(at(c, c.sku()).get("atp").asInt()).isEqualTo(8); // 재차감했다면 6

        // 아직 물류사에 전달되지 않은 ORD-2 는 외부 가용에 반영되지 않았으므로 내부에서 뺀다
        reserve(c, "ORD-2", c.sku(), 1);
        applied(c, "S3", Instant.now().minus(10, ChronoUnit.MINUTES), List.of(line("AMP-30", 8, true)));
        assertThat(at(c, c.sku()).get("atp").asInt()).isEqualTo(7);

        // ORD-1 출고 확정 후 받은 스냅샷: 이미 출고된 것은 반영·차감 대상 아님
        act(c, ord1, "pick", Map.of());
        act(c, ord1, "consume", Map.of());
        JsonNode n = at(c, c.sku());
        assertThat(n.get("sellable_on_hand").asInt()).isEqualTo(8);
        assertThat(n.get("atp").asInt()).isEqualTo(7);
        JsonNode s4 = applied(c, "S4", Instant.now().plusSeconds(1), List.of(line("AMP-30", 8, true)));
        assertThat(result(s4, c.sku()).get("reflected_quantity").asInt()).isZero();
        assertThat(at(c, c.sku()).get("atp").asInt()).isEqualTo(7);
        assertThat(at(c, c.sku()).get("restricted").asBoolean()).isFalse();
        assertVerified(c.o());
    }

    @Test
    void onHandSemantic_shipmentsAfterWatermarkAreSubtracted() {
        Ctx c = ctx("ON_HAND");
        applied(c, "S1", Instant.now().minus(30, ChronoUnit.MINUTES), List.of(line("AMP-30", 10, true)));
        String r = reserve(c, "ORD-1", c.sku(), 2);
        assertThat(at(c, c.sku()).get("atp").asInt()).isEqualTo(8);
        act(c, r, "allocate", Map.of());
        act(c, r, "pick", Map.of());
        act(c, r, "consume", Map.of());
        // 출고 확정 전 시점(기준시각)의 총재고 10 → 그 뒤 출고된 2 를 빼야 한다
        JsonNode s2 = applied(c, "S2", Instant.now().minus(5, ChronoUnit.MINUTES), List.of(line("AMP-30", 10, true)));
        assertThat(result(s2, c.sku()).get("shipped_after_quantity").asInt()).isEqualTo(2);
        assertThat(at(c, c.sku()).get("sellable_on_hand").asInt()).isEqualTo(8);
        assertThat(at(c, c.sku()).get("atp").asInt()).isEqualTo(8);
        assertVerified(c.o());
    }

    @Test
    void T09_unknownSemanticOrMissingWatermark_conservativeRestriction_andWorkItem() {
        Ctx c = ctx(null);
        patchNode(c.o(), c.node(), Map.of("restricted_sell_buffer", 2));
        JsonNode s1 = applied(c, "S1", Instant.now().minus(30, ChronoUnit.MINUTES), List.of(line("AMP-30", 10, true)));
        assertThat(s1.get("snapshot").get("status").asString()).isEqualTo("APPLIED_RESTRICTED");
        JsonNode n = at(c, c.sku());
        assertThat(n.get("restricted").asBoolean()).isTrue();
        assertThat(n.get("restriction_reason").asString()).isEqualTo("QUANTITY_SEMANTIC_UNKNOWN");
        assertThat(n.get("atp").asInt()).isEqualTo(8); // 버퍼 2 보류
        List<JsonNode> open = items(c.o(), "EXTERNAL_INVENTORY_RESTRICTED", "OPEN");
        assertThat(open).hasSize(1);
        assertThat(open.get(0).get("cause_ko").asString()).contains("총재고인지 가용재고인지");

        // 반영 여부를 모르면 물류사가 접수한 확보도 이미 빠졌다고 가정하지 않는다(보수적)
        String r = reserve(c, "ORD-1", c.sku(), 2);
        act(c, r, "allocate", Map.of());
        act(c, r, "external-ack", Map.of("acknowledged_at", Instant.now().minus(20, ChronoUnit.MINUTES).toString()));
        JsonNode s2 = applied(c, "S2", Instant.now().minus(15, ChronoUnit.MINUTES), List.of(line("AMP-30", 8, true)));
        assertThat(result(s2, c.sku()).get("reflected_quantity").asInt()).isZero();
        assertThat(result(s2, c.sku()).get("certain").asBoolean()).isFalse();
        assertThat(at(c, c.sku()).get("atp").asInt()).isEqualTo(4); // 8 − 확보 2 − 버퍼 2
        Api.Res over = c.o().post("/api/v1/inventory/reservations", Map.of("business_entity_id", c.be().toString(), "node_id", c.node().toString(),
                "demand_type", "ORDER_LINE", "demand_ref", "ORD-2", "lines", List.of(Map.of("sku_id", c.sku().toString(), "quantity", 5))));
        assertThat(over.errorCode()).isEqualTo("INSUFFICIENT_AVAILABLE_STOCK");

        // 수량 의미를 확인해도 기준시각 없는 자료는 판단 불가
        setSemantic(c.o(), c.node(), "AVAILABLE");
        applied(c, "S3", null, List.of(line("AMP-30", 8, true)));
        assertThat(at(c, c.sku()).get("restriction_reason").asString()).isEqualTo("WATERMARK_MISSING");
        // 확인 가능한 자료가 오면 제한 해제 + 업무 자동 종료
        applied(c, "S4", Instant.now().minus(1, ChronoUnit.MINUTES), List.of(line("AMP-30", 8, true)));
        n = at(c, c.sku());
        assertThat(n.get("restricted").asBoolean()).isFalse();
        assertThat(n.get("atp").asInt()).isEqualTo(8); // 가용 8 + 반영된 확보 2 − 확보 2
        assertThat(items(c.o(), "EXTERNAL_INVENTORY_RESTRICTED", "OPEN")).isEmpty();
        assertThat(items(c.o(), "EXTERNAL_INVENTORY_RESTRICTED", "DONE")).hasSize(2); // 사유별(수량 의미 미확인 → 기준시각 없음) 업무가 모두 닫힘

        // 오래된 스냅샷: 감시 작업이 판매 제한으로 돌린다
        jdbc.sql("update inventory_position set external_as_of = now() - interval '2 hours' where node_id = :n").param("n", c.node()).update();
        assertThat(watch.scan()).isGreaterThanOrEqualTo(1);
        n = at(c, c.sku());
        assertThat(n.get("restriction_reason").asString()).isEqualTo("SNAPSHOT_STALE");
        assertThat(n.get("atp").asInt()).isEqualTo(6);
        assertVerified(c.o());
    }

    @Test
    void T38_externalNonSellableDecrease_doesNotDeleteInternalClassification() {
        Ctx c = ctx("AVAILABLE");
        applied(c, "S1", Instant.now().minus(30, ChronoUnit.MINUTES), List.of(line("AMP-30", 5, true), line("AMP-30", 3, false)));
        JsonNode stocks = c.o().get("/api/v1/inventory/stocks?sku_id=" + c.sku() + "&quality_status=UNDETERMINED").data();
        assertThat(stocks.get(0).get("on_hand").asInt()).isEqualTo(3);
        assertThat(stocks.get(0).get("hold_reason").asString()).isEqualTo("EXTERNAL_UNCLASSIFIED");
        assertThat(items(c.o(), "EXTERNAL_INVENTORY_QUALITY", "OPEN")).hasSize(1);
        String undetermined = stocks.get(0).get("id").asString();
        // 내부 보완 분류: 비가용 안에서만 바꿀 수 있다(판매가능 전환은 물류사 동기화로만)
        assertThat(c.o().post("/api/v1/inventory/quality-changes", Map.of("stock_id", undetermined, "quantity", 1, "to_quality_status", "SELLABLE",
                "reason", "x")).errorCode()).isEqualTo("NODE_INVENTORY_EXTERNAL");
        assertThat(c.o().post("/api/v1/inventory/quality-changes", Map.of("stock_id", undetermined, "quantity", 1, "to_quality_status", "DEFECTIVE",
                "reason", "물류사 검수 화면: 용기 파손 없음·내용물 불량")).status()).isEqualTo(201);
        assertThat(c.o().post("/api/v1/inventory/quality-changes", Map.of("stock_id", undetermined, "quantity", 1, "to_quality_status", "DAMAGED",
                "reason", "물류사 검수 화면: 외박스 파손")).status()).isEqualTo(201);

        // 물류사 비가용 3 → 2. 내부 세부분류(미확정1·불량1·파손1) 중 무엇이 빠졌는지 모르므로 바꾸지 않는다
        JsonNode s2 = applied(c, "S2", Instant.now().minus(10, ChronoUnit.MINUTES), List.of(line("AMP-30", 5, true), line("AMP-30", 2, false)));
        JsonNode r = result(s2, c.sku());
        assertThat(r.get("non_sellable_before").asInt()).isEqualTo(3);
        assertThat(r.get("non_sellable_after").asInt()).isEqualTo(3);
        Map<String, Integer> byQuality = new LinkedHashMap<>();
        c.o().get("/api/v1/inventory/stocks?sku_id=" + c.sku()).data().forEach(s -> byQuality.merge(s.get("quality_status").asString(), s.get("on_hand").asInt(), Integer::sum));
        assertThat(byQuality).containsEntry("UNDETERMINED", 1).containsEntry("DEFECTIVE", 1).containsEntry("DAMAGED", 1).containsEntry("SELLABLE", 5);
        List<JsonNode> q = items(c.o(), "EXTERNAL_INVENTORY_QUALITY", "OPEN").stream().filter(i -> i.get("severity").asString().equals("HIGH")).toList();
        assertThat(q).hasSize(1);
        assertThat(q.get(0).get("cause_ko").asString()).contains("바꾸지 않았습니다");

        // 세부분류 없이 '미확정'만 있으면 줄어든 만큼 줄여도 임의 삭제가 아니다
        applied(c, "S3", Instant.now().minus(5, ChronoUnit.MINUTES), List.of(line("AMP-50", 0, true), line("AMP-50", 3, false)));
        applied(c, "S4", Instant.now().minus(4, ChronoUnit.MINUTES), List.of(line("AMP-50", 0, true), line("AMP-50", 1, false)));
        JsonNode s50 = c.o().get("/api/v1/inventory/stocks?sku_id=" + c.sku2()).data();
        assertThat(s50).hasSize(1);
        assertThat(s50.get(0).get("on_hand").asInt()).isEqualTo(1);
        assertVerified(c.o());
    }

    @Test
    void watermark_outOfOrderIgnored_duplicateKeyIdempotent_shortageRestricted_unmappedLinesReported() {
        Ctx c = ctx("AVAILABLE");
        Instant t2 = Instant.now().minus(10, ChronoUnit.MINUTES);
        JsonNode s1 = applied(c, "S1", t2, List.of(line("AMP-30", 8, true), line("NOPE-1", 3, true), line("AMP-SET", 1, true)));
        assertThat(s1.get("snapshot").get("issue_count").asInt()).isEqualTo(2);
        List<String> issues = new ArrayList<>();
        s1.get("lines").forEach(l -> issues.add(l.get("issue").isNull() ? "-" : l.get("issue").asString()));
        assertThat(issues).containsExactly("-", "SKU_UNMAPPED", "SKU_NOT_INVENTORY_MANAGED");
        assertThat(items(c.o(), "EXTERNAL_SKU_UNMAPPED", "OPEN")).hasSize(1);
        int ledgerRows = jdbc.sql("select count(*) from inventory_transaction where workspace_id = :ws").param("ws", c.ws().id()).query(Integer.class).single();

        // 같은 자료 재전송 → 같은 스냅샷, 반영 없음
        JsonNode again = applied(c, "S1", t2, List.of(line("AMP-30", 8, true)));
        assertThat(again.get("snapshot").get("id").asString()).isEqualTo(s1.get("snapshot").get("id").asString());
        // 더 이른 기준시각 자료가 늦게 도착 → 보관만 하고 반영하지 않음
        JsonNode late = applied(c, "S0", t2.minus(5, ChronoUnit.MINUTES), List.of(line("AMP-30", 100, true)));
        assertThat(late.get("snapshot").get("status").asString()).isEqualTo("IGNORED_OUT_OF_ORDER");
        assertThat(late.get("results")).isEmpty();
        assertThat(at(c, c.sku()).get("sellable_on_hand").asInt()).isEqualTo(8);
        assertThat(jdbc.sql("select count(*) from inventory_transaction where workspace_id = :ws").param("ws", c.ws().id()).query(Integer.class).single())
                .isEqualTo(ledgerRows);

        // 과확보: 외부 가용이 확보수량보다 적어짐 → 확보수량까지만, ATP 0, 판매 제한 + 업무(HIGH)
        reserve(c, "ORD-1", c.sku(), 5);
        applied(c, "S2", Instant.now().minus(5, ChronoUnit.MINUTES), List.of(line("AMP-30", 3, true)));
        JsonNode n = at(c, c.sku());
        assertThat(n.get("sellable_on_hand").asInt()).isEqualTo(5);
        assertThat(n.get("atp").asInt()).isZero();
        assertThat(n.get("restriction_reason").asString()).isEqualTo("EXTERNAL_SHORTAGE");
        assertThat(items(c.o(), "EXTERNAL_INVENTORY_RESTRICTED", "OPEN").get(0).get("severity").asString()).isEqualTo("HIGH");

        // 전체 스냅샷: 목록에 없는 SKU 는 0 (확보가 없을 때)
        applied(c, "S3", Instant.now().minus(4, ChronoUnit.MINUTES), List.of(line("AMP-50", 4, true)));
        assertThat(at(c, c.sku2()).get("sellable_on_hand").asInt()).isEqualTo(4);
        Api.Res full = snapshot(c, "S4", Instant.now().minus(3, ChronoUnit.MINUTES), true, List.of(line("AMP-30", 9, true)));
        assertThat(full.status()).isEqualTo(201);
        assertThat(at(c, c.sku2()).get("sellable_on_hand").asInt()).isZero();
        assertThat(at(c, c.sku()).get("restricted").asBoolean()).isFalse();
        assertThat(items(c.o(), "EXTERNAL_SKU_UNMAPPED", "OPEN")).isEmpty(); // 전체 자료에 미연결 행이 없으면 종료

        // 원본 스냅샷·행·결론은 바꿀 수 없다
        assertThatThrownBy(() -> jdbc.sql("update external_inventory_line set quantity = 1 where workspace_id = :ws").param("ws", c.ws().id()).update())
                .rootCause().hasMessageContaining("append-only");
        // 내부 원장 거점에는 외부 스냅샷을 받지 않는다 / 화주계정 없는 사업자 거부
        UUID self = c.o().post("/api/v1/fulfillment-nodes", Map.of("code", "SELF", "name", "자체", "logistics_provider_code", "SELF_WMS", "cutoff_time", "16:00")).id();
        assertThat(c.o().post("/api/v1/inventory/external-snapshots", Map.of("node_id", self.toString(), "business_entity_id", c.be().toString(),
                "source_key", "X", "lines", List.of(line("AMP-30", 1, true)))).errorCode()).isEqualTo("NODE_INVENTORY_INTERNAL");
        UUID other = fx.businessEntity(c.o(), "비상사");
        assertThat(c.o().post("/api/v1/inventory/external-snapshots", Map.of("node_id", c.node().toString(), "business_entity_id", other.toString(),
                "source_key", "X", "lines", List.of(line("AMP-30", 1, true)))).errorCode()).isEqualTo("OWNER_NOT_ON_NODE");
        // 조회 전용은 반영 불가, 조회는 가능
        Api viewer = fx.member(c.ws(), "VIEWER");
        assertThat(viewer.get("/api/v1/inventory/external-snapshots").data().size()).isGreaterThanOrEqualTo(5);
        assertThat(viewer.post("/api/v1/inventory/external-snapshots", Map.of("node_id", c.node().toString(), "business_entity_id", c.be().toString(),
                "source_key", "V", "lines", List.of(line("AMP-30", 1, true)))).errorCode()).isEqualTo("PERMISSION_DENIED");
        assertVerified(c.o());
    }

    @Test
    void fileUpload_storesOriginal_isIdempotentByHash_andRejectsBadRows() {
        Ctx c = ctx("AVAILABLE");
        String csv = "﻿SKU 코드*,수량*,판매가능(Y/N)*,상태,LOT\r\nAMP-30,7,Y,정상,L-1\r\nAMP-30,2,N,불량,L-1\r\n";
        Api.Res r = upload(c, "파스토재고.csv", csv, Instant.now().minus(5, ChronoUnit.MINUTES));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        assertThat(r.data().get("snapshot").get("source").asString()).isEqualTo("FILE");
        assertThat(r.data().get("snapshot").get("file_id").isNull()).isFalse();
        assertThat(at(c, c.sku()).get("sellable_on_hand").asInt()).isEqualTo(7);
        Api.Res again = upload(c, "파스토재고(1).csv", csv, Instant.now().minus(5, ChronoUnit.MINUTES));
        assertThat(again.data().get("snapshot").get("id").asString()).isEqualTo(r.data().get("snapshot").get("id").asString());
        Api.Res bad = upload(c, "bad.csv", "SKU 코드,수량,판매가능\r\nAMP-30,-1,Y\r\nAMP-50,3,몰라\r\n", Instant.now());
        assertThat(bad.errorCode()).isEqualTo("EXTERNAL_FILE_INVALID");
        assertThat(bad.body().get("error").get("details").get("error_count").asInt()).isEqualTo(2);
        assertThat(upload(c, "nocol.csv", "SKU 코드,수량\r\nAMP-30,1\r\n", Instant.now()).errorCode()).isEqualTo("TEMPLATE_COLUMN_MISSING");
    }

    Api.Res upload(Ctx c, String name, String csv, Instant asOf) {
        return c.o().perform(multipart("/api/v1/inventory/external-snapshots/upload")
                .file(new MockMultipartFile("file", name, "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .param("node_id", c.node().toString()).param("business_entity_id", c.be().toString()).param("as_of", asOf.toString()));
    }

    @Test
    void inventoryFetchJob_viaMockConnector_appliesSnapshot_andReportsUnresolvedRows() {
        Ctx c = ctx("AVAILABLE");
        Api o = c.o();
        Api.Res created = o.post("/api/v1/integrations/connections", Map.of("target_type", "SHIPPER_ACCOUNT", "target_id", c.shipper().toString(),
                "connector_code", "mock-3pl", "display_name", "파스토 모의", "auth_type", "API_KEY", "credential", Map.of("api_key", "k")));
        UUID conn = created.id();
        // 지원 기능이 확인되기 전에는 재고 조회 작업을 받지 않는다
        assertThat(o.post("/api/v1/integrations/" + conn + "/sync-jobs", Map.of("job_type", "INVENTORY_FETCH")).errorCode()).isEqualTo("CAPABILITY_NOT_AVAILABLE");
        assertThat(o.put("/api/v1/integrations/connections/" + conn + "/capabilities/INVENTORY_QUERY/READ", Map.of("support_level", "OFFICIAL_DOCUMENTED",
                "account_verification", "VERIFIED", "evidence_ref", "테스트 계정 재고 조회 응답 캡처"), 1).status()).isEqualTo(200);
        o.post("/api/v1/integrations/" + conn + "/sync-jobs", Map.of());
        drainAsync();

        Instant asOf = Instant.now().minus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        mockControl.setInventory(conn, asOf, List.of(
                Map.of("sku_code", "AMP-30", "quantity", 6, "sellable", true, "status", "NORMAL", "node_code", "FASSTO-YI"),
                Map.of("sku_code", "amp-50", "quantity", 4, "sellable", true, "status", "NORMAL", "node_code", "FASSTO-YI"),
                Map.of("sku_code", "AMP-30", "quantity", 1, "sellable", false, "status", "DEFECT", "node_code", "FASSTO-YI"),
                Map.of("sku_code", "AMP-30", "quantity", 9, "sellable", true, "status", "NORMAL", "node_code", "UNKNOWN-CENTER")));
        Api.Res job = o.post("/api/v1/integrations/" + conn + "/sync-jobs", Map.of("job_type", "INVENTORY_FETCH"));
        assertThat(job.status()).as(job.body().toString()).isEqualTo(202);
        drainAsync();
        JsonNode j = o.get("/api/v1/jobs/" + job.data().get("job_id").asString()).data();
        assertThat(j.get("status").asString()).as(j.toString()).isEqualTo("SUCCEEDED");
        assertThat(j.get("result").get("row_count").asInt()).isEqualTo(4); // 2행씩 2쪽
        assertThat(j.get("result").get("unresolved_rows").asInt()).isEqualTo(1);
        assertThat(at(c, c.sku()).get("sellable_on_hand").asInt()).isEqualTo(6);
        assertThat(at(c, c.sku2()).get("sellable_on_hand").asInt()).isEqualTo(4); // SKU 코드 대소문자 무시
        JsonNode snaps = o.get("/api/v1/inventory/external-snapshots?node_id=" + c.node()).data();
        assertThat(snaps.get(0).get("source").asString()).isEqualTo("API");
        assertThat(Instant.parse(snaps.get(0).get("as_of").asString())).isEqualTo(asOf);
        assertThat(items(o, "EXTERNAL_NODE_UNRESOLVED", "OPEN")).hasSize(1);
        assertThat(items(o, "EXTERNAL_INVENTORY_QUALITY", "OPEN")).hasSize(1);
        assertVerified(o);
    }
}
