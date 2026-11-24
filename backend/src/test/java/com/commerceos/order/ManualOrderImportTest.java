package com.commerceos.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.commerceos.finance.FinanceTestBase;
import com.commerceos.support.Api;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** 수동·B2B 주문 파일 일괄 등록: 묶음 = 주문, 묶음 단위 오류 제외, 단가표, 여신, 개인정보 마스킹·암호화·원본 삭제, 재업로드 중복 방지. */
class ManualOrderImportTest extends FinanceTestBase {
    static final String HEADER = "주문 묶음 키*,판매채널 코드*,판매계정 외부키*,주문 종류,거래처 코드,거래처명,결제 조건,받는 분*,연락처*,우편번호,주소*,상세 주소,배송 메모,배송비,주문 메모,SKU 코드*,수량*,단가(부가세 포함)\r\n";

    UUID upload(Api api, String csv) {
        Api.Res r = api.perform(multipart("/api/v1/files").file(new MockMultipartFile("file", "b2b_orders.csv", "text/csv", ("﻿" + csv).getBytes(StandardCharsets.UTF_8)))
                .param("purpose", "GENERIC_IMPORT"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.id();
    }

    @Test
    void bulkManualOrders_groupedByRef_groupErrorsExcluded_piiProtected_sourcePurged_reuploadDeduplicated() {
        Ctx c = ctx("일괄주문상사");
        Api o = c.o();
        String key = jdbc.sql("select external_account_key from channel_account where id = :id").param("id", c.acc()).query(String.class).single();
        String cu = o.post("/api/v1/b2b-customers", Map.of("business_entity_id", c.be().toString(), "code", "OLIVE", "name", "올리브 유통", "credit_limit", 100000))
                .data().get("id").asString();
        o.post("/api/v1/b2b-customers/" + cu + "/prices", Map.of("prices", List.of(Map.of("sku_id", c.amp().toString(), "unit_price", 12000))));
        String acc = "NAVER_SMARTSTORE," + key + ",";
        String csv = HEADER
                + "PO-1," + acc + "B2B,OLIVE,,CREDIT,김납품,010-2222-3333,13529,경기도 성남시 분당구 판교역로 1,3층,,0,,AMP-30,2,\r\n"        // 단가표 단가(경고)
                + "PO-1," + acc + "B2B,OLIVE,,CREDIT,김납품,010-2222-3333,13529,경기도 성남시 분당구 판교역로 1,3층,,0,,AMP-50,1,15000\r\n"
                + "PO-2," + acc + "MANUAL,,,,이현장,010-1111-2222,,서울특별시 중구 세종대로 110,,,3000,,AMP-30,1,30000\r\n"
                + "PO-3," + acc + "MANUAL,,,,박하나,010-3333-4444,,서울특별시 종로구 1,,,0,,AMP-30,1,30000\r\n"
                + "PO-3," + acc + "MANUAL,,,,박둘,010-3333-4444,,서울특별시 종로구 1,,,0,,AMP-50,1,30000\r\n"               // 묶음 안 수령인 다름 → 묶음 전체 오류
                + "PO-4," + acc + "MANUAL,,,,최없음,010-5555-6666,,부산광역시 해운대구 1,,,0,,NOPE-1,1,1000\r\n";             // 없는 SKU
        UUID file = upload(o, csv);
        Api.Res pr = o.post("/api/v1/imports", Map.of("file_id", file.toString(), "import_type", "MANUAL_ORDER"));
        assertThat(pr.status()).as(pr.body().toString()).isEqualTo(201);
        JsonNode b = pr.data();
        assertThat(b.get("ok_rows").asInt()).isEqualTo(2);
        assertThat(b.get("warning_rows").asInt()).isEqualTo(1);
        assertThat(b.get("error_rows").asInt()).isEqualTo(3);
        String id = b.get("id").asString();
        String rows = o.get("/api/v1/imports/" + id + "/rows").data().toString();
        assertThat(rows).contains("첫 행과 다릅니다", "함께 제외", "NOPE-1", "단가표 단가");
        // 개인정보: 미리보기·저장 행 모두 평문 없음(마스킹 + 암호화 묶음)
        assertThat(rows).doesNotContain("010-2222-3333").doesNotContain("판교역로").doesNotContain("김납품");
        assertThat(jdbc.sql("select count(*) from import_row where batch_id = cast(:b as uuid) and (raw::text like '%010-2222%' or normalized::text like '%010-2222%')")
                .param("b", id).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from import_row where batch_id = cast(:b as uuid) and status <> 'ERROR' and secret_cipher is not null").param("b", id)
                .query(Integer.class).single()).isEqualTo(3);

        int v = o.get("/api/v1/imports/" + id).data().get("version").asInt();
        Api.Res ex = o.postIfMatch("/api/v1/imports/" + id + "/execute", Map.of("confirm_rows", 3), v);
        assertThat(ex.status()).as(ex.body().toString()).isIn(200, 202);
        drainAsync();
        JsonNode done = o.get("/api/v1/imports/" + id).data();
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("applied_rows").asInt()).isEqualTo(3);
        List<String> refs = new ArrayList<>();
        o.get("/api/v1/imports/" + id + "/rows?status=WARNING").data().forEach(r -> refs.add(r.get("applied_ref").asString()));
        o.get("/api/v1/imports/" + id + "/rows?status=OK").data().forEach(r -> refs.add(r.get("applied_ref").asString()));
        assertThat(refs).hasSize(3);
        assertThat(refs.stream().distinct().count()).isEqualTo(2); // PO-1 두 줄 = 주문 하나

        // PO-1: 거래처 외상 주문(단가표 12,000 × 2 + 15,000), 수령인은 주문 원장에 암호화·마스킹
        String po1 = jdbc.sql("select id from sales_order where import_ref = :r").param("r", ManualOrderService.importRef(c.acc(), "PO-1")).query(UUID.class).single()
                .toString();
        JsonNode order = o.get("/api/v1/orders/" + po1).data();
        assertThat(order.get("order").get("total_amount").asInt()).isEqualTo(39000);
        assertThat(order.get("order").get("order_kind").asString()).isEqualTo("B2B");
        assertThat(order.get("items")).hasSize(2);
        assertThat(order.toString()).doesNotContain("010-2222-3333");
        assertThat(new java.math.BigDecimal(o.get("/api/v1/b2b-customers/" + cu + "/receivables").data().get("unbilled_orders").asString()).intValue()).isEqualTo(39000);
        String po2 = jdbc.sql("select id from sales_order where import_ref = :r").param("r", ManualOrderService.importRef(c.acc(), "PO-2")).query(UUID.class).single()
                .toString();
        assertThat(o.get("/api/v1/orders/" + po2).data().get("order").get("total_amount").asInt()).isEqualTo(33000);

        // 원본 파일 본문은 처리 후 삭제(기록·해시는 남음)
        assertThat(jdbc.sql("select count(*) from stored_file_purge where file_id = :f").param("f", file).query(Integer.class).single()).isEqualTo(1);
        assertThat(o.post("/api/v1/imports", Map.of("file_id", file.toString(), "import_type", "MANUAL_ORDER")).errorCode()).isEqualTo("FILE_PURGED");

        // 같은 파일 재업로드: 이미 등록한 묶음은 오류(중복 주문 없음)
        JsonNode again = o.post("/api/v1/imports", Map.of("file_id", upload(o, csv).toString(), "import_type", "MANUAL_ORDER")).data();
        assertThat(again.get("ok_rows").asInt() + again.get("warning_rows").asInt()).isZero();
        assertThat(o.get("/api/v1/imports/" + again.get("id").asString() + "/rows").data().toString()).contains("이미 등록한 주문 묶음");
        // 미리보기만 하고 취소해도 원본 본문 삭제
        int av = o.get("/api/v1/imports/" + again.get("id").asString()).data().get("version").asInt();
        assertThat(o.postIfMatch("/api/v1/imports/" + again.get("id").asString() + "/cancel", Map.of(), av).status()).isEqualTo(200);
        assertThat(jdbc.sql("select count(*) from stored_file_purge where workspace_id = :ws").param("ws", c.ws().id()).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from sales_order where import_ref like :r").param("r", "REF:" + c.acc() + ":%").query(Integer.class).single()).isEqualTo(2);

        // 미리보기 후 7일 넘게 실행하지 않으면 보관기간 정리가 자동 취소 + 암호화 값·원본 본문 삭제
        String stale = o.post("/api/v1/imports", Map.of("file_id", upload(o, HEADER + "PO-9," + acc + "MANUAL,,,,정오래,010-9999-0000,,서울특별시 1,,,0,,AMP-30,1,1000\r\n")
                .toString(), "import_type", "MANUAL_ORDER")).data().get("id").asString();
        jdbc.sql("update import_batch set created_at = now() - interval '8 days' where id = cast(:id as uuid)").param("id", stale).update();
        assertThat(retention.purge(java.time.Instant.now()).expiredSensitiveImports()).isGreaterThanOrEqualTo(1);
        assertThat(o.get("/api/v1/imports/" + stale).data().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("select count(*) from import_row where batch_id = cast(:b as uuid) and secret_cipher is not null").param("b", stale).query(Integer.class)
                .single()).isZero();
        assertThat(jdbc.sql("select count(*) from stored_file_purge where workspace_id = :ws").param("ws", c.ws().id()).query(Integer.class).single()).isEqualTo(3);
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.commerceos.operations.RetentionService retention;
}
