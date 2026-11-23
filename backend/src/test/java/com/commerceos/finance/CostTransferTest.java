package com.commerceos.finance;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M5 3차: 입고 부대비용(T33), 공통비 배부(T34), 사업자 간 재고 이전·내부거래 제거(T35). */
class CostTransferTest extends FinanceTestBase {

    UUID receive(Ctx c, UUID sku, int qty, Integer unitCost) {
        Map<String, Object> b = new LinkedHashMap<>(Map.of("business_entity_id", c.be().toString(), "sku_id", sku.toString(), "node_id", c.hs().toString(), "quantity", qty));
        if (unitCost != null) {
            b.put("unit_cost", unitCost);
        }
        assertThat(c.o().post("/api/v1/inventory/receipts", b).status()).isEqualTo(201);
        JsonNode txns = c.o().get("/api/v1/inventory/transactions?txn_type=RECEIPT&sku_id=" + sku + "&limit=1").data();
        return UUID.fromString(txns.get(0).get("id").asString());
    }

    BigDecimal avg(Ctx c, UUID sku) {
        return jdbc.sql("select avg_unit_cost from sku_moving_cost where business_entity_id = :b and sku_id = :s").param("b", c.be()).param("s", sku)
                .query(BigDecimal.class).single();
    }

    UUID unit(Ctx c) {
        return UUID.fromString(c.o().get("/api/v1/channel-accounts/" + c.acc() + "/sales-units").data().get(0).get("id").asString());
    }

