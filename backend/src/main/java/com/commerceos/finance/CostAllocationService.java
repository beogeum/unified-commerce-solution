package com.commerceos.finance;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사업자 간 공통비 배부(M5 T34, docs/05 §2·§5, DLG-171·172).
 * 공동 광고비·보관비 등 한 장의 청구를 여러 사업자에 순매출 비중·균등·직접 비율로 나눠 각 사업자 원장에 실제 비용으로 기록한다.
 * 배부 근거(분자·분모)와 버전을 남기고, 배부 합계 = 원본 금액(원 미만 잔액은 분자가 가장 큰 사업자, DLG-030).
 * 재배부는 이전 버전의 원장 행을 반대 거래로 상계하고 새 버전을 기록한다(기존 행을 고치지 않음).
 */
@Service
public class CostAllocationService {
    static final Set<String> METHODS = Set.of("NET_SALES", "EQUAL", "MANUAL_RATIO");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final Ledger ledger;
    private final AuditService audit;

    public CostAllocationService(JdbcClient jdbc, AccessGuard guard, Ledger ledger, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.ledger = ledger;
        this.audit = audit;
    }

    public record Target(UUID businessEntityId, BigDecimal ratio) {
    }

    public record NewAllocation(String category, BigDecimal supplyAmount, BigDecimal vatAmount, LocalDate periodDate, String method, LocalDate basisFrom,
                                LocalDate basisTo, List<Target> targets, String sourceRef, String memo) {
    }

    public record LineView(UUID businessEntityId, String businessEntityName, BigDecimal numerator, BigDecimal denominator, BigDecimal supplyAmount, BigDecimal vatAmount,
                           UUID entryId) {
    }

    public record AllocationView(UUID id, String allocationNo, int version, UUID supersedesId, String status, String category, BigDecimal supplyAmount,
                                 BigDecimal vatAmount, LocalDate periodDate, String method, LocalDate basisFrom, LocalDate basisTo, String sourceRef, String memo,
                                 List<LineView> lines, java.time.Instant createdAt) {
    }

