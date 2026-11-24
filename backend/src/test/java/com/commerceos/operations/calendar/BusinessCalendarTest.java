package com.commerceos.operations.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.finance.FinanceTestBase;
import com.commerceos.support.Api;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 영업일·공휴일·CS 응답기한: 영업시간 더하기(주말·공휴일·영업시간 밖 건너뜀), 정책 전환, 중요도 배수, 권한·충돌, CS 문의 기한 반영. */
class BusinessCalendarTest extends FinanceTestBase {
    static final LocalDate FRI = LocalDate.now(BusinessCalendarService.KST).with(TemporalAdjusters.next(DayOfWeek.FRIDAY));
    static final LocalDate MON = FRI.plusDays(3);

    static String at(LocalDate d, int h, int m) {
        return d.atTime(h, m).atZone(BusinessCalendarService.KST).toInstant().toString();
    }

    static LocalDateTime local(JsonNode due) {
        return ZonedDateTime.ofInstant(Instant.parse(due.asString()), BusinessCalendarService.KST).toLocalDateTime();
    }

    JsonNode preview(Api api, String from, String priority) {
        Api.Res r = api.get("/api/v1/settings/business-calendar/cs-sla/preview?from=" + from + (priority == null ? "" : "&priority=" + priority));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.data();
    }

