package com.commerceos.finance;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** M6 2차: 사업자별 부가세 신고자료 표준화(docs/15) — 수집·검증·상호 배타 분류·보정·확정(다른 담당자)·대사·사업자 단위 내보내기. */
class VatTest extends FinanceTestBase {
    static final String HEAD = "거래일,주문번호,매출유형,세금유형,결제수단,증빙유형,공급가,세액,합계,사업자등록번호\r\n";

    Api.Res upload(Api api, String batch, String name, String csv) {
        return api.perform(multipart("/api/v1/vat/batches/" + batch + "/files").file(new MockMultipartFile("file", name, "text/csv", csv.getBytes(StandardCharsets.UTF_8))));
    }

    String brn(UUID be) {
        return jdbc.sql("select business_registration_no from business_entity where id = :id").param("id", be).query(String.class).single();
    }

    String batch(Api api, UUID account, LocalDate from, LocalDate to) {
        Api.Res r = api.post("/api/v1/vat/batches", Map.of("channel_account_id", account.toString(), "period_from", from.toString(), "period_to", to.toString()));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data().get("id").asString();
    }

    static JsonNode total(JsonNode totals, String cls) {
        for (JsonNode t : totals) {
            if (t.get("classification").asString().equals(cls)) {
                return t;
            }
        }
        return null;
    }

    @Test
    void vatData_validatedClassifiedExclusively_overriddenAndConfirmedByAnotherAccountant_reconciled_exportedPerEntityOnly() {
        Fixtures.Ws ws = fx.workspace();
        Ctx c = ctx(ws, "부가세에이", "VA");
        Ctx b = ctx(ws, "부가세비이", "VB");
        shipOrder(c, order(c, "V-1", new Object[]{"P-100", 1, 33000, 0, 0, false})); // 원장 인식 매출 33,000(부가세 포함)
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate from = today.withDayOfMonth(1);
        LocalDate to = today.withDayOfMonth(today.lengthOfMonth());
        String d = today.toString();
        String brn = brn(c.be());
        String batchId = batch(c.o(), c.acc(), from, to);
        assertThat(c.o().post("/api/v1/vat/batches", Map.of("channel_account_id", c.acc().toString(), "period_from", from.toString(), "period_to", to.toString()))
                .errorCode()).isEqualTo("VAT_BATCH_EXISTS");

        String csv = HEAD
                + d + ",V-1,판매,과세,카드,없음,30000,3000,33000," + brn + "\r\n"
                + d + ",V-2,판매,과세,현금영수증,,10000,1000,11000," + brn + "\r\n"
                + d + ",V-3,판매,면세,카드,없음,5000,0,5000," + brn + "\r\n"
                + d + ",V-4,판매,과세,계좌이체,세금계산서,20000,2000,22000," + brn + "\r\n"
                + d + ",V-5,판매,면세,기타,전자계산서,7000,0,7000," + brn + "\r\n"
                + d + ",V-1,환불,과세,카드,없음,-10000,-1000,-11000," + brn + "\r\n"
                + from.minusDays(1) + ",V-6,판매,과세,카드,없음,1000,100,1100," + brn + "\r\n"   // 기간 혼입
                + d + ",V-7,판매,기타,카드,없음,1000,100,1100," + brn + "\r\n"                  // 세금유형 미상
                + d + ",V-8,판매,면세,카드,없음,1000,100,1100," + brn + "\r\n"                  // 면세인데 세액
                + d + ",V-9,판매,과세,카드,없음,1000,100,1200," + brn + "\r\n"                  // 합계 불일치
                + d + ",V-10,판매,과세,카드,없음,1000,50,1050," + brn + "\r\n";                 // 세율 이탈(경고)
        Api.Res r = upload(c.o(), batchId, "smartstore-vat-2611.csv", csv);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.data().get("new_rows").asInt()).isEqualTo(11);
        assertThat(r.data().get("blocking_rows").asInt()).isEqualTo(4);
        assertThat(r.data().get("warning_rows").asInt()).isEqualTo(1);
        // 같은 파일 다시 → 거부, 겹치는 행이 있는 다른 파일 → 새 행만
        assertThat(upload(c.o(), batchId, "again.csv", csv).errorCode()).isEqualTo("VAT_FILE_DUPLICATE");
        Api.Res r2 = upload(c.o(), batchId, "part2.csv", HEAD + d + ",V-2,판매,과세,현금영수증,,10000,1000,11000," + brn + "\r\n"
                + d + ",V-11,판매,과세,휴대폰,없음,2000,200,2200," + brn + "\r\n");
        assertThat(r2.data().get("new_rows").asInt()).isEqualTo(1);
        assertThat(r2.data().get("duplicate_rows").asInt()).isEqualTo(1);
        // 다른 사업자 자료·잘못된 값 → 파일 전체 거부
        assertThat(upload(c.o(), batchId, "other.csv", HEAD + d + ",X-1,판매,과세,카드,없음,1000,100,1100," + brn(b.be()) + "\r\n").errorCode())
                .isEqualTo("VAT_ENTITY_MISMATCH");
        assertThat(upload(c.o(), batchId, "bad.csv", HEAD + d + ",X-2,판매,몰라,카드,없음,1000,100,1100,\r\n").errorCode()).isEqualTo("VAT_FILE_INVALID");
        assertThat(jdbc.sql("select count(*) from vat_row where batch_id = :b").param("b", UUID.fromString(batchId)).query(Integer.class).single()).isEqualTo(12);

