package com.commerceos.operations.calendar;

import com.commerceos.shared.web.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 영업일·공휴일·CS 응답기한 정책. 조회는 운영그룹 구성원 모두, 변경은 calendar.manage. */
@RestController
@RequestMapping("/api/v1/settings/business-calendar")
public class CalendarController {
    private final BusinessCalendarService calendar;

    public CalendarController(BusinessCalendarService calendar) {
        this.calendar = calendar;
    }

    public record HoursBody(List<BusinessCalendarService.Hours> hours) {
    }

    public record HolidaysBody(List<BusinessCalendarService.HolidayInput> holidays) {
    }

    @GetMapping
    public ApiResponse<BusinessCalendarService.CalendarView> get() {
        return ApiResponse.of(calendar.view());
    }

    @PutMapping("/hours")
    public ApiResponse<BusinessCalendarService.CalendarView> hours(@RequestBody HoursBody body) {
        return ApiResponse.of(calendar.setHours(body == null ? null : body.hours()));
    }

    @PostMapping("/holidays")
    public ResponseEntity<ApiResponse<BusinessCalendarService.CalendarView>> holidays(@RequestBody HolidaysBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(calendar.addHolidays(body == null ? null : body.holidays())));
    }

    @DeleteMapping("/holidays/{id}")
    public ApiResponse<BusinessCalendarService.CalendarView> deleteHoliday(@PathVariable UUID id) {
        return ApiResponse.of(calendar.deleteHoliday(id));
    }

    @PutMapping("/cs-sla")
    public ApiResponse<BusinessCalendarService.CalendarView> sla(@RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                                @RequestBody BusinessCalendarService.SlaInput body) {
        return ApiResponse.of(calendar.setSla(ifMatch == null || ifMatch.isBlank() ? null : com.commerceos.shared.web.IfMatch.parse(ifMatch), body));
    }

    @GetMapping("/cs-sla/preview")
    public ApiResponse<BusinessCalendarService.Preview> preview(@RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) Instant from, @RequestParam(required = false) String priority) {
        return ApiResponse.of(calendar.preview(from, priority));
    }
}
