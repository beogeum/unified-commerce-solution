package com.commerceos.inventory;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.db.TenantContext;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 재고 검증센터(M3 I6). 원장·버킷·집계·확보가 서로 맞는지와 운영상 방치된 상태를 정기·수동으로 검사한다.
 * 검사는 아무것도 고치지 않는다 — 이상을 이슈로 남기고(같은 이상은 한 건으로 이어서), 다시 검사해 사라지면 자동 해결한다.
 * 이슈는 사업자·출고거점 범위로 보인다. HIGH 이슈가 있는 사업자에는 업무함에 한 건을 올린다.
 */
@Service
public class VerificationService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final InventoryService inventory;
    private final WorkItemService workItems;
    private final AuditService audit;
    private final Json json;
    private final TransactionTemplate tx;
    private final int transferOverdueDays;
    private final int countReviewOverdueDays;
    private final int countLockHours;

    public VerificationService(JdbcClient jdbc, AccessGuard guard, InventoryService inventory, WorkItemService workItems, AuditService audit, Json json,
                               PlatformTransactionManager tm,
                               @Value("${commerce.inventory.check.transfer-overdue-days:7}") int transferOverdueDays,
                               @Value("${commerce.inventory.check.count-review-overdue-days:3}") int countReviewOverdueDays,
                               @Value("${commerce.inventory.check.count-lock-hours:24}") int countLockHours) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.inventory = inventory;
        this.workItems = workItems;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
        this.transferOverdueDays = transferOverdueDays;
        this.countReviewOverdueDays = countReviewOverdueDays;
        this.countLockHours = countLockHours;
    }

    public record RunView(UUID id, String triggerType, String requestedByName, Instant startedAt, Instant finishedAt, int checkedPositions, int foundIssues,
                          int newIssues, int resolvedIssues) {
    }

    public record IssueView(UUID id, String checkCode, String checkNameKo, String severity, UUID businessEntityId, String businessEntityName, UUID nodeId,
                            String nodeName, UUID skuId, String skuCode, UUID positionId, String refType, String refId, Long expected, Long actual,
                            BigDecimal costImpact, String messageKo, JsonNode evidence, String status, Instant firstDetectedAt, Instant lastDetectedAt,
                            int detectionCount, String acknowledgedByName, String acknowledgeReason, Instant resolvedAt, int version) {
    }

    public record Summary(int openHigh, int openMedium, int openLow, int acknowledged, RunView lastRun) {
    }

    record Finding(String key, String check, String severity, UUID be, UUID node, UUID sku, UUID position, String refType, String refId, Long expected,
                   Long actual, String messageKo, Map<String, Object> evidence) {
    }

    static final Map<String, String> CHECK_KO = Map.ofEntries(
            Map.entry("POSITION_VS_BUCKETS", "판매가능 집계 ≠ 판매가능 버킷 합"),
            Map.entry("COMMITTED_VS_RESERVATIONS", "확보수량 ≠ 활성 확보 합"),
            Map.entry("BUCKETS_VS_LEDGER", "실물 버킷 합 ≠ 원장 누적"),
            Map.entry("COMMITTED_VS_LEDGER", "확보수량 ≠ 원장 확보 누적"),
            Map.entry("BUCKET_VS_LEDGER", "버킷 수량 ≠ 버킷 원장 누적"),
            Map.entry("RESERVATION_LEDGER", "확보 단계 ≠ 확보 원장"),
            Map.entry("RESERVATION_EXPIRED_UNRELEASED", "만료된 확보가 풀리지 않음"),
            Map.entry("EXTERNAL_RESTRICTED", "외부 재고 판매 제한 중"),
            Map.entry("TRANSFER_OVERDUE", "이동 중 장기 체류"),
            Map.entry("COUNT_LOCK_PROLONGED", "실사 잠금 장기화"),
            Map.entry("COUNT_REVIEW_OVERDUE", "실사 차이 검토 지연"));

    // ---------------------------------------------------------------- 실행

    @Transactional
    public RunView runManual() {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        UUID runId = run(a.workspaceId(), "MANUAL", a.memberId());
        audit.record(AuditEntry.builder(a.workspaceId(), "INVENTORY_CHECK_RUN", "INVENTORY_CHECK_RUN", runId).actor(ActorType.USER, a.memberId(), a.displayName()).build());
        return runView(a.workspaceId(), runId);
    }

    /** 재검증: 전체 검사를 다시 돌려 이 이슈가 아직 있는지 본다(검사가 서로 얽혀 있어 대상만 따로 보지 않는다). */
    @Transactional
    public IssueView recheck(UUID issueId) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        IssueView before = get(issueId);
        CurrentActor a = guard.actor();
        run(a.workspaceId(), "RECHECK", a.memberId());
        return find(a.workspaceId(), before.id());
    }

    @Scheduled(cron = "${commerce.inventory.check.cron:0 17 3 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        scanAll();
    }

    /** 모든 운영그룹 정기 검사(시스템). @return 검사한 운영그룹 수 */
    public int scanAll() {
        return TenantContext.asSystem(() -> {
            List<UUID> workspaces = jdbc.sql("select distinct workspace_id from inventory_position").query(UUID.class).list();
            for (UUID ws : workspaces) {
                tx.executeWithoutResult(s -> run(ws, "SCHEDULED", null));
            }
            return workspaces.size();
        });
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    UUID run(UUID ws, String trigger, UUID requestedBy) {
        // 같은 운영그룹 검사는 한 번에 하나(이슈 갱신 경합 방지)
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:k))").param("k", "inventory_check:" + ws).query().listOfRows();
        UUID runId = Ids.newId();
        jdbc.sql("insert into inventory_check_run (id, workspace_id, trigger_type, requested_by) values (:id, :ws, :t, cast(:by as uuid))")
                .param("id", runId).param("ws", ws).param("t", trigger).param("by", requestedBy).update();
        int positions = jdbc.sql("select count(*) from inventory_position where workspace_id = :ws").param("ws", ws).query(Integer.class).single();
        List<Finding> findings = detect(ws);
        Set<String> keys = new HashSet<>();
        int created = 0;
        for (Finding f : findings) {
            if (!keys.add(f.key())) {
                continue;
            }
            BigDecimal cost = null;
            if (f.sku() != null && f.expected() != null && f.actual() != null && !f.check().startsWith("COMMITTED") && !"RESERVATION_LEDGER".equals(f.check())) {
                BigDecimal unit = inventory.unitCost(ws, f.be(), f.sku()).orElse(null);
                cost = unit == null ? null : unit.multiply(BigDecimal.valueOf(f.actual() - f.expected()));
            }
            int updated = jdbc.sql("""
                    update inventory_check_issue set last_detected_at = now(), detection_count = detection_count + 1, last_run_id = :run, expected = cast(:e as bigint),
                           actual = cast(:a as bigint), cost_impact = cast(:c as numeric), message_ko = :m, evidence = cast(:ev as jsonb), version = version + 1
                     where workspace_id = :ws and issue_key = :k and status in ('OPEN', 'ACKNOWLEDGED')
                    """).param("run", runId).param("e", f.expected()).param("a", f.actual()).param("c", cost).param("m", f.messageKo())
                    .param("ev", json.write(f.evidence())).param("ws", ws).param("k", f.key()).update();
            if (updated == 0) {
                jdbc.sql("""
                        insert into inventory_check_issue (id, workspace_id, issue_key, check_code, severity, business_entity_id, node_id, sku_id, position_id, ref_type,
                            ref_id, expected, actual, cost_impact, message_ko, evidence, last_run_id)
                        values (:id, :ws, :k, :check, :sev, :be, cast(:n as uuid), cast(:sku as uuid), cast(:p as uuid), cast(:rt as text), cast(:rid as text),
                                cast(:e as bigint), cast(:a as bigint), cast(:c as numeric), :m, cast(:ev as jsonb), :run)
                        """).param("id", Ids.newId()).param("ws", ws).param("k", f.key()).param("check", f.check()).param("sev", f.severity()).param("be", f.be())
                        .param("n", f.node()).param("sku", f.sku()).param("p", f.position()).param("rt", f.refType()).param("rid", f.refId())
                        .param("e", f.expected()).param("a", f.actual()).param("c", cost).param("m", f.messageKo()).param("ev", json.write(f.evidence()))
                        .param("run", runId).update();
                created++;
            }
        }
        // 이번 검사에서 나오지 않은 열린 이슈는 해결
        List<UUID> open = jdbc.sql("select id from inventory_check_issue where workspace_id = :ws and status in ('OPEN', 'ACKNOWLEDGED') and last_run_id <> :run")
                .param("ws", ws).param("run", runId).query(UUID.class).list();
        if (!open.isEmpty()) {
            jdbc.sql("""
                    update inventory_check_issue set status = 'RESOLVED', resolved_at = now(), resolved_run_id = :run, version = version + 1 where id in (:ids)
                    """).param("run", runId).param("ids", open).update();
        }
        // HIGH 이슈 업무함: 사업자당 한 건
        List<UUID> bes = jdbc.sql("select id from business_entity where workspace_id = :ws").param("ws", ws).query(UUID.class).list();
        for (UUID be : bes) {
            int high = jdbc.sql("select count(*) from inventory_check_issue where workspace_id = :ws and business_entity_id = :b and severity = 'HIGH' and status = 'OPEN'")
                    .param("ws", ws).param("b", be).query(Integer.class).single();
            String key = "inventory_check:" + be + ":HIGH";
            if (high > 0) {
                workItems.raise(new WorkItemService.Raise(ws, be, null, null, "INVENTORY_CHECK_FAILED", "HIGH", "재고 검증 이상 " + high + "건",
                        "재고 원장·버킷·집계·확보가 서로 맞지 않는 항목이 있습니다. 해당 재고는 출고 확정·조정 전에 확인이 필요합니다.",
                        "재고 > 검증센터에서 근거(원장 기록)를 확인하고, 실사 또는 승인된 조정으로 바로잡은 뒤 재검증하세요.", key,
                        Map.of("run_id", runId.toString(), "high_issues", high)));
            } else {
                workItems.resolveByKey(ws, key);
            }
        }
        jdbc.sql("""
                update inventory_check_run set finished_at = now(), checked_positions = :p, found_issues = :f, new_issues = :n, resolved_issues = :r where id = :id
                """).param("p", positions).param("f", keys.size()).param("n", created).param("r", open.size()).param("id", runId).update();
        return runId;
    }

    private List<Finding> detect(UUID ws) {
        List<Finding> out = new ArrayList<>();
        // 1~4 집계 단위 대사
        for (Map<String, Object> r : jdbc.sql("""
                select p.id, p.owner_business_entity_id as be, p.sku_id, p.node_id, p.sellable_on_hand, p.committed,
                       coalesce((select sum(on_hand) from inventory_stock s where s.owner_business_entity_id = p.owner_business_entity_id and s.sku_id = p.sku_id
                                 and s.node_id = p.node_id and s.quality_status = 'SELLABLE'), 0) as sellable_buckets,
                       coalesce((select sum(on_hand) from inventory_stock s where s.owner_business_entity_id = p.owner_business_entity_id and s.sku_id = p.sku_id
                                 and s.node_id = p.node_id), 0) as all_buckets,
                       coalesce((select sum(quantity) from inventory_reservation x where x.position_id = p.id and x.state in ('RESERVED','ALLOCATED','PICKING')), 0) as active,
                       coalesce((select sum(on_hand_delta) from inventory_transaction t where t.position_id = p.id), 0) as ledger_on_hand,
                       coalesce((select sum(committed_delta) from inventory_transaction t where t.position_id = p.id), 0) as ledger_committed
                  from inventory_position p where p.workspace_id = :ws
                """).param("ws", ws).query().listOfRows()) {
            UUID pid = (UUID) r.get("id");
            UUID be = (UUID) r.get("be");
            UUID sku = (UUID) r.get("sku_id");
            UUID node = (UUID) r.get("node_id");
            positionCheck(out, ws, "POSITION_VS_BUCKETS", pid, be, sku, node, n(r, "sellable_on_hand"), n(r, "sellable_buckets"),
                    "판매가능 집계(" + n(r, "sellable_on_hand") + ")와 판매가능 버킷 합(" + n(r, "sellable_buckets") + ")이 다릅니다.");
            positionCheck(out, ws, "COMMITTED_VS_RESERVATIONS", pid, be, sku, node, n(r, "committed"), n(r, "active"),
                    "확보수량(" + n(r, "committed") + ")과 활성 확보 합(" + n(r, "active") + ")이 다릅니다.");
            positionCheck(out, ws, "BUCKETS_VS_LEDGER", pid, be, sku, node, n(r, "ledger_on_hand"), n(r, "all_buckets"),
                    "원장 누적 실물(" + n(r, "ledger_on_hand") + ")과 버킷 합(" + n(r, "all_buckets") + ")이 다릅니다.");
            positionCheck(out, ws, "COMMITTED_VS_LEDGER", pid, be, sku, node, n(r, "ledger_committed"), n(r, "committed"),
                    "원장 누적 확보(" + n(r, "ledger_committed") + ")와 확보수량(" + n(r, "committed") + ")이 다릅니다.");
        }
        // 5 버킷 단위 원장 대사
        for (Map<String, Object> r : jdbc.sql("""
                select s.id, s.owner_business_entity_id as be, s.sku_id, s.node_id, s.on_hand, s.quality_status,
                       coalesce((select sum(on_hand_delta) from inventory_transaction t where t.stock_id = s.id), 0) as ledger
                  from inventory_stock s where s.workspace_id = :ws
                """).param("ws", ws).query().listOfRows()) {
            long expected = n(r, "ledger");
            long actual = n(r, "on_hand");
            if (expected != actual) {
                UUID sid = (UUID) r.get("id");
                out.add(new Finding("BUCKET_VS_LEDGER:" + sid, "BUCKET_VS_LEDGER", "HIGH", (UUID) r.get("be"), (UUID) r.get("node_id"), (UUID) r.get("sku_id"),
                        null, "INVENTORY_STOCK", sid.toString(), expected, actual,
                        "버킷(" + r.get("quality_status") + ") 수량 " + actual + " 이 이 버킷 원장 누적 " + expected + " 과 다릅니다.",
                        Map.of("stock_id", sid.toString(), "recent_ledger", recentLedger("stock_id", sid))));
            }
        }
        // 6 확보별 원장 대사(활성 = 수량, 종료 = 0)
        for (Map<String, Object> r : jdbc.sql("""
                select x.id, x.owner_business_entity_id as be, x.sku_id, x.node_id, x.position_id, x.state, x.quantity, x.demand_type, x.demand_ref,
                       coalesce((select sum(committed_delta) from inventory_transaction t where t.reservation_id = x.id), 0) as ledger
                  from inventory_reservation x where x.workspace_id = :ws
                """).param("ws", ws).query().listOfRows()) {
            boolean active = Set.of("RESERVED", "ALLOCATED", "PICKING").contains((String) r.get("state"));
            long expected = active ? n(r, "quantity") : 0;
            long actual = n(r, "ledger");
            if (expected != actual) {
                UUID rid = (UUID) r.get("id");
                out.add(new Finding("RESERVATION_LEDGER:" + rid, "RESERVATION_LEDGER", "HIGH", (UUID) r.get("be"), (UUID) r.get("node_id"), (UUID) r.get("sku_id"),
                        (UUID) r.get("position_id"), "INVENTORY_RESERVATION", rid.toString(), expected, actual,
                        "확보(" + r.get("demand_type") + " " + r.get("demand_ref") + ", " + r.get("state") + ")의 원장 확보 누적이 " + actual + " 입니다(정상 " + expected + ").",
                        Map.of("reservation_id", rid.toString(), "recent_ledger", recentLedger("reservation_id", rid))));
            }
        }
        // 7 만료됐는데 풀리지 않은 확보(자동 해제 작업 지연·실패)
        jdbc.sql("""
                select id, owner_business_entity_id as be, sku_id, node_id, position_id, quantity, demand_type, demand_ref, expires_at from inventory_reservation
                 where workspace_id = :ws and state = 'RESERVED' and expires_at < now() - interval '10 minutes'
                """).param("ws", ws).query().listOfRows().forEach(r -> out.add(new Finding("RESERVATION_EXPIRED_UNRELEASED:" + r.get("id"),
                "RESERVATION_EXPIRED_UNRELEASED", "MEDIUM", (UUID) r.get("be"), (UUID) r.get("node_id"), (UUID) r.get("sku_id"), (UUID) r.get("position_id"),
                "INVENTORY_RESERVATION", r.get("id").toString(), null, n(r, "quantity"),
                "만료 시각이 지난 확보 " + n(r, "quantity") + "개(" + r.get("demand_type") + " " + r.get("demand_ref") + ")가 아직 풀리지 않았습니다.",
                Map.of("reservation_id", r.get("id").toString(), "expires_at", String.valueOf(r.get("expires_at"))))));
        // 8 외부 재고 판매 제한
        jdbc.sql("""
                select p.id, p.owner_business_entity_id as be, p.sku_id, p.node_id, p.restriction_reason, p.restricted_hold, p.external_as_of, p.external_snapshot_id
                  from inventory_position p where p.workspace_id = :ws and p.restricted
                """).param("ws", ws).query().listOfRows().forEach(r -> out.add(new Finding("EXTERNAL_RESTRICTED:" + r.get("id"), "EXTERNAL_RESTRICTED",
                "EXTERNAL_SHORTAGE".equals(r.get("restriction_reason")) ? "HIGH" : "MEDIUM", (UUID) r.get("be"), (UUID) r.get("node_id"), (UUID) r.get("sku_id"),
                (UUID) r.get("id"), "EXTERNAL_INVENTORY_SNAPSHOT", r.get("external_snapshot_id") == null ? null : r.get("external_snapshot_id").toString(), null,
                n(r, "restricted_hold"), "외부 재고 판매 제한 중: " + ExternalSnapshotService.restrictionCauseKo((String) r.get("restriction_reason")),
                Map.of("reason", r.get("restriction_reason"), "external_as_of", String.valueOf(r.get("external_as_of"))))));
        // 9 이동 중 장기 체류
        jdbc.sql("""
                select t.id, t.transfer_no, t.business_entity_id as be, t.to_node_id, t.shipped_at, (select sum(quantity) from inventory_transfer_line l where l.transfer_id = t.id) as qty
                  from inventory_transfer t where t.workspace_id = :ws and t.status = 'IN_TRANSIT' and t.shipped_at < now() - make_interval(days => :d)
                """).param("ws", ws).param("d", transferOverdueDays).query().listOfRows().forEach(r -> out.add(new Finding("TRANSFER_OVERDUE:" + r.get("id"),
                "TRANSFER_OVERDUE", "MEDIUM", (UUID) r.get("be"), (UUID) r.get("to_node_id"), null, null, "INVENTORY_TRANSFER", (String) r.get("transfer_no"), null,
                n(r, "qty"), "재고 이동 " + r.get("transfer_no") + " 이(가) " + transferOverdueDays + "일 넘게 이동 중입니다(" + n(r, "qty") + "개).",
                Map.of("transfer_id", r.get("id").toString(), "shipped_at", String.valueOf(r.get("shipped_at"))))));
        // 10~11 실사 방치(잠금 장기화는 출고를 막는다)
        jdbc.sql("""
                select id, count_no, business_entity_id as be, node_id, status, snapshot_at, submitted_at from stock_count where workspace_id = :ws
                   and ((status = 'COUNTING' and updated_at < now() - make_interval(hours => :h)) or (status = 'REVIEW' and submitted_at < now() - make_interval(days => :d)))
                """).param("ws", ws).param("h", countLockHours).param("d", countReviewOverdueDays).query().listOfRows().forEach(r -> {
            boolean counting = "COUNTING".equals(r.get("status"));
            String check = counting ? "COUNT_LOCK_PROLONGED" : "COUNT_REVIEW_OVERDUE";
            out.add(new Finding(check + ":" + r.get("id"), check, counting ? "MEDIUM" : "LOW", (UUID) r.get("be"), (UUID) r.get("node_id"), null, null, "STOCK_COUNT",
                    (String) r.get("count_no"), null, null, counting ? "실사 " + r.get("count_no") + " 이(가) " + countLockHours + "시간 넘게 카운트 중이라 범위 재고의 입출고가 잠겨 있습니다."
                    : "실사 " + r.get("count_no") + " 의 차이 검토가 " + countReviewOverdueDays + "일 넘게 끝나지 않았습니다.", Map.of("count_id", r.get("id").toString())));
        });
        return out;
    }

    private void positionCheck(List<Finding> out, UUID ws, String check, UUID pid, UUID be, UUID sku, UUID node, long expected, long actual, String msg) {
        if (expected != actual) {
            out.add(new Finding(check + ":" + pid, check, "HIGH", be, node, sku, pid, "INVENTORY_POSITION", pid.toString(), expected, actual, msg,
                    Map.of("position_id", pid.toString(), "recent_ledger", recentLedger("position_id", pid))));
        }
    }

    private List<Map<String, Object>> recentLedger(String column, UUID id) {
        return jdbc.sql("select id, txn_type, on_hand_delta, committed_delta, occurred_at, reference_type, reference_id from inventory_transaction where " + column
                + " = :id order by id desc limit 5").param("id", id).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", Db.uuid(rs, "id").toString());
                    m.put("txn_type", rs.getString("txn_type"));
                    m.put("on_hand_delta", rs.getInt("on_hand_delta"));
                    m.put("committed_delta", rs.getInt("committed_delta"));
                    m.put("occurred_at", String.valueOf(Db.instant(rs, "occurred_at")));
                    m.put("reference", rs.getString("reference_type") == null ? null : rs.getString("reference_type") + " " + rs.getString("reference_id"));
                    return m;
                }).list();
    }

    private static long n(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v == null ? 0 : ((Number) v).longValue();
    }

    // ---------------------------------------------------------------- 이슈

    @Transactional
    public IssueView acknowledge(UUID id, String reason) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        IssueView v = get(id);
        guard.checkWrite("INVENTORY_CHECK_ISSUE", id, v.businessEntityId(), null);
        guard.checkNode(AccessKind.WRITE, "INVENTORY_CHECK_ISSUE", id, v.nodeId());
        String r = InventoryService.trimOrNull(reason);
        InventoryService.requireReason(r, true);
        if (!"OPEN".equals(v.status())) {
            throw Errors.conflict("CHECK_ISSUE_NOT_OPEN", "열린 이슈만 확인 처리할 수 있습니다.", Map.of("status", v.status()));
        }
        jdbc.sql("""
                update inventory_check_issue set status = 'ACKNOWLEDGED', acknowledged_by = :by, acknowledged_at = now(), acknowledge_reason = :r, version = version + 1
                 where id = :id
                """).param("by", a.memberId()).param("r", r).param("id", id).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "INVENTORY_CHECK_ISSUE_ACKNOWLEDGED", "INVENTORY_CHECK_ISSUE", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(v.businessEntityId(), null).reason(r).build());
        return find(a.workspaceId(), id);
    }

    public List<IssueView> issues(List<String> businessEntityIds, UUID nodeId, String status, String checkCode, String severity) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        Set<UUID> nodes = inventory.visibleNodes(nodeId);
        StringBuilder sql = new StringBuilder(ISSUE_SELECT + " where i.workspace_id = :ws and i.business_entity_id in (:bes) and (i.node_id in (:nodes)"
                + (nodeId == null ? " or i.node_id is null)" : ")"));
        Map<String, Object> p = new HashMap<>(Map.of("ws", guard.actor().workspaceId(), "bes", bes, "nodes", nodes));
        if ("ACTIVE".equals(status) || status == null || status.isBlank()) {
            sql.append(" and i.status in ('OPEN', 'ACKNOWLEDGED')");
        } else if (!"ALL".equals(status)) {
            sql.append(" and i.status = :st");
            p.put("st", status);
        }
        if (checkCode != null && !checkCode.isBlank()) {
            sql.append(" and i.check_code = :cc");
            p.put("cc", checkCode);
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" and i.severity = :sev");
            p.put("sev", severity);
        }
        sql.append(" order by case i.severity when 'HIGH' then 0 when 'MEDIUM' then 1 else 2 end, i.last_detected_at desc limit 500");
        return jdbc.sql(sql.toString()).params(p).query((rs, i) -> issueView(rs)).list();
    }

    public IssueView get(UUID id) {
        guard.requirePermission(Perm.INVENTORY_READ);
        IssueView v = find(guard.actor().workspaceId(), id);
        guard.checkRead("INVENTORY_CHECK_ISSUE", id, v.businessEntityId(), null);
        guard.checkNode(AccessKind.READ, "INVENTORY_CHECK_ISSUE", id, v.nodeId());
        return v;
    }

    public List<RunView> runs() {
        guard.requirePermission(Perm.INVENTORY_READ);
        return jdbc.sql(RUN_SELECT + " where r.workspace_id = :ws order by r.started_at desc limit 20").param("ws", guard.actor().workspaceId())
                .query((rs, i) -> runView(rs)).list();
    }

    public Summary summary() {
        List<IssueView> active = issues(null, null, "ACTIVE", null, null);
        List<RunView> runs = runs();
        return new Summary((int) active.stream().filter(i -> "OPEN".equals(i.status()) && "HIGH".equals(i.severity())).count(),
                (int) active.stream().filter(i -> "OPEN".equals(i.status()) && "MEDIUM".equals(i.severity())).count(),
                (int) active.stream().filter(i -> "OPEN".equals(i.status()) && "LOW".equals(i.severity())).count(),
                (int) active.stream().filter(i -> "ACKNOWLEDGED".equals(i.status())).count(), runs.isEmpty() ? null : runs.get(0));
    }

    private IssueView find(UUID ws, UUID id) {
        return jdbc.sql(ISSUE_SELECT + " where i.workspace_id = :ws and i.id = :id").param("ws", ws).param("id", id).query((rs, i) -> issueView(rs)).optional()
                .orElseThrow(() -> Errors.notFound("INVENTORY_CHECK_ISSUE"));
    }

    private RunView runView(UUID ws, UUID id) {
        return jdbc.sql(RUN_SELECT + " where r.workspace_id = :ws and r.id = :id").param("ws", ws).param("id", id).query((rs, i) -> runView(rs)).single();
    }

    private static final String RUN_SELECT = """
            select r.*, u.display_name as by_name from inventory_check_run r
              left join workspace_member m on m.workspace_id = r.workspace_id and m.id = r.requested_by left join user_account u on u.id = m.user_id
            """;

    private static RunView runView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RunView(Db.uuid(rs, "id"), rs.getString("trigger_type"), rs.getString("by_name"), Db.instant(rs, "started_at"), Db.instant(rs, "finished_at"),
                rs.getInt("checked_positions"), rs.getInt("found_issues"), rs.getInt("new_issues"), rs.getInt("resolved_issues"));
    }

    private static final String ISSUE_SELECT = """
            select i.*, be.display_name as be_name, n.name as node_name, s.sku_code, u.display_name as ack_name from inventory_check_issue i
              join business_entity be on be.id = i.business_entity_id left join fulfillment_node n on n.id = i.node_id left join sku s on s.id = i.sku_id
              left join workspace_member m on m.workspace_id = i.workspace_id and m.id = i.acknowledged_by left join user_account u on u.id = m.user_id
            """;

    private IssueView issueView(java.sql.ResultSet rs) throws java.sql.SQLException {
        String code = rs.getString("check_code");
        return new IssueView(Db.uuid(rs, "id"), code, CHECK_KO.getOrDefault(code, code), rs.getString("severity"), Db.uuid(rs, "business_entity_id"),
                rs.getString("be_name"), Db.uuid(rs, "node_id"), rs.getString("node_name"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"),
                Db.uuid(rs, "position_id"), rs.getString("ref_type"), rs.getString("ref_id"), (Long) rs.getObject("expected"), (Long) rs.getObject("actual"),
                rs.getBigDecimal("cost_impact"), rs.getString("message_ko"), json.read(rs.getString("evidence")), rs.getString("status"),
                Db.instant(rs, "first_detected_at"), Db.instant(rs, "last_detected_at"), rs.getInt("detection_count"), rs.getString("ack_name"),
                rs.getString("acknowledge_reason"), Db.instant(rs, "resolved_at"), rs.getInt("version"));
    }
}
