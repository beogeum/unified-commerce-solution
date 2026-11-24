package com.commerceos.b2b;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.finance.FinanceTestBase;
import com.commerceos.support.Api;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** B2B 거래처·단가표·외상: 여신 검사(한도·연체·보류), 출고 청구 = 매출 원장, 반품 차감, 수금(멱등)·취소, 대손 승인, 연령, 권한·범위·동시성. */
class ReceivableTest extends FinanceTestBase {
    static final Map<String, Object> RECIPIENT = Map.of("name", "김납품", "phone", "010-2222-3333", "address1", "경기도 성남시 분당구 판교역로 1");
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    ReceivableService receivables;

    String customer(Ctx c, String code, int limit) {
        Api.Res r = c.o().post("/api/v1/b2b-customers", Map.of("business_entity_id", c.be().toString(), "code", code, "name", "거래처 " + code,
                "business_registration_no", "123-45-67890", "contact_name", "박담당", "contact_phone", "010-5555-1234", "credit_limit", limit, "payment_terms_days", 30));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data().get("id").asString();
    }

    Api.Res order(Api api, Ctx c, String customer, String terms, int qty, Integer price) {
        Map<String, Object> line = new LinkedHashMap<>(Map.of("sku_id", c.amp().toString(), "quantity", qty));
        if (price != null) {
            line.put("unit_price", price);
        }
        Map<String, Object> b = new LinkedHashMap<>(Map.of("channel_account_id", c.acc().toString(), "kind", "B2B", "customer_id", customer,
                "recipient", RECIPIENT, "lines", List.of(line)));
        if (terms != null) {
            b.put("payment_terms", terms);
        }
        return api.postWithKey("/api/v1/orders/manual", b, "b2b-" + UUID.randomUUID());
    }

    JsonNode st(Ctx c, String customer) {
        Api.Res r = c.o().get("/api/v1/b2b-customers/" + customer + "/receivables");
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.data();
    }

    static int n(JsonNode v) {
        return new BigDecimal(v.asString()).intValue();
    }

