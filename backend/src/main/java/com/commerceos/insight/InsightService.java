package com.commerceos.insight;

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
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 인사이트 카드(M6 1차, FR-OPS-05, docs/14 §4, DLG-179~182). 이상 발견 → 원인 후보 → 제안 → 사람 판단(검토 업무 생성·무시). 자동 실행 없음.
 * <ul>
 *   <li>같은 대상의 같은 규칙은 카드 하나(dedupe). 다시 계산하면 지표를 갱신하고, 더 이상 감지되지 않으면 해결 처리한다.</li>
 *   <li>무시한 카드는 해결될 때까지 다시 열지 않는다. 해결 뒤 다시 감지되면 새로 연다.</li>
 *   <li>조회는 규칙별 권한(손익·재고·정산)과 사업자·판매계정 범위로 거른다.</li>
 * </ul>
 */
@Service
public class InsightService {
    static final Map<String, String> RULE_PERM = Map.of("MARGIN_DROP", Perm.PROFIT_READ, "STOCKOUT_RISK", Perm.INVENTORY_READ, "SLOW_MOVING", Perm.INVENTORY_READ,
            "EXPIRY_RISK", Perm.INVENTORY_READ, "SETTLEMENT_MISSING", Perm.SETTLEMENT_READ, "UNDEPOSITED", Perm.SETTLEMENT_READ);
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final InsightEngine engine;
    private final WorkItemService workItems;
    private final AuditService audit;
    private final Json json;
    private final TransactionTemplate tx;

    public InsightService(JdbcClient jdbc, AccessGuard guard, InsightEngine engine, WorkItemService workItems, AuditService audit, Json json, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.engine = engine;
        this.workItems = workItems;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
    }

    public record Card(UUID id, String rule, String severity, String status, UUID businessEntityId, String businessEntityName, UUID channelAccountId, UUID skuId,
                       String title, String summary, JsonNode metric, JsonNode causes, JsonNode evidence, JsonNode unconfirmed, LocalDate observedOn,
                       java.time.Instant firstDetectedAt, java.time.Instant lastDetectedAt, java.time.Instant resolvedAt, UUID workItemId, String dismissReason) {
    }

    public record RunResult(UUID runId, int detected, int opened, int resolved, java.time.Instant finishedAt) {
    }

    // ---------------------------------------------------------------- 계산

    public RunResult refresh() {
        CurrentActor a = guard.actor();
        if (!(a.has(Perm.PROFIT_READ) || a.has(Perm.INVENTORY_READ) || a.has(Perm.SETTLEMENT_READ))) {
            guard.requirePermission(Perm.PROFIT_READ);
        }
        // 계산은 운영그룹 전체(시스템), 조회 때 사용자 범위로 거른다
        return TenantContext.asSystem(() -> run(a.workspaceId(), "MANUAL", a.memberId()));
    }

