package com.commerceos.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.commerceos.support.Api;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** M5 2차: 정산 원본·대사(T29), 입금 다대다(T30·T31), 월 마감·마감 후 조정(T32). */
class SettlementTest extends FinanceTestBase {
    static final String HEAD = "정산번호,정산기간시작,정산기간종료,지급예정일,지급액,주문번호,상품주문번호,항목,금액,수량\r\n";

    Api.Res upload(Ctx c, String csv) {
        return c.o().perform(multipart("/api/v1/finance/settlements/imports").file(new MockMultipartFile("file", "settlement.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8))).param("channel_account_id", c.acc().toString()));
    }

    JsonNode run(Ctx c, String id) {
        Api.Res r = c.o().get("/api/v1/finance/settlements/" + id);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.data();
    }

    String manualRun(Ctx c, String key, LocalDate periodTo, Object[]... lines) {
        List<Map<String, Object>> ls = new java.util.ArrayList<>();
        for (Object[] l : lines) {
            Map<String, Object> m = new LinkedHashMap<>(Map.of("line_type", l[0], "amount", l[1]));
            if (l.length > 2 && l[2] != null) {
                m.put("external_order_key", l[2]);
            }
            ls.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>(Map.of("channel_account_id", c.acc().toString(), "settlement_key", key, "lines", ls));
        if (periodTo != null) {
            body.put("period_to", periodTo.toString());
        }
        Api.Res r = c.o().post("/api/v1/finance/settlements", body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data().get("id").asString();
    }

    String deposit(Ctx c, int amount, String key, String ref) {
        Map<String, Object> b = new LinkedHashMap<>(Map.of("business_entity_id", c.be().toString(), "deposited_on", LocalDate.now().toString(), "amount", amount));
        if (ref != null) {
            b.put("external_ref", ref);
        }
        Api.Res r = c.o().postWithKey("/api/v1/finance/deposits", b, key);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data().get("id").asString();
    }

    int count(String sql, UUID ws) {
        return jdbc.sql(sql).param("ws", ws).query(Integer.class).single();
    }

    @Test
    void T29_settlementFile_reuploadNoDuplicates_feesReplaceEstimates_unmatchedReviewed_rematchAfterCollection() {
        Ctx c = ctx("정산상사");
        rule(c, "SELLING_FEE", "PER_UNIT", 1500, null, false);
        String o = order(c, "S-1", new Object[]{"P-100", 2, 33000, 6600, 0, false});
        shipOrder(c, o);
        assertThat(cost(total(c.o(), c.be()), "SELLING_FEE")).isEqualTo(3000); // 예상
        String csv = HEAD
                + "ST-1,2026-11-01,2026-11-15,2026-11-20,56000,S-1,S-1-1,판매,59400,2\r\n"
                + "ST-1,2026-11-01,2026-11-15,2026-11-20,56000,S-1,S-1-1,판매수수료,-3300,\r\n"
                + "ST-1,2026-11-01,2026-11-15,2026-11-20,56000,S-1,,결제수수료,-1100,\r\n"
                + "ST-1,2026-11-01,2026-11-15,2026-11-20,56000,S-2,,판매,1000,1\r\n";
        Api.Res r = upload(c, csv);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.data().get("new_lines").asInt()).isEqualTo(4);
        String runId = r.data().get("run_ids").get(0).asString();
        JsonNode run = run(c, runId).get("run");
        assertThat(run.get("payout").asInt()).isEqualTo(56000);
        assertThat(run.get("declared_payout").asInt()).isEqualTo(56000);
        assertThat(run.get("status").asString()).isEqualTo("ISSUES"); // 원주문 없는 행
        assertThat(run.get("review_lines").asInt()).isEqualTo(1);
        assertThat(run.get("differences")).isEmpty(); // S-1: 정산 판매 59,400 = 원장(상품 66,000 − 판매자 할인 6,600)
        // 수수료: 실제가 예상을 대체(합산 금지), 결제수수료(주문 단위)는 주문상품에 배부
        JsonNode t = total(c.o(), c.be());
        assertThat(cost(t, "SELLING_FEE")).isEqualTo(3000); // 3,300 부가세 포함 → 공급가 3,000, 예상 3,000 상계
        assertThat(cost(t, "PAYMENT_FEE")).isEqualTo(1000);
        assertThat(t.get("estimated_cost").asInt()).isZero();
        assertThat(t.get("net_sales").asInt()).isEqualTo(54000); // 정산 판매 행은 대사용, 매출 이중 인식 없음
        assertThat(c.o().get("/api/v1/work-items?item_type=SETTLEMENT_UNMATCHED&status=OPEN").data()).hasSize(1);
        int entries = count("select count(*) from financial_entry where workspace_id = :ws", c.ws().id());

        // 같은 파일 재업로드 → 중복 없음(T29)
        Api.Res again = upload(c, csv);
        assertThat(again.data().get("new_lines").asInt()).isZero();
        assertThat(again.data().get("duplicate_lines").asInt()).isEqualTo(4);
        assertThat(again.data().get("run_ids").get(0).asString()).isEqualTo(runId);
        assertThat(count("select count(*) from settlement_line where workspace_id = :ws", c.ws().id())).isEqualTo(4);
        assertThat(count("select count(*) from financial_entry where workspace_id = :ws", c.ws().id())).isEqualTo(entries);
        // 행 순서가 바뀐 재발행 파일도 같은 행으로 본다
        String reordered = HEAD + "ST-1,2026-11-01,2026-11-15,2026-11-20,56000,S-2,,판매,1000,1\r\n"
                + "ST-1,2026-11-01,2026-11-15,2026-11-20,56000,S-1,,결제수수료,-1100,\r\n";
        assertThat(upload(c, reordered).data().get("new_lines").asInt()).isZero();
        // 원본 행은 불변(추가 전용)
        assertThatThrownBy(() -> jdbc.sql("update settlement_line set amount = 1 where workspace_id = :ws and match_level <> 'UNMATCHED'").param("ws", c.ws().id())
                .update()).hasMessageContaining("immutable");

        // 잘못된 행이 있으면 아무것도 반영하지 않음
        Api.Res bad = upload(c, HEAD + "ST-9,,,,,S-1,,판매,abc,\r\n");
        assertThat(bad.errorCode()).isEqualTo("SETTLEMENT_FILE_INVALID");
        assertThat(upload(c, "주문번호,금액\r\nS-1,1\r\n").errorCode()).isEqualTo("TEMPLATE_COLUMN_MISSING");

        // 주문 수집 후 재매칭 → 연결, 업무 해결
        order(c, "S-2", new Object[]{"P-100", 1, 1000, 0, 0, false});
        JsonNode re = c.o().post("/api/v1/finance/settlements/" + runId + "/rematch", Map.of()).data();
        assertThat(re.get("review_lines").asInt()).isZero();
        assertThat(c.o().get("/api/v1/work-items?item_type=SETTLEMENT_UNMATCHED&status=OPEN").data()).isEmpty();
        assertThat(re.get("differences")).hasSize(1); // S-2 는 아직 출고 전(원장 매출 0) → 대사 차이로 남음
        assertThat(re.get("status").asString()).isEqualTo("ISSUES");

        // 권한: 정산 조회 권한 없는 사용자는 거부, 다른 워크스페이스는 보이지 않음
        Api logi = fx.member(c.ws(), "LOGISTICS_MANAGER");
        assertThat(logi.get("/api/v1/finance/settlements").status()).isEqualTo(403);
        Api other = fx.workspace().owner();
        assertThat(other.get("/api/v1/finance/settlements/" + runId).status()).isEqualTo(404);
        assertThat(other.get("/api/v1/finance/settlements").data()).isEmpty();
    }

    @Test
    void T30_oneSettlementTwoDeposits_balanceZero_overMatchRejected_cancelKeepsHistory_depositIdempotent() {
        Ctx c = ctx("입금상사");
        String run = manualRun(c, "ST-30", null, new Object[]{"SALE", 56000});
        String d1 = deposit(c, 30000, "dep-30-a-key", "BANK-1");
        // 같은 멱등키 → 같은 입금, 같은 거래번호 다른 키 → 중복 거부
        assertThat(deposit(c, 30000, "dep-30-a-key", "BANK-1")).isEqualTo(d1);
        assertThat(c.o().postWithKey("/api/v1/finance/deposits", Map.of("business_entity_id", c.be().toString(), "deposited_on", LocalDate.now().toString(),
                "amount", 30000, "external_ref", "BANK-1"), "dep-30-b-key").errorCode()).isEqualTo("DEPOSIT_DUPLICATE");
        assertThat(c.o().post("/api/v1/finance/deposits/" + d1 + "/auto-match", Map.of()).data().get("outcome").asString()).isEqualTo("NO_MATCH");
        Api.Res m1 = c.o().post("/api/v1/finance/deposits/" + d1 + "/matches", Map.of("run_id", run, "amount", 30000));
        assertThat(m1.status()).as(m1.body().toString()).isEqualTo(201);
        String d2 = deposit(c, 26000, "dep-30-c-key", null);
        JsonNode auto = c.o().post("/api/v1/finance/deposits/" + d2 + "/auto-match", Map.of()).data();
        assertThat(auto.get("outcome").asString()).isEqualTo("MATCHED");
        assertThat(auto.get("matches").get(0).get("amount").asInt()).isEqualTo(26000);
        JsonNode r = run(c, run);
        assertThat(r.get("run").get("deposited").asInt()).isEqualTo(56000);
        assertThat(r.get("run").get("remaining").asInt()).isZero();
        assertThat(r.get("matches")).hasSize(2);
        // 초과 연결 거부
        String d3 = deposit(c, 1000, "dep-30-d-key", null);
        assertThat(c.o().post("/api/v1/finance/deposits/" + d3 + "/matches", Map.of("run_id", run, "amount", 1000)).errorCode()).isEqualTo("SETTLEMENT_OVER_MATCHED");
        assertThat(c.o().post("/api/v1/finance/deposits/" + d2 + "/matches", Map.of("run_id", run, "amount", 1)).errorCode()).isEqualTo("DEPOSIT_OVER_MATCHED");
        // 연결 취소: 이력은 남고 잔액이 돌아온다
        String mid = m1.data().get("id").asString();
        assertThat(c.o().post("/api/v1/finance/deposit-matches/" + mid + "/cancel", Map.of()).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(c.o().post("/api/v1/finance/deposit-matches/" + mid + "/cancel", Map.of("reason", "다른 정산 입금")).status()).isEqualTo(200);
        assertThat(run(c, run).get("run").get("remaining").asInt()).isEqualTo(30000);
        assertThat(count("select count(*) from deposit_match where workspace_id = :ws", c.ws().id())).isEqualTo(2);
        assertThat(c.o().post("/api/v1/finance/deposit-matches/" + mid + "/cancel", Map.of("reason", "again")).errorCode()).isEqualTo("DEPOSIT_MATCH_CANCELLED");
        // 입금 원본은 추가 전용
        assertThatThrownBy(() -> jdbc.sql("update bank_deposit set amount = 1 where workspace_id = :ws").param("ws", c.ws().id()).update()).isNotNull();
        // 입금은 손익과 무관
        assertThat(total(c.o(), c.be()).get("net_sales").asInt()).isZero();
    }

    @Test
    void T31_severalSettlementsOneDeposit_eachLinked_ambiguousNotGuessed_otherEntityRejected() {
        Ctx c = ctx("다건상사");
        String a = manualRun(c, "ST-A", null, new Object[]{"SALE", 20000}, new Object[]{"COMMISSION", -1000, null});
        String b = manualRun(c, "ST-B", null, new Object[]{"SALE", 16000});
        String dep = deposit(c, 35000, "dep-31-a-key", null);
        JsonNode auto = c.o().post("/api/v1/finance/deposits/" + dep + "/auto-match", Map.of()).data();
        assertThat(auto.get("outcome").asString()).as(auto.toString()).isEqualTo("MATCHED");
        assertThat(auto.get("matches")).hasSize(2);
        assertThat(run(c, a).get("run").get("remaining").asInt()).isZero();
        assertThat(run(c, b).get("run").get("remaining").asInt()).isZero();
        JsonNode d = c.o().get("/api/v1/finance/deposits/" + dep).data();
        assertThat(d.get("remaining").asInt()).isZero();
        assertThat(d.get("matches")).hasSize(2);
        // 주문 없는 수수료 행은 자동 반영하지 않고 검토
        assertThat(run(c, a).get("run").get("review_lines").asInt()).isEqualTo(1);
        assertThat(c.o().get("/api/v1/work-items?item_type=SETTLEMENT_LINE_REVIEW&status=OPEN").data()).hasSize(1);
        // 같은 금액 후보가 여럿이면 추정 연결하지 않음
        manualRun(c, "ST-C", null, new Object[]{"SALE", 10000});
        manualRun(c, "ST-D", null, new Object[]{"SALE", 10000});
        String dep2 = deposit(c, 10000, "dep-31-b-key", null);
        JsonNode amb = c.o().post("/api/v1/finance/deposits/" + dep2 + "/auto-match", Map.of()).data();
        assertThat(amb.get("outcome").asString()).isEqualTo("AMBIGUOUS");
        assertThat(amb.get("candidate_run_ids")).hasSize(2);
        assertThat(c.o().get("/api/v1/finance/deposits/" + dep2).data().get("matches")).isEmpty();
        // 다른 사업자의 정산과는 연결 불가
        UUID be2 = fx.businessEntity(c.o(), "다른사업자");
        UUID acc2 = fx.channelAccount(c.o(), be2, "COUPANG_MARKETPLACE", "다른 스토어");
        Api.Res other = c.o().post("/api/v1/finance/settlements", Map.of("channel_account_id", acc2.toString(), "settlement_key", "ST-X", "lines",
                List.of(Map.of("line_type", "SALE", "amount", 10000))));
        assertThat(c.o().post("/api/v1/finance/deposits/" + dep2 + "/matches", Map.of("run_id", other.data().get("id").asString())).errorCode())
                .isEqualTo("DEPOSIT_ENTITY_MISMATCH");
    }

    @Test
    void T32_closedMonth_lateCostsGoToNextOpenMonthLinkedToOriginal_closedTotalsUnchanged_reopenNeedsAnotherApprover() {
        Ctx c = ctx("마감상사");
        LocalDate thisMonth = LocalDate.now(ZoneId.of("Asia/Seoul")).withDayOfMonth(1);
        LocalDate prev = thisMonth.minusMonths(1);
        String o = order(c, "M-1", new Object[]{"P-100", 1, 10000, 0, 0, false});
        String item = itemId(c, o, "P-100");
        // 전월 귀속 비용: 3PL 피킹 예상 2,000(주문상품 범위), 보관비 실제 5,000
        Map<String, Object> est = Map.of("business_entity_id", c.be().toString(), "category", "THIRD_PARTY_PICK", "basis", "ESTIMATED", "supply_amount", 2000,
                "period_date", prev.plusDays(10).toString(), "scope", Map.of("order_item_id", item));
        assertThat(c.o().postWithKey("/api/v1/finance/cost-entries", est, "m-est-0001").status()).isEqualTo(201);
        assertThat(c.o().postWithKey("/api/v1/finance/cost-entries", Map.of("business_entity_id", c.be().toString(), "category", "STORAGE", "basis", "ACTUAL",
                "supply_amount", 5000, "period_date", prev.plusDays(20).toString()), "m-sto-0001").status()).isEqualTo(201);
        // 미해결 정산 검토(경고)
        manualRun(c, "ST-M", prev.plusDays(25), new Object[]{"SALE", 1000, "NO-SUCH-ORDER"});

        // 이번 달은 마감 불가
        assertThat(c.o().post("/api/v1/finance/periods/close", Map.of("business_entity_id", c.be().toString(), "month", thisMonth.toString())).errorCode())
                .isEqualTo("PERIOD_NOT_ENDED");
        JsonNode pv = c.o().get("/api/v1/finance/periods/preview?business_entity_id=" + c.be() + "&month=" + prev).data();
        assertThat(pv.get("closable").asBoolean()).isTrue();
        assertThat(pv.get("totals").get("contribution").asInt()).isEqualTo(-7000);
        List<String> codes = new java.util.ArrayList<>();
        pv.get("checks").forEach(ch -> codes.add(ch.get("code").asString()));
        assertThat(codes).contains("SETTLEMENT_REVIEW_LINES", "FINANCE_WORK_OPEN", "ESTIMATED_COST");
        // 경고 확인 없이 마감 불가, 마감 권한 없는 사용자 거부
        Map<String, Object> close = new LinkedHashMap<>(Map.of("business_entity_id", c.be().toString(), "month", prev.toString()));
        assertThat(c.o().post("/api/v1/finance/periods/close", close).errorCode()).isEqualTo("PERIOD_CLOSE_WARNINGS");
        Api ops = fx.member(c.ws(), "OPS_ADMIN");
        close.put("acknowledge_warnings", true);
        assertThat(ops.post("/api/v1/finance/periods/close", close).status()).isEqualTo(403);
        close.put("note", "정산 검토 1건 다음 달 처리");
        Api.Res closed = c.o().post("/api/v1/finance/periods/close", close);
        assertThat(closed.status()).as(closed.body().toString()).isEqualTo(200);
        assertThat(closed.data().get("status").asString()).isEqualTo("CLOSED");
        assertThat(c.o().post("/api/v1/finance/periods/close", close).errorCode()).isEqualTo("PERIOD_ALREADY_CLOSED");

        // 마감 후: 실제 3PL 청구(예상 대체) + 지연 보관비 → 이번 달로, 원 귀속월 연결
        Map<String, Object> actual = Map.of("business_entity_id", c.be().toString(), "category", "THIRD_PARTY_PICK", "basis", "ACTUAL", "supply_amount", 2400,
                "period_date", prev.plusDays(10).toString(), "scope", Map.of("order_item_id", item), "source_ref", "3PL 청구서");
        Api.Res ar = c.o().postWithKey("/api/v1/finance/cost-entries", actual, "m-act-0001");
        assertThat(ar.status()).as(ar.body().toString()).isEqualTo(201);
        assertThat(ar.data().get("variance").asInt()).isEqualTo(400);
        c.o().postWithKey("/api/v1/finance/cost-entries", Map.of("business_entity_id", c.be().toString(), "category", "STORAGE", "basis", "ACTUAL", "supply_amount", 700,
                "period_date", prev.plusDays(28).toString(), "source_ref", "지연 청구"), "m-sto-0002");
        assertThat(jdbc.sql("select count(*) from financial_entry where business_entity_id = :be and original_period_date >= :f and period_date >= :t")
                .param("be", c.be()).param("f", prev).param("t", thisMonth).query(Integer.class).single()).isEqualTo(3); // 예상 상계 + 실제 + 지연 보관비
        JsonNode after = c.o().get("/api/v1/finance/periods/preview?business_entity_id=" + c.be() + "&month=" + prev).data();
        assertThat(after.get("totals").get("contribution").asInt()).isEqualTo(-7000); // 마감 월 불변
        assertThat(after.get("snapshot_matches").asBoolean()).isTrue();
        assertThat(after.get("totals").get("posted_after_close").asInt()).isEqualTo(3);
        JsonNode cur = c.o().get("/api/v1/finance/periods/preview?business_entity_id=" + c.be() + "&month=" + thisMonth).data();
        assertThat(cur.get("totals").get("contribution").asInt()).isEqualTo(-1100); // −2,400 + 2,000 − 700
        JsonNode lateRows = c.o().get("/api/v1/finance/entries?business_entity_id=" + c.be() + "&category=STORAGE&from=" + thisMonth).data();
        assertThat(lateRows.get(0).get("memo").asString()).startsWith("마감 후 발생(원 귀속 " + prev.toString().substring(0, 7) + ")");
        // DB 에서도 마감 월에 직접 기록 불가
        assertThatThrownBy(() -> jdbc.sql("""
                insert into financial_entry (id, workspace_id, business_entity_id, entry_class, category, basis, supply_amount, period_date, source_type, source_key)
                values (gen_random_uuid(), :ws, :be, 'COST', 'STORAGE', 'ACTUAL', 1, :d, 'MANUAL_COST', 'raw-bypass')
                """).param("ws", c.ws().id()).param("be", c.be()).param("d", prev.plusDays(3)).update()).hasMessageContaining("closed");

        // 재개방: 승인 필요, 본인 승인 불가, 승인자는 월 마감 권한 필요
        Api.Res req = c.o().post("/api/v1/finance/periods/reopen-requests", Map.of("business_entity_id", c.be().toString(), "month", prev.toString(), "reason", "누락 매출 반영"));
        assertThat(req.status()).as(req.body().toString()).isEqualTo(201);
        String approval = req.data().get("id").asString();
        assertThat(req.data().get("impact").get("posted_after_close").asInt()).isEqualTo(3);
        assertThat(c.o().post("/api/v1/approvals/" + approval + "/decision", Map.of("decision", "APPROVE")).errorCode()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        assertThat(ops.post("/api/v1/approvals/" + approval + "/decision", Map.of("decision", "APPROVE")).errorCode()).isEqualTo("APPROVER_NEEDS_PERIOD_CLOSE");
        Api admin = fx.member(c.ws(), "SUPER_ADMIN");
        assertThat(admin.post("/api/v1/approvals/" + approval + "/decision", Map.of("decision", "APPROVE", "note", "확인")).status()).isEqualTo(200);
        JsonNode reopened = c.o().get("/api/v1/finance/periods/preview?business_entity_id=" + c.be() + "&month=" + prev).data();
        assertThat(reopened.get("status").asString()).isEqualTo("OPEN");
        assertThat(reopened.get("events")).hasSize(2);
        assertThat(reopened.get("events").get(1).get("event_type").asString()).isEqualTo("REOPENED");
        // 재개방해도 이미 다음 달로 귀속된 조정은 그대로(이중 반영 없음)
        assertThat(reopened.get("totals").get("contribution").asInt()).isEqualTo(-7000);
    }

    @Test
    void concurrentMatchesToSameSettlement_neverExceedPayout_concurrentReuploadNoDuplicateLines() throws Exception {
        Ctx c = ctx("동시상사");
        String run = manualRun(c, "ST-RACE", null, new Object[]{"SALE", 10000});
        List<String> deps = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            deps.add(deposit(c, 4000, "dep-race-key-" + i, null));
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        List<java.util.concurrent.Callable<Integer>> tasks = new java.util.ArrayList<>();
        for (String d : deps) {
            tasks.add(() -> c.o().post("/api/v1/finance/deposits/" + d + "/matches", Map.of("run_id", run, "amount", 4000)).status());
        }
        List<Integer> st = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<Integer> f : pool.invokeAll(tasks)) {
            st.add(f.get());
        }
        assertThat(st).filteredOn(x -> x == 201).hasSize(2); // 4,000 × 2 = 8,000 ≤ 10,000, 세 번째부터 초과
        assertThat(run(c, run).get("run").get("deposited").asInt()).isEqualTo(8000);
        // 같은 파일 동시 업로드 → 행 중복 없음
        String csv = HEAD + "ST-RACE2,,,,,,,판매,5000,\r\n" + "ST-RACE2,,,,,,,판매,5000,\r\n";
        List<java.util.concurrent.Callable<Integer>> ups = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ups.add(() -> upload(c, csv).status());
        }
        for (java.util.concurrent.Future<Integer> f : pool.invokeAll(ups)) {
            assertThat(f.get()).isEqualTo(200);
        }
        pool.shutdown();
        assertThat(jdbc.sql("select count(*) from settlement_line l join settlement_run r on r.id = l.run_id where r.settlement_key = 'ST-RACE2' and l.workspace_id = :ws")
                .param("ws", c.ws().id()).query(Integer.class).single()).isEqualTo(2); // 내용이 같은 별개 행 2개는 각각, 재업로드분은 0
    }
}