    @Transactional
    public AllocationView create(NewAllocation in, String idempotencyKey) {
        guard.requirePermission(Perm.COST_MANAGE);
        CurrentActor a = guard.actor();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw Errors.idempotencyKeyRequired();
        }
        UUID existing = jdbc.sql("select id from cost_allocation where workspace_id = :ws and idempotency_key = :k").param("ws", a.workspaceId())
                .param("k", idempotencyKey.trim()).query(UUID.class).optional().orElse(null);
        if (existing != null) {
            return get(existing);
        }
        String no = "AL-" + jdbc.sql("select nextval('cost_allocation_no_seq')").query(Long.class).single();
        return post(a, no, 1, null, in, idempotencyKey.trim());
    }

    /** 재배부: 이전 버전 원장을 반대 거래로 상계하고 새 버전으로 기록. 원본 금액·항목·귀속일은 이전 버전 것을 유지. */
    @Transactional
    public AllocationView reallocate(UUID id, NewAllocation in) {
        guard.requirePermission(Perm.COST_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> prev = jdbc.sql("select * from cost_allocation where workspace_id = :ws and id = :id for update").param("ws", a.workspaceId()).param("id", id)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("COST_ALLOCATION"));
        if (!"ACTIVE".equals(prev.get("status"))) {
            throw Errors.conflict("ALLOCATION_SUPERSEDED", "이미 새 버전으로 바뀐 배부입니다. 최신 버전에서 다시 배부하세요.");
        }
        List<Map<String, Object>> oldLines = jdbc.sql("select * from cost_allocation_line where allocation_id = :id").param("id", id).query().listOfRows();
        for (Map<String, Object> l : oldLines) {
            guard.checkWrite("BUSINESS_ENTITY", (UUID) l.get("business_entity_id"), (UUID) l.get("business_entity_id"), null);
        }
        LocalDate period = ((java.sql.Date) prev.get("period_date")).toLocalDate();
        for (Map<String, Object> l : oldLines) {
            UUID entryId = (UUID) l.get("entry_id");
            Map<String, Object> e = jdbc.sql("select * from financial_entry where id = :id").param("id", entryId).query().listOfRows().get(0);
            ledger.post(new Ledger.Entry(a.workspaceId(), (UUID) e.get("business_entity_id"), null, null, null, null, null, null, null, null, "COST",
                    (String) e.get("category"), "ACTUAL", ((BigDecimal) e.get("supply_amount")).negate(), ((BigDecimal) e.get("vat_amount")).negate(), "TAXABLE", null, null,
                    period, "COST_ALLOCATION", (String) e.get("source_ref"), "REV:" + entryId, entryId, null, "공통비 재배부(이전 버전 상계)", a.memberId()));
        }
        jdbc.sql("update cost_allocation set status = 'SUPERSEDED' where id = :id").param("id", id).update();
        NewAllocation merged = new NewAllocation((String) prev.get("category"), (BigDecimal) prev.get("supply_amount"), (BigDecimal) prev.get("vat_amount"), period,
                in == null || in.method() == null ? (String) prev.get("method") : in.method(), in == null ? null : in.basisFrom(), in == null ? null : in.basisTo(),
                in == null ? null : in.targets(), (String) prev.get("source_ref"), in == null || in.memo() == null ? (String) prev.get("memo") : in.memo());
        return post(a, (String) prev.get("allocation_no"), ((Number) prev.get("version")).intValue() + 1, id, merged, null);
    }

    private AllocationView post(CurrentActor a, String no, int version, UUID supersedes, NewAllocation in, String idemKey) {
        if (in == null || in.category() == null || !(FinanceService.COSTS.contains(in.category()) || FinanceService.FIXED.contains(in.category()))
                || "PRODUCT_COGS".equals(in.category())
                || "INVENTORY_IMPAIRMENT".equals(in.category())) {
            throw Errors.validation("category", "배부할 비용 항목을 선택하세요(매출원가·평가손실 제외).");
        }
        if (in.supplyAmount() == null || in.supplyAmount().signum() <= 0 || in.supplyAmount().stripTrailingZeros().scale() > 0) {
            throw Errors.validation("supply_amount", "공급가(부가세 제외)를 원 단위 양수로 입력하세요.");
        }
        BigDecimal vat = in.vatAmount() == null ? BigDecimal.ZERO : in.vatAmount();
        if (vat.signum() < 0 || vat.stripTrailingZeros().scale() > 0) {
            throw Errors.validation("vat_amount", "부가세를 원 단위로 입력하세요.");
        }
        String method = in.method() == null ? "NET_SALES" : in.method();
        if (!METHODS.contains(method)) {
            throw Errors.validation("method", "배부 기준은 순매출 비중·균등·직접 비율 중 하나입니다.");
        }
        LocalDate period = in.periodDate() == null ? Ledger.period(null) : in.periodDate();
        List<Target> targets = in.targets() == null ? List.of() : in.targets();
        Set<UUID> bes = new LinkedHashSet<>();
        for (Target t : targets) {
            if (t == null || t.businessEntityId() == null || !bes.add(t.businessEntityId())) {
                throw Errors.validation("targets", "배부 대상 사업자가 비었거나 중복되었습니다.");
            }
            guard.checkWrite("BUSINESS_ENTITY", t.businessEntityId(), t.businessEntityId(), null);
            if (jdbc.sql("select count(*) from business_entity where workspace_id = :ws and id = :id").param("ws", a.workspaceId()).param("id", t.businessEntityId())
                    .query(Integer.class).single() == 0) {
                throw Errors.notFound("BUSINESS_ENTITY");
            }
        }
        if (bes.size() < 2) {
            throw Errors.validation("targets", "공통비 배부 대상 사업자를 2곳 이상 선택하세요. 한 사업자의 비용은 비용 등록으로 기록합니다.");
        }
        LocalDate bf = in.basisFrom() == null ? period.withDayOfMonth(1) : in.basisFrom();
        LocalDate bt = in.basisTo() == null ? period.withDayOfMonth(period.lengthOfMonth()) : in.basisTo();
        Map<UUID, BigDecimal> num = new LinkedHashMap<>();
        for (Target t : targets) {
            BigDecimal n = switch (method) {
                case "EQUAL" -> BigDecimal.ONE;
                case "MANUAL_RATIO" -> {
                    if (t.ratio() == null || t.ratio().signum() < 0) {
                        throw Errors.validation("targets", "직접 비율은 사업자마다 0 이상의 값을 입력하세요.");
                    }
                    yield t.ratio();
                }
                default -> jdbc.sql("""
                        select coalesce(sum(supply_amount), 0) from financial_entry where business_entity_id = :be and period_date between :f and :t
                           and category in ('SALES', 'SELLER_DISCOUNT', 'SALES_RETURN', 'EXCHANGE_PRICE_DIFFERENCE') and not intercompany
                        """).param("be", t.businessEntityId()).param("f", bf).param("t", bt).query(BigDecimal.class).single().max(BigDecimal.ZERO);
            };
            num.put(t.businessEntityId(), n);
        }
        BigDecimal den = num.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (den.signum() <= 0) {
            throw Errors.unprocessable("ALLOCATION_BASE_ZERO", "NET_SALES".equals(method) ? "기준 기간에 대상 사업자들의 순매출이 없습니다. 균등·직접 비율로 배부하세요."
                    : "배부 비율 합계가 0 입니다.");
        }
        Map<UUID, BigDecimal> sup = split(num, den, in.supplyAmount());
        Map<UUID, BigDecimal> vats = split(num, den, vat);
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into cost_allocation (id, workspace_id, allocation_no, version, supersedes_id, status, category, supply_amount, vat_amount, period_date, method,
                                             basis_from, basis_to, source_ref, memo, idempotency_key, created_by)
                values (:id, :ws, :no, :v, cast(:sup as uuid), 'ACTIVE', :c, :s, :vat, :pd, :m, :bf, :bt, cast(:r as text), cast(:memo as text), cast(:k as text), :by)
                """).param("id", id).param("ws", a.workspaceId()).param("no", no).param("v", version).param("sup", supersedes).param("c", in.category())
                .param("s", in.supplyAmount()).param("vat", vat).param("pd", period).param("m", method).param("bf", "NET_SALES".equals(method) ? bf : null)
                .param("bt", "NET_SALES".equals(method) ? bt : null).param("r", blank(in.sourceRef())).param("memo", blank(in.memo())).param("k", idemKey)
                .param("by", a.memberId()).update();
        String basisNote = switch (method) {
            case "EQUAL" -> "균등";
            case "MANUAL_RATIO" -> "직접 비율";
            default -> "순매출 비중 " + bf + "~" + bt;
        };
        for (UUID be : num.keySet()) {
            String sk = "ALLOC:" + id + ":" + be;
            ledger.post(new Ledger.Entry(a.workspaceId(), be, null, null, null, null, null, null, null, null, "COST", in.category(), "ACTUAL", sup.get(be), vats.get(be),
                    "TAXABLE", null, null, period, "COST_ALLOCATION", blank(in.sourceRef()) == null ? no : blank(in.sourceRef()), sk, null, null,
                    "공통비 배부 " + no + " v" + version + " · " + basisNote + " " + num.get(be).stripTrailingZeros().toPlainString() + "/"
                            + den.stripTrailingZeros().toPlainString(), a.memberId()));
            UUID entryId = jdbc.sql("select id from financial_entry where workspace_id = :ws and source_key = :k").param("ws", a.workspaceId()).param("k", sk)
                    .query(UUID.class).single();
            jdbc.sql("""
                    insert into cost_allocation_line (id, workspace_id, allocation_id, business_entity_id, numerator, denominator, supply_amount, vat_amount, entry_id)
                    values (:id, :ws, :a, :be, :n, :d, :s, :v, :e)
                    """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("a", id).param("be", be).param("n", num.get(be)).param("d", den)
                    .param("s", sup.get(be)).param("v", vats.get(be)).param("e", entryId).update();
        }
        audit.record(AuditEntry.builder(a.workspaceId(), version == 1 ? "COST_ALLOCATED" : "COST_REALLOCATED", "COST_ALLOCATION", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).after(Map.of("allocation_no", no, "version", version, "method", method,
                        "supply", in.supplyAmount().toPlainString(), "targets", num.size())).build());
        return get(id);
    }

    /** 비중 배부, 원 미만 잔액은 분자가 가장 큰 사업자(DLG-030). 합계 = total. */
    static Map<UUID, BigDecimal> split(Map<UUID, BigDecimal> num, BigDecimal den, BigDecimal total) {
        Map<UUID, BigDecimal> out = new LinkedHashMap<>();
        BigDecimal used = BigDecimal.ZERO;
        UUID largest = null;
        for (var e : num.entrySet()) {
            BigDecimal share = total.multiply(e.getValue()).divide(den, 0, RoundingMode.HALF_UP);
            out.put(e.getKey(), share);
            used = used.add(share);
            if (largest == null || e.getValue().compareTo(num.get(largest)) > 0) {
                largest = e.getKey();
            }
        }
        out.merge(largest, total.subtract(used), BigDecimal::add);
        return out;
    }

    public List<AllocationView> list(List<String> businessEntityIds) {
        guard.requirePermission(Perm.COST_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        // 대상 사업자 중 하나라도 볼 수 있으면 목록에 표시하되, 줄은 볼 수 있는 사업자만
        return jdbc.sql("""
                select distinct a.id, a.created_at from cost_allocation a join cost_allocation_line l on l.allocation_id = a.id
                 where a.workspace_id = :ws and l.business_entity_id in (:bes) order by a.created_at desc limit 200
                """).param("ws", guard.actor().workspaceId()).param("bes", bes).query((rs, i) -> Db.uuid(rs, "id")).list().stream().map(id -> view(id, bes)).toList();
    }

    public AllocationView get(UUID id) {
        guard.requirePermission(Perm.COST_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, null);
        if (jdbc.sql("select count(*) from cost_allocation_line l join cost_allocation a on a.id = l.allocation_id where a.workspace_id = :ws and a.id = :id"
                + " and l.business_entity_id in (:bes)").param("ws", guard.actor().workspaceId()).param("id", id).param("bes", bes.isEmpty() ? Set.of(UUID.randomUUID()) : bes)
                .query(Integer.class).single() == 0) {
            throw Errors.notFound("COST_ALLOCATION");
        }
        return view(id, bes);
    }

    private AllocationView view(UUID id, Set<UUID> visible) {
        Map<String, Object> h = jdbc.sql("select * from cost_allocation where id = :id").param("id", id).query().listOfRows().get(0);
        List<LineView> lines = new ArrayList<>();
        for (LineView l : jdbc.sql("""
                select l.*, be.display_name from cost_allocation_line l join business_entity be on be.id = l.business_entity_id where l.allocation_id = :id order by be.display_name
                """).param("id", id).query((rs, i) -> new LineView(Db.uuid(rs, "business_entity_id"), rs.getString("display_name"), rs.getBigDecimal("numerator"),
                rs.getBigDecimal("denominator"), rs.getBigDecimal("supply_amount"), rs.getBigDecimal("vat_amount"), Db.uuid(rs, "entry_id"))).list()) {
            lines.add(visible.contains(l.businessEntityId()) ? l : new LineView(l.businessEntityId(), "접근 범위 밖 사업자", null, null, null, null, null));
        }
        return new AllocationView(id, (String) h.get("allocation_no"), ((Number) h.get("version")).intValue(), (UUID) h.get("supersedes_id"), (String) h.get("status"),
                (String) h.get("category"), (BigDecimal) h.get("supply_amount"), (BigDecimal) h.get("vat_amount"), ((java.sql.Date) h.get("period_date")).toLocalDate(),
                (String) h.get("method"), h.get("basis_from") == null ? null : ((java.sql.Date) h.get("basis_from")).toLocalDate(),
                h.get("basis_to") == null ? null : ((java.sql.Date) h.get("basis_to")).toLocalDate(), (String) h.get("source_ref"), (String) h.get("memo"), lines,
                PostingService.instant(h.get("created_at")));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
