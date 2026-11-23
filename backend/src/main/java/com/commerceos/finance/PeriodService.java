package com.commerceos.finance;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.operations.approval.ApprovalHandler;
import com.commerceos.operations.approval.ApprovalService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사업자별 월 마감(M5 N7, FR-FIN-08, docs/05 §12, DLG-036·167·168, T32).
 * <ul>
 *   <li>마감 전 점검(정산 검토 행·대사 차이·미입금·미해결 재무 업무·원가 미등록·남은 예상 비용). 경고가 있으면 확인(acknowledge) 후 마감.</li>
 *   <li>마감 시 원장 합계 스냅샷 보존. 마감된 달에는 원장 행을 넣을 수 없다(DB 트리거). 마감 후 발생분은 다음 열린 달로 귀속하고 원 귀속월을 남긴다.</li>
 *   <li>재개방은 승인 요청(요청자 ≠ 승인자, 승인자도 월 마감 권한). 재개방·재마감 이력은 추가 전용.</li>
 * </ul>
 */
@Service
public class PeriodService implements ApprovalHandler {
    public static final String REOPEN_TYPE = "PERIOD_REOPEN";
    static final List<String> FINANCE_WORK_TYPES = List.of("SETTLEMENT_UNMATCHED", "SETTLEMENT_LINE_REVIEW", "COST_VARIANCE_REVIEW", "FINANCE_COST_MISSING");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final Json json;
    private final SettlementService settlements;

    public PeriodService(JdbcClient jdbc, AccessGuard guard, @Lazy ApprovalService approvals, AuditService audit, Json json, SettlementService settlements) {
        this.jdbc = jdbc;
        this.settlements = settlements;
        this.guard = guard;
        this.approvals = approvals;
        this.audit = audit;
        this.json = json;
    }

    public record Check(String code, String severity, long count, BigDecimal amount, String message) {
    }

    public record Totals(Map<String, BigDecimal> supplyByCategory, Map<String, BigDecimal> vatByCategory, BigDecimal netSales, BigDecimal otherRevenue,
                         BigDecimal totalCost, BigDecimal contribution, long entryCount, long postedAfterClose) {
    }

    public record PeriodEvent(String eventType, String reason, String actorName, UUID approvalId, java.time.Instant occurredAt) {
    }

    public record PeriodView(UUID businessEntityId, String businessEntityName, LocalDate month, String status, boolean closable, String notClosableReason,
                             List<Check> checks, Totals totals, Totals snapshot, Boolean snapshotMatches, java.time.Instant closedAt, String closedByName,
                             UUID pendingReopenApprovalId, List<PeriodEvent> events) {
    }

    public record CloseRequest(UUID businessEntityId, LocalDate month, Boolean acknowledgeWarnings, String note) {
    }

    public record ReopenRequest(UUID businessEntityId, LocalDate month, String reason) {
    }

    // ---------------------------------------------------------------- 조회

    public List<PeriodView> list(UUID businessEntityId, int months) {
        guard.requirePermission(Perm.PROFIT_READ);
        guard.checkRead("BUSINESS_ENTITY", businessEntityId, businessEntityId, null);
        LocalDate m = LocalDate.now(Ledger.KST).withDayOfMonth(1);
        List<PeriodView> out = new ArrayList<>();
        for (int i = 0; i < Math.max(1, Math.min(months, 24)); i++) {
            out.add(view(businessEntityId, m.minusMonths(i), false));
        }
        return out;
    }

    public PeriodView preview(UUID businessEntityId, LocalDate month) {
        guard.requirePermission(Perm.PROFIT_READ);
        requireBe(businessEntityId);
        guard.checkRead("BUSINESS_ENTITY", businessEntityId, businessEntityId, null);
        return view(businessEntityId, monthOf(month), true);
    }

    // ---------------------------------------------------------------- 마감