    @Scheduled(cron = "${commerce.insight.cron:0 17 6 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        TenantContext.asSystem(() -> {
            for (UUID ws : jdbc.sql("select id from workspace").query(UUID.class).list()) {
                run(ws, "SCHEDULED", null);
            }
        });
    }

    RunResult run(UUID ws, String trigger, UUID by) {
        return tx.execute(s -> {
            // 같은 운영그룹 동시 계산 직렬화
            jdbc.sql("select pg_advisory_xact_lock(hashtext('insight:' || cast(:ws as text)))").param("ws", ws).query().listOfRows();
            UUID runId = Ids.newId();
            LocalDate today = LocalDate.now(AnalyticsService.KST);
            List<InsightEngine.Detection> found = engine.detect(ws, today);
            int opened = 0;
            Set<String> keys = new HashSet<>();
            for (InsightEngine.Detection d : found) {
                keys.add(d.dedupeKey());
                Map<String, Object> cur = jdbc.sql("select id, status from insight_card where workspace_id = :ws and dedupe_key = :k for update").param("ws", ws)
                        .param("k", d.dedupeKey()).query().listOfRows().stream().findFirst().orElse(null);
                if (cur == null) {
                    jdbc.sql("""
                            insert into insight_card (id, workspace_id, rule_code, dedupe_key, business_entity_id, channel_account_id, sku_id, severity, status, title, summary,
                                                      metric, causes, evidence, unconfirmed, observed_on)
                            values (:id, :ws, :r, :k, :be, cast(:ca as uuid), cast(:sku as uuid), :sev, 'OPEN', :t, :s, cast(:m as jsonb), cast(:c as jsonb), cast(:e as jsonb),
                                    cast(:u as jsonb), :o)
                            """).param("id", Ids.newId()).param("ws", ws).param("r", d.rule()).param("k", d.dedupeKey()).param("be", d.businessEntityId())
                            .param("ca", d.channelAccountId()).param("sku", d.skuId()).param("sev", d.severity()).param("t", d.title()).param("s", d.summary())
                            .param("m", json.write(d.metric())).param("c", json.write(d.causes())).param("e", json.write(d.evidence())).param("u", json.write(d.unconfirmed()))
                            .param("o", today).update();
                    opened++;
                } else {
                    String st = (String) cur.get("status");
                    String next = "RESOLVED".equals(st) ? "OPEN" : st; // 해결 뒤 다시 감지 → 다시 열기, 무시·조치 중은 유지
                    if ("RESOLVED".equals(st)) {
                        opened++;
                    }
                    jdbc.sql("""
                            update insight_card set severity = :sev, status = :st, title = :t, summary = :s, metric = cast(:m as jsonb), causes = cast(:c as jsonb),
                                   evidence = cast(:e as jsonb), unconfirmed = cast(:u as jsonb), observed_on = :o, last_detected_at = now(),
                                   first_detected_at = case when :reopen then now() else first_detected_at end, resolved_at = case when :reopen then null else resolved_at end,
                                   work_item_id = case when :reopen then null else work_item_id end, dismiss_reason = case when :reopen then null else dismiss_reason end,
                                   version = version + 1
                             where id = :id
                            """).param("sev", d.severity()).param("st", next).param("t", d.title()).param("s", d.summary()).param("m", json.write(d.metric()))
                            .param("c", json.write(d.causes())).param("e", json.write(d.evidence())).param("u", json.write(d.unconfirmed())).param("o", today)
                            .param("reopen", "RESOLVED".equals(st)).param("id", cur.get("id")).update();
                }
            }
            int resolved = 0;
            for (Map<String, Object> c : jdbc.sql("select id, dedupe_key, work_item_id from insight_card where workspace_id = :ws and status in ('OPEN', 'ACTIONED', 'DISMISSED')")
                    .param("ws", ws).query().listOfRows()) {
                if (keys.contains((String) c.get("dedupe_key"))) {
                    continue;
                }
                jdbc.sql("update insight_card set status = 'RESOLVED', resolved_at = now(), version = version + 1 where id = :id").param("id", c.get("id")).update();
                if (c.get("work_item_id") != null) {
                    workItems.resolveByKey(ws, "insight:" + c.get("id"));
                }
                resolved++;
            }
            jdbc.sql("""
                    insert into insight_run (id, workspace_id, trigger, finished_at, detected, opened, resolved, requested_by)
                    values (:id, :ws, :t, now(), :d, :o, :r, cast(:by as uuid))
                    """).param("id", runId).param("ws", ws).param("t", trigger).param("d", found.size()).param("o", opened).param("r", resolved).param("by", by).update();
            return new RunResult(runId, found.size(), opened, resolved, java.time.Instant.now());
        });
    }

    // ---------------------------------------------------------------- 조회

    public List<Card> list(List<String> businessEntityIds, String status, String rule) {
        CurrentActor a = guard.actor();
        Set<String> rules = new HashSet<>();
        RULE_PERM.forEach((r, p) -> {
            if (a.has(p)) {
                rules.add(r);
            }
        });
        if (rules.isEmpty()) {
            guard.requirePermission(Perm.PROFIT_READ);
        }
        if (rule != null && !rule.isBlank()) {
            rules.retainAll(Set.of(rule));
        }
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        if (bes.isEmpty() || rules.isEmpty()) {
            return List.of();
        }
        String st = status == null || status.isBlank() ? "OPEN" : status;
        if (!Set.of("OPEN", "ACTIONED", "DISMISSED", "RESOLVED", "ALL").contains(st)) {
            throw Errors.validation("status", "상태는 OPEN·ACTIONED·DISMISSED·RESOLVED·ALL 중 하나입니다.");
        }
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", a.workspaceId(), "bes", bes, "rules", rules, "cas", cas.isEmpty() ? Set.of(UUID.randomUUID()) : cas));
        String where = " where c.workspace_id = :ws and c.business_entity_id in (:bes) and c.rule_code in (:rules) and (c.channel_account_id is null or c.channel_account_id in (:cas))";
        if (!"ALL".equals(st)) {
            where += " and c.status = :st";
            p.put("st", st);
        }
        return jdbc.sql(SELECT + where + " order by case c.severity when 'HIGH' then 0 when 'MEDIUM' then 1 else 2 end, c.last_detected_at desc limit 300").params(p)
                .query(this::map).list();
    }

    public Card get(UUID id) {
        CurrentActor a = guard.actor();
        Card c = jdbc.sql(SELECT + " where c.workspace_id = :ws and c.id = :id").param("ws", a.workspaceId()).param("id", id).query(this::map).optional()
                .orElseThrow(() -> Errors.notFound("INSIGHT_CARD"));
        guard.requirePermission(RULE_PERM.get(c.rule()));
        guard.checkRead("INSIGHT_CARD", id, c.businessEntityId(), c.channelAccountId());
        return c;
    }

    public Map<String, Object> lastRun() {
        guard.actor();
        return jdbc.sql("select started_at, finished_at, trigger, detected, opened, resolved from insight_run where workspace_id = :ws order by started_at desc limit 1")
                .param("ws", guard.actor().workspaceId()).query().listOfRows().stream().findFirst().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>(r);
                    m.put("finished_at", r.get("finished_at") == null ? null : ((java.sql.Timestamp) r.get("finished_at")).toInstant());
                    m.put("started_at", ((java.sql.Timestamp) r.get("started_at")).toInstant());
                    return m;
                }).orElse(Map.of());
    }

    // ---------------------------------------------------------------- 사람 판단

    @Transactional
    public Card createWorkItem(UUID id) {
        guard.requirePermission(Perm.WORK_ITEM_UPDATE);
        CurrentActor a = guard.actor();
        Card c = get(id);
        guard.checkWrite("INSIGHT_CARD", id, c.businessEntityId(), c.channelAccountId());
        if (!"OPEN".equals(c.status())) {
            throw Errors.conflict("INSIGHT_NOT_OPEN", "열린 카드만 검토 업무로 만들 수 있습니다.", Map.of("status", c.status()));
        }
        String suggestion = c.evidence().has("suggestion") ? c.evidence().get("suggestion").asString() : "근거 레코드를 확인하고 필요한 조치를 판단하세요.";
        UUID wi = workItems.raise(new WorkItemService.Raise(a.workspaceId(), c.businessEntityId(), c.channelAccountId(), null, "INSIGHT_REVIEW",
                "HIGH".equals(c.severity()) ? "HIGH" : "MEDIUM", "인사이트 검토: " + c.title(), c.summary(), suggestion, "insight:" + id,
                Map.of("insight_id", id.toString(), "rule", c.rule())));
        jdbc.sql("update insight_card set status = 'ACTIONED', work_item_id = :w, version = version + 1 where id = :id").param("w", wi).param("id", id).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "INSIGHT_ACTIONED", "INSIGHT_CARD", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(c.businessEntityId(), c.channelAccountId()).after(Map.of("work_item_id", wi.toString(), "rule", c.rule())).build());
        return get(id);
    }

    @Transactional
    public Card dismiss(UUID id, String reason) {
        guard.requirePermission(Perm.WORK_ITEM_UPDATE);
        CurrentActor a = guard.actor();
        Card c = get(id);
        guard.checkWrite("INSIGHT_CARD", id, c.businessEntityId(), c.channelAccountId());
        String r = reason == null ? null : reason.trim();
        if (r == null || r.isEmpty() || r.length() > 500) {
            throw Errors.validation("reason", "무시하는 이유를 500자 이내로 입력하세요.");
        }
        if (!"OPEN".equals(c.status())) {
            throw Errors.conflict("INSIGHT_NOT_OPEN", "열린 카드만 무시할 수 있습니다.", Map.of("status", c.status()));
        }
        jdbc.sql("update insight_card set status = 'DISMISSED', dismissed_by = :by, dismissed_at = now(), dismiss_reason = :r, version = version + 1 where id = :id")
                .param("by", a.memberId()).param("r", r).param("id", id).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "INSIGHT_DISMISSED", "INSIGHT_CARD", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(c.businessEntityId(), c.channelAccountId()).reason(r).after(Map.of("rule", c.rule())).build());
        return get(id);
    }

    static final String SELECT = "select c.*, be.display_name as be_name from insight_card c join business_entity be on be.id = c.business_entity_id";

    private Card map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Card(Db.uuid(rs, "id"), rs.getString("rule_code"), rs.getString("severity"), rs.getString("status"), Db.uuid(rs, "business_entity_id"),
                rs.getString("be_name"), Db.uuid(rs, "channel_account_id"), Db.uuid(rs, "sku_id"), rs.getString("title"), rs.getString("summary"),
                json.read(rs.getString("metric")), json.read(rs.getString("causes")), json.read(rs.getString("evidence")), json.read(rs.getString("unconfirmed")),
                rs.getObject("observed_on", LocalDate.class), rs.getTimestamp("first_detected_at").toInstant(), rs.getTimestamp("last_detected_at").toInstant(),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(), Db.uuid(rs, "work_item_id"), rs.getString("dismiss_reason"));
    }
}
