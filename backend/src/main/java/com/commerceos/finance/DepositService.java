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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 입금 ↔ 정산 다대다 매칭(M5 N4, docs/05 §11, DLG-166, T30·T31).
 * 입금 행은 추가 전용(같은 멱등키·외부 거래번호는 한 번). 한 입금은 여러 정산에, 한 정산은 여러 입금에 나눠 연결할 수 있다.
 * 입금별 연결 합계 ≤ 입금액, 정산별 연결 합계 ≤ 정산 지급액. 연결 취소는 행을 지우지 않고 취소 시각을 남긴다.
 * 입금은 현금 대사이며 매출·손익에 더하지 않는다(매출 ≠ 정산액 ≠ 입금액).
 */
@Service
public class DepositService {
    /** 부분합 자동 매칭 후보 수 상한(2^n 탐색). */
    static final int SUBSET_LIMIT = 16;
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;

    public DepositService(JdbcClient jdbc, AccessGuard guard, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    public record NewDeposit(UUID businessEntityId, UUID bankAccountId, LocalDate depositedOn, BigDecimal amount, String depositor, String externalRef, String memo) {
    }

    public record MatchView(UUID id, UUID depositId, UUID runId, String settlementKey, String channelAccountName, BigDecimal amount, String method, java.time.Instant createdAt,
                            java.time.Instant cancelledAt) {
    }

    public record DepositView(UUID id, UUID businessEntityId, String businessEntityName, UUID bankAccountId, String bankAccountLast4, LocalDate depositedOn,
                              BigDecimal amount, BigDecimal matched, BigDecimal remaining, String depositor, String externalRef, String memo, List<MatchView> matches,
                              java.time.Instant createdAt) {
    }

    public record AutoResult(String outcome, List<MatchView> matches, List<UUID> candidateRunIds, String message) {
    }

    // ---------------------------------------------------------------- 입금 등록

    @Transactional
    public DepositView create(NewDeposit in, String idempotencyKey) {
        guard.requirePermission(Perm.SETTLEMENT_MANAGE);
        CurrentActor a = guard.actor();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw Errors.idempotencyKeyRequired();
        }
        UUID existing = jdbc.sql("select id from bank_deposit where workspace_id = :ws and idempotency_key = :k").param("ws", a.workspaceId())
                .param("k", idempotencyKey.trim()).query(UUID.class).optional().orElse(null);
        if (existing != null) {
            return get(existing);
        }
        if (in == null || in.businessEntityId() == null) {
            throw Errors.validation("business_entity_id", "입금 받은 사업자를 선택하세요.");
        }
        guard.checkWrite("BUSINESS_ENTITY", in.businessEntityId(), in.businessEntityId(), null);
        if (in.depositedOn() == null) {
            throw Errors.validation("deposited_on", "입금일을 입력하세요.");
        }
        if (in.amount() == null || in.amount().signum() <= 0 || in.amount().stripTrailingZeros().scale() > 0) {
            throw Errors.validation("amount", "입금액을 원 단위 양수로 입력하세요.");
        }
        if (in.bankAccountId() != null && jdbc.sql("select count(*) from bank_account where workspace_id = :ws and id = :id and business_entity_id = :be")
                .param("ws", a.workspaceId()).param("id", in.bankAccountId()).param("be", in.businessEntityId()).query(Integer.class).single() == 0) {
            throw Errors.validation("bank_account_id", "이 사업자의 정산계좌가 아닙니다.");
        }
        String ref = trim(in.externalRef(), 120, "external_ref");
        if (ref != null && jdbc.sql("select count(*) from bank_deposit where business_entity_id = :be and external_ref = :r").param("be", in.businessEntityId())
                .param("r", ref).query(Integer.class).single() > 0) {
            throw Errors.conflict("DEPOSIT_DUPLICATE", "같은 거래번호의 입금이 이미 등록되어 있습니다.");
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into bank_deposit (id, workspace_id, business_entity_id, bank_account_id, deposited_on, amount, depositor, external_ref, memo, idempotency_key, created_by)
                values (:id, :ws, :be, cast(:ba as uuid), :d, :a, cast(:dep as text), cast(:ref as text), cast(:m as text), :k, :by)
                """).param("id", id).param("ws", a.workspaceId()).param("be", in.businessEntityId()).param("ba", in.bankAccountId()).param("d", in.depositedOn())
                .param("a", in.amount()).param("dep", trim(in.depositor(), 100, "depositor")).param("ref", ref).param("m", trim(in.memo(), 500, "memo"))
                .param("k", idempotencyKey.trim()).param("by", a.memberId()).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "DEPOSIT_RECORDED", "BANK_DEPOSIT", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(in.businessEntityId(), null).after(Map.of("amount", in.amount().toPlainString(), "deposited_on", in.depositedOn().toString())).build());
        return get(id);
    }

    // ---------------------------------------------------------------- 연결

    @Transactional
    public MatchView match(UUID depositId, UUID runId, BigDecimal amount) {
        guard.requirePermission(Perm.SETTLEMENT_MANAGE);
        CurrentActor a = guard.actor();
        Locked l = lock(a.workspaceId(), depositId, runId);
        BigDecimal amt = amount == null ? l.depositRemaining.min(l.runRemaining) : amount;
        if (amt.signum() <= 0 || amt.stripTrailingZeros().scale() > 0) {
            throw Errors.validation("amount", "연결 금액을 원 단위 양수로 입력하세요.");
        }
        if (amt.compareTo(l.depositRemaining) > 0) {
            throw Errors.unprocessable("DEPOSIT_OVER_MATCHED", "입금의 남은 금액보다 많이 연결할 수 없습니다.", Map.of("deposit_remaining", l.depositRemaining.toPlainString()));
        }
        if (amt.compareTo(l.runRemaining) > 0) {
            throw Errors.unprocessable("SETTLEMENT_OVER_MATCHED", "정산의 남은 지급액보다 많이 연결할 수 없습니다.", Map.of("settlement_remaining", l.runRemaining.toPlainString()));
        }
        return insertMatch(a, l, amt, "MANUAL");
    }

    /**
     * 자동 매칭: 남은 지급액이 입금 잔액과 정확히 같은 정산 1건, 없으면 정산 여러 건의 남은 지급액 합이 정확히 같은 조합이 하나뿐일 때만 연결한다.
     * 후보가 여럿이거나 조합이 여럿이면 연결하지 않고 후보를 돌려준다(추정 연결 금지).
     */
    @Transactional
    public AutoResult autoMatch(UUID depositId) {
        guard.requirePermission(Perm.SETTLEMENT_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> d = depositRow(a.workspaceId(), depositId, true);
        UUID be = (UUID) d.get("business_entity_id");
        BigDecimal remaining = ((BigDecimal) d.get("amount")).subtract(matchedOfDeposit(depositId));
        if (remaining.signum() <= 0) {
            return new AutoResult("NOTHING_TO_MATCH", List.of(), List.of(), "이미 전액 연결된 입금입니다.");
        }
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.WRITE);
        List<Map<String, Object>> cands = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("""
                select r.id, r.channel_account_id, r.payout_date,
                       coalesce((select sum(l.amount) from settlement_line l where l.run_id = r.id), 0)
                     - coalesce((select sum(m.amount) from deposit_match m where m.run_id = r.id and m.cancelled_at is null), 0) as remaining
                  from settlement_run r where r.workspace_id = :ws and r.business_entity_id = :be
                 order by abs(coalesce(r.payout_date, r.created_at::date) - cast(:d as date)), r.created_at
                """).param("ws", a.workspaceId()).param("be", be).param("d", d.get("deposited_on")).query().listOfRows()) {
            if (((BigDecimal) r.get("remaining")).signum() > 0 && cas.contains((UUID) r.get("channel_account_id"))) {
                cands.add(r);
            }
        }
        List<Map<String, Object>> exact = cands.stream().filter(r -> ((BigDecimal) r.get("remaining")).compareTo(remaining) == 0).toList();
        List<Map<String, Object>> chosen = null;
        if (exact.size() == 1) {
            chosen = exact;
        } else if (exact.size() > 1) {
            return new AutoResult("AMBIGUOUS", List.of(), exact.stream().map(r -> (UUID) r.get("id")).toList(), "금액이 같은 정산이 여러 건입니다. 직접 연결하세요.");
        } else {
            List<Map<String, Object>> pool = cands.size() > SUBSET_LIMIT ? cands.subList(0, SUBSET_LIMIT) : cands;
            List<Integer> solutions = new ArrayList<>();
            int n = pool.size();
            for (int mask = 1; mask < (1 << n) && solutions.size() < 2; mask++) {
                if (Integer.bitCount(mask) < 2) {
                    continue;
                }
                BigDecimal s = BigDecimal.ZERO;
                for (int i = 0; i < n; i++) {
                    if ((mask & (1 << i)) != 0) {
                        s = s.add((BigDecimal) pool.get(i).get("remaining"));
                    }
                }
                if (s.compareTo(remaining) == 0) {
                    solutions.add(mask);
                }
            }
            if (solutions.size() == 1) {
                int mask = solutions.get(0);
                chosen = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    if ((mask & (1 << i)) != 0) {
                        chosen.add(pool.get(i));
                    }
                }
            } else {
                return new AutoResult(solutions.isEmpty() ? "NO_MATCH" : "AMBIGUOUS", List.of(), cands.stream().map(r -> (UUID) r.get("id")).toList(),
                        solutions.isEmpty() ? "금액이 맞는 정산(또는 정산 조합)을 찾지 못했습니다. 직접 나눠 연결하세요." : "금액이 맞는 정산 조합이 여러 개입니다. 직접 연결하세요.");
            }
        }
        List<MatchView> out = new ArrayList<>();
        for (Map<String, Object> r : chosen) {
            Locked l = lock(a.workspaceId(), depositId, (UUID) r.get("id"));
            out.add(insertMatch(a, l, l.runRemaining.min(l.depositRemaining), "AUTO"));
        }
        return new AutoResult("MATCHED", out, List.of(), out.size() == 1 ? "정산 1건과 연결했습니다." : "정산 " + out.size() + "건과 연결했습니다.");
    }

    @Transactional
    public MatchView cancel(UUID matchId, String reason) {
        guard.requirePermission(Perm.SETTLEMENT_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> m = jdbc.sql("""
                select m.*, d.business_entity_id from deposit_match m join bank_deposit d on d.id = m.deposit_id where m.workspace_id = :ws and m.id = :id for update of m
                """).param("ws", a.workspaceId()).param("id", matchId).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("DEPOSIT_MATCH"));
        UUID be = (UUID) m.get("business_entity_id");
        guard.checkWrite("DEPOSIT_MATCH", matchId, be, null);
        String why = trim(reason, 500, "reason");
        if (why == null) {
            throw Errors.validation("reason", "연결 취소 사유를 입력하세요.");
        }
        if (m.get("cancelled_at") != null) {
            throw Errors.conflict("DEPOSIT_MATCH_CANCELLED", "이미 취소된 연결입니다.");
        }
        jdbc.sql("update deposit_match set cancelled_at = now(), cancelled_by = :by where id = :id").param("by", a.memberId()).param("id", matchId).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "DEPOSIT_MATCH_CANCELLED", "DEPOSIT_MATCH", matchId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).reason(why).after(Map.of("deposit_id", m.get("deposit_id").toString(), "run_id", m.get("run_id").toString(),
                        "amount", ((BigDecimal) m.get("amount")).toPlainString())).build());
        return matches("m.id = :id", Map.of("id", matchId)).get(0);
    }

    // ---------------------------------------------------------------- 조회

    public List<DepositView> list(List<String> businessEntityIds, Boolean unmatchedOnly) {
        guard.requirePermission(Perm.SETTLEMENT_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        List<DepositView> all = jdbc.sql("select id from bank_deposit where workspace_id = :ws and business_entity_id in (:bes) order by deposited_on desc, created_at desc limit 300")
                .param("ws", guard.actor().workspaceId()).param("bes", bes).query(UUID.class).list().stream().map(this::view).toList();
        return Boolean.TRUE.equals(unmatchedOnly) ? all.stream().filter(v -> v.remaining().signum() > 0).toList() : all;
    }

    public DepositView get(UUID id) {
        guard.requirePermission(Perm.SETTLEMENT_READ);
        Map<String, Object> d = depositRow(guard.actor().workspaceId(), id, false);
        guard.checkRead("BANK_DEPOSIT", id, (UUID) d.get("business_entity_id"), null);
        return view(id);
    }

    /** 정산별 연결 내역(정산 상세용). */
    public List<MatchView> matchesOfRun(UUID runId) {
        return matches("m.run_id = :r", Map.of("r", runId));
    }

    private DepositView view(UUID id) {
        Map<String, Object> d = jdbc.sql("""
                select d.*, be.display_name as be_name, ba.account_number_last4 from bank_deposit d join business_entity be on be.id = d.business_entity_id
                  left join bank_account ba on ba.id = d.bank_account_id where d.id = :id
                """).param("id", id).query().listOfRows().get(0);
        BigDecimal matched = matchedOfDeposit(id);
        BigDecimal amount = (BigDecimal) d.get("amount");
        return new DepositView(id, (UUID) d.get("business_entity_id"), (String) d.get("be_name"), (UUID) d.get("bank_account_id"), (String) d.get("account_number_last4"),
                ((java.sql.Date) d.get("deposited_on")).toLocalDate(), amount, matched, amount.subtract(matched), (String) d.get("depositor"), (String) d.get("external_ref"),
                (String) d.get("memo"), matches("m.deposit_id = :d", Map.of("d", id)), PostingService.instant(d.get("created_at")));
    }

    private List<MatchView> matches(String where, Map<String, Object> p) {
        return jdbc.sql("""
                select m.*, r.settlement_key, ca.display_name as ca_name from deposit_match m join settlement_run r on r.id = m.run_id
                  join channel_account ca on ca.id = r.channel_account_id where\s""" + where + " order by m.created_at").params(p)
                .query((rs, i) -> new MatchView(Db.uuid(rs, "id"), Db.uuid(rs, "deposit_id"), Db.uuid(rs, "run_id"), rs.getString("settlement_key"), rs.getString("ca_name"),
                        rs.getBigDecimal("amount"), rs.getString("method"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toInstant())).list();
    }

    // ---------------------------------------------------------------- 내부

    private record Locked(UUID depositId, UUID runId, UUID businessEntityId, BigDecimal depositRemaining, BigDecimal runRemaining) {
    }

    /** 입금 → 정산 순서로 잠근다(교착 방지). */
    private Locked lock(UUID ws, UUID depositId, UUID runId) {
        if (depositId == null || runId == null) {
            throw Errors.validation("run_id", "입금과 정산을 선택하세요.");
        }
        Map<String, Object> d = depositRow(ws, depositId, true);
        UUID be = (UUID) d.get("business_entity_id");
        Map<String, Object> r = jdbc.sql("select business_entity_id, channel_account_id from settlement_run where workspace_id = :ws and id = :id for update")
                .param("ws", ws).param("id", runId).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SETTLEMENT_RUN"));
        guard.checkWrite("BANK_DEPOSIT", depositId, be, null);
        guard.checkWrite("SETTLEMENT_RUN", runId, (UUID) r.get("business_entity_id"), (UUID) r.get("channel_account_id"));
        if (!be.equals(r.get("business_entity_id"))) {
            throw Errors.unprocessable("DEPOSIT_ENTITY_MISMATCH", "입금 사업자와 정산 사업자가 다릅니다. 같은 사업자의 정산만 연결할 수 있습니다.");
        }
        BigDecimal payout = jdbc.sql("select coalesce(sum(amount), 0) from settlement_line where run_id = :r").param("r", runId).query(BigDecimal.class).single();
        BigDecimal runMatched = jdbc.sql("select coalesce(sum(amount), 0) from deposit_match where run_id = :r and cancelled_at is null").param("r", runId)
                .query(BigDecimal.class).single();
        return new Locked(depositId, runId, be, ((BigDecimal) d.get("amount")).subtract(matchedOfDeposit(depositId)), payout.subtract(runMatched));
    }

    private MatchView insertMatch(CurrentActor a, Locked l, BigDecimal amt, String method) {
        if (amt.signum() <= 0) {
            throw Errors.unprocessable("NOTHING_TO_MATCH", "연결할 남은 금액이 없습니다.");
        }
        UUID id = Ids.newId();
        jdbc.sql("insert into deposit_match (id, workspace_id, deposit_id, run_id, amount, method, created_by) values (:id, :ws, :d, :r, :a, :m, :by)").param("id", id)
                .param("ws", a.workspaceId()).param("d", l.depositId()).param("r", l.runId()).param("a", amt).param("m", method).param("by", a.memberId()).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "DEPOSIT_MATCHED", "DEPOSIT_MATCH", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(l.businessEntityId(), null).after(Map.of("deposit_id", l.depositId().toString(), "run_id", l.runId().toString(), "amount", amt.toPlainString(),
                        "method", method)).build());
        return matches("m.id = :id", Map.of("id", id)).get(0);
    }

    private Map<String, Object> depositRow(UUID ws, UUID id, boolean forUpdate) {
        return jdbc.sql("select * from bank_deposit where workspace_id = :ws and id = :id" + (forUpdate ? " for update" : "")).param("ws", ws).param("id", id).query()
                .listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("BANK_DEPOSIT"));
    }

    private BigDecimal matchedOfDeposit(UUID depositId) {
        return jdbc.sql("select coalesce(sum(amount), 0) from deposit_match where deposit_id = :d and cancelled_at is null").param("d", depositId).query(BigDecimal.class)
                .single();
    }

    private static String trim(String s, int max, String field) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        if (t.length() > max) {
            throw Errors.validation(field, max + "자 이내로 입력하세요.");
        }
        return t;
    }
}
