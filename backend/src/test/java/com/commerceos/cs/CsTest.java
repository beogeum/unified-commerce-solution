package com.commerceos.cs;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.finance.FinanceTestBase;
import com.commerceos.support.Api;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** FR-CS-01·05: 문의 기록(채널 문의번호 중복 방지·주문 연결·PII), 답변 기록 → 채널 직접 등록 표시(자동 전송 없음), 재문의, 템플릿, 응답기한 업무, 권한. */
class CsTest extends FinanceTestBase {
    @Autowired
    CsService cs;

    @Test
    void inquiryLifecycle_templates_overdue_permissions() {
        Ctx c = ctx("CS상사");
        String o = order(c, "CS-1", new Object[]{"P-100", 1, 33000, 0, 0, false});
        shipOrder(c, o);
        Map<String, Object> body = new LinkedHashMap<>(Map.of("order_id", o, "source", "CHANNEL", "external_inquiry_key", "Q-777", "inquiry_type", "DELIVERY",
                "title", "배송 언제 오나요", "message", "주문한 앰플 언제 도착하나요?"));
        Api.Res r = c.o().post("/api/v1/cs/inquiries", body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        JsonNode q = r.data();
        String id = q.get("id").asString();
        assertThat(q.get("inquiry_no").asString()).startsWith("CS-");
        assertThat(q.get("status").asString()).isEqualTo("OPEN");
        assertThat(q.get("business_entity_id").asString()).isEqualTo(c.be().toString()); // 주문의 사업자
        assertThat(q.get("order_summary").get("tracking_no").asString()).isNotEmpty();
        assertThat(c.o().post("/api/v1/cs/inquiries", body).errorCode()).isEqualTo("CS_INQUIRY_DUPLICATE");

        // 템플릿 → 변수 채우기(고객명은 마스킹)
        String tpl = c.o().post("/api/v1/cs/templates", Map.of("name", "배송 안내", "inquiry_type", "DELIVERY",
                "body", "{고객명}님, 주문 {주문번호}의 {상품}은 송장 {송장번호}로 출고되었습니다.")).data().get("id").asString();
        JsonNode rendered = c.o().get("/api/v1/cs/inquiries/" + id + "/render?template_id=" + tpl).data();
        assertThat(rendered.get("body").asString()).contains("CS-1").contains("앰플").doesNotContain("{송장번호}").doesNotContain("홍길동");
        // 답변 기록 → 채널 직접 등록 필요(자동 전송 없음)
        JsonNode ans = c.o().post("/api/v1/cs/inquiries/" + id + "/messages", Map.of("direction", "OUTBOUND", "body", rendered.get("body").asString(),
                "template_id", tpl)).data();
        assertThat(ans.get("status").asString()).isEqualTo("ANSWERED");
        assertThat(ans.get("pending_channel_answers").asInt()).isEqualTo(1);
        assertThat(ans.get("first_response_at").isNull()).isFalse();
        String msg = null;
        for (JsonNode m : ans.get("messages")) {
            if (m.get("direction").asString().equals("OUTBOUND")) {
                msg = m.get("id").asString();
                assertThat(m.get("channel_status").asString()).isEqualTo("MANUAL_REQUIRED");
            }
        }
        assertThat(c.o().get("/api/v1/cs/kpi").data().get("waiting_channel_post").asInt()).isEqualTo(1);
        JsonNode posted = c.o().post("/api/v1/cs/inquiries/" + id + "/messages/" + msg + "/channel-posted", Map.of()).data();
        assertThat(posted.get("pending_channel_answers").asInt()).isZero();
        assertThat(c.o().post("/api/v1/cs/inquiries/" + id + "/messages/" + msg + "/channel-posted", Map.of()).errorCode()).isEqualTo("CS_MESSAGE_NOT_PENDING");
        // 메시지 원문은 바꿀 수 없음
        UUID mid = UUID.fromString(msg);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("update cs_message set body = 'x' where id = :id").param("id", mid).update())
                .hasMessageContaining("immutable");

        // 고객 재문의 → 다시 열림·재응답 표시, 종료 후 재문의도 다시 열림
        JsonNode re = c.o().post("/api/v1/cs/inquiries/" + id + "/messages", Map.of("direction", "INBOUND", "body", "아직 안 왔어요")).data();
        assertThat(re.get("status").asString()).isEqualTo("OPEN");
        assertThat(re.get("customer_reply").asBoolean()).isTrue();
        JsonNode closed = c.o().patch("/api/v1/cs/inquiries/" + id, Map.of("status", "CLOSED"), null).data();
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(c.o().post("/api/v1/cs/inquiries/" + id + "/messages", Map.of("direction", "NOTE", "body", "x")).errorCode()).isEqualTo("CS_INQUIRY_CLOSED");
        assertThat(c.o().patch("/api/v1/cs/inquiries/" + id, Map.of("status", "IN_PROGRESS"), 1).errorCode()).isEqualTo("VERSION_CONFLICT");

        // 주문 없는 전화 문의: 고객 정보 암호화·마스킹, 응답기한 지나면 업무함
        Api.Res phone = c.o().post("/api/v1/cs/inquiries", Map.of("channel_account_id", c.acc().toString(), "source", "PHONE", "inquiry_type", "PRODUCT",
                "title", "성분 문의", "message", "임산부 사용 가능한가요", "customer_name", "박고객", "customer_phone", "010-9999-8888", "priority", "URGENT"));
        assertThat(phone.status()).as(phone.body().toString()).isEqualTo(201);
        assertThat(phone.data().get("customer_name_masked").asString()).isEqualTo("박*객");
        assertThat(phone.body().toString()).doesNotContain("010-9999-8888");
        assertThat(cs.scanOverdue(Instant.now().plusSeconds(6 * 3600))).isGreaterThanOrEqualTo(1);
        assertThat(c.o().get("/api/v1/work-items?item_type=CS_RESPONSE_OVERDUE&status=OPEN").data()).hasSize(1);
        assertThat(cs.scanOverdue(Instant.now().plusSeconds(6 * 3600))).isZero(); // 중복 없음
        c.o().post("/api/v1/cs/inquiries/" + phone.data().get("id").asString() + "/messages", Map.of("direction", "OUTBOUND", "body", "사용 전 전문의 상담을 권합니다."));
        assertThat(c.o().get("/api/v1/work-items?item_type=CS_RESPONSE_OVERDUE&status=OPEN").data()).isEmpty(); // 답변하면 해결

        // 권한: 조회만 가능한 창고 작업자는 기록 불가, 다른 운영그룹은 존재도 모름
        Api wh = fx.member(c.ws(), "WAREHOUSE_WORKER");
        assertThat(wh.get("/api/v1/cs/inquiries/" + id).status()).isEqualTo(200);
        assertThat(wh.post("/api/v1/cs/inquiries/" + id + "/messages", Map.of("direction", "NOTE", "body", "x")).status()).isEqualTo(403);
        assertThat(fx.workspace().owner().get("/api/v1/cs/inquiries/" + id).status()).isEqualTo(404);
        assertThat(c.o().get("/api/v1/cs/inquiries?status=ACTIVE").data()).hasSize(1);
    }
}