    @Transactional
    public PeriodView close(CloseRequest in) {
        guard.requirePermission(Perm.PERIOD_CLOSE);
        CurrentActor a = guard.actor();
        UUID be = in == null ? null : in.businessEntityId();
        requireBe(be);
        guard.checkWrite("BUSINESS_ENTITY", be, be, null);
        LocalDate m = monthOf(in.month());
        String notClosable = notClosable(m);
        if (notClosable != null) {
            throw Errors.unprocessable("PERIOD_NOT_ENDED", notClosable);
        }
        Map<String, Object> p = lockPeriod(a.workspaceId(), be, m);
        if ("CLOSED".equals(p.get("status"))) {
            throw Errors.conflict("PERIOD_ALREADY_CLOSED", "이미 마감된 달입니다.");
        }
        List<Check> checks = checks(a.workspaceId(), be, m);
        List<Check> warnings = checks.stream().filter(c -> "WARNING".equals(c.severity())).toList();
        if (!warnings.isEmpty() && !Boolean.TRUE.equals(in.acknowledgeWarnings())) {
            throw Errors.unprocessable("PERIOD_CLOSE_WARNINGS", "마감 전 확인할 항목이 있습니다. 점검 결과를 확인한 뒤 '확인하고 마감'으로 다시 실행하세요.",
                    Map.of("warnings", warnings.stream().map(Check::code).toList()));
        }
        Totals t = totals(be, m);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("totals", t);
        snapshot.put("checks", checks);
        snapshot.put("note", in.note());
        jdbc.sql("""
                update accounting_period set status = 'CLOSED', closed_by = :by, closed_at = now(), snapshot = cast(:s as jsonb), version = version + 1
                 where business_entity_id = :be and month = :m
                """).param("by", a.memberId()).param("s", json.write(snapshot)).param("be", be).param("m", m).update();
        event(a.workspaceId(), be, m, "CLOSED", in.note(), a.memberId(), null);
        audit.record(AuditEntry.builder(a.workspaceId(), "PERIOD_CLOSED", "ACCOUNTING_PERIOD", null).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).reason(in.note()).after(Map.of("month", m.toString().substring(0, 7), "warnings", warnings.stream().map(Check::code).toList(),
                        "contribution", t.contribution().toPlainString(), "entries", t.entryCount())).build());
        return view(be, m, true);
    }

    // ---------------------------------------------------------------- 재개방(승인)

    @Transactional
    public ApprovalService.Request requestReopen(ReopenRequest in) {
        guard.requirePermission(Perm.PERIOD_CLOSE);
        UUID be = in == null ? null : in.businessEntityId();
        requireBe(be);
        guard.checkWrite("BUSINESS_ENTITY", be, be, null);
        LocalDate m = monthOf(in.month());
        String reason = in.reason() == null ? null : in.reason().trim();
        if (reason == null || reason.isEmpty() || reason.length() > 500) {
            throw Errors.validation("reason", "재개방 사유를 500자 이내로 입력하세요.");
        }
        Map<String, Object> p = jdbc.sql("select * from accounting_period where business_entity_id = :be and month = :m").param("be", be).param("m", m).query()
                .listOfRows().stream().findFirst().orElse(null);
        if (p == null || !"CLOSED".equals(p.get("status"))) {
            throw Errors.unprocessable("PERIOD_NOT_CLOSED", "마감되지 않은 달은 재개방할 필요가 없습니다.");
        }
        String target = be + ":" + m;
        if (approvals.pending(REOPEN_TYPE, target).isPresent()) {
            throw Errors.conflict("APPROVAL_PENDING", "이미 재개방 승인 요청이 대기 중입니다.");
        }
        long later = jdbc.sql("select count(*) from financial_entry where business_entity_id = :be and original_period_date >= :f and original_period_date < :t")
                .param("be", be).param("f", m).param("t", m.plusMonths(1)).query(Long.class).single();
        Map<String, Object> payload = Map.of("business_entity_id", be.toString(), "month", m.toString(), "expected_version", ((Number) p.get("version")).intValue());
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("month", m.toString().substring(0, 7));
        impact.put("closed_at", String.valueOf(p.get("closed_at")));
        impact.put("posted_after_close", later);
        impact.put("note", "재개방하면 그 달에 원장을 다시 기록할 수 있고, 다시 마감할 때 새 스냅샷이 저장됩니다. 마감 중 다음 달로 귀속된 조정은 옮기지 않습니다.");
        return approvals.create(new ApprovalService.NewRequest(REOPEN_TYPE, "ACCOUNTING_PERIOD", target, be, payload, impact, reason));
    }

    @Override
    public String requestType() {
        return REOPEN_TYPE;
    }

    @Override
    public boolean isCurrent(ApprovalService.Request r) {
        UUID be = UUID.fromString(r.payload().get("business_entity_id").asString());
        LocalDate m = LocalDate.parse(r.payload().get("month").asString());
        return jdbc.sql("select count(*) from accounting_period where business_entity_id = :be and month = :m and status = 'CLOSED' and version = :v").param("be", be)
                .param("m", m).param("v", r.payload().get("expected_version").asInt()).query(Integer.class).single() == 1;
    }

    @Override
    public void apply(ApprovalService.Request r) {
        CurrentActor a = guard.actor();
        if (!a.has(Perm.PERIOD_CLOSE)) {
            throw Errors.unprocessable("APPROVER_NEEDS_PERIOD_CLOSE", "월 재개방 승인은 월 마감 권한이 있는 승인자만 할 수 있습니다.");
        }
        UUID be = UUID.fromString(r.payload().get("business_entity_id").asString());
        LocalDate m = LocalDate.parse(r.payload().get("month").asString());
        jdbc.sql("update accounting_period set status = 'OPEN', version = version + 1 where business_entity_id = :be and month = :m").param("be", be).param("m", m).update();
        event(a.workspaceId(), be, m, "REOPENED", r.reason(), r.requestedBy(), r.id());
        audit.record(AuditEntry.builder(a.workspaceId(), "PERIOD_REOPENED", "ACCOUNTING_PERIOD", null).actor(ActorType.APPROVER, a.memberId(), a.displayName())
                .scope(be, null).reason(r.reason()).after(Map.of("month", m.toString().substring(0, 7), "approval_id", r.id().toString())).build());
    }

    // ---------------------------------------------------------------- 내부

    private PeriodView view(UUID be, LocalDate m, boolean detail) {
        UUID ws = guard.actor().workspaceId();
        Map<String, Object> p = jdbc.sql("""
                select p.*, u.display_name as closed_by_name from accounting_period p left join workspace_member mem on mem.id = p.closed_by left join user_account u on u.id = mem.user_id
                 where p.business_entity_id = :be and p.month = :m
                """).param("be", be).param("m", m).query().listOfRows().stream().findFirst().orElse(null);
        String beName = jdbc.sql("select display_name from business_entity where id = :id").param("id", be).query(String.class).single();
        String status = p == null ? "OPEN" : (String) p.get("status");
        Totals totals = totals(be, m);
        Totals snap = null;
        Boolean matches = null;
        if (p != null && p.get("snapshot") != null && "CLOSED".equals(status)) {
            tools.jackson.databind.JsonNode node = json.read(String.valueOf(p.get("snapshot")));
            snap = node.has("totals") ? json.read(json.write(node.get("totals")), Totals.class) : null;
            matches = snap != null && sameNumbers(snap, totals);
        }
        String notClosable = "CLOSED".equals(status) ? "이미 마감된 달입니다." : notClosable(m);
        UUID pending = approvals.pending(REOPEN_TYPE, be + ":" + m).map(ApprovalService.Request::id).orElse(null);
        List<PeriodEvent> events = !detail ? List.of() : jdbc.sql("""
                select e.event_type, e.reason, u.display_name, e.approval_id, e.occurred_at from accounting_period_event e
                  left join workspace_member mem on mem.id = e.actor_member_id left join user_account u on u.id = mem.user_id where e.business_entity_id = :be and e.month = :m order by e.occurred_at
                """).param("be", be).param("m", m).query((rs, i) -> new PeriodEvent(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getTimestamp(5).toInstant())).list();
        return new PeriodView(be, beName, m, status, notClosable == null, notClosable, detail || !"CLOSED".equals(status) ? checks(ws, be, m) : List.of(), totals, snap,
                matches, p == null ? null : PostingService.instant(p.get("closed_at")), p == null ? null : (String) p.get("closed_by_name"), pending, events);
    }

    private List<Check> checks(UUID ws, UUID be, LocalDate m) {
        LocalDate end = m.plusMonths(1);
        List<Check> out = new ArrayList<>();
        // 정산: 정산기간 종료일(없으면 지급일·등록일)이 이 달인 정산
        String runsInMonth = "select r.id from settlement_run r where r.business_entity_id = :be and coalesce(r.period_to, r.payout_date, r.created_at::date) >= :f"
                + " and coalesce(r.period_to, r.payout_date, r.created_at::date) < :t";
        long review = jdbc.sql("select count(*) from settlement_line where applied = 'REVIEW' and run_id in (" + runsInMonth + ")").param("be", be).param("f", m)
                .param("t", end).query(Long.class).single();
        if (review > 0) {
            out.add(new Check("SETTLEMENT_REVIEW_LINES", "WARNING", review, null, "원주문을 못 찾았거나 범위가 없는 정산 행 " + review + "건이 원장에 반영되지 않았습니다."));
        }
        long issues = jdbc.sql(runsInMonth).param("be", be).param("f", m).param("t", end).query(UUID.class).list().stream()
                .filter(id -> "ISSUES".equals(settlements.view(id, false).status())).count();
        if (issues > 0) {
            out.add(new Check("SETTLEMENT_ISSUES", "WARNING", issues, null, "대사 차이·검토가 남은 정산 " + issues + "건이 있습니다."));
        }
        Map<String, Object> und = jdbc.sql("""
                select count(*) as c, coalesce(sum(x.rem), 0) as a from (
                  select (select coalesce(sum(l.amount), 0) from settlement_line l where l.run_id = r.id)
                       - (select coalesce(sum(dm.amount), 0) from deposit_match dm where dm.run_id = r.id and dm.cancelled_at is null) as rem
                    from settlement_run r where r.id in (""" + runsInMonth + ")) x where x.rem > 0").param("be", be).param("f", m).param("t", end).query().listOfRows().get(0);
        long undCount = ((Number) und.get("c")).longValue();
        if (undCount > 0) {
            out.add(new Check("SETTLEMENT_UNDEPOSITED", "INFO", undCount, (BigDecimal) und.get("a"), "입금 연결이 끝나지 않은 정산 " + undCount + "건(현금 대사, 손익과 무관)."));
        }
        long work = jdbc.sql("select count(*) from work_item where workspace_id = :ws and business_entity_id = :be and item_type in (:types) and status in ('OPEN', 'IN_PROGRESS', 'ON_HOLD')")
                .param("ws", ws).param("be", be).param("types", FINANCE_WORK_TYPES).query(Long.class).single();
        if (work > 0) {
            out.add(new Check("FINANCE_WORK_OPEN", "WARNING", work, null, "미해결 재무 업무(정산 검토·비용 차이·원가 미등록) " + work + "건이 있습니다."));
        }
        long missing = jdbc.sql("""
                select count(*) from financial_entry where business_entity_id = :be and period_date >= :f and period_date < :t and category = 'PRODUCT_COGS'
                   and unit_cost is null and quantity > 0
                """).param("be", be).param("f", m).param("t", end).query(Long.class).single();
        if (missing > 0) {
            out.add(new Check("COST_MISSING", "WARNING", missing, null, "원가가 등록되지 않아 매출원가 0 으로 기록된 행 " + missing + "건이 있습니다."));
        }
        BigDecimal est = jdbc.sql("""
                select coalesce(sum(supply_amount), 0) from financial_entry where business_entity_id = :be and period_date >= :f and period_date < :t
                   and entry_class = 'COST' and basis = 'ESTIMATED'
                """).param("be", be).param("f", m).param("t", end).query(BigDecimal.class).single();
        if (est.signum() != 0) {
            out.add(new Check("ESTIMATED_COST", "INFO", 0, est, "실제 청구·정산으로 대체되지 않은 예상 비용이 남아 있습니다. 마감 후 실제가 오면 다음 열린 달에 대체됩니다."));
        }
        return out;
    }

    Totals totals(UUID be, LocalDate m) {
        Map<String, BigDecimal> sup = new TreeMap<>();
        Map<String, BigDecimal> vat = new TreeMap<>();
        long[] count = new long[1];
        jdbc.sql("""
                select category, sum(supply_amount) as s, sum(vat_amount) as v, count(*) as c from financial_entry where business_entity_id = :be and period_date >= :f
                   and period_date < :t and entry_class <> 'MEMO' group by category
                """).param("be", be).param("f", m).param("t", m.plusMonths(1)).query().listOfRows().forEach(r -> {
            sup.put((String) r.get("category"), (BigDecimal) r.get("s"));
            vat.put((String) r.get("category"), (BigDecimal) r.get("v"));
            count[0] += ((Number) r.get("c")).longValue();
        });
        BigDecimal net = sum(sup, FinanceService.NET_SALES);
        BigDecimal other = sum(sup, FinanceService.OTHER_REVENUE);
        BigDecimal cost = sum(sup, FinanceService.COSTS);
        long after = jdbc.sql("select count(*) from financial_entry where business_entity_id = :be and original_period_date >= :f and original_period_date < :t")
                .param("be", be).param("f", m).param("t", m.plusMonths(1)).query(Long.class).single();
        return new Totals(sup, vat, net, other, cost, net.add(other).subtract(cost), count[0], after);
    }

    private static BigDecimal sum(Map<String, BigDecimal> m, java.util.Set<String> cats) {
        return m.entrySet().stream().filter(e -> cats.contains(e.getKey())).map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean sameNumbers(Totals a, Totals b) {
        return a.entryCount() == b.entryCount() && a.contribution().compareTo(b.contribution()) == 0 && sameMap(a.supplyByCategory(), b.supplyByCategory())
                && sameMap(a.vatByCategory(), b.vatByCategory());
    }

    private static boolean sameMap(Map<String, BigDecimal> a, Map<String, BigDecimal> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        return a.entrySet().stream().allMatch(e -> e.getValue().compareTo(b.get(e.getKey())) == 0);
    }

    private Map<String, Object> lockPeriod(UUID ws, UUID be, LocalDate m) {
        jdbc.sql("insert into accounting_period (workspace_id, business_entity_id, month, status) values (:ws, :be, :m, 'OPEN') on conflict do nothing").param("ws", ws)
                .param("be", be).param("m", m).update();
        return jdbc.sql("select * from accounting_period where business_entity_id = :be and month = :m for update").param("be", be).param("m", m).query().listOfRows()
                .get(0);
    }

    private void event(UUID ws, UUID be, LocalDate m, String type, String reason, UUID actor, UUID approvalId) {
        jdbc.sql("""
                insert into accounting_period_event (id, workspace_id, business_entity_id, month, event_type, reason, actor_member_id, approval_id)
                values (:id, :ws, :be, :m, :t, cast(:r as text), :a, cast(:ap as uuid))
                """).param("id", Ids.newId()).param("ws", ws).param("be", be).param("m", m).param("t", type).param("r", reason).param("a", actor).param("ap", approvalId)
                .update();
    }

    /** 끝나지 않은 달(이번 달·미래)은 마감할 수 없다. */
    private static String notClosable(LocalDate m) {
        LocalDate thisMonth = LocalDate.now(Ledger.KST).withDayOfMonth(1);
        return m.isBefore(thisMonth) ? null : "끝나지 않은 달은 마감할 수 없습니다. 다음 달부터 마감하세요.";
    }

    private static LocalDate monthOf(LocalDate d) {
        if (d == null) {
            throw Errors.validation("month", "마감할 달을 선택하세요.");
        }
        return d.withDayOfMonth(1);
    }

    private static void requireBe(UUID be) {
        if (be == null) {
            throw Errors.validation("business_entity_id", "사업자를 선택하세요.");
        }
    }
}