    @Test
    void T33_landedFreightCapitalized_heldShareToInventory_soldShareToCogs_sameBillNeverExpensedTwice() {
        Ctx c = ctx("운임상사");
        map(c.o(), unit(c), "P-50", c.amp50());
        UUID r1 = receive(c, c.amp(), 10, 9000);   // amp: 기존 10 + 10 = 20
        UUID r2 = receive(c, c.amp50(), 4, 12000); // amp50: 기존 10 + 4 = 14
        shipOrder(c, order(c, "L-1", new Object[]{"P-100", 2, 33000, 0, 0, false})); // amp 보유 18
        shipOrder(c, order(c, "L-2", new Object[]{"P-50", 12, 15000, 0, 0, false})); // amp50 보유 2
        int cogsBefore = cost(total(c.o(), c.be()), "PRODUCT_COGS");

        // 금액 기준은 입고 단가가 있어야 한다(기존 입고는 단가 없음)
        UUID noCost = UUID.fromString(c.o().get("/api/v1/inventory/transactions?txn_type=RECEIPT&sku_id=" + c.gift() + "&limit=1").data().get(0).get("id").asString());
        Map<String, Object> bad = Map.of("business_entity_id", c.be().toString(), "cost_type", "FREIGHT", "supply_amount", 1000, "method", "AMOUNT", "source_ref", "X-1",
                "receipts", List.of(Map.of("receipt_txn_id", noCost.toString())));
        assertThat(c.o().post("/api/v1/finance/landed-costs", bad).errorCode()).isEqualTo("RECEIPT_COST_MISSING");
        assertThat(c.o().post("/api/v1/finance/landed-costs", Map.of("business_entity_id", c.be().toString(), "cost_type", "FREIGHT", "supply_amount", 1000,
                "method", "MANUAL", "source_ref", "X-2", "receipts", List.of(Map.of("receipt_txn_id", r1.toString(), "amount", 900)))).errorCode())
                .isEqualTo("ALLOCATION_TOTAL_MISMATCH");

        // 수입 운임 14,000(공급가) + 부가세 1,400 을 수량 기준으로 두 입고에 배부: amp 10,000 / amp50 4,000
        Map<String, Object> body = Map.of("business_entity_id", c.be().toString(), "cost_type", "FREIGHT", "supply_amount", 14000, "vat_amount", 1400,
                "method", "QUANTITY", "source_ref", "FRT-2026-11-01", "receipts", List.of(Map.of("receipt_txn_id", r1.toString()), Map.of("receipt_txn_id", r2.toString())));
        Api.Res r = c.o().post("/api/v1/finance/landed-costs", body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        // amp: 입고 10개 모두 보유(18 ≥ 10) → 10,000 취득원가. amp50: 입고 4개 중 보유 2 → 2,000 취득원가, 2,000 매출원가
        assertThat(r.data().get("capitalized").asInt()).isEqualTo(12000);
        assertThat(r.data().get("expensed").asInt()).isEqualTo(2000);
        assertThat(cost(total(c.o(), c.be()), "PRODUCT_COGS")).isEqualTo(cogsBefore + 2000);
        assertThat(avg(c, c.amp())).isEqualByComparingTo("9555.5556"); // 9,000 + 10,000 / 18
        assertThat(avg(c, c.amp50())).isEqualByComparingTo("13000");     // 12,000 + 2,000 / 2
        JsonNode lines = r.data().get("lines");
        assertThat(lines).hasSize(2);
        int allocated = 0;
        for (JsonNode l : lines) {
            allocated += l.get("allocated").asInt();
            assertThat(l.get("capitalized").asInt() + l.get("expensed").asInt()).isEqualTo(l.get("allocated").asInt());
        }
        assertThat(allocated).isEqualTo(14000); // 배부 합계 = 원본
        // 부대비용 부가세는 손익 밖(매입세액), 출고 배송비로 잡히지 않음
        assertThat(cost(total(c.o(), c.be()), "OUTBOUND_SHIPPING")).isZero();

        // 같은 청구를 다시: 부대비용 중복·출고비로 중복 차감 모두 거부
        assertThat(c.o().post("/api/v1/finance/landed-costs", body).errorCode()).isEqualTo("LANDED_COST_DUPLICATE");
        assertThat(c.o().postWithKey("/api/v1/finance/cost-entries", Map.of("business_entity_id", c.be().toString(), "category", "OUTBOUND_SHIPPING", "basis", "ACTUAL",
                "supply_amount", 14000, "source_ref", "FRT-2026-11-01"), "frt-dup-0001").errorCode()).isEqualTo("LANDED_COST_CAPITALIZED");
        assertThat(cost(total(c.o(), c.be()), "OUTBOUND_SHIPPING")).isZero();
        // 반대: 이미 비용으로 기록한 청구는 취득원가로 다시 반영 불가
        assertThat(c.o().postWithKey("/api/v1/finance/cost-entries", Map.of("business_entity_id", c.be().toString(), "category", "INBOUND_SERVICE", "basis", "ACTUAL",
                "supply_amount", 3000, "source_ref", "INB-77"), "inb-77-0001").status()).isEqualTo(201);
        assertThat(c.o().post("/api/v1/finance/landed-costs", Map.of("business_entity_id", c.be().toString(), "cost_type", "INSPECTION", "supply_amount", 3000,
                "source_ref", "INB-77", "receipts", List.of(Map.of("receipt_txn_id", r1.toString())))).errorCode()).isEqualTo("COST_ALREADY_EXPENSED");

        // 이후 출고는 부대비용이 포함된 원가로(별도 운임 비용 없이)
        shipOrder(c, order(c, "L-3", new Object[]{"P-50", 1, 15000, 0, 0, false}));
        JsonNode cogs = c.o().get("/api/v1/finance/entries?category=PRODUCT_COGS&business_entity_id=" + c.be()).data();
        boolean found = false;
        for (JsonNode e : cogs) {
            if (e.get("order_no") != null && !e.get("order_no").isNull() && e.get("supply_amount").asInt() == 13000) {
                found = true;
            }
        }
        assertThat(found).isTrue();
        assertThat(c.o().get("/api/v1/finance/landed-costs").data()).hasSize(1);
    }

    @Test
    void T34_commonAdCostAllocatedByNetSales_totalExact_remainderToLargest_reallocationReversesPrevious_entityProfitSeparated() {
        Fixtures.Ws ws = fx.workspace();
        Ctx a = ctx(ws, "공통에이", "CA");
        Ctx b = ctx(ws, "공통비이", "CB");
        UUID cbe = fx.businessEntity(a.o(), "공통씨");
        shipOrder(a, order(a, "C-1", new Object[]{"P-100", 1, 33000, 0, 0, false})); // 순매출 30,000
        shipOrder(b, order(b, "C-2", new Object[]{"P-100", 2, 33000, 0, 0, false})); // 순매출 60,000
        Map<String, Object> body = new LinkedHashMap<>(Map.of("category", "ADVERTISING", "supply_amount", 10000, "vat_amount", 1000, "method", "NET_SALES",
                "source_ref", "META-ADS-2026-11", "targets", List.of(Map.of("business_entity_id", a.be().toString()), Map.of("business_entity_id", b.be().toString()))));
        Api.Res r = a.o().postWithKey("/api/v1/finance/cost-allocations", body, "alloc-key-0001");
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        JsonNode lines = r.data().get("lines");
        Map<String, Integer> byBe = new LinkedHashMap<>();
        int sum = 0;
        int vat = 0;
        for (JsonNode l : lines) {
            byBe.put(l.get("business_entity_id").asString(), l.get("supply_amount").asInt());
            sum += l.get("supply_amount").asInt();
            vat += l.get("vat_amount").asInt();
            assertThat(new BigDecimal(l.get("denominator").asString())).isEqualByComparingTo("90000"); // 배부 근거(분모) 보존
        }
        assertThat(sum).isEqualTo(10000);
        assertThat(vat).isEqualTo(1000);
        assertThat(byBe.get(a.be().toString())).isEqualTo(3333);
        assertThat(byBe.get(b.be().toString())).isEqualTo(6667);
        // 같은 요청 재전송 → 같은 배부
        assertThat(a.o().postWithKey("/api/v1/finance/cost-allocations", body, "alloc-key-0001").data().get("id").asString()).isEqualTo(r.data().get("id").asString());
        // 사업자 손익 분리
        assertThat(cost(total(a.o(), a.be()), "ADVERTISING")).isEqualTo(3333);
        assertThat(cost(total(a.o(), b.be()), "ADVERTISING")).isEqualTo(6667);
        assertThat(cost(a.o().get("/api/v1/finance/profit").data().get("total"), "ADVERTISING")).isEqualTo(10000);

        // 재배부: 3곳 균등 → 3,333 × 3 = 9,999, 잔액 1 은 첫 대상(분자 동률)에. 이전 버전은 반대 거래로 상계
        Map<String, Object> re = Map.of("method", "EQUAL", "targets", List.of(Map.of("business_entity_id", a.be().toString()),
                Map.of("business_entity_id", b.be().toString()), Map.of("business_entity_id", cbe.toString())));
        Api.Res v2 = a.o().post("/api/v1/finance/cost-allocations/" + r.data().get("id").asString() + "/reallocate", re);
        assertThat(v2.status()).as(v2.body().toString()).isEqualTo(201);
        assertThat(v2.data().get("version").asInt()).isEqualTo(2);
        assertThat(v2.data().get("allocation_no").asString()).isEqualTo(r.data().get("allocation_no").asString());
        assertThat(cost(total(a.o(), a.be()), "ADVERTISING")).isEqualTo(3334);
        assertThat(cost(total(a.o(), b.be()), "ADVERTISING")).isEqualTo(3333);
        assertThat(cost(total(a.o(), cbe), "ADVERTISING")).isEqualTo(3333);
        assertThat(cost(a.o().get("/api/v1/finance/profit").data().get("total"), "ADVERTISING")).isEqualTo(10000);
        assertThat(a.o().get("/api/v1/finance/cost-allocations/" + r.data().get("id").asString()).data().get("status").asString()).isEqualTo("SUPERSEDED");
        assertThat(a.o().post("/api/v1/finance/cost-allocations/" + r.data().get("id").asString() + "/reallocate", re).errorCode()).isEqualTo("ALLOCATION_SUPERSEDED");
        assertThat(jdbc.sql("select count(*) from financial_entry where workspace_id = :ws and source_type = 'COST_ALLOCATION' and reverses_id is not null")
                .param("ws", ws.id()).query(Integer.class).single()).isEqualTo(2);

        // 대상 1곳·순매출 없는 기준은 거부
        assertThat(a.o().postWithKey("/api/v1/finance/cost-allocations", Map.of("category", "ADVERTISING", "supply_amount", 100, "targets",
                List.of(Map.of("business_entity_id", a.be().toString()))), "alloc-key-0002").errorCode()).isEqualTo("VALIDATION_FAILED");
        UUID d = fx.businessEntity(a.o(), "공통디");
        assertThat(a.o().postWithKey("/api/v1/finance/cost-allocations", Map.of("category", "ADVERTISING", "supply_amount", 100, "method", "NET_SALES", "targets",
                List.of(Map.of("business_entity_id", cbe.toString()), Map.of("business_entity_id", d.toString()))), "alloc-key-0003").errorCode())
                .isEqualTo("ALLOCATION_BASE_ZERO");

        // 범위: A 만 보는 재무 담당은 B 몫을 볼 수 없고, B 를 포함한 배부를 만들 수 없다
        Api fin = fx.member(ws, "FINANCE");
        fx.setPolicy(a.o(), fin, null, List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", a.be(), "ALLOW"),
                scope("BUSINESS_ENTITY", "WRITE", "SELECTED", a.be(), "ALLOW")));
        JsonNode seen = fin.get("/api/v1/finance/cost-allocations/" + v2.data().get("id").asString()).data();
        for (JsonNode l : seen.get("lines")) {
            if (!l.get("business_entity_id").asString().equals(a.be().toString())) {
                assertThat(l.get("supply_amount").isNull()).isTrue();
            }
        }
        assertThat(fin.postWithKey("/api/v1/finance/cost-allocations", body, "alloc-key-0004").status()).isIn(403, 404); // 범위 밖 사업자는 존재도 숨김
    }

