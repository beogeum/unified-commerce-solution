package com.commerceos.claim;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.finance.FinanceTestBase;
import com.commerceos.operations.calendar.BusinessCalendarService;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** 반품 검수 기한(DLG-244): 입고일 다음 2번째 영업일 마감(주말·휴일 건너뜀), 기한 초과 업무, 검수 완료 시 해결. */
class ReturnInspectionDueTest extends FinanceTestBase {
    @Autowired
    BusinessCalendarService calendar;
    @Autowired
    ReturnInspectionWatcher watcher;

    @Test
    void inspectionDue_isSecondBusinessDayClose_overdueWorkItem_resolvedOnInspection() {
        Ctx c = ctx("검수기한상사");
        UUID ws = c.ws().id();
        // 계산 규칙: 금요일 입고 → 월 휴일 → 화(1) → 수(2) 18:00
        LocalDate fri = LocalDate.now(BusinessCalendarService.KST).with(TemporalAdjusters.next(DayOfWeek.FRIDAY));
        c.o().post("/api/v1/settings/business-calendar/holidays", Map.of("holidays", List.of(Map.of("day", fri.plusDays(3).toString(), "name", "휴일"))));
        Instant due = jdbcAs(ws, () -> calendar.businessDayClose(ws, fri.atTime(15, 0).atZone(BusinessCalendarService.KST).toInstant(), 2));
        assertThat(ZonedDateTime.ofInstant(due, BusinessCalendarService.KST).toLocalDateTime()).isEqualTo(fri.plusDays(5).atTime(18, 0));

        String o = order(c, "RID-1", new Object[]{"P-100", 1, 33000, 0, 0, false});
        shipOrder(c, o);
        String claim = c.o().post("/api/v1/claims", Map.of("order_id", o, "claim_type", "RETURN", "reason_code", "CHANGE_OF_MIND",
                "items", List.of(Map.of("order_item_id", itemId(c, o, "P-100"), "quantity", 1)))).data().get("id").asString();
        c.o().post("/api/v1/claims/" + claim + "/approve", Map.of());
        drainAsync();
        JsonNode rc = c.o().post("/api/v1/returns/receipts", Map.of("node_id", c.hs().toString(), "claim_id", claim, "lines",
                List.of(Map.of("sku_id", c.amp().toString(), "quantity", 1)))).data();
        Instant received = Instant.parse(rc.get("received_at").asString());
        assertThat(Instant.parse(rc.get("inspection_due_at").asString())).isEqualTo(jdbcAs(ws, () -> calendar.businessDayClose(ws, received, 2)));
        assertThat(rc.get("inspection_overdue").asBoolean()).isFalse();
        // 기한이 지나면 업무(한 번만)
        jdbc.sql("update return_receipt set inspection_due_at = now() - interval '1 hour' where id = cast(:id as uuid)").param("id", rc.get("id").asString()).update();
        assertThat(watcher.scan(Instant.now())).isGreaterThanOrEqualTo(1);
        watcher.scan(Instant.now());
        assertThat(c.o().get("/api/v1/work-items?item_type=RETURN_INSPECTION_OVERDUE&status=OPEN").data()).hasSize(1);
        // 검수 완료 → 업무 해결
        c.o().post("/api/v1/returns/receipts/" + rc.get("id").asString() + "/inspections", Map.of("lines", List.of(Map.of("receipt_line_id",
                rc.get("lines").get(0).get("id").asString(), "decisions", List.of(Map.of("condition", "SELLABLE", "quantity", 1))))));
        assertThat(c.o().get("/api/v1/work-items?item_type=RETURN_INSPECTION_OVERDUE&status=OPEN").data()).isEmpty();
    }

    <T> T jdbcAs(UUID ws, java.util.function.Supplier<T> s) {
        return com.commerceos.shared.db.TenantContext.asSystem(s);
    }
}
