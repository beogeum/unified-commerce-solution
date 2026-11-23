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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예상 비용 규칙(계약요율, M5 N2). 출고·반품 시 예상(ESTIMATED) 비용을 만들고, 실제 청구·정산이 오면 같은 범위의 예상을 대체한다(T26).
 * 적용 순서: 판매계정 지정 > 사업자 지정 > 운영그룹 공통, 같은 수준이면 거점 지정 우선, 그다음 최근 시작 규칙. 규칙은 고치지 않고 종료·새 규칙으로 바꾼다(이력 보존).
 */
@Service
public class CostRuleService {
    static final Set<String> CATEGORIES = Set.of("SELLING_FEE", "PAYMENT_FEE", "OUTBOUND_SHIPPING", "THIRD_PARTY_PICK", "PACK", "MATERIAL", "RETURN_SHIPPING",
            "RETURN_INSPECTION", "EXCHANGE_SHIPPING");
    static final Set<String> METHODS = Set.of("RATE_OF_NET_SALES", "PER_UNIT", "PER_ORDER", "PER_SHIPMENT", "PER_RETURN");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;

    public CostRuleService(JdbcClient jdbc, AccessGuard guard, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    public record NewRule(UUID businessEntityId, UUID channelAccountId, UUID nodeId, String category, String method, Integer rateBps, BigDecimal amount,
                          Boolean refundableOnReturn, Instant effectiveFrom, String memo) {
    }

    public record RuleView(UUID id, UUID businessEntityId, UUID channelAccountId, UUID nodeId, String category, String method, Integer rateBps, BigDecimal amount,
                           boolean refundableOnReturn, Instant effectiveFrom, Instant effectiveTo, String memo, Instant createdAt) {
    }

    /** 적용할 규칙(출고·반품 시점). */
    public record Rule(UUID id, String method, Integer rateBps, BigDecimal amount, boolean refundableOnReturn) {
    }

    public Optional<Rule> find(UUID ws, UUID be, UUID ca, UUID node, String category, Instant at) {
        return jdbc.sql("""
                select * from cost_rule where workspace_id = :ws and category = :c and effective_from <= :at and (effective_to is null or effective_to > :at)
                   and (business_entity_id is null or business_entity_id = :be) and (channel_account_id is null or channel_account_id = cast(:ca as uuid))
                   and (node_id is null or node_id = cast(:n as uuid))
                 order by (channel_account_id is not null) desc, (business_entity_id is not null) desc, (node_id is not null) desc, effective_from desc limit 1
                """).param("ws", ws).param("c", category).param("at", Db.ts(at)).param("be", be).param("ca", ca).param("n", node)
                .query((rs, i) -> new Rule(Db.uuid(rs, "id"), rs.getString("method"), (Integer) rs.getObject("rate_bps"), rs.getBigDecimal("amount"),
                        rs.getBoolean("refundable_on_return"))).optional();
    }

    @Transactional
    public RuleView create(NewRule r) {
        guard.requirePermission(Perm.COST_MANAGE);
        CurrentActor a = guard.actor();
        if (r == null || r.category() == null || !CATEGORIES.contains(r.category())) {
            throw Errors.validation("category", "비용 항목을 선택하세요.");
        }
        if (r.method() == null || !METHODS.contains(r.method())) {
            throw Errors.validation("method", "계산 방식을 선택하세요.");
        }
        boolean rate = "RATE_OF_NET_SALES".equals(r.method());
        if (rate && (r.rateBps() == null || r.rateBps() < 0 || r.rateBps() > 10000)) {
            throw Errors.validation("rate_bps", "요율은 0~100.00%(0~10000 bp)입니다.");
        }
        if (!rate && (r.amount() == null || r.amount().signum() < 0 || r.amount().stripTrailingZeros().scale() > 0)) {
            throw Errors.validation("amount", "금액은 0 이상 원 단위(공급가)입니다.");
        }
        if (r.businessEntityId() != null) {
            guard.checkWrite("BUSINESS_ENTITY", r.businessEntityId(), r.businessEntityId(), null);
        }
        if (r.channelAccountId() != null) {
            UUID be = jdbc.sql("select business_entity_id from channel_account where workspace_id = :ws and id = :id").param("ws", a.workspaceId())
                    .param("id", r.channelAccountId()).query(UUID.class).optional().orElseThrow(() -> Errors.notFound("CHANNEL_ACCOUNT"));
            guard.checkWrite("CHANNEL_ACCOUNT", r.channelAccountId(), be, r.channelAccountId());
        }
        Instant from = r.effectiveFrom() == null ? Instant.now() : r.effectiveFrom();
        // 같은 대상·항목의 열린 규칙은 새 규칙 시작 시각에 종료(겹침 없음)
        jdbc.sql("""
                update cost_rule set effective_to = :f where workspace_id = :ws and category = :c and effective_to is null and effective_from < :f
                   and business_entity_id is not distinct from cast(:be as uuid) and channel_account_id is not distinct from cast(:ca as uuid)
                   and node_id is not distinct from cast(:n as uuid)
                """).param("f", Db.ts(from)).param("ws", a.workspaceId()).param("c", r.category()).param("be", r.businessEntityId()).param("ca", r.channelAccountId())
                .param("n", r.nodeId()).update();
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into cost_rule (id, workspace_id, business_entity_id, channel_account_id, node_id, category, method, rate_bps, amount, refundable_on_return,
                                       effective_from, memo, created_by)
                values (:id, :ws, cast(:be as uuid), cast(:ca as uuid), cast(:n as uuid), :c, :m, cast(:r as integer), cast(:a as numeric), :rf, :f, cast(:memo as text), :by)
                """).param("id", id).param("ws", a.workspaceId()).param("be", r.businessEntityId()).param("ca", r.channelAccountId()).param("n", r.nodeId())
                .param("c", r.category()).param("m", r.method()).param("r", rate ? r.rateBps() : null).param("a", rate ? null : r.amount())
                .param("rf", r.refundableOnReturn() == null || r.refundableOnReturn()).param("f", Db.ts(from)).param("memo", r.memo()).param("by", a.memberId()).update();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("category", r.category());
        after.put("method", r.method());
        after.put("rate_bps", r.rateBps());
        after.put("amount", r.amount() == null ? null : r.amount().toPlainString());
        audit.record(AuditEntry.builder(a.workspaceId(), "COST_RULE_CREATED", "COST_RULE", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(r.businessEntityId(), r.channelAccountId()).after(after).build());
        return list(null).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    public List<RuleView> list(UUID businessEntityId) {
        guard.requirePermission(Perm.COST_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityId == null ? null : List.of(businessEntityId.toString()));
        return jdbc.sql("""
                select * from cost_rule where workspace_id = :ws and (business_entity_id is null or business_entity_id in (:bes)) order by category, effective_from desc
                """).param("ws", guard.actor().workspaceId()).param("bes", bes.isEmpty() ? Set.of(UUID.randomUUID()) : bes)
                .query((rs, i) -> new RuleView(Db.uuid(rs, "id"), Db.uuid(rs, "business_entity_id"), Db.uuid(rs, "channel_account_id"), Db.uuid(rs, "node_id"),
                        rs.getString("category"), rs.getString("method"), (Integer) rs.getObject("rate_bps"), rs.getBigDecimal("amount"), rs.getBoolean("refundable_on_return"),
                        Db.instant(rs, "effective_from"), Db.instant(rs, "effective_to"), rs.getString("memo"), Db.instant(rs, "created_at"))).list();
    }
}