        JsonNode v = c.o().get("/api/v1/vat/batches/" + batchId).data();
        JsonNode totals = v.get("totals");
        assertThat(total(totals, "CASH_RECEIPT").get("total").asInt()).isEqualTo(11000);
        assertThat(total(totals, "TAX_INVOICE").get("total").asInt()).isEqualTo(22000); // 증빙이 결제수단보다 우선(이중 합산 없음)
        assertThat(total(totals, "E_INVOICE").get("total").asInt()).isEqualTo(7000);
        assertThat(total(totals, "MOBILE").get("total").asInt()).isEqualTo(2200);
        assertThat(total(totals, "REVIEW").get("rows").asInt()).isEqualTo(1);
        int rows = 0;
        for (JsonNode t : totals) {
            rows += t.get("rows").asInt();
        }
        assertThat(rows).isEqualTo(12); // 모든 행이 정확히 한 분류에
        assertThat(v.get("files")).hasSize(2);
        assertThat(v.get("files").get(0).get("sha256").asString()).hasSize(64);
        assertThat(v.get("recon").get("ledger_total").asInt()).isEqualTo(33000);
        assertThat(v.get("recon").get("settlement_imported").asBoolean()).isFalse();

        // 확정: 올린 사람 불가, 다른 회계 담당자도 문제 행 보정 전에는 불가
        Api acct = fx.member(ws, "FINANCE");
        assertThat(c.o().post("/api/v1/vat/batches/" + batchId + "/confirm", Map.of()).errorCode()).isEqualTo("VAT_SELF_CONFIRM_FORBIDDEN");
        assertThat(acct.post("/api/v1/vat/batches/" + batchId + "/confirm", Map.of()).errorCode()).isEqualTo("VAT_BATCH_HAS_ISSUES");
        for (JsonNode row : v.get("issue_rows")) {
            if (!row.get("blocking").asBoolean()) {
                continue;
            }
            List<String> issues = new java.util.ArrayList<>();
            row.get("issues").forEach(x -> issues.add(x.asString()));
            String cls = issues.contains("TAX_TYPE_UNKNOWN") ? "CARD" : "EXCLUDED";
            Api.Res o = acct.post("/api/v1/vat/rows/" + row.get("id").asString() + "/override", Map.of("classification", cls, "reason", "원본 확인: " + issues));
            assertThat(o.status()).as(o.body().toString()).isEqualTo(200);
        }
        assertThat(acct.post("/api/v1/vat/rows/" + v.get("issue_rows").get(0).get("id").asString() + "/override", Map.of("classification", "CARD")).errorCode())
                .isEqualTo("VALIDATION_FAILED");
        Api.Res conf = acct.post("/api/v1/vat/batches/" + batchId + "/confirm", Map.of());
        assertThat(conf.status()).as(conf.body().toString()).isEqualTo(200);
        assertThat(conf.data().get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(conf.data().get("rule_version").asString()).isEqualTo("vat-class-v1");
        assertThat(upload(c.o(), batchId, "late.csv", HEAD + d + ",V-12,판매,과세,카드,없음,100,10,110," + brn + "\r\n").errorCode()).isEqualTo("VAT_BATCH_CONFIRMED");
        // 원본 행은 불변
        assertThat(jdbc.sql("select count(*) from vat_row where batch_id = :b and classification = 'REVIEW'").param("b", UUID.fromString(batchId)).query(Integer.class)
                .single()).isEqualTo(1);

        // 사업자 B 자료(별도 묶음)
        String bBatch = batch(b.o(), b.acc(), from, to);
        upload(b.o(), bBatch, "b.csv", HEAD + d + ",VB-1,판매,과세,카드,없음,9000,900,9900," + brn(b.be()) + "\r\n");

        // 사업자 단위 신고자료: A 만, 모두 확정
        JsonNode rep = c.o().get("/api/v1/vat/reports?business_entity_id=" + c.be() + "&from=" + from + "&to=" + to).data();
        assertThat(rep.get("all_confirmed").asBoolean()).isTrue();
        assertThat(rep.get("batches")).hasSize(1);
        assertThat(total(rep.get("totals"), "EXCLUDED").get("rows").asInt()).isEqualTo(3);

        // 내보내기: 사업자 1곳, 실행 시점 범위 재평가, B 자료 없음
        Api.Res ex = c.o().post("/api/v1/vat/exports", Map.of("business_entity_id", c.be().toString(), "period_from", from.toString(), "period_to", to.toString()));
        assertThat(ex.status()).as(ex.body().toString()).isEqualTo(202);
        drainAsync();
        JsonNode job = c.o().get("/api/v1/jobs/" + ex.data().get("job_id").asString()).data();
        assertThat(job.get("status").asString()).isEqualTo("SUCCEEDED");
        String file = new String(c.o().download("/api/v1/files/" + job.get("result").get("file_id").asString() + "/content"), StandardCharsets.UTF_8);
        assertThat(file).contains(brn).contains("세금계산서").contains("전자계산서").contains("vat-class-v1").doesNotContain("VB-1").doesNotContain(brn(b.be()));

        // 범위: A 만 보는 회계 담당자는 B 묶음·B 내보내기 불가, 다른 운영그룹은 존재도 모름
        Api finA = fx.member(ws, "FINANCE");
        fx.setPolicy(c.o(), finA, null, List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", c.be(), "ALLOW"),
                scope("BUSINESS_ENTITY", "EXPORT", "SELECTED", c.be(), "ALLOW")));
        assertThat(finA.get("/api/v1/vat/batches/" + bBatch).status()).isIn(403, 404);
        assertThat(finA.post("/api/v1/vat/exports", Map.of("business_entity_id", b.be().toString(), "period_from", from.toString(), "period_to", to.toString()))
                .status()).isIn(403, 404);
        assertThat(finA.get("/api/v1/vat/batches").data()).hasSize(1);
        assertThat(fx.workspace().owner().get("/api/v1/vat/batches/" + batchId).status()).isEqualTo(404);
        assertThat(new BigDecimal(v.get("recon").get("diff_vs_ledger").asString()).signum()).isNotZero();
    }

    @Test
    void concurrentSameFileUpload_importedOnce() throws Exception {
        Ctx c = ctx("동시부가세");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        String batchId = batch(c.o(), c.acc(), today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()));
        String csv = HEAD + today + ",C-1,판매,과세,카드,없음,1000,100,1100,\r\n" + today + ",C-2,판매,과세,카드,없음,2000,200,2200,\r\n";
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Callable<Integer>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> upload(c.o(), batchId, "same.csv", csv).status());
        }
        List<Integer> st = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<Integer> f : pool.invokeAll(tasks)) {
            st.add(f.get());
        }
        pool.shutdown();
        assertThat(st).filteredOn(x -> x == 200).hasSize(1);
        assertThat(st).filteredOn(x -> x == 409).hasSize(3);
        assertThat(jdbc.sql("select count(*) from vat_row where batch_id = :b").param("b", UUID.fromString(batchId)).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void classificationRule_isMutuallyExclusive() {
        assertThat(VatService.classify("TAXABLE", "CARD", "NONE")).isEqualTo("CARD");
        assertThat(VatService.classify("TAXABLE", "CARD", "TAX_INVOICE")).isEqualTo("TAX_INVOICE");
        assertThat(VatService.classify("TAX_FREE", "MOBILE", "NONE")).isEqualTo("FREE_MOBILE");
        assertThat(VatService.classify("TAX_FREE", "CARD", "E_INVOICE")).isEqualTo("E_INVOICE");
        assertThat(VatService.classify("TAX_FREE", "CARD", "TAX_INVOICE")).isEqualTo("REVIEW");
        assertThat(VatService.classify("ZERO_RATED", "CARD", "NONE")).isEqualTo("ZERO_RATED");
        assertThat(VatService.classify("OTHER", "CARD", "NONE")).isEqualTo("REVIEW");
    }
}