    @Test
    void T35_ownershipTransfer_needsApproval_changesOwnerNotLocation_internalSaleEliminatedInGroup_unrealizedProfitUntilSold() {
        Fixtures.Ws ws = fx.workspace();
        Ctx a = ctx(ws, "이전에이", "TA");
        Ctx b = ctx(ws, "이전비이", "TB");
        UUID stock = UUID.fromString(jdbc.sql("select id::text from inventory_stock where owner_business_entity_id = :o and sku_id = :s and on_hand > 0")
                .param("o", a.be()).param("s", a.amp()).query(String.class).single());
        Map<String, Object> req = new LinkedHashMap<>(Map.of("stock_id", stock.toString(), "to_business_entity_id", b.be().toString(), "quantity", 4,
                "unit_price", 10000, "reason", "B 사업자 판매용 재고 이관"));
        // 같은 사업자로는 이전 불가(거점 이동으로 처리), 보유보다 많이 불가
        Map<String, Object> same = new LinkedHashMap<>(req);
        same.put("to_business_entity_id", a.be().toString());
        assertThat(a.o().post("/api/v1/inventory/ownership-transfers", same).errorCode()).isEqualTo("VALIDATION_FAILED");
        Map<String, Object> many = new LinkedHashMap<>(req);
        many.put("quantity", 100);
        assertThat(a.o().post("/api/v1/inventory/ownership-transfers", many).errorCode()).isEqualTo("STOCK_INSUFFICIENT");

        Api.Res r = a.o().post("/api/v1/inventory/ownership-transfers", req);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(202);
        String id = r.data().get("id").asString();
        String approval = r.data().get("approval_id").asString();
        assertThat(r.data().get("status").asString()).isEqualTo("PENDING");
        assertThat(r.data().get("supply_amount").asInt()).isEqualTo(40000);
        assertThat(r.data().get("vat_amount").asInt()).isEqualTo(4000);
        assertThat(r.data().get("tax_invoice_required").asBoolean()).isTrue();
        assertThat(onHand(a.be(), a.amp())).isEqualTo(10); // 승인 전 불변
        // 승인: 본인 불가, 원가 권한 없는 승인자 불가
        assertThat(a.o().post("/api/v1/approvals/" + approval + "/decision", Map.of("decision", "APPROVE")).errorCode()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        Api ops = fx.member(ws, "OPS_ADMIN");
        assertThat(ops.post("/api/v1/approvals/" + approval + "/decision", Map.of("decision", "APPROVE")).errorCode()).isEqualTo("APPROVER_NEEDS_COST_MANAGE");
        Api admin = fx.member(ws, "SUPER_ADMIN");
        assertThat(admin.post("/api/v1/approvals/" + approval + "/decision", Map.of("decision", "APPROVE", "note", "이전가 확인")).status()).isEqualTo(200);
        drainAsync();

        // 소유자만 바뀌고 실물 위치(거점·로케이션·LOT)는 그대로
        assertThat(onHand(a.be(), a.amp())).isEqualTo(6);
        assertThat(onHand(b.be(), a.amp())).isEqualTo(4);
        assertThat(jdbc.sql("select count(distinct node_id) from inventory_stock where sku_id = :s and on_hand > 0").param("s", a.amp()).query(Integer.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from inventory_transaction where reference_id = :id and txn_type in ('OWNERSHIP_TRANSFER_OUT', 'OWNERSHIP_TRANSFER_IN')")
                .param("id", id).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select avg_unit_cost from sku_moving_cost where business_entity_id = :b and sku_id = :s").param("b", b.be()).param("s", a.amp())
                .query(BigDecimal.class).single()).isEqualByComparingTo("10000");
        JsonNode done = a.o().get("/api/v1/inventory/ownership-transfers/" + id).data();
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(new BigDecimal(done.get("unit_cost").asString())).isEqualByComparingTo("9000");

        // 사업자 A: 내부 매출 40,000 · 내부 매출원가 36,000 그대로 표시. B: 손익 없음(재고 자산)
        JsonNode ta = total(a.o(), a.be());
        assertThat(ta.get("net_sales").asInt()).isEqualTo(40000);
        assertThat(cost(ta, "PRODUCT_COGS")).isEqualTo(36000);
        assertThat(total(a.o(), b.be()).get("net_sales").asInt()).isZero();
        // 운영그룹 합계: 내부거래 제거, 아직 B 가 보유 → 그룹 손익 0
        JsonNode g = a.o().get("/api/v1/finance/profit?group_by=business_entity").data();
        assertThat(g.get("total").get("net_sales").asInt()).isZero();
        assertThat(cost(g.get("total"), "PRODUCT_COGS")).isZero();
        assertThat(g.get("total").get("contribution").asInt()).isZero();
        assertThat(g.get("elimination").get("internal_sales").asInt()).isEqualTo(40000);
        assertThat(g.get("elimination").get("unrealized_at_end").asInt()).isEqualTo(4000);
        assertThat(g.get("rows")).hasSize(1); // B 는 아직 손익 행 없음(재고 자산)

        // B 가 1개를 외부 판매(순매출 20,000) → 그룹 매출원가 = A 원가 9,000, 그룹 공헌이익 11,000
        map(b.o(), unit(b), "P-A30", a.amp());
        shipOrder(b, order(b, "T-1", new Object[]{"P-A30", 1, 22000, 0, 0, false}));
        assertThat(cost(total(a.o(), b.be()), "PRODUCT_COGS")).isEqualTo(10000); // B 는 이전 단가로
        g = a.o().get("/api/v1/finance/profit?group_by=business_entity").data();
        assertThat(g.get("total").get("net_sales").asInt()).isEqualTo(20000);
        assertThat(cost(g.get("total"), "PRODUCT_COGS")).isEqualTo(9000);
        assertThat(g.get("total").get("contribution").asInt()).isEqualTo(11000);
        assertThat(g.get("elimination").get("unrealized_at_end").asInt()).isEqualTo(3000);
        // A 만 보는 사용자에게는 제거 없음(사업자 화면 그대로)
        assertThat(a.o().get("/api/v1/finance/profit?business_entity_id=" + a.be()).data().get("elimination").isNull()).isTrue();
        // 공통비 순매출 기준에서 내부 매출은 제외
        assertThat(jdbc.sql("select count(*) from financial_entry where business_entity_id = :b and intercompany and counterparty_business_entity_id = :c")
                .param("b", a.be()).param("c", b.be()).query(Integer.class).single()).isEqualTo(2);

        // 반려하면 재고 불변, 상태 반려
        Map<String, Object> req2 = new LinkedHashMap<>(req);
        req2.put("quantity", 1);
        Api.Res r2 = a.o().post("/api/v1/inventory/ownership-transfers", req2);
        admin.post("/api/v1/approvals/" + r2.data().get("approval_id").asString() + "/decision", Map.of("decision", "REJECT", "note", "가격 재검토"));
        assertThat(a.o().get("/api/v1/inventory/ownership-transfers/" + r2.data().get("id").asString()).data().get("status").asString()).isEqualTo("REJECTED");
        assertThat(onHand(a.be(), a.amp())).isEqualTo(6);
    }

    @Test
    void concurrentSameBill_capitalizedOnce_movingAverageConsistent() throws Exception {
        Ctx c = ctx("동시운임");
        UUID r1 = receive(c, c.amp(), 10, 9000); // 보유 20
        Map<String, Object> body = Map.of("business_entity_id", c.be().toString(), "cost_type", "FREIGHT", "supply_amount", 2000, "source_ref", "RACE-FRT",
                "receipts", List.of(Map.of("receipt_txn_id", r1.toString())));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5);
        List<java.util.concurrent.Callable<Integer>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> c.o().post("/api/v1/finance/landed-costs", body).status());
        }
        List<Integer> st = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<Integer> f : pool.invokeAll(tasks)) {
            st.add(f.get());
        }
        pool.shutdown();
        assertThat(st).filteredOn(x -> x == 201).hasSize(1);
        assertThat(st).filteredOn(x -> x == 409).hasSize(4);
        assertThat(avg(c, c.amp())).isEqualByComparingTo("9100"); // 9,000 + 2,000 / 20 — 한 번만
    }

    int onHand(UUID be, UUID sku) {
        return jdbc.sql("select coalesce(sum(on_hand), 0) from inventory_stock where owner_business_entity_id = :b and sku_id = :s").param("b", be).param("s", sku)
                .query(Integer.class).single();
    }
}