    @Test
    void creditOrder_chargeOnShipmentEqualsRevenue_paymentIdempotent_reversal_returnCreditNote_writeOffApproval() {
        Ctx c = ctx("외상상사");
        // 권한: MD 는 거래처 관리 없음, 주문담당은 거래처는 만들지만 외상 한도는 못 정함
        assertThat(fx.member(c.ws(), "MD").post("/api/v1/b2b-customers", Map.of("business_entity_id", c.be().toString(), "code", "X", "name", "x")).status())
                .isEqualTo(403);
        Api om = fx.member(c.ws(), "ORDER_MANAGER");
        assertThat(om.post("/api/v1/b2b-customers", Map.of("business_entity_id", c.be().toString(), "code", "OM1", "name", "주문담당 거래처", "credit_limit", 1000))
                .status()).isEqualTo(403);
        assertThat(om.post("/api/v1/b2b-customers", Map.of("business_entity_id", c.be().toString(), "code", "OM1", "name", "주문담당 거래처")).status()).isEqualTo(201);
        String cu = customer(c, "OLIVE", 100000);
        assertThat(c.o().post("/api/v1/b2b-customers", Map.of("business_entity_id", c.be().toString(), "code", "OLIVE", "name", "다른 이름")).errorCode())
                .isEqualTo("CUSTOMER_DUPLICATE");
        JsonNode cv = c.o().get("/api/v1/b2b-customers/" + cu).data();
        assertThat(cv.get("contact_phone_masked").asString()).doesNotContain("5555");
        assertThat(cv.toString()).doesNotContain("010-5555-1234");
        assertThat(c.o().post("/api/v1/b2b-customers/" + cu + "/contact/reveal", Map.of()).data().get("phone").asString()).isEqualTo("010-5555-1234");

        // 단가표: 지난 날짜 불가, 단가 생략 주문은 단가표 단가
        assertThat(c.o().post("/api/v1/b2b-customers/" + cu + "/prices", Map.of("prices", List.of(Map.of("sku_id", c.amp().toString(), "unit_price", 12000,
                "effective_from", TODAY.minusDays(1).toString())))).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(c.o().post("/api/v1/b2b-customers/" + cu + "/prices", Map.of("prices", List.of(Map.of("sku_id", c.amp().toString(), "unit_price", 12000))))
                .status()).isEqualTo(201);
        assertThat(order(c.o(), c, cu, null, 1, null).errorCode()).isEqualTo("VALIDATION_FAILED"); // 결제 조건 필수
        Api.Res o1 = order(c.o(), c, cu, "CREDIT", 3, null);
        assertThat(o1.status()).as(o1.body().toString()).isEqualTo(201);
        String order1 = o1.data().get("order_id").asString();
        drainAsync();
        assertThat(c.o().get("/api/v1/orders/" + order1).data().get("order").get("total_amount").asInt()).isEqualTo(36000);
        JsonNode s0 = st(c, cu);
        assertThat(n(s0.get("balance"))).isZero();
        assertThat(n(s0.get("unbilled_orders"))).isEqualTo(36000);
        assertThat(n(s0.get("available"))).isEqualTo(64000);
        // 한도 초과 외상 거부(상세 포함), 선결제는 한도와 무관
        Api.Res over = order(c.o(), c, cu, "CREDIT", 6, null);
        assertThat(over.errorCode()).isEqualTo("CREDIT_LIMIT_EXCEEDED");
        assertThat(over.body().toString()).contains("available");
        assertThat(order(c.o(), c, cu, "PREPAID", 1, 11000).status()).isEqualTo(201);

        // 출고 → 청구 = 그 출고의 매출 원장(부가세 포함), 기한 = 출고일 + 30일
        shipOrder(c, order1);
        JsonNode s1 = st(c, cu);
        assertThat(n(s1.get("balance"))).isEqualTo(36000);
        assertThat(n(s1.get("unbilled_orders"))).isZero();
        JsonNode charge = s1.get("open_charges").get(0);
        assertThat(charge.get("due_on").asString()).isEqualTo(TODAY.plusDays(30).toString());
        BigDecimal revenue = jdbc.sql("select sum(supply_amount + vat_amount) from financial_entry where order_id = cast(:o as uuid) and entry_class = 'REVENUE'")
                .param("o", order1).query(BigDecimal.class).single();
        assertThat(revenue).isEqualByComparingTo("36000");
        drainAsync(); // 재전달돼도 청구는 한 번
        assertThat(jdbc.sql("select count(*) from receivable_entry where order_id = cast(:o as uuid) and entry_type = 'CHARGE'").param("o", order1).query(Integer.class)
                .single()).isEqualTo(1);

        // 수금: 멱등 키 필수·같은 키 재요청은 같은 기록
        Api fin = fx.member(c.ws(), "FINANCE");
        assertThat(fin.postWithKey("/api/v1/b2b-customers/" + cu + "/payments", Map.of("amount", 20000), null).errorCode()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        String p1 = fin.postWithKey("/api/v1/b2b-customers/" + cu + "/payments", Map.of("amount", 20000, "reference", "입금 1"), "ar-pay-0001").data().get("id").asString();
        assertThat(fin.postWithKey("/api/v1/b2b-customers/" + cu + "/payments", Map.of("amount", 20000), "ar-pay-0001").data().get("id").asString()).isEqualTo(p1);
        assertThat(om.postWithKey("/api/v1/b2b-customers/" + cu + "/payments", Map.of("amount", 1), "ar-pay-om01").status()).isEqualTo(403);
        assertThat(n(st(c, cu).get("balance"))).isEqualTo(16000);
        // 수금 취소(사유 필수, 한 번만)
        assertThat(fin.post("/api/v1/receivable-entries/" + p1 + "/reverse", Map.of()).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(fin.post("/api/v1/receivable-entries/" + p1 + "/reverse", Map.of("reason", "다른 거래처 입금")).status()).isEqualTo(201);
        assertThat(fin.post("/api/v1/receivable-entries/" + p1 + "/reverse", Map.of("reason", "다시")).errorCode()).isEqualTo("ENTRY_ALREADY_REVERSED");
        assertThat(n(st(c, cu).get("balance"))).isEqualTo(36000);
        fin.postWithKey("/api/v1/b2b-customers/" + cu + "/payments", Map.of("amount", 6000), "ar-pay-0002");

        // 반품 1개 → 클레임 완료 → 반품 차감(매출 원장 반품과 같은 금액)
        String item = c.o().get("/api/v1/orders/" + order1).data().get("items").get(0).get("id").asString();
        String claim = c.o().post("/api/v1/claims", Map.of("order_id", order1, "claim_type", "RETURN", "reason_code", "CHANGE_OF_MIND",
                "items", List.of(Map.of("order_item_id", item, "quantity", 1)))).data().get("id").asString();
        c.o().post("/api/v1/claims/" + claim + "/approve", Map.of());
        drainAsync();
        JsonNode rc = c.o().post("/api/v1/returns/receipts", Map.of("node_id", c.hs().toString(), "claim_id", claim, "lines",
                List.of(Map.of("sku_id", c.amp().toString(), "quantity", 1)))).data();
        drainAsync();
        c.o().post("/api/v1/returns/receipts/" + rc.get("id").asString() + "/inspections", Map.of("lines", List.of(Map.of("receipt_line_id",
                rc.get("lines").get(0).get("id").asString(), "decisions", List.of(Map.of("condition", "SELLABLE", "quantity", 1))))));
        drainAsync();
        int refund = c.o().get("/api/v1/claims/" + claim).data().get("expected_refund_amount").asInt();
        String rid = c.o().postWithKey("/api/v1/claims/" + claim + "/refund-requests", Map.of("amount", refund), "rf-b2b-01").data().get("refunds").get(0).get("id")
                .asString();
        assertThat(c.o().post("/api/v1/refunds/" + rid + "/confirm", Map.of("confirmed_amount", refund)).status()).isEqualTo(200);
        drainAsync();
        BigDecimal returned = jdbc.sql("select sum(supply_amount + vat_amount) from financial_entry where claim_id = cast(:c as uuid) and entry_class = 'REVENUE'")
                .param("c", claim).query(BigDecimal.class).single();
        BigDecimal note = jdbc.sql("select amount from receivable_entry where claim_id = cast(:c as uuid)").param("c", claim).query(BigDecimal.class).single();
        assertThat(note).isEqualByComparingTo(returned);
        assertThat(note.signum()).isNegative();
        int balance = n(st(c, cu).get("balance"));
        assertThat(balance).isEqualTo(30000 + note.intValue());

        // 대손: 재무가 요청 → 본인 승인 불가 → 최고관리자 승인 → 잔액 감소, 잔액 초과 요청 거부
        assertThat(fin.post("/api/v1/b2b-customers/" + cu + "/write-offs", Map.of("amount", balance + 1, "reason", "x")).errorCode())
                .isEqualTo("WRITE_OFF_EXCEEDS_BALANCE");
        Api.Res wo = fin.post("/api/v1/b2b-customers/" + cu + "/write-offs", Map.of("amount", 1000, "reason", "거래처 폐업 확인분 일부"));
        assertThat(wo.status()).as(wo.body().toString()).isEqualTo(202);
        String ap = wo.data().get("id").asString();
        assertThat(fin.post("/api/v1/approvals/" + ap + "/decision", Map.of("decision", "APPROVE")).status()).isIn(403, 422);
        Api.Res d = c.o().post("/api/v1/approvals/" + ap + "/decision", Map.of("decision", "APPROVE"));
        assertThat(d.status()).as(d.body().toString()).isEqualTo(200);
        JsonNode s3 = st(c, cu);
        assertThat(n(s3.get("balance"))).isEqualTo(balance - 1000);
        List<String> types = new ArrayList<>();
        s3.get("entries").forEach(e -> types.add(e.get("entry_type").asString()));
        assertThat(types).containsExactlyInAnyOrder("CHARGE", "PAYMENT", "PAYMENT_REVERSAL", "PAYMENT", "CREDIT_NOTE", "WRITE_OFF");
        // 원장은 고칠 수 없다
        assertThat(jdbc.sql("select count(*) from receivable_entry where customer_id = cast(:c as uuid)").param("c", cu).query(Integer.class).single()).isEqualTo(6);
        try {
            jdbc.sql("update receivable_entry set amount = 1 where customer_id = cast(:c as uuid)").param("c", cu).update();
            throw new AssertionError("append-only");
        } catch (org.springframework.dao.DataAccessException expected) {
            // ok
        }
        // 범위: 다른 워크스페이스·읽기 권한 없는 역할
        assertThat(fx.workspace().owner().get("/api/v1/b2b-customers/" + cu).status()).isEqualTo(404);
        assertThat(fx.member(c.ws(), "CS_AGENT").get("/api/v1/b2b-customers/" + cu + "/receivables").status()).isEqualTo(403);
        assertThat(jdbc.sql("select count(*) from audit_log where target_type = 'B2B_CUSTOMER' and target_id = cast(:c as uuid)").param("c", cu).query(Integer.class)
                .single()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void overdueBlocksCreditOrders_raisesWorkItem_resolvedByPayment_holdBlocksAll_concurrentOrdersRespectLimit() throws Exception {
        Ctx c = ctx("연체상사");
        String cu = customer(c, "LATE", 100000);
        UUID cid = UUID.fromString(cu);
        // 40일 전 출고분 청구(기한 10일 경과) — 원장 추가 전용이라 테스트 데이터도 insert 로만
        jdbc.sql("""
                insert into receivable_entry (id, workspace_id, business_entity_id, customer_id, entry_type, amount, occurred_on, due_on, source_key)
                values (gen_random_uuid(), :ws, :be, :c, 'CHARGE', 15000, :d, :due, 'TEST:OLD')
                """).param("ws", c.ws().id()).param("be", c.be()).param("c", cid).param("d", TODAY.minusDays(40)).param("due", TODAY.minusDays(10)).update();
        Api.Res r = order(c.o(), c, cu, "CREDIT", 1, 10000);
        assertThat(r.errorCode()).isEqualTo("CREDIT_OVERDUE");
        JsonNode s = st(c, cu);
        assertThat(n(s.get("overdue"))).isEqualTo(15000);
        assertThat(n(s.get("aging").get("d1to30"))).isEqualTo(15000);
        assertThat(receivables.scanOverdue(TODAY)).isGreaterThanOrEqualTo(1);
        assertThat(c.o().get("/api/v1/work-items?item_type=RECEIVABLE_OVERDUE&status=OPEN").data()).hasSize(1);
        assertThat(c.o().get("/api/v1/receivables?overdue_only=true").data()).hasSize(1);
        c.o().postWithKey("/api/v1/b2b-customers/" + cu + "/payments", Map.of("amount", 15000), "late-pay-0001");
        assertThat(c.o().get("/api/v1/work-items?item_type=RECEIVABLE_OVERDUE&status=OPEN").data()).isEmpty();
        assertThat(n(st(c, cu).get("overdue"))).isZero();

        // 거래 보류: 사유 필수, 보류 중에는 선결제도 막음. 한도 변경은 재무 권한
        JsonNode cv = c.o().get("/api/v1/b2b-customers/" + cu).data();
        assertThat(c.o().patch("/api/v1/b2b-customers/" + cu, Map.of("status", "ON_HOLD"), cv.get("version").asInt()).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(c.o().patch("/api/v1/b2b-customers/" + cu, Map.of("status", "ON_HOLD", "hold_reason", "세금계산서 분쟁"), cv.get("version").asInt()).status()).isEqualTo(200);
        assertThat(order(c.o(), c, cu, "PREPAID", 1, 10000).errorCode()).isEqualTo("CUSTOMER_NOT_ACTIVE");
        int ver = c.o().get("/api/v1/b2b-customers/" + cu).data().get("version").asInt();
        assertThat(fx.member(c.ws(), "ORDER_MANAGER").patch("/api/v1/b2b-customers/" + cu, Map.of("credit_limit", 999999), ver).status()).isEqualTo(403);
        assertThat(c.o().patch("/api/v1/b2b-customers/" + cu, Map.of("status", "ACTIVE"), ver).status()).isEqualTo(200);

        // 동시 외상 주문 4건 × 30,000(한도 100,000) → 정확히 3건만
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> order(c.o(), c, cu, "CREDIT", 3, 10000).status());
        }
        List<Integer> sts = new ArrayList<>();
        for (java.util.concurrent.Future<Integer> f : pool.invokeAll(tasks)) {
            sts.add(f.get());
        }
        pool.shutdown();
        assertThat(sts).filteredOn(x -> x == 201).hasSize(3);
        assertThat(n(st(c, cu).get("exposure"))).isEqualTo(90000);
    }
}
