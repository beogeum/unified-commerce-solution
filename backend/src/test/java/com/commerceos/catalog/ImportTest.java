package com.commerceos.catalog;

import static com.commerceos.support.Fixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** M2 C6: 일괄 가져오기 — 양식·인코딩·미리보기 규칙·문제 행·실행 전 확인·비동기 실행·재실행 멱등·권한. */
class ImportTest extends IntegrationTest {

    UUID upload(Api api, String name, byte[] content) {
        Api.Res r = api.perform(multipart("/api/v1/files").file(new MockMultipartFile("file", name, "text/csv", content))
                .param("purpose", "GENERIC_IMPORT"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.id();
    }

    Api.Res preview(Api api, UUID file, String type) {
        return api.post("/api/v1/imports", Map.of("file_id", file.toString(), "import_type", type));
    }

    static byte[] utf8Bom(String csv) {
        return ("﻿" + csv).getBytes(StandardCharsets.UTF_8);
    }

    record Ctx(Fixtures.Ws ws, UUID existingProduct) {
    }

    Ctx ctx() {
        Fixtures.Ws ws = fx.workspace();
        Api o = ws.owner();
        o.post("/api/v1/brands", Map.of("name", "루미에르"));
        UUID root = o.post("/api/v1/categories", Map.of("name", "스킨케어")).id();
        o.post("/api/v1/categories", Map.of("name", "앰플", "parent_id", root.toString()));
        UUID p = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "CRM", "name", "수분 크림")).data().get("product").get("id").asString());
        o.post("/api/v1/skus", Map.of("product_id", p.toString(), "sku_code", "CRM-50", "sku_type", "NORMAL", "barcode", "8801234567909"));
        return new Ctx(ws, p);
    }

    static final String HEADER = "상품코드*,상품명*,브랜드,카테고리,과세유형,옵션1,옵션1 값,SKU 코드*,SKU 유형,바코드,중량(g)\r\n";

    @Test
    void productSkuImport_previewRules_problemRows_confirmedAsyncExecution() {
        Ctx c = ctx();
        Api o = c.ws().owner();
        String csv = HEADER
                + "AMP,비타 앰플,루미에르,스킨케어 > 앰플,과세,용량,30ml,AMP-30,일반,8801234567001,120\r\n"
                + "AMP,비타 앰플,루미에르,스킨케어 > 앰플,과세,용량,50ml,AMP-50,,,\r\n"       // 중량 미입력 경고
                + "CRM,수분 크림,,,,,,CRM-50,일반,,100\r\n"                                     // 기존 SKU 코드 → 오류
                + "LIP,립밤 3종 세트,루미에르,앰플,과세,,,LIP-SET3,일반,8801234567909,30\r\n"  // 세트 이름+일반 경고, 바코드 중복 경고
                + "TNR,토너,없는브랜드,,과세,,,TNR-500,일반,,500\r\n"                          // 브랜드 없음 → 오류
                + "AMP,비타 앰플,루미에르,스킨케어 > 앰플,과세,용량,30ml,AMP-30B,일반,,120\r\n"; // 파일 안 옵션 조합 중복 → 오류
        Api.Res r = preview(o, upload(o, "12월_신규상품.csv", utf8Bom(csv)), "PRODUCT_SKU");
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        JsonNode b = r.data();
        assertThat(b.get("total_rows").asInt()).isEqualTo(6);
        assertThat(b.get("ok_rows").asInt()).isEqualTo(1);
        assertThat(b.get("warning_rows").asInt()).isEqualTo(2);
        assertThat(b.get("error_rows").asInt()).isEqualTo(3);
        String id = b.get("id").asString();
        String rows = o.get("/api/v1/imports/" + id + "/rows").data().toString();
        assertThat(rows).contains("SKU 코드가 이미 있습니다", "중량 미입력", "세트로 보이지만", "CRM-50 와(과) 중복", "브랜드 '없는브랜드'", "같은 상품·옵션 조합");
        // 행 번호는 엑셀에서 보이는 번호(머리글 1행)
        assertThat(o.get("/api/v1/imports/" + id + "/rows?status=ERROR").data().get(0).get("row_number").asInt()).isEqualTo(4);

        String problems = new String(o.download("/api/v1/imports/" + id + "/problem-rows"), StandardCharsets.UTF_8);
        assertThat(problems).startsWith("﻿원본 행,상태,사유").contains("오류").contains("경고").doesNotContain("AMP-30,");

        // 실행 전 확인: 미리보기의 실행 대상 수(정상+경고 = 3)와 맞아야 실행
        int v = o.get("/api/v1/imports/" + id).data().get("version").asInt();
        assertThat(o.postIfMatch("/api/v1/imports/" + id + "/execute", Map.of("confirm_rows", 6), v).errorCode()).isEqualTo("IMPORT_CONFIRMATION_MISMATCH");
        Api.Res ex = o.postIfMatch("/api/v1/imports/" + id + "/execute", Map.of("confirm_rows", 3), v);
        assertThat(ex.status()).as(ex.body().toString()).isEqualTo(202);
        assertThat(ex.data().get("status").asString()).isEqualTo("QUEUED");
        drainAsync();
        JsonNode done = o.get("/api/v1/imports/" + id).data();
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("applied_rows").asInt()).isEqualTo(3);
        // 새 상품 AMP 는 두 행의 옵션 값을 모아 한 번에 생성(초안)
        JsonNode amp = o.get("/api/v1/products?q=AMP").data().get(0);
        assertThat(amp.get("status").asString()).isEqualTo("DRAFT");
        JsonNode detail = o.get("/api/v1/products/" + amp.get("id").asString()).data();
        assertThat(detail.get("options").get(0).get("values")).hasSize(2);
        assertThat(detail.get("skus")).hasSize(2);
        assertThat(o.get("/api/v1/skus?q=AMP-30").data().get(0).get("barcode").asString()).isEqualTo("8801234567001");
        assertThat(o.get("/api/v1/imports/" + id + "/rows?status=SKIPPED").data()).hasSize(3);
        assertThat(o.postIfMatch("/api/v1/imports/" + id + "/execute", Map.of("confirm_rows", 3), done.get("version").asInt()).errorCode())
                .isEqualTo("IMPORT_NOT_PREVIEWED");

        // 작업이 다시 실행돼도(재전달) 적용된 행을 다시 적용하지 않는다
        UUID jobId = UUID.fromString(done.get("job_id").asString());
        jdbc.sql("update async_job set status = 'PENDING', available_at = now() where id = :id").param("id", jobId).update();
        drainAsync();
        assertThat(jdbc.sql("select count(*) from sku where workspace_id = :ws").param("ws", c.ws().id()).query(Integer.class).single()).isEqualTo(4);
        assertThat(o.get("/api/v1/imports/" + id).data().get("failed_rows").asInt()).isZero();
        assertThat(jdbc.sql("select count(*) from audit_log where workspace_id = :ws and action = 'SKU_CREATED'").param("ws", c.ws().id())
                .query(Integer.class).single()).isEqualTo(4); // 기존 1 + 가져오기 3: 같은 감사·규칙 경로를 거침
    }

    @Test
    void xlsxWithNumericBarcode_andCp949Csv_areReadExactly() throws Exception {
        Ctx c = ctx();
        Api o = c.ws().owner();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("상품");
            String[] head = {"상품코드", "상품명", "SKU 코드", "바코드", "중량(g)"};
            var h = sheet.createRow(0);
            for (int i = 0; i < head.length; i++) {
                h.createCell(i).setCellValue(head[i]);
            }
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("GRN");
            row.createCell(1).setCellValue("그래놀라");
            row.createCell(2).setCellValue("GRN-500");
            row.createCell(3).setCellValue(8801234567018d); // 숫자 셀 13자리
            row.createCell(4).setCellValue(500d);
            wb.write(out);
        }
        JsonNode b = preview(o, upload(o, "food.xlsx", out.toByteArray()), "PRODUCT_SKU").data();
        assertThat(b.get("sheet_name").asString()).isEqualTo("상품");
        assertThat(b.get("ok_rows").asInt()).isEqualTo(1);
        o.postIfMatch("/api/v1/imports/" + b.get("id").asString() + "/execute", Map.of("confirm_rows", 1), b.get("version").asInt());
        drainAsync();
        assertThat(o.get("/api/v1/skus?q=GRN-500").data().get(0).get("barcode").asString()).isEqualTo("8801234567018");
        assertThat(o.get("/api/v1/skus?q=GRN-500").data().get(0).get("weight_g").asString()).isEqualTo("500.000");

        byte[] cp949 = "상품코드,상품명,SKU 코드\r\nOAT,오트밀,OAT-1\r\n".getBytes(Charset.forName("MS949"));
        JsonNode k = preview(o, upload(o, "excel-export.csv", cp949), "PRODUCT_SKU").data();
        assertThat(k.get("ok_rows").asInt() + k.get("warning_rows").asInt()).isEqualTo(1);
        assertThat(o.get("/api/v1/imports/" + k.get("id").asString() + "/rows").data().get(0).get("raw").get("상품명").asString()).isEqualTo("오트밀");

        // 필수 컬럼 없음
        Api.Res missing = preview(o, upload(o, "bad.csv", utf8Bom("이름,코드\r\n가,나\r\n")), "PRODUCT_SKU");
        assertThat(missing.errorCode()).isEqualTo("IMPORT_COLUMNS_MISSING");
        assertThat(missing.body().get("error").get("details").get("missing").toString()).contains("상품코드", "SKU 코드");
        // 수식 주입 방지: 양식 파일
        String template = new String(o.download("/api/v1/imports/templates/PRODUCT_SKU/file"), StandardCharsets.UTF_8);
        assertThat(template).startsWith("﻿\"상품코드*\"");
    }

    @Test
    void channelListingImport_respectsScope_mapsAndRefusesSilentRemap() {
        Ctx c = ctx();
        Api o = c.ws().owner();
        UUID beA = fx.businessEntity(o, "A 코스메틱");
        UUID beB = fx.businessEntity(o, "B 리빙");
        fx.channelAccount(o, beA, "NAVER_SMARTSTORE", "A 스토어", "seller-a", null);
        fx.channelAccount(o, beB, "NAVER_SMARTSTORE", "B 스토어", "seller-b", null);
        UUID p = UUID.fromString(o.post("/api/v1/products", Map.of("product_code", "AMP", "name", "앰플")).data().get("product").get("id").asString());
        o.post("/api/v1/skus", Map.of("product_id", p.toString(), "sku_code", "AMP-30", "sku_type", "NORMAL"));
        o.post("/api/v1/skus", Map.of("product_id", p.toString(), "sku_code", "AMP-50", "sku_type", "NORMAL"));

        Api md = fx.member(c.ws(), "MD");
        assertThat(o.put("/api/v1/users/" + md.userId + "/access-policy", Map.of("permission_overrides", List.of(),
                "scopes", List.of(scope("BUSINESS_ENTITY", "READ", "SELECTED", beA, "ALLOW"), scope("BUSINESS_ENTITY", "WRITE", "SELECTED", beA, "ALLOW"))),
                1).status()).isEqualTo(200);
        String csv = "판매채널 코드,판매계정 외부키,외부 상품ID,외부 옵션ID,채널 상품명,연결 SKU 코드\r\n"
                + "NAVER_SMARTSTORE,seller-a,9921,1,비타 앰플 30ml,AMP-30\r\n"
                + "NAVER_SMARTSTORE,seller-b,9921,1,비타 앰플 30ml,AMP-30\r\n"   // 범위 밖 계정 → 없는 계정과 같은 메시지
                + "NAVER_SMARTSTORE,seller-x,1,1,없는 계정,AMP-30\r\n"
                + "NAVER_SMARTSTORE,seller-a,9922,,클렌징폼,NOPE\r\n";
        JsonNode b = preview(md, upload(md, "listings.csv", utf8Bom(csv)), "CHANNEL_LISTING").data();
        assertThat(b.get("ok_rows").asInt()).isEqualTo(1);
        assertThat(b.get("error_rows").asInt()).isEqualTo(3);
        JsonNode rows = md.get("/api/v1/imports/" + b.get("id").asString() + "/rows?status=ERROR").data();
        assertThat(rows.get(0).get("messages").get(0).get("text").asString()).isEqualTo(rows.get(1).get("messages").get(0).get("text").asString());
        md.postIfMatch("/api/v1/imports/" + b.get("id").asString() + "/execute", Map.of("confirm_rows", 1), b.get("version").asInt());
        drainAsync();
        JsonNode listing = md.get("/api/v1/channel-listings?q=9921").data().get(0);
        assertThat(listing.get("current_mapping").get("sku_code").asString()).isEqualTo("AMP-30");
        assertThat(listing.get("current_mapping").get("match_basis").asString()).isEqualTo("FILE");

        // 같은 채널상품을 다른 SKU 로: 처음 연결이면 반영되지만(이력에 다른 SKU 없음 → 그대로 있음), 이력이 있으면 파일로는 거부
        o.post("/api/v1/channel-listings/" + listing.get("id").asString() + "/mapping",
                Map.of("sku_id", o.get("/api/v1/skus?q=AMP-50").data().get(0).get("id").asString(), "confirm_remap", true, "reason", "정정"));
        String again = "판매채널 코드,판매계정 외부키,외부 상품ID,외부 옵션ID,채널 상품명,연결 SKU 코드\r\nNAVER_SMARTSTORE,seller-a,9921,1,비타 앰플 30ml,AMP-30\r\n";
        JsonNode r2 = preview(md, upload(md, "again.csv", utf8Bom(again)), "CHANNEL_LISTING").data();
        assertThat(r2.get("error_rows").asInt()).isEqualTo(1);
        assertThat(md.get("/api/v1/imports/" + r2.get("id").asString() + "/rows").data().toString()).contains("다른 SKU 에 연결된 이력");

        assertThat(preview(fx.member(c.ws(), "VIEWER"), upload(o, "x.csv", utf8Bom(again)), "CHANNEL_LISTING").status()).isIn(403, 404);
    }

    @Test
    void permissionRevokedBeforeExecution_failsBatchWithoutChanges() {
        Ctx c = ctx();
        Api md = fx.member(c.ws(), "MD");
        JsonNode b = preview(md, upload(md, "p.csv", utf8Bom("상품코드,상품명,SKU 코드\r\nNEW,새 상품,NEW-1\r\n")), "PRODUCT_SKU").data();
        assertThat(md.postIfMatch("/api/v1/imports/" + b.get("id").asString() + "/execute", Map.of("confirm_rows", 1), b.get("version").asInt())
                .status()).isEqualTo(202);
        assertThat(c.ws().owner().patch("/api/v1/users/" + md.userId, Map.of("role_codes", List.of("VIEWER")), 1).status()).isEqualTo(200);
        drainAsync();
        JsonNode done = c.ws().owner().get("/api/v1/imports/" + b.get("id").asString()).data();
        assertThat(done.get("status").asString()).isEqualTo("FAILED");
        assertThat(c.ws().owner().get("/api/v1/skus?q=NEW-1").data()).isEmpty();
        // 다른 운영그룹은 배치를 볼 수 없다
        assertThat(ctx().ws().owner().get("/api/v1/imports/" + b.get("id").asString()).status()).isEqualTo(404);
    }
}