    @Test
    void businessHoursSla_skipsWeekendHolidaysAndOffHours_policyAndPermissions() {
        Ctx c = ctx("영업일상사");
        Api o = c.o();
        JsonNode v = o.get("/api/v1/settings/business-calendar").data();
        assertThat(v.get("default_hours").asBoolean()).isTrue();
        assertThat(v.get("hours")).hasSize(5);
        assertThat(v.get("cs_sla").get("mode").asString()).isEqualTo("CALENDAR_HOURS");
        // 기본: 달력 24시간
        assertThat(local(preview(o, at(FRI, 17, 0), null).get("due"))).isEqualTo(FRI.plusDays(1).atTime(17, 0));

        // 영업시간 기준 9시간: 금 17:00 → 금 1시간 + 월 8시간 = 월 17:00
        Api cs = fx.member(c.ws(), "CS_AGENT");
        assertThat(cs.put("/api/v1/settings/business-calendar/cs-sla", Map.of("mode", "BUSINESS_HOURS", "base_hours", 9), null).status()).isEqualTo(403);
        assertThat(o.put("/api/v1/settings/business-calendar/cs-sla", Map.of("mode", "BUSINESS_HOURS", "base_hours", 9), 5).errorCode()).isEqualTo("VERSION_CONFLICT");
        assertThat(o.put("/api/v1/settings/business-calendar/cs-sla", Map.of("mode", "BUSINESS_HOURS", "base_hours", 0), null).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(o.put("/api/v1/settings/business-calendar/cs-sla", Map.of("mode", "BUSINESS_HOURS", "base_hours", 9), null).status()).isEqualTo(200);
        assertThat(local(preview(o, at(FRI, 17, 0), null).get("due"))).isEqualTo(MON.atTime(17, 0));
        // 긴급(÷6 → 1시간): 금 17:30 → 금 30분 + 월 30분
        assertThat(local(preview(o, at(FRI, 17, 30), "URGENT").get("due"))).isEqualTo(MON.atTime(9, 30));
        // 영업 전 접수: 월 07:00 → 09:00 부터 9시간 = 18:00 정각
        assertThat(local(preview(o, at(MON, 7, 0), null).get("due"))).isEqualTo(MON.atTime(18, 0));
        // 공휴일(월요일) → 화요일로
        Api.Res h = o.post("/api/v1/settings/business-calendar/holidays", Map.of("holidays", List.of(Map.of("day", MON.toString(), "name", "임시 휴무"))));
        assertThat(h.status()).as(h.body().toString()).isEqualTo(201);
        assertThat(o.post("/api/v1/settings/business-calendar/holidays", Map.of("holidays", List.of(Map.of("day", MON.toString(), "name", "중복")))).errorCode())
                .isEqualTo("HOLIDAY_DUPLICATE");
        assertThat(local(preview(o, at(FRI, 17, 0), null).get("due"))).isEqualTo(MON.plusDays(1).atTime(17, 0));
        // 토요일 영업 추가(10~15시) → 금 1시간 + 토 5시간 + 화 3시간(월 휴일)
        assertThat(cs.put("/api/v1/settings/business-calendar/hours", Map.of("hours", List.of(Map.of("weekday", 6, "start", "10:00", "end", "15:00"))), null).status())
                .isEqualTo(403);
        assertThat(o.put("/api/v1/settings/business-calendar/hours", Map.of("hours", List.of(Map.of("weekday", 1, "start", "18:00", "end", "09:00"))), null).errorCode())
                .isEqualTo("VALIDATION_FAILED");
        List<Map<String, Object>> week = new java.util.ArrayList<>();
        for (int d = 1; d <= 5; d++) {
            week.add(Map.of("weekday", d, "start", "09:00", "end", "18:00"));
        }
        week.add(Map.of("weekday", 6, "start", "10:00", "end", "15:00"));
        JsonNode set = o.put("/api/v1/settings/business-calendar/hours", Map.of("hours", week), null).data();
        assertThat(set.get("default_hours").asBoolean()).isFalse();
        assertThat(local(preview(o, at(FRI, 17, 0), null).get("due"))).isEqualTo(MON.plusDays(1).atTime(12, 0));
        // 공휴일 삭제: 다가오는 휴일은 가능, 지난 휴일은 불가
        String hid = set.get("holidays").get(0).get("id").asString();
        assertThat(o.delete("/api/v1/settings/business-calendar/holidays/" + hid).status()).isEqualTo(200);
        jdbc.sql("insert into business_holiday (id, workspace_id, day, name) values (gen_random_uuid(), :ws, :d, '지난 휴일')").param("ws", c.ws().id())
                .param("d", LocalDate.now(BusinessCalendarService.KST).minusDays(3)).update();
        String past = o.get("/api/v1/settings/business-calendar").data().get("holidays").get(0).get("id").asString();
        assertThat(o.delete("/api/v1/settings/business-calendar/holidays/" + past).errorCode()).isEqualTo("HOLIDAY_IN_PAST");
        assertThat(jdbc.sql("select count(*) from audit_log where workspace_id = :ws and action in ('CS_SLA_POLICY_CHANGED', 'BUSINESS_HOURS_CHANGED', 'HOLIDAYS_ADDED', 'HOLIDAY_DELETED')")
                .param("ws", c.ws().id()).query(Integer.class).single()).isEqualTo(4);
        // 다른 운영그룹은 영향 없음(기본 정책)
        assertThat(fx.workspace().owner().get("/api/v1/settings/business-calendar").data().get("cs_sla").get("mode").asString()).isEqualTo("CALENDAR_HOURS");

        // CS 문의: 영업시간 기준 기한(경과 시간 ≥ 9시간, 영업시간 안에 떨어짐)
        Api.Res q = o.post("/api/v1/cs/inquiries", Map.of("channel_account_id", c.acc().toString(), "source", "PHONE", "inquiry_type", "OTHER", "title", "영업시간 문의",
                "message", "언제 답을 받을 수 있나요?", "customer_name", "홍길동"));
        assertThat(q.status()).as(q.body().toString()).isEqualTo(201);
        Instant created = Instant.parse(q.data().get("created_at").asString());
        LocalDateTime due = local(q.data().get("due_at"));
        assertThat(Duration.between(created, Instant.parse(q.data().get("due_at").asString())).toMinutes()).isGreaterThanOrEqualTo(9 * 60 - 1);
        int dow = due.getDayOfWeek().getValue();
        assertThat(dow).isLessThanOrEqualTo(6);
        assertThat(due.toLocalTime()).isBetween(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0));
    }
}
