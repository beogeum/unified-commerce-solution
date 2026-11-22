package com.commerceos.inventory;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.ActorFactory;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.inventory.InventoryLedger.Bucket;
import com.commerceos.inventory.InventoryLedger.Position;
import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 실사(M3 I5, CONF-012~014, DEC-F21, T40).
 * <ol>
 *   <li>생성: 범위(전체·Zone·SKU)의 버킷 수량을 기준 스냅샷으로 고정하고, 행마다 재실사 기준 버전을 고정한다(Zone → 거점 → 사업자 기본값).
 *       기준이 없는 범위는 실사를 만들 수 없다.</li>
 *   <li>카운트 중에는 범위가 잠긴다(입고 적치·출고 배정·피킹·출고 확정·조정·이동 출고 중단). 블라인드면 시스템 수량을 보여 주지 않는다.</li>
 *   <li>제출: 차이·차이율·원가 영향을 계산하고 기준 초과 행은 재실사 필수/권장으로 표시한다. 잠금 해제. 실재고는 아직 바뀌지 않는다(T40).</li>
 *   <li>승인: 재고 조정 권한이 있고 요청자·카운트 담당자가 아닌 사람만. 승인한 행만 조정 원장으로 반영한다.</li>
 * </ol>
 */
@Service
public class StockCountService {
    private static final DateTimeFormatter NO = DateTimeFormatter.ofPattern("yyMM").withZone(ZoneId.of("Asia/Seoul"));
    private static final Set<String> OPEN_LINE = Set.of("COUNTING", "RECOUNT", "PENDING_APPROVAL", "RECOUNT_REQUIRED");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final InventoryLedger ledger;
    private final InventoryService inventory;
    private final ActorFactory actors;
    private final WorkItemService workItems;
    private final AuditService audit;

    public StockCountService(JdbcClient jdbc, AccessGuard guard, InventoryLedger ledger, InventoryService inventory, ActorFactory actors,
                             WorkItemService workItems, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.ledger = ledger;
        this.inventory = inventory;
        this.actors = actors;
        this.workItems = workItems;
        this.audit = audit;
    }

    // ================================================================ 재실사 기준(CONF-014)

    public record PolicyPut(UUID businessEntityId, UUID nodeId, String zone, Boolean removed, Integer qtyThreshold, BigDecimal rateThresholdPct,
                            BigDecimal costThreshold, String combine, String onExceed, String recountAssignee) {
    }

    public record PolicyView(UUID id, UUID businessEntityId, UUID nodeId, String nodeName, String zone, int version, boolean removed, Integer qtyThreshold,
                             BigDecimal rateThresholdPct, BigDecimal costThreshold, String combine, String onExceed, String recountAssignee,
                             String createdByName, Instant createdAt) {
    }

    /** 사업자의 현재 기준(범위별 최신 버전, 삭제된 재정의 제외). */
    public List<PolicyView> policies(UUID businessEntityId) {
        guard.requirePermission(Perm.INVENTORY_READ);
        guard.checkRead("BUSINESS_ENTITY", businessEntityId, businessEntityId, null);
        return jdbc.sql(POLICY_SELECT + """
                 where p.workspace_id = :ws and p.business_entity_id = :b and not p.removed
                   and p.version = (select max(x.version) from stock_count_policy x where x.business_entity_id = p.business_entity_id
                        and x.node_id is not distinct from p.node_id and x.zone is not distinct from p.zone)
                 order by p.node_id nulls first, p.zone nulls first
                """).param("ws", ws()).param("b", businessEntityId).query((rs, i) -> policyView(rs)).list();
    }

    public List<PolicyView> policyHistory(UUID businessEntityId) {
        guard.requirePermission(Perm.INVENTORY_READ);
        guard.checkRead("BUSINESS_ENTITY", businessEntityId, businessEntityId, null);
        return jdbc.sql(POLICY_SELECT + " where p.workspace_id = :ws and p.business_entity_id = :b order by p.created_at desc limit 200")
                .param("ws", ws()).param("b", businessEntityId).query((rs, i) -> policyView(rs)).list();
    }

