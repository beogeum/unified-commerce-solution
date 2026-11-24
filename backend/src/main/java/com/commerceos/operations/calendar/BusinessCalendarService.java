package com.commerceos.operations.calendar;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영그룹 영업일·영업시간·공휴일(Asia/Seoul)과 CS 응답기한 정책(DLG-239~243).
 * <ul>
 *   <li>요일별 영업시간(없는 요일 = 휴무). 설정이 없으면 월~금 09:00~18:00.</li>
 *   <li>공휴일은 담당자가 등록한다(음력·대체공휴일을 시스템이 추정하지 않음). 지난 공휴일은 지우지 않는다(이미 계산된 기한의 근거).</li>
 *   <li>영업시간 더하기: 시작 시각부터 영업시간 안의 분만 센다(휴무·공휴일·영업시간 밖은 건너뜀).</li>
 *   <li>CS 응답기한 정책: 달력 시간(기본, 설정 `commerce.cs.response-sla-hours`) 또는 영업시간. 중요도 배수(긴급 ÷6·높음 ÷2·낮음 ×2)는 같다.</li>
 * </ul>
 */
@Service
public class BusinessCalendarService {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int MAX_DAYS = 800;
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final int defaultCalendarHours;

    public BusinessCalendarService(JdbcClient jdbc, AccessGuard guard, AuditService audit, @Value("${commerce.cs.response-sla-hours:24}") int defaultCalendarHours) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.defaultCalendarHours = defaultCalendarHours;
    }

    public record Hours(int weekday, LocalTime start, LocalTime end) {
    }

    public record Holiday(UUID id, LocalDate day, String name) {
    }

    public record HolidayInput(LocalDate day, String name) {
    }

    public record SlaPolicy(String mode, int baseHours, boolean configured, int version) {
    }

    public record SlaInput(String mode, Integer baseHours) {
    }

    public record CalendarView(List<Hours> hours, boolean defaultHours, List<Holiday> holidays, SlaPolicy csSla) {
    }

    public record Preview(Instant from, String priority, Instant due, String mode, int hours) {
    }

    // ---------------------------------------------------------------- 계산

    /** 영업시간 기준으로 시간을 더한다. */
    public Instant addBusinessHours(UUID ws, Instant from, Duration amount) {
        Map<Integer, Hours> hours = hoursMap(ws);
        if (hours.isEmpty()) {
            throw Errors.unprocessable("CALENDAR_NO_WORKING_DAY", "영업일이 하루도 없습니다. 영업시간을 설정하세요.");
        }
        Set<LocalDate> holidays = holidays(ws, from.atZone(KST).toLocalDate());
        long remaining = amount.toMinutes();
        LocalDateTime cursor = LocalDateTime.ofInstant(from, KST);
        for (int i = 0; i < MAX_DAYS; i++) {
            LocalDate d = cursor.toLocalDate();
            Hours h = hours.get(d.getDayOfWeek().getValue());
            if (h != null && !holidays.contains(d)) {
                LocalDateTime s = d.atTime(h.start());
                LocalDateTime e = d.atTime(h.end());
                LocalDateTime c = cursor.isBefore(s) ? s : cursor;
                if (c.isBefore(e)) {
                    long avail = Duration.between(c, e).toMinutes();
                    if (remaining <= avail) {
                        return c.plusMinutes(remaining).atZone(KST).toInstant();
                    }
                    remaining -= avail;
                }
            }
            cursor = d.plusDays(1).atStartOfDay();
        }
        throw Errors.unprocessable("CALENDAR_RANGE_EXCEEDED", "기한을 계산할 수 없습니다(영업일이 너무 적습니다).");
    }

    /** CS 응답기한(접수·재문의 시각 + 정책 시간 × 중요도 배수). */
    public Instant csDue(UUID ws, Instant from, String priority) {
        SlaPolicy p = policy(ws);
        int h = priorityHours(p.baseHours(), priority);
        return "BUSINESS_HOURS".equals(p.mode()) ? addBusinessHours(ws, from, Duration.ofHours(h)) : from.plus(Duration.ofHours(h));
    }

    static int priorityHours(int base, String priority) {
        return switch (priority == null ? "NORMAL" : priority) {
            case "URGENT" -> Math.max(1, base / 6);
            case "HIGH" -> Math.max(1, base / 2);
            case "LOW" -> base * 2;
            default -> base;
        };
    }

    public SlaPolicy policy(UUID ws) {
        return jdbc.sql("select mode, base_hours, version from cs_sla_policy where workspace_id = :ws").param("ws", ws)
                .query((rs, i) -> new SlaPolicy(rs.getString("mode"), rs.getInt("base_hours"), true, rs.getInt("version"))).optional()
                .orElse(new SlaPolicy("CALENDAR_HOURS", defaultCalendarHours, false, 0));
    }

    // ---------------------------------------------------------------- 설정

    public CalendarView view() {
        UUID ws = guard.actor().workspaceId();
        boolean configured = jdbc.sql("select count(*) from business_hours where workspace_id = :ws").param("ws", ws).query(Integer.class).single() > 0;
        List<Holiday> hs = jdbc.sql("select id, day, name from business_holiday where workspace_id = :ws and day >= :d order by day limit 200").param("ws", ws)
                .param("d", LocalDate.now(KST).minusDays(31)).query((rs, i) -> new Holiday(Db.uuid(rs, "id"), rs.getObject("day", LocalDate.class), rs.getString("name")))
                .list();
        return new CalendarView(new ArrayList<>(hoursMap(ws).values()), !configured, hs, policy(ws));
    }

    @Transactional
    public CalendarView setHours(List<Hours> in) {
        guard.requirePermission(Perm.CALENDAR_MANAGE);
        CurrentActor a = guard.actor();
        if (in == null || in.isEmpty()) {
            throw Errors.validation("hours", "영업하는 요일을 하나 이상 입력하세요.");
        }
        Set<Integer> seen = new HashSet<>();
        for (Hours h : in) {
            if (h == null || h.weekday() < 1 || h.weekday() > 7 || !seen.add(h.weekday())) {
                throw Errors.validation("weekday", "요일(1 월요일 ~ 7 일요일)이 비었거나 중복입니다.");
            }
            if (h.start() == null || h.end() == null || !h.end().isAfter(h.start())) {
                throw Errors.validation("end_time", "마감 시각은 시작 시각보다 늦어야 합니다.");
            }
        }
        List<Hours> before = new ArrayList<>(hoursMap(a.workspaceId()).values());
        jdbc.sql("delete from business_hours where workspace_id = :ws").param("ws", a.workspaceId()).update();
        for (Hours h : in) {
            jdbc.sql("insert into business_hours (workspace_id, weekday, start_time, end_time) values (:ws, :w, :s, :e)").param("ws", a.workspaceId())
                    .param("w", h.weekday()).param("s", h.start()).param("e", h.end()).update();
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "BUSINESS_HOURS_CHANGED", "WORKSPACE", a.workspaceId()).actor(ActorType.USER, a.memberId(), a.displayName())
                .before(Map.of("hours", describe(before))).after(Map.of("hours", describe(in))).build());
        return view();
    }

    @Transactional
    public CalendarView addHolidays(List<HolidayInput> in) {
        guard.requirePermission(Perm.CALENDAR_MANAGE);
        CurrentActor a = guard.actor();
        if (in == null || in.isEmpty() || in.size() > 100) {
            throw Errors.validation("holidays", "공휴일을 1~100개 입력하세요.");
        }
        for (HolidayInput h : in) {
            if (h == null || h.day() == null || h.name() == null || h.name().isBlank() || h.name().trim().length() > 50) {
                throw Errors.validation("holidays", "날짜와 이름(50자 이내)을 입력하세요.");
            }
            try {
                jdbc.sql("insert into business_holiday (id, workspace_id, day, name, created_by) values (:id, :ws, :d, :n, :by)").param("id", Ids.newId())
                        .param("ws", a.workspaceId()).param("d", h.day()).param("n", h.name().trim()).param("by", a.memberId()).update();
            } catch (DuplicateKeyException e) {
                throw Errors.conflict("HOLIDAY_DUPLICATE", h.day() + " 은 이미 등록된 휴일입니다.");
            }
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "HOLIDAYS_ADDED", "WORKSPACE", a.workspaceId()).actor(ActorType.USER, a.memberId(), a.displayName())
                .after(Map.of("days", in.stream().map(h -> h.day().toString()).toList())).build());
        return view();
    }

    @Transactional
    public CalendarView deleteHoliday(UUID id) {
        guard.requirePermission(Perm.CALENDAR_MANAGE);
        CurrentActor a = guard.actor();
        Holiday h = jdbc.sql("select id, day, name from business_holiday where workspace_id = :ws and id = :id").param("ws", a.workspaceId()).param("id", id)
                .query((rs, i) -> new Holiday(Db.uuid(rs, "id"), rs.getObject("day", LocalDate.class), rs.getString("name"))).optional()
                .orElseThrow(() -> Errors.notFound("BUSINESS_HOLIDAY"));
        if (h.day().isBefore(LocalDate.now(KST))) {
            throw Errors.unprocessable("HOLIDAY_IN_PAST", "지난 휴일은 지울 수 없습니다(이미 계산된 기한의 근거입니다).");
        }
        jdbc.sql("delete from business_holiday where id = :id").param("id", id).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "HOLIDAY_DELETED", "WORKSPACE", a.workspaceId()).actor(ActorType.USER, a.memberId(), a.displayName())
                .before(Map.of("day", h.day().toString(), "name", h.name())).build());
        return view();
    }

    @Transactional
    public CalendarView setSla(Integer version, SlaInput in) {
        guard.requirePermission(Perm.CALENDAR_MANAGE);
        CurrentActor a = guard.actor();
        if (in == null || !Set.of("CALENDAR_HOURS", "BUSINESS_HOURS").contains(in.mode())) {
            throw Errors.validation("mode", "기준을 달력 시간(CALENDAR_HOURS) 또는 영업시간(BUSINESS_HOURS)으로 선택하세요.");
        }
        if (in.baseHours() == null || in.baseHours() < 1 || in.baseHours() > 240) {
            throw Errors.validation("base_hours", "기본 응답 시간은 1~240시간입니다.");
        }
        SlaPolicy before = policy(a.workspaceId());
        if (version != null && version != before.version()) {
            throw Errors.conflict("VERSION_CONFLICT", "다른 사용자가 먼저 바꿨습니다. 새로 고친 뒤 다시 시도하세요.");
        }
        jdbc.sql("""
                insert into cs_sla_policy (workspace_id, mode, base_hours, updated_by) values (:ws, :m, :h, :by)
                on conflict (workspace_id) do update set mode = excluded.mode, base_hours = excluded.base_hours, updated_by = excluded.updated_by, updated_at = now(),
                       version = cs_sla_policy.version + 1
                """).param("ws", a.workspaceId()).param("m", in.mode()).param("h", in.baseHours()).param("by", a.memberId()).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "CS_SLA_POLICY_CHANGED", "WORKSPACE", a.workspaceId()).actor(ActorType.USER, a.memberId(), a.displayName())
                .before(Map.of("mode", before.mode(), "base_hours", before.baseHours())).after(Map.of("mode", in.mode(), "base_hours", in.baseHours())).build());
        return view();
    }

    public Preview preview(Instant from, String priority) {
        UUID ws = guard.actor().workspaceId();
        Instant f = from == null ? Instant.now() : from;
        SlaPolicy p = policy(ws);
        return new Preview(f, priority == null ? "NORMAL" : priority, csDue(ws, f, priority), p.mode(), priorityHours(p.baseHours(), priority));
    }

    // ---------------------------------------------------------------- 내부

    private Map<Integer, Hours> hoursMap(UUID ws) {
        Map<Integer, Hours> m = new TreeMap<>();
        jdbc.sql("select weekday, start_time, end_time from business_hours where workspace_id = :ws").param("ws", ws).query().listOfRows()
                .forEach(r -> m.put(((Number) r.get("weekday")).intValue(), new Hours(((Number) r.get("weekday")).intValue(), ((java.sql.Time) r.get("start_time")).toLocalTime(),
                        ((java.sql.Time) r.get("end_time")).toLocalTime())));
        if (m.isEmpty()) {
            for (int d = 1; d <= 5; d++) {
                m.put(d, new Hours(d, LocalTime.of(9, 0), LocalTime.of(18, 0)));
            }
        }
        return m;
    }

    private Set<LocalDate> holidays(UUID ws, LocalDate from) {
        return new HashSet<>(jdbc.sql("select day from business_holiday where workspace_id = :ws and day >= :d and day < :to").param("ws", ws).param("d", from)
                .param("to", from.plusDays(MAX_DAYS)).query(LocalDate.class).list());
    }

    private static List<String> describe(List<Hours> hs) {
        String[] ko = {"", "월", "화", "수", "목", "금", "토", "일"};
        Map<Integer, String> m = new LinkedHashMap<>();
        hs.stream().sorted(java.util.Comparator.comparingInt(Hours::weekday)).forEach(h -> m.put(h.weekday(), ko[h.weekday()] + " " + h.start() + "~" + h.end()));
        return new ArrayList<>(m.values());
    }
}