    /** 기준 저장 = 해당 범위의 새 버전(이전 버전은 그대로, 이미 만든 실사는 이전 버전 유지). */
    @Transactional
    public PolicyView putPolicy(PolicyPut p) {
        guard.requirePermission(Perm.INVENTORY_ADJUST);
        CurrentActor a = guard.actor();
        if (p.businessEntityId() == null) {
            throw Errors.validation("business_entity_id", "사업자를 지정하세요.");
        }
        guard.checkWrite("BUSINESS_ENTITY", p.businessEntityId(), p.businessEntityId(), null);
        String zone = InventoryService.trimOrNull(p.zone());
        if (zone != null && p.nodeId() == null) {
            throw Errors.validation("zone", "Zone 재정의는 거점과 함께 지정하세요.");
        }
        if (p.nodeId() != null) {
            guard.checkNode(AccessKind.WRITE, "FULFILLMENT_NODE", p.nodeId(), p.nodeId());
            String source = jdbc.sql("select inventory_source from fulfillment_node where workspace_id = :ws and id = :id").param("ws", a.workspaceId())
                    .param("id", p.nodeId()).query(String.class).optional().orElseThrow(() -> Errors.notFound("FULFILLMENT_NODE"));
            if ("EXTERNAL".equals(source)) {
                throw Errors.unprocessable("NODE_INVENTORY_EXTERNAL", "물류사 재고 기준 거점은 3PL 실사 결과를 외부 재고 동기화로 대조합니다(내부 실사 기준 없음).");
            }
        }
        boolean removed = Boolean.TRUE.equals(p.removed());
        if (removed && p.nodeId() == null) {
            throw Errors.validation("removed", "사업자 기본값은 삭제할 수 없습니다. 값을 바꿔 저장하세요.");
        }
        if (!removed) {
            if (p.qtyThreshold() == null && p.rateThresholdPct() == null && p.costThreshold() == null) {
                throw Errors.validation("qty_threshold", "수량 차이·차이율·원가 영향 중 하나 이상을 입력하세요(조건이 없으면 실사를 만들 수 없습니다).");
            }
            if ((p.qtyThreshold() != null && p.qtyThreshold() < 0) || (p.rateThresholdPct() != null && p.rateThresholdPct().signum() < 0)
                    || (p.costThreshold() != null && p.costThreshold().signum() < 0)) {
                throw Errors.validation("qty_threshold", "기준값은 0 이상입니다.");
            }
            if (p.costThreshold() != null && p.costThreshold().scale() > 0 && p.costThreshold().stripTrailingZeros().scale() > 0) {
                throw Errors.validation("cost_threshold", "원가 영향 기준은 원 단위 정수입니다.");
            }
            if (!Set.of("ANY", "ALL").contains(String.valueOf(p.combine()))) {
                throw Errors.validation("combine", "조건 결합 방식을 선택하세요(하나라도/모두).");
            }
            if (!Set.of("RECOUNT_REQUIRED", "RECOUNT_RECOMMENDED").contains(String.valueOf(p.onExceed()))) {
                throw Errors.validation("on_exceed", "기준 초과 시 처리를 선택하세요(재실사 필수/권장).");
            }
            if (!Set.of("DIFFERENT_COUNTER", "SAME_ALLOWED").contains(String.valueOf(p.recountAssignee()))) {
                throw Errors.validation("recount_assignee", "재실사 담당 규칙을 선택하세요.");
            }
        }
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:k))").param("k", "count_policy:" + p.businessEntityId() + ":" + p.nodeId() + ":" + zone)
                .query().listOfRows();
        int version = jdbc.sql("""
                select coalesce(max(version), 0) + 1 from stock_count_policy where business_entity_id = :b and node_id is not distinct from cast(:n as uuid)
                   and zone is not distinct from cast(:z as text)
                """).param("b", p.businessEntityId()).param("n", p.nodeId()).param("z", zone).query(Integer.class).single();
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into stock_count_policy (id, workspace_id, business_entity_id, node_id, zone, version, removed, qty_threshold, rate_threshold_pct,
                    cost_threshold, combine, on_exceed, recount_assignee, created_by)
                values (:id, :ws, :b, cast(:n as uuid), cast(:z as text), :v, :rm, cast(:q as integer), cast(:r as numeric), cast(:c as numeric),
                        cast(:cb as text), cast(:oe as text), cast(:ra as text), :by)
                """).param("id", id).param("ws", a.workspaceId()).param("b", p.businessEntityId()).param("n", p.nodeId()).param("z", zone).param("v", version)
                .param("rm", removed).param("q", removed ? null : p.qtyThreshold()).param("r", removed ? null : p.rateThresholdPct())
                .param("c", removed ? null : p.costThreshold()).param("cb", removed ? null : p.combine()).param("oe", removed ? null : p.onExceed())
                .param("ra", removed ? null : p.recountAssignee()).param("by", a.memberId()).update();
        audit.record(AuditEntry.builder(a.workspaceId(), removed ? "STOCK_COUNT_POLICY_REMOVED" : "STOCK_COUNT_POLICY_SAVED", "STOCK_COUNT_POLICY", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(p.businessEntityId(), null)
                .after(policyMap(p, zone, version, removed)).build());
        return jdbc.sql(POLICY_SELECT + " where p.id = :id").param("id", id).query((rs, i) -> policyView(rs)).single();
    }

    /** 좁은 범위 우선: Zone → 거점 → 사업자 기본값. */
    Optional<PolicyView> resolvePolicy(UUID ws, UUID be, UUID nodeId, String zone) {
        List<String[]> scopes = new ArrayList<>();
        if (zone != null) {
            scopes.add(new String[]{nodeId.toString(), zone});
        }
        scopes.add(new String[]{nodeId.toString(), null});
        scopes.add(new String[]{null, null});
        for (String[] sc : scopes) {
            Optional<PolicyView> v = jdbc.sql(POLICY_SELECT + """
                     where p.workspace_id = :ws and p.business_entity_id = :b and p.node_id is not distinct from cast(:n as uuid)
                       and p.zone is not distinct from cast(:z as text) order by p.version desc limit 1
                    """).param("ws", ws).param("b", be).param("n", sc[0] == null ? null : UUID.fromString(sc[0])).param("z", sc[1])
                    .query((rs, i) -> policyView(rs)).optional();
            if (v.isPresent() && !v.get().removed()) {
                return v;
            }
        }
        return Optional.empty();
    }

    // ================================================================ 실사

    public record Create(UUID businessEntityId, UUID nodeId, String scope, String zone, List<UUID> skuIds, Boolean blind, List<UUID> counterMemberIds,
                         String note) {
    }

    public record Entry(UUID lineId, Integer countedQty) {
    }

    public record FoundLine(UUID skuId, String locationCode, String lotCode, String qualityStatus, Integer countedQty) {
    }

    public record Entries(List<Entry> entries, List<FoundLine> foundLines) {
    }

    public record Decision(List<UUID> lineIds, Boolean approve, String reason) {
    }

    public record LineIds(List<UUID> lineIds) {
    }

    public record CountView(UUID id, String countNo, UUID businessEntityId, String businessEntityName, UUID nodeId, String nodeName, String scope, String zone,
                            List<UUID> skuIds, boolean blind, UUID policyId, int policyVersion, String status, int round, Instant snapshotAt, String note,
                            UUID requestedBy, String requestedByName, List<Map<String, Object>> counters, int lineCount, int countedLines, int diffLines,
                            int pendingLines, int recountRequiredLines, Instant submittedAt, Instant completedAt, int version) {
    }

    public record LineView(UUID id, int lineNo, UUID skuId, String skuCode, String skuName, String locationCode, String zone, String lotCode,
                           String qualityStatus, String holdReason, Integer systemQty, boolean foundInCount, Integer countedQty, String countedByName,
                           int recountCount, String status, Integer diff, BigDecimal diffRatePct, BigDecimal unitCost, BigDecimal costImpact,
                           Boolean exceedsPolicy, boolean recountRecommended, UUID policyId, int policyVersion, String decidedByName, String decisionReason) {
    }

    public record CountDetail(CountView count, List<LineView> lines, Map<String, Object> impact) {
    }

    /** 생성 전 영향 미리보기: 잠길 버킷·로케이션 수, 이 범위에 걸린 진행 중 확보, 적용 기준. */
    public Map<String, Object> preview(Create c) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        guard.checkWrite("BUSINESS_ENTITY", c.businessEntityId(), c.businessEntityId(), null);
        guard.checkNode(AccessKind.WRITE, "FULFILLMENT_NODE", c.nodeId(), c.nodeId());
        Scope s = scope(c);
        List<Map<String, Object>> buckets = bucketsInScope(ws(), c.businessEntityId(), c.nodeId(), s);
        return impact(ws(), c.businessEntityId(), c.nodeId(), s, buckets);
    }

    @Transactional
    public CountDetail create(Create c) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        if (c.businessEntityId() == null || c.nodeId() == null) {
            throw Errors.validation("node_id", "소유 사업자와 거점을 지정하세요.");
        }
        guard.checkWrite("BUSINESS_ENTITY", c.businessEntityId(), c.businessEntityId(), null);
        guard.checkNode(AccessKind.WRITE, "FULFILLMENT_NODE", c.nodeId(), c.nodeId());
        Map<String, Object> node = jdbc.sql("select inventory_source, status, logistics_provider_code from fulfillment_node where workspace_id = :ws and id = :id")
                .param("ws", a.workspaceId()).param("id", c.nodeId()).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("FULFILLMENT_NODE"));
        if ("EXTERNAL".equals(node.get("inventory_source"))) {
            throw Errors.unprocessable("NODE_INVENTORY_EXTERNAL", "물류사 재고 기준 거점은 3PL 이 실사하고, 결과는 외부 재고 동기화로 받아 대조합니다.");
        }
        if (!"ACTIVE".equals(node.get("status"))) {
            throw Errors.unprocessable("NODE_NOT_ACTIVE", "운영 중인 거점이 아닙니다.");
        }
        Scope s = scope(c);
        List<UUID> counters = c.counterMemberIds() == null ? List.of() : c.counterMemberIds().stream().distinct().toList();
        if (counters.isEmpty() || counters.size() > 20) {
            throw Errors.validation("counter_member_ids", "카운트 담당자를 1~20명 지정하세요.");
        }
        for (UUID m : counters) {
            CurrentActor ca = actors.forMember(a.workspaceId(), m).orElseThrow(() -> Errors.validation("counter_member_ids", "활성 사용자가 아닌 카운트 담당자가 있습니다."));
            if (!ca.has(Perm.INVENTORY_OPERATE)) {
                throw Errors.validation("counter_member_ids", ca.displayName() + " 님은 재고 처리 권한이 없어 카운트 담당자가 될 수 없습니다.");
            }
        }
        // 같은 범위가 겹치는 진행 중 실사가 있으면 거부(잠금 이중화 방지)
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:k))").param("k", "stock_count:" + c.businessEntityId() + ":" + c.nodeId()).query().listOfRows();
        int overlapping = jdbc.sql("""
                select count(*) from stock_count where workspace_id = :ws and business_entity_id = :b and node_id = :n and status in ('COUNTING', 'REVIEW')
                   and (scope = 'ALL' or :scope = 'ALL' or (scope = 'ZONE' and zone = cast(:z as text)) or (scope = 'SKU' and sku_ids && cast(:skus as uuid[]))
                        or (scope <> :scope))
                """).param("ws", a.workspaceId()).param("b", c.businessEntityId()).param("n", c.nodeId()).param("scope", s.scope()).param("z", s.zone())
                .param("skus", s.skuIds() == null ? new UUID[0] : s.skuIds().toArray(new UUID[0])).query(Integer.class).single();
        if (overlapping > 0) {
            throw Errors.conflict("STOCK_COUNT_OVERLAP", "같은 범위에 진행 중인 실사가 있습니다. 그 실사를 마치거나 취소한 뒤 만드세요.");
        }
        PolicyView base = resolvePolicy(a.workspaceId(), c.businessEntityId(), c.nodeId(), s.zone())
                .orElseThrow(() -> Errors.unprocessable("COUNT_POLICY_MISSING", "이 범위에 적용할 재실사 기준이 없습니다. 재고정책에서 사업자 기본값 또는 거점·Zone 기준을 먼저 정하세요."));
        List<Map<String, Object>> buckets = bucketsInScope(a.workspaceId(), c.businessEntityId(), c.nodeId(), s);
        Map<String, PolicyView> zonePolicies = new LinkedHashMap<>();
        for (Map<String, Object> b : buckets) {
            String z = (String) b.get("zone");
            if (z != null && !zonePolicies.containsKey(z)) {
                zonePolicies.put(z, resolvePolicy(a.workspaceId(), c.businessEntityId(), c.nodeId(), z).orElse(base));
            }
        }
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:k))").param("k", "stock_count_no:" + a.workspaceId()).query().listOfRows();
        Instant now = Instant.now();
        String prefix = "C-" + NO.format(now) + "-";
        int seq = jdbc.sql("select count(*) + 1 from stock_count where workspace_id = :ws and count_no like :p").param("ws", a.workspaceId()).param("p", prefix + "%")
                .query(Integer.class).single();
        String countNo = prefix + String.format("%03d", seq);
        UUID id = Ids.newId();
        boolean blind = c.blind() == null || c.blind();
        jdbc.sql("""
                insert into stock_count (id, workspace_id, count_no, business_entity_id, node_id, scope, zone, sku_ids, blind, policy_id, snapshot_at, note, requested_by)
                values (:id, :ws, :no, :b, :n, :scope, cast(:z as text), cast(:skus as uuid[]), :blind, :pol, now(), cast(:note as text), :by)
                """).param("id", id).param("ws", a.workspaceId()).param("no", countNo).param("b", c.businessEntityId()).param("n", c.nodeId())
                .param("scope", s.scope()).param("z", s.zone()).param("skus", s.skuIds() == null ? null : s.skuIds().toArray(new UUID[0]))
                .param("blind", blind).param("pol", base.id()).param("note", InventoryService.trimOrNull(c.note())).param("by", a.memberId()).update();
        for (UUID m : counters) {
            jdbc.sql("insert into stock_count_counter (workspace_id, count_id, member_id) values (:ws, :c, :m)").param("ws", a.workspaceId()).param("c", id)
                    .param("m", m).update();
        }
        int no = 1;
        for (Map<String, Object> b : buckets) {
            String z = (String) b.get("zone");
            PolicyView pol = z == null ? base : zonePolicies.get(z);
            insertLine(a.workspaceId(), id, no++, (UUID) b.get("sku_id"), (UUID) b.get("location_id"), (UUID) b.get("lot_id"), (String) b.get("quality_status"),
                    (String) b.get("hold_reason"), ((Number) b.get("on_hand")).intValue(), false, pol.id());
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "STOCK_COUNT_CREATED", "STOCK_COUNT", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(c.businessEntityId(), null).after(Map.of("count_no", countNo, "scope", s.scope(), "blind", blind, "lines", buckets.size(),
                        "policy_id", base.id().toString(), "counters", counters.stream().map(UUID::toString).toList())).build());
        return get(id);
    }

    /** 카운트 입력(배정된 담당자만, 회차별 이력 보존). 실사 중 발견한 버킷은 시스템 수량 0 행으로 추가한다. */
    @Transactional
    public CountDetail enter(UUID countId, Entries req) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        CountRow c = lockCount(a.workspaceId(), countId);
        if (!"COUNTING".equals(c.status())) {
            throw Errors.conflict("STOCK_COUNT_NOT_COUNTING", "카운트 중인 실사가 아닙니다.", Map.of("status", c.status()));
        }
        if (!isCounter(countId, a.memberId())) {
            throw Errors.unprocessable("NOT_COUNT_ASSIGNEE", "이 실사의 카운트 담당자만 수량을 입력할 수 있습니다.");
        }
        PolicyView base = policyById(c.policyId());
        List<Entry> entries = req.entries() == null ? List.of() : req.entries();
        for (Entry e : entries) {
            if (e.lineId() == null || e.countedQty() == null || e.countedQty() < 0 || e.countedQty() > 10_000_000) {
                throw Errors.validation("entries.counted_qty", "실사 수량은 0 이상의 정수입니다.");
            }
            Map<String, Object> line = jdbc.sql("select status, first_counted_by, policy_id, system_qty from stock_count_line where count_id = :c and id = :id for update")
                    .param("c", countId).param("id", e.lineId()).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("STOCK_COUNT_LINE"));
            String st = (String) line.get("status");
            if (!"COUNTING".equals(st) && !"RECOUNT".equals(st)) {
                throw Errors.conflict("COUNT_LINE_NOT_OPEN", "카운트를 받는 행이 아닙니다.", Map.of("line_id", e.lineId().toString(), "status", st));
            }
            if ("RECOUNT".equals(st)) {
                PolicyView pol = policyById((UUID) line.get("policy_id"));
                if ("DIFFERENT_COUNTER".equals(pol.recountAssignee()) && a.memberId().equals(line.get("first_counted_by"))) {
                    throw Errors.unprocessable("RECOUNT_SAME_COUNTER", "재실사는 첫 카운트 담당자가 아닌 사람이 해야 합니다(재고정책).");
                }
            }
            jdbc.sql("""
                    update stock_count_line set counted_qty = :q, counted_by = :by, counted_at = now(), counted_round = :r,
                           first_counted_by = coalesce(first_counted_by, :by) where id = :id
                    """).param("q", e.countedQty()).param("by", a.memberId()).param("r", c.round()).param("id", e.lineId()).update();
            jdbc.sql("insert into stock_count_entry (id, workspace_id, line_id, round, system_qty, counted_qty, counted_by) values (:id, :ws, :l, :r, :s, :q, :by)")
                    .param("id", Ids.newId()).param("ws", a.workspaceId()).param("l", e.lineId()).param("r", c.round())
                    .param("s", ((Number) line.get("system_qty")).intValue()).param("q", e.countedQty()).param("by", a.memberId()).update();
        }
        for (FoundLine f : req.foundLines() == null ? List.<FoundLine>of() : req.foundLines()) {
            addFoundLine(a, c, base, f);
        }
        return get(countId);
    }

    /** 제출: 차이 계산·기준 판정, 잠금 해제. 실재고는 바꾸지 않는다(T40). */
    @Transactional
    public CountDetail submit(UUID countId) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        CountRow c = lockCount(a.workspaceId(), countId);
        if (!"COUNTING".equals(c.status())) {
            throw Errors.conflict("STOCK_COUNT_NOT_COUNTING", "카운트 중인 실사가 아닙니다.", Map.of("status", c.status()));
        }
        if (!isCounter(countId, a.memberId()) && !a.memberId().equals(c.requestedBy())) {
            throw Errors.unprocessable("NOT_COUNT_ASSIGNEE", "실사 요청자 또는 카운트 담당자만 제출할 수 있습니다.");
        }
        int uncounted = jdbc.sql("""
                select count(*) from stock_count_line where count_id = :c and status in ('COUNTING', 'RECOUNT') and (counted_round is null or counted_round <> :r)
                """).param("c", countId).param("r", c.round()).query(Integer.class).single();
        if (uncounted > 0) {
            throw Errors.unprocessable("COUNT_INCOMPLETE", "아직 카운트하지 않은 행이 " + uncounted + "개 있습니다.", Map.of("uncounted", uncounted));
        }
        List<Map<String, Object>> lines = jdbc.sql("select * from stock_count_line where count_id = :c and status in ('COUNTING', 'RECOUNT') order by line_no")
                .param("c", countId).query().listOfRows();
        int recountRequired = 0;
        for (Map<String, Object> l : lines) {
            int system = ((Number) l.get("system_qty")).intValue();
            int counted = ((Number) l.get("counted_qty")).intValue();
            int diff = counted - system;
            UUID sku = (UUID) l.get("sku_id");
            Optional<BigDecimal> unit = inventory.unitCost(a.workspaceId(), c.businessEntityId(), sku);
            BigDecimal rate = system == 0 ? null : BigDecimal.valueOf(Math.abs(diff) * 100L).divide(BigDecimal.valueOf(system), 2, RoundingMode.HALF_UP);
            BigDecimal cost = unit.map(u -> u.multiply(BigDecimal.valueOf(diff))).orElse(null);
            String status;
            Boolean exceeds = null;
            boolean recommended = false;
            if (diff == 0) {
                status = "MATCHED";
            } else {
                PolicyView pol = policyById((UUID) l.get("policy_id"));
                exceeds = exceeds(pol, Math.abs(diff), system, rate, cost);
                int recounts = ((Number) l.get("recount_count")).intValue();
                if (exceeds && recounts == 0 && "RECOUNT_REQUIRED".equals(pol.onExceed())) {
                    status = "RECOUNT_REQUIRED";
                    recountRequired++;
                } else {
                    status = "PENDING_APPROVAL";
                    recommended = exceeds && recounts == 0 && "RECOUNT_RECOMMENDED".equals(pol.onExceed());
                }
            }
            jdbc.sql("""
                    update stock_count_line set status = :st, diff = :d, diff_rate_pct = cast(:rate as numeric), unit_cost = cast(:u as numeric),
                           cost_impact = cast(:cost as numeric), exceeds_policy = cast(:ex as boolean), recount_recommended = :rec where id = :id
                    """).param("st", status).param("d", diff).param("rate", rate).param("u", unit.orElse(null)).param("cost", cost).param("ex", exceeds)
                    .param("rec", recommended).param("id", l.get("id")).update();
        }
        jdbc.sql("update stock_count set status = 'REVIEW', submitted_at = now(), updated_at = now(), version = version + 1 where id = :id").param("id", countId).update();
        String key = "stock_count:" + countId + ":RECOUNT_REQUIRED";
        if (recountRequired > 0) {
            workItems.raise(new WorkItemService.Raise(a.workspaceId(), c.businessEntityId(), null, null, "STOCK_RECOUNT_REQUIRED", "MEDIUM",
                    "재실사 필요 " + recountRequired + "행 · 실사 " + c.countNo(), "재실사 기준을 넘는 차이가 있어 승인 전에 재실사가 필요합니다.",
                    "실사 상세에서 해당 행의 재실사를 시작하고, 재고정책의 담당 규칙에 맞는 담당자가 다시 카운트하세요.", key,
                    Map.of("count_id", countId.toString(), "count_no", c.countNo(), "lines", recountRequired)));
        } else {
            workItems.resolveByKey(a.workspaceId(), key);
        }
        completeIfDone(a, countId);
        audit.record(AuditEntry.builder(a.workspaceId(), "STOCK_COUNT_SUBMITTED", "STOCK_COUNT", countId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(c.businessEntityId(), null).metadata(Map.of("round", c.round(), "lines", lines.size(), "recount_required", recountRequired)).build());
        return get(countId);
    }

    /** 재실사 시작: 해당 행의 시스템 수량을 지금 버킷 수량으로 다시 고정하고 실사를 다시 카운트 중(잠금)으로 돌린다. */
    @Transactional
    public CountDetail recount(UUID countId, LineIds req) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        CountRow c = lockCount(a.workspaceId(), countId);
        if (!"REVIEW".equals(c.status())) {
            throw Errors.conflict("STOCK_COUNT_NOT_IN_REVIEW", "차이 검토 중인 실사만 재실사할 수 있습니다.", Map.of("status", c.status()));
        }
        if (!a.memberId().equals(c.requestedBy()) && !a.has(Perm.INVENTORY_ADJUST)) {
            throw Errors.permissionDenied(Perm.INVENTORY_ADJUST);
        }
        List<UUID> ids = req == null || req.lineIds() == null ? List.of() : req.lineIds().stream().distinct().toList();
        if (ids.isEmpty()) {
            throw Errors.validation("line_ids", "재실사할 행을 선택하세요.");
        }
        int round = c.round() + 1;
        for (UUID lineId : ids) {
            Map<String, Object> l = jdbc.sql("select * from stock_count_line where count_id = :c and id = :id for update").param("c", countId).param("id", lineId)
                    .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("STOCK_COUNT_LINE"));
            if (!Set.of("RECOUNT_REQUIRED", "PENDING_APPROVAL").contains((String) l.get("status"))) {
                throw Errors.conflict("COUNT_LINE_NOT_PENDING", "차이 검토 중인 행만 재실사할 수 있습니다.", Map.of("line_id", lineId.toString()));
            }
            int current = ledger.findBucket(a.workspaceId(), bucketOf(c, l)).map(InventoryLedger.Stock::onHand).orElse(0);
            jdbc.sql("""
                    update stock_count_line set status = 'RECOUNT', system_qty = :s, counted_qty = null, counted_by = null, counted_at = null,
                           recount_count = recount_count + 1, diff = null, diff_rate_pct = null, cost_impact = null, exceeds_policy = null, recount_recommended = false
                     where id = :id
                    """).param("s", current).param("id", lineId).update();
        }
        jdbc.sql("update stock_count set status = 'COUNTING', round = :r, updated_at = now(), version = version + 1 where id = :id").param("r", round)
                .param("id", countId).update();
        workItems.resolveByKey(a.workspaceId(), "stock_count:" + countId + ":RECOUNT_REQUIRED");
        audit.record(AuditEntry.builder(a.workspaceId(), "STOCK_COUNT_RECOUNT_STARTED", "STOCK_COUNT", countId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(c.businessEntityId(), null).metadata(Map.of("round", round, "lines", ids.size())).build());
        return get(countId);
    }

    /**
     * 차이 승인·반려(CONF-013). 재고 조정 권한 + 요청자·카운트 담당자가 아닌 사람. 재실사 필수 행·기준 초과 미재실사 행은 승인 불가.
     * 승인 시 버킷이 스냅샷 이후 바뀌었으면 반영하지 않고 409(재실사 필요).
     */
    @Transactional
    public CountDetail decide(UUID countId, Decision d) {
        guard.requirePermission(Perm.INVENTORY_ADJUST);
        CurrentActor a = guard.actor();
        CountRow c = lockCount(a.workspaceId(), countId);
        guard.checkWrite("BUSINESS_ENTITY", c.businessEntityId(), c.businessEntityId(), null);
        if (!"REVIEW".equals(c.status())) {
            throw Errors.conflict("STOCK_COUNT_NOT_IN_REVIEW", "차이 검토 중인 실사가 아닙니다.", Map.of("status", c.status()));
        }
        boolean counted = jdbc.sql("""
                select count(*) from stock_count_entry e join stock_count_line l on l.id = e.line_id where l.count_id = :c and e.counted_by = :m
                """).param("c", countId).param("m", a.memberId()).query(Integer.class).single() > 0;
        if (a.memberId().equals(c.requestedBy()) || isCounter(countId, a.memberId()) || counted) {
            throw Errors.unprocessable("COUNT_APPROVER_CONFLICT", "실사를 요청했거나 카운트를 맡은 사람은 이 실사의 차이를 승인·반려할 수 없습니다. 다른 승인자에게 요청하세요.");
        }
        List<UUID> ids = d == null || d.lineIds() == null ? List.of() : d.lineIds().stream().distinct().toList();
        if (ids.isEmpty() || d.approve() == null) {
            throw Errors.validation("line_ids", "승인·반려할 행과 결정을 선택하세요.");
        }
        String reason = InventoryService.trimOrNull(d.reason());
        InventoryService.requireReason(reason, false);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (UUID lineId : ids) {
            Map<String, Object> l = jdbc.sql("select * from stock_count_line where count_id = :c and id = :id for update").param("c", countId).param("id", lineId)
                    .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("STOCK_COUNT_LINE"));
            String st = (String) l.get("status");
            if ("RECOUNT_REQUIRED".equals(st)) {
                throw Errors.conflict("RECOUNT_REQUIRED_BEFORE_APPROVAL", "재실사 기준을 넘은 행은 재실사 후에 승인할 수 있습니다.", Map.of("line_id", lineId.toString()));
            }
            if (!"PENDING_APPROVAL".equals(st)) {
                throw Errors.conflict("COUNT_LINE_NOT_PENDING", "승인 대기 중인 행이 아닙니다.", Map.of("line_id", lineId.toString(), "status", st));
            }
            if (d.approve() && reason == null && (Boolean.TRUE.equals(l.get("recount_recommended")) || !c.blind())) {
                throw Errors.validation("reason", Boolean.TRUE.equals(l.get("recount_recommended"))
                        ? "재실사 권장 행을 재실사 없이 승인하려면 사유가 필요합니다." : "수량 표시 방식 실사의 차이를 승인하려면 사유가 필요합니다.");
            }
            lines.add(l);
        }
        if (d.approve()) {
            applyApproved(a, c, lines, reason);
        }
        for (Map<String, Object> l : lines) {
            jdbc.sql("update stock_count_line set status = :st, decided_by = :by, decided_at = now(), decision_reason = cast(:r as text) where id = :id")
                    .param("st", d.approve() ? "APPROVED" : "REJECTED").param("by", a.memberId()).param("r", reason).param("id", l.get("id")).update();
        }
        audit.record(AuditEntry.builder(a.workspaceId(), d.approve() ? "STOCK_COUNT_DIFF_APPROVED" : "STOCK_COUNT_DIFF_REJECTED", "STOCK_COUNT", countId)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(c.businessEntityId(), null).reason(reason)
                .metadata(Map.of("lines", ids.stream().map(UUID::toString).toList(), "count_no", c.countNo())).build());
        completeIfDone(a, countId);
        return get(countId);
    }

    @Transactional
    public CountDetail cancel(UUID countId, String reason) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        CountRow c = lockCount(a.workspaceId(), countId);
        if (!Set.of("COUNTING", "REVIEW").contains(c.status())) {
            throw Errors.conflict("STOCK_COUNT_CLOSED", "이미 끝난 실사입니다.", Map.of("status", c.status()));
        }
        if (!a.memberId().equals(c.requestedBy()) && !a.has(Perm.INVENTORY_ADJUST)) {
            throw Errors.permissionDenied(Perm.INVENTORY_ADJUST);
        }
        InventoryService.requireReason(reason, true);
        jdbc.sql("update stock_count set status = 'CANCELLED', cancelled_at = now(), updated_at = now(), version = version + 1 where id = :id").param("id", countId).update();
        workItems.resolveByKey(a.workspaceId(), "stock_count:" + countId + ":RECOUNT_REQUIRED");
        audit.record(AuditEntry.builder(a.workspaceId(), "STOCK_COUNT_CANCELLED", "STOCK_COUNT", countId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(c.businessEntityId(), null).reason(reason.trim()).build());
        return get(countId);
    }

    public List<CountView> list(List<String> businessEntityIds, UUID nodeId, String status) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(COUNT_SELECT + " where c.workspace_id = :ws and c.business_entity_id in (:bes) and c.node_id in (:nodes)");
        Map<String, Object> params = new LinkedHashMap<>(Map.of("ws", ws(), "bes", bes, "nodes", inventory.visibleNodes(nodeId)));
        if (status != null && !status.isBlank()) {
            sql.append(" and c.status = :st");
            params.put("st", status);
        }
        sql.append(" order by c.id desc limit 200");
        return jdbc.sql(sql.toString()).params(params).query((rs, i) -> countView(rs)).list().stream().map(this::withCounters).toList();
    }

    public CountDetail get(UUID id) {
        guard.requirePermission(Perm.INVENTORY_READ);
        CountView v = jdbc.sql(COUNT_SELECT + " where c.workspace_id = :ws and c.id = :id").param("ws", ws()).param("id", id).query((rs, i) -> countView(rs))
                .optional().orElseThrow(() -> Errors.notFound("STOCK_COUNT"));
        guard.checkRead("STOCK_COUNT", id, v.businessEntityId(), null);
        guard.checkNode(AccessKind.READ, "STOCK_COUNT", id, v.nodeId());
        // 블라인드 실사는 카운트 중에 시스템 수량·차이를 누구에게도 보여 주지 않는다
        boolean hide = v.blind() && "COUNTING".equals(v.status());
        List<LineView> lines = jdbc.sql("""
                select l.*, s.sku_code, coalesce(s.name, pr.name) as sku_name, loc.code as location_code, loc.zone, lot.lot_code, p.version as policy_version,
                       cu.display_name as counted_by_name, du.display_name as decided_by_name
                  from stock_count_line l join sku s on s.id = l.sku_id join product pr on pr.id = s.product_id
                  left join node_location loc on loc.id = l.location_id left join inventory_lot lot on lot.id = l.lot_id
                  join stock_count_policy p on p.id = l.policy_id
                  left join workspace_member cm on cm.workspace_id = l.workspace_id and cm.id = l.counted_by left join user_account cu on cu.id = cm.user_id
                  left join workspace_member dm on dm.workspace_id = l.workspace_id and dm.id = l.decided_by left join user_account du on du.id = dm.user_id
                 where l.count_id = :id order by l.line_no
                """).param("id", id).query((rs, i) -> new LineView(Db.uuid(rs, "id"), rs.getInt("line_no"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"),
                rs.getString("sku_name"), rs.getString("location_code"), rs.getString("zone"), rs.getString("lot_code"), rs.getString("quality_status"),
                rs.getString("hold_reason"), hide ? null : rs.getInt("system_qty"), rs.getBoolean("found_in_count"), (Integer) rs.getObject("counted_qty"),
                rs.getString("counted_by_name"), rs.getInt("recount_count"), rs.getString("status"), hide ? null : (Integer) rs.getObject("diff"),
                hide ? null : rs.getBigDecimal("diff_rate_pct"), rs.getBigDecimal("unit_cost"), hide ? null : rs.getBigDecimal("cost_impact"),
                (Boolean) rs.getObject("exceeds_policy"), rs.getBoolean("recount_recommended"), Db.uuid(rs, "policy_id"), rs.getInt("policy_version"),
                rs.getString("decided_by_name"), rs.getString("decision_reason"))).list();
        Map<String, Object> impact = "COUNTING".equals(v.status()) ? Map.of("locked", true) : Map.of("locked", false);
        return new CountDetail(withCounters(v), lines, impact);
    }

    public record Assignee(UUID memberId, String displayName) {
    }

    /** 카운트 담당자 후보: 재고 처리 권한이 있는 활성 사용자(사용자 관리 권한 없이도 고를 수 있게). */
    public List<Assignee> assignees() {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        UUID ws = ws();
        return jdbc.sql("""
                select m.id from workspace_member m join user_account u on u.id = m.user_id where m.workspace_id = :ws and m.status = 'ACTIVE'
                   and u.status = 'ACTIVE' order by u.display_name limit 500
                """).param("ws", ws).query(UUID.class).list().stream()
                .map(id -> actors.forMember(ws, id)).flatMap(Optional::stream).filter(x -> x.has(Perm.INVENTORY_OPERATE))
                .map(x -> new Assignee(x.memberId(), x.displayName())).toList();
    }

    // ================================================================ 내부

    record Scope(String scope, String zone, List<UUID> skuIds) {
    }

    record CountRow(UUID id, String countNo, UUID businessEntityId, UUID nodeId, String status, int round, boolean blind, UUID policyId, UUID requestedBy) {
    }

    private Scope scope(Create c) {
        String scope = c.scope() == null ? "ALL" : c.scope();
        return switch (scope) {
            case "ALL" -> new Scope("ALL", null, null);
            case "ZONE" -> {
                String z = InventoryService.trimOrNull(c.zone());
                if (z == null) {
                    throw Errors.validation("zone", "Zone 을 선택하세요.");
                }
                int n = jdbc.sql("select count(*) from node_location where workspace_id = :ws and node_id = :n and zone = :z").param("ws", ws())
                        .param("n", c.nodeId()).param("z", z).query(Integer.class).single();
                if (n == 0) {
                    throw Errors.validation("zone", "이 거점에 없는 Zone 입니다.");
                }
                yield new Scope("ZONE", z, null);
            }
            case "SKU" -> {
                if (c.skuIds() == null || c.skuIds().isEmpty() || c.skuIds().size() > 500) {
                    throw Errors.validation("sku_ids", "실사할 SKU 를 1~500개 선택하세요.");
                }
                List<UUID> skus = c.skuIds().stream().distinct().toList();
                skus.forEach(inventory::skuInfo);
                yield new Scope("SKU", null, skus);
            }
            default -> throw Errors.validation("scope", "범위는 ALL·ZONE·SKU 중 하나입니다.");
        };
    }

    private List<Map<String, Object>> bucketsInScope(UUID ws, UUID be, UUID node, Scope s) {
        String filter = switch (s.scope()) {
            case "ZONE" -> " and loc.zone = :z";
            case "SKU" -> " and st.sku_id in (:skus)";
            default -> "";
        };
        var q = jdbc.sql("""
                select st.id, st.sku_id, st.location_id, st.lot_id, st.quality_status, st.hold_reason, st.on_hand, loc.zone
                  from inventory_stock st left join node_location loc on loc.id = st.location_id join sku k on k.id = st.sku_id
                 where st.workspace_id = :ws and st.owner_business_entity_id = :b and st.node_id = :n and st.on_hand > 0
                """ + filter + " order by loc.code nulls first, k.sku_code, st.quality_status").param("ws", ws).param("b", be).param("n", node);
        if ("ZONE".equals(s.scope())) {
            q = q.param("z", s.zone());
        }
        if ("SKU".equals(s.scope())) {
            q = q.param("skus", s.skuIds());
        }
        return q.query().listOfRows();
    }

    private Map<String, Object> impact(UUID ws, UUID be, UUID node, Scope s, List<Map<String, Object>> buckets) {
        Set<UUID> locations = buckets.stream().map(b -> (UUID) b.get("location_id")).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> skus = buckets.stream().map(b -> (UUID) b.get("sku_id")).collect(Collectors.toSet());
        int activeReservations = skus.isEmpty() ? 0 : jdbc.sql("""
                select count(*) from inventory_reservation where workspace_id = :ws and owner_business_entity_id = :b and node_id = :n and sku_id in (:s)
                   and state in ('RESERVED', 'ALLOCATED', 'PICKING')
                """).param("ws", ws).param("b", be).param("n", node).param("s", skus).query(Integer.class).single();
        Optional<PolicyView> pol = resolvePolicy(ws, be, node, s.zone());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("buckets", buckets.size());
        m.put("locations", locations.size());
        m.put("skus", skus.size());
        m.put("active_reservations", activeReservations);
        m.put("policy", pol.orElse(null));
        m.put("policy_missing", pol.isEmpty());
        return m;
    }

    private void insertLine(UUID ws, UUID countId, int no, UUID sku, UUID loc, UUID lot, String quality, String hold, int system, boolean found, UUID policy) {
        jdbc.sql("""
                insert into stock_count_line (id, workspace_id, count_id, line_no, sku_id, location_id, lot_id, quality_status, hold_reason, policy_id, system_qty, found_in_count)
                values (:id, :ws, :c, :no, :s, cast(:loc as uuid), cast(:lot as uuid), :q, cast(:h as text), :p, :sys, :f)
                """).param("id", Ids.newId()).param("ws", ws).param("c", countId).param("no", no).param("s", sku).param("loc", loc).param("lot", lot)
                .param("q", quality).param("h", hold).param("p", policy).param("sys", system).param("f", found).update();
    }

    private void addFoundLine(CurrentActor a, CountRow c, PolicyView base, FoundLine f) {
        if (f.skuId() == null || f.countedQty() == null || f.countedQty() < 0) {
            throw Errors.validation("found_lines", "발견 재고는 SKU 와 0 이상의 수량이 필요합니다.");
        }
        InventoryService.SkuInfo sku = inventory.skuInfo(f.skuId());
        String quality = f.qualityStatus() == null ? "SELLABLE" : f.qualityStatus();
        if (!InventoryService.QUALITIES.contains(quality)) {
            throw Errors.validation("found_lines.quality_status", "품질 상태 값이 올바르지 않습니다.");
        }
        UUID locationId = null;
        String zone = null;
        String code = InventoryService.trimOrNull(f.locationCode());
        if (code != null) {
            Map<String, Object> loc = jdbc.sql("select id, zone from node_location where workspace_id = :ws and node_id = :n and code = :c").param("ws", a.workspaceId())
                    .param("n", c.nodeId()).param("c", code).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> Errors.validation("found_lines.location_code", "거점에 없는 로케이션입니다."));
            locationId = (UUID) loc.get("id");
            zone = (String) loc.get("zone");
        }
        UUID lotId = null;
        String lot = InventoryService.trimOrNull(f.lotCode());
        if (sku.lotManaged() && lot == null) {
            throw Errors.validation("found_lines.lot_code", "LOT 관리 SKU 는 LOT 번호가 필요합니다.");
        }
        if (lot != null) {
            lotId = jdbc.sql("select id from inventory_lot where sku_id = :s and lot_code = :c").param("s", f.skuId()).param("c", lot).query(UUID.class).optional()
                    .orElseThrow(() -> Errors.validation("found_lines.lot_code", "등록되지 않은 LOT 입니다. 입고로 먼저 등록하세요."));
        }
        Map<String, Object> scope = jdbc.sql("select scope, zone, sku_ids from stock_count where id = :id").param("id", c.id()).query().listOfRows().get(0);
        String sc = (String) scope.get("scope");
        boolean inScope = switch (sc) {
            case "ZONE" -> Objects.equals(scope.get("zone"), zone);
            case "SKU" -> skuSet(scope.get("sku_ids")).contains(f.skuId());
            default -> true;
        };
        if (!inScope) {
            throw Errors.validation("found_lines", "실사 범위 밖의 재고입니다.");
        }
        int exists = jdbc.sql("""
                select count(*) from stock_count_line where count_id = :c and sku_id = :s and location_id is not distinct from cast(:loc as uuid)
                   and lot_id is not distinct from cast(:lot as uuid) and quality_status = :q and hold_reason is null
                """).param("c", c.id()).param("s", f.skuId()).param("loc", locationId).param("lot", lotId).param("q", quality).query(Integer.class).single();
        if (exists > 0) {
            throw Errors.conflict("COUNT_LINE_EXISTS", "이미 실사 목록에 있는 재고입니다. 해당 행에 수량을 입력하세요.");
        }
        PolicyView pol = zone == null ? base : resolvePolicy(a.workspaceId(), c.businessEntityId(), c.nodeId(), zone).orElse(base);
        int current = ledger.findBucket(a.workspaceId(), new Bucket(c.businessEntityId(), f.skuId(), c.nodeId(), locationId, lotId, quality, null))
                .map(InventoryLedger.Stock::onHand).orElse(0);
        int no = jdbc.sql("select coalesce(max(line_no), 0) + 1 from stock_count_line where count_id = :c").param("c", c.id()).query(Integer.class).single();
        insertLine(a.workspaceId(), c.id(), no, f.skuId(), locationId, lotId, quality, null, current, true, pol.id());
        UUID lineId = jdbc.sql("select id from stock_count_line where count_id = :c and line_no = :no").param("c", c.id()).param("no", no).query(UUID.class).single();
        jdbc.sql("update stock_count_line set counted_qty = :q, counted_by = :by, counted_at = now(), counted_round = :r, first_counted_by = :by where id = :id")
                .param("q", f.countedQty()).param("by", a.memberId()).param("r", c.round()).param("id", lineId).update();
        jdbc.sql("insert into stock_count_entry (id, workspace_id, line_id, round, system_qty, counted_qty, counted_by) values (:id, :ws, :l, :r, :s, :q, :by)")
                .param("id", Ids.newId()).param("ws", a.workspaceId()).param("l", lineId).param("r", c.round()).param("s", current).param("q", f.countedQty())
                .param("by", a.memberId()).update();
    }

    private void applyApproved(CurrentActor a, CountRow c, List<Map<String, Object>> lines, String reason) {
        // 잠금 순서: 집계(id 순) → 버킷
        Set<UUID> skus = new LinkedHashSet<>();
        for (Map<String, Object> l : lines) {
            skus.add((UUID) l.get("sku_id"));
        }
        ledger.ensurePositions(a.workspaceId(), c.businessEntityId(), skus, c.nodeId());
        Map<UUID, Position> positions = new LinkedHashMap<>();
        for (Position p : ledger.lockPositions(a.workspaceId(), c.businessEntityId(), skus)) {
            if (p.nodeId().equals(c.nodeId())) {
                positions.put(p.skuId(), p);
            }
        }
        List<Map<String, Object>> stale = new ArrayList<>();
        lines.sort(Comparator.comparing(l -> ((Number) l.get("line_no")).intValue()));
        for (Map<String, Object> l : lines) {
            int current = ledger.findBucket(a.workspaceId(), bucketOf(c, l)).map(InventoryLedger.Stock::onHand).orElse(0);
            if (current != ((Number) l.get("system_qty")).intValue()) {
                stale.add(Map.of("line_id", l.get("id").toString(), "system_qty", l.get("system_qty"), "current_qty", current));
            }
        }
        if (!stale.isEmpty()) {
            throw Errors.conflict("STOCK_COUNT_STALE", "실사 기준 이후 재고가 바뀐 행이 있어 반영하지 않았습니다. 해당 행을 재실사하세요.", Map.of("lines", stale));
        }
        String why = "실사 차이 승인 " + c.countNo() + (reason == null ? "" : " · " + reason);
        for (Map<String, Object> l : lines) {
            int diff = ((Number) l.get("diff")).intValue();
            UUID sku = (UUID) l.get("sku_id");
            Position p = positions.get(sku);
            Position after = ledger.changeBucket(a.workspaceId(), p, bucketOf(c, l), diff, "ADJUSTMENT", "STOCK_COUNT", c.countNo(), why, a.memberId());
            positions.put(sku, after);
            if ("SELLABLE".equals(l.get("quality_status"))) {
                inventory.availabilityChanged(a.workspaceId(), after);
            }
        }
    }

    private void completeIfDone(CurrentActor a, UUID countId) {
        int open = jdbc.sql("select count(*) from stock_count_line where count_id = :c and status in (:st)").param("c", countId).param("st", OPEN_LINE)
                .query(Integer.class).single();
        if (open == 0) {
            jdbc.sql("update stock_count set status = 'COMPLETED', completed_at = now(), updated_at = now(), version = version + 1 where id = :id")
                    .param("id", countId).update();
            audit.record(AuditEntry.builder(a.workspaceId(), "STOCK_COUNT_COMPLETED", "STOCK_COUNT", countId).actor(ActorType.USER, a.memberId(), a.displayName()).build());
        }
    }

    static boolean exceeds(PolicyView pol, int absDiff, int system, BigDecimal ratePct, BigDecimal cost) {
        List<Boolean> conds = new ArrayList<>();
        if (pol.qtyThreshold() != null) {
            conds.add(absDiff > pol.qtyThreshold());
        }
        if (pol.rateThresholdPct() != null) {
            conds.add(system == 0 || ratePct.compareTo(pol.rateThresholdPct()) > 0); // 시스템 0 에서 생긴 차이는 무한대 차이율
        }
        if (pol.costThreshold() != null) {
            conds.add(cost == null || cost.abs().compareTo(pol.costThreshold()) >= 0); // 원가 미등록은 판단 불가 → 초과로 본다(보수적)
        }
        return "ALL".equals(pol.combine()) ? conds.stream().allMatch(b -> b) : conds.stream().anyMatch(b -> b);
    }

    private Bucket bucketOf(CountRow c, Map<String, Object> l) {
        return new Bucket(c.businessEntityId(), (UUID) l.get("sku_id"), c.nodeId(), (UUID) l.get("location_id"), (UUID) l.get("lot_id"),
                (String) l.get("quality_status"), (String) l.get("hold_reason"));
    }

    private boolean isCounter(UUID countId, UUID memberId) {
        return jdbc.sql("select count(*) from stock_count_counter where count_id = :c and member_id = :m").param("c", countId).param("m", memberId)
                .query(Integer.class).single() > 0;
    }

    private CountRow lockCount(UUID ws, UUID id) {
        CountRow c = jdbc.sql("select * from stock_count where workspace_id = :ws and id = :id for update").param("ws", ws).param("id", id)
                .query((rs, i) -> new CountRow(Db.uuid(rs, "id"), rs.getString("count_no"), Db.uuid(rs, "business_entity_id"), Db.uuid(rs, "node_id"),
                        rs.getString("status"), rs.getInt("round"), rs.getBoolean("blind"), Db.uuid(rs, "policy_id"), Db.uuid(rs, "requested_by")))
                .optional().orElseThrow(() -> Errors.notFound("STOCK_COUNT"));
        guard.checkWrite("STOCK_COUNT", id, c.businessEntityId(), null);
        guard.checkNode(AccessKind.WRITE, "STOCK_COUNT", id, c.nodeId());
        return c;
    }

    private PolicyView policyById(UUID id) {
        return jdbc.sql(POLICY_SELECT + " where p.id = :id").param("id", id).query((rs, i) -> policyView(rs)).single();
    }

    private static Set<UUID> skuSet(Object arr) {
        if (arr == null) {
            return Set.of();
        }
        try {
            Object[] values = arr instanceof Array sqlArr ? (Object[]) sqlArr.getArray() : (Object[]) arr;
            return Arrays.stream(values).map(o -> (UUID) o).collect(Collectors.toSet());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> policyMap(PolicyPut p, String zone, int version, boolean removed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node_id", String.valueOf(p.nodeId()));
        m.put("zone", String.valueOf(zone));
        m.put("version", version);
        m.put("removed", removed);
        m.put("qty_threshold", String.valueOf(p.qtyThreshold()));
        m.put("rate_threshold_pct", String.valueOf(p.rateThresholdPct()));
        m.put("cost_threshold", String.valueOf(p.costThreshold()));
        m.put("combine", String.valueOf(p.combine()));
        m.put("on_exceed", String.valueOf(p.onExceed()));
        m.put("recount_assignee", String.valueOf(p.recountAssignee()));
        return m;
    }

    private CountView withCounters(CountView v) {
        List<Map<String, Object>> counters = jdbc.sql("""
                select c.member_id, u.display_name from stock_count_counter c join workspace_member m on m.workspace_id = c.workspace_id and m.id = c.member_id
                  join user_account u on u.id = m.user_id where c.count_id = :id order by u.display_name
                """).param("id", v.id()).query((rs, i) -> Map.<String, Object>of("member_id", Db.uuid(rs, "member_id").toString(), "name", rs.getString("display_name")))
                .list();
        return new CountView(v.id(), v.countNo(), v.businessEntityId(), v.businessEntityName(), v.nodeId(), v.nodeName(), v.scope(), v.zone(), v.skuIds(),
                v.blind(), v.policyId(), v.policyVersion(), v.status(), v.round(), v.snapshotAt(), v.note(), v.requestedBy(), v.requestedByName(), counters,
                v.lineCount(), v.countedLines(), v.diffLines(), v.pendingLines(), v.recountRequiredLines(), v.submittedAt(), v.completedAt(), v.version());
    }

    private static final String POLICY_SELECT = """
            select p.*, n.name as node_name, u.display_name as created_by_name from stock_count_policy p left join fulfillment_node n on n.id = p.node_id
              left join workspace_member m on m.workspace_id = p.workspace_id and m.id = p.created_by left join user_account u on u.id = m.user_id
            """;

    private static PolicyView policyView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PolicyView(Db.uuid(rs, "id"), Db.uuid(rs, "business_entity_id"), Db.uuid(rs, "node_id"), rs.getString("node_name"), rs.getString("zone"),
                rs.getInt("version"), rs.getBoolean("removed"), (Integer) rs.getObject("qty_threshold"), rs.getBigDecimal("rate_threshold_pct"),
                rs.getBigDecimal("cost_threshold"), rs.getString("combine"), rs.getString("on_exceed"), rs.getString("recount_assignee"),
                rs.getString("created_by_name"), Db.instant(rs, "created_at"));
    }

    private static final String COUNT_SELECT = """
            select c.*, be.display_name as be_name, n.name as node_name, p.version as policy_version, u.display_name as requested_by_name,
                   (select count(*) from stock_count_line l where l.count_id = c.id) as line_count,
                   (select count(*) from stock_count_line l where l.count_id = c.id and l.counted_round = c.round) as counted_lines,
                   (select count(*) from stock_count_line l where l.count_id = c.id and coalesce(l.diff, 0) <> 0) as diff_lines,
                   (select count(*) from stock_count_line l where l.count_id = c.id and l.status = 'PENDING_APPROVAL') as pending_lines,
                   (select count(*) from stock_count_line l where l.count_id = c.id and l.status = 'RECOUNT_REQUIRED') as recount_required_lines
              from stock_count c join business_entity be on be.id = c.business_entity_id join fulfillment_node n on n.id = c.node_id
              join stock_count_policy p on p.id = c.policy_id
              left join workspace_member m on m.workspace_id = c.workspace_id and m.id = c.requested_by left join user_account u on u.id = m.user_id
            """;

    private static CountView countView(java.sql.ResultSet rs) throws java.sql.SQLException {
        Array arr = rs.getArray("sku_ids");
        List<UUID> skus = arr == null ? null : Arrays.stream((Object[]) arr.getArray()).map(o -> (UUID) o).toList();
        return new CountView(Db.uuid(rs, "id"), rs.getString("count_no"), Db.uuid(rs, "business_entity_id"), rs.getString("be_name"), Db.uuid(rs, "node_id"),
                rs.getString("node_name"), rs.getString("scope"), rs.getString("zone"), skus, rs.getBoolean("blind"), Db.uuid(rs, "policy_id"),
                rs.getInt("policy_version"), rs.getString("status"), rs.getInt("round"), Db.instant(rs, "snapshot_at"), rs.getString("note"),
                Db.uuid(rs, "requested_by"), rs.getString("requested_by_name"), List.of(), rs.getInt("line_count"), rs.getInt("counted_lines"),
                rs.getInt("diff_lines"), rs.getInt("pending_lines"), rs.getInt("recount_required_lines"), Db.instant(rs, "submitted_at"),
                Db.instant(rs, "completed_at"), rs.getInt("version"));
    }

    private UUID ws() {
        return guard.actor().workspaceId();
    }
}
