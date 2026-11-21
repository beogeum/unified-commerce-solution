package com.commerceos.identity.business;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.identity.access.ScopeAutoInclusion;
import com.commerceos.identity.access.ScopeDimension;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.outbox.OutboxPublisher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import com.commerceos.shared.web.PageQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import tools.jackson.databind.JsonNode;

/** 사업자(FR-ORG-01). 이력은 감사로그를 단일 출처로 사용한다. */
@Service
public class BusinessEntityService {
    static final Set<String> LEGAL_TYPES = Set.of("CORPORATION", "INDIVIDUAL");
    static final Set<String> TAX_TYPES = Set.of("GENERAL", "SIMPLIFIED", "EXEMPT");

    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final ScopeAutoInclusion autoInclusion;
    private final Json json;

    public BusinessEntityService(JdbcClient jdbc, AccessGuard guard, AuditService audit, OutboxPublisher outbox,
                                 ScopeAutoInclusion autoInclusion, Json json) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.outbox = outbox;
        this.autoInclusion = autoInclusion;
        this.json = json;
    }

    public record BusinessEntityView(UUID id, String displayName, String legalName, String legalType, String businessRegistrationNo,
                                     String taxType, String status, int version, Instant createdAt, Instant updatedAt) {
    }

    public record Create(String displayName, String legalName, String legalType, String businessRegistrationNo, String taxType) {
    }

    public record Patch(String displayName, String legalName, String taxType, String status, String legalType,
                        String businessRegistrationNo) {
    }

    public record HistoryItem(UUID auditId, Instant occurredAt, String action, String actorLabel, JsonNode before, JsonNode after) {
    }

    public List<BusinessEntityView> list(PageQuery page, List<String> businessEntityIds, String status, String q) {
        guard.requirePermission(Perm.BUSINESS_ENTITY_READ);
        CurrentActor a = guard.actor();
        Set<UUID> allowed = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        return jdbc.sql("""
                select * from business_entity
                 where workspace_id = :ws and id = any(:ids)
                   and (cast(:status as text) is null or status = :status)
                   and (cast(:q as text) is null or display_name ilike '%' || :q || '%' or legal_name ilike '%' || :q || '%'
                        or business_registration_no = regexp_replace(:q, '-', '', 'g'))
                   and (cast(:after as uuid) is null or id < :after)
                 order by id desc limit :limit
                """).param("ws", a.workspaceId()).param("ids", Db.uuids(allowed)).param("status", status)
                .param("q", q == null || q.isBlank() ? null : q.trim()).param("after", page.after()).param("limit", page.fetchSize())
                .query(this::map).list();
    }

    public BusinessEntityView get(UUID id) {
        guard.requirePermission(Perm.BUSINESS_ENTITY_READ);
        BusinessEntityView v = find(id).orElseThrow(() -> Errors.notFound("BUSINESS_ENTITY"));
        guard.checkRead("BUSINESS_ENTITY", id, id, null);
        return v;
    }

    @Transactional
    public BusinessEntityView create(Create c) {
        guard.requirePermission(Perm.BUSINESS_ENTITY_MANAGE);
        CurrentActor a = guard.actor();
        validateEnum("legal_type", c.legalType(), LEGAL_TYPES);
        validateEnum("tax_type", c.taxType(), TAX_TYPES);
        String brn = BusinessRegistrationNumber.normalize(c.businessRegistrationNo());
        if (jdbc.sql("select count(*) from business_entity where workspace_id = :ws and business_registration_no = :b")
                .param("ws", a.workspaceId()).param("b", brn).query(Integer.class).single() > 0) {
            throw Errors.conflict("BUSINESS_REGISTRATION_NO_DUPLICATE", "같은 사업자등록번호의 사업자가 이미 등록되어 있습니다.");
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into business_entity (id, workspace_id, display_name, legal_name, legal_type, business_registration_no, tax_type)
                values (:id, :ws, :dn, :ln, :lt, :brn, :tt)
                """).param("id", id).param("ws", a.workspaceId()).param("dn", c.displayName().trim()).param("ln", c.legalName().trim())
                .param("lt", c.legalType()).param("brn", brn).param("tt", c.taxType()).update();
        int autoIncluded = autoInclusion.onTargetCreated(a.workspaceId(), ScopeDimension.BUSINESS_ENTITY, id, a.memberId());
        guard.invalidateScope();
        BusinessEntityView v = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "BUSINESS_ENTITY_CREATED", "BUSINESS_ENTITY", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(id, null).after(snapshot(v))
                .metadata(Map.of("scope_auto_included", autoIncluded)).build());
        outbox.publish(a.workspaceId(), id, "BUSINESS_ENTITY", id, "BusinessEntityCreated", Map.of("business_entity_id", id.toString()));
        return v;
    }

    @Transactional
    public BusinessEntityView patch(UUID id, int expectedVersion, Patch p) {
        guard.requirePermission(Perm.BUSINESS_ENTITY_MANAGE);
        CurrentActor a = guard.actor();
        BusinessEntityView before = jdbc.sql("select * from business_entity where workspace_id = :ws and id = :id for update")
                .param("ws", a.workspaceId()).param("id", id).query(this::map).optional()
                .orElseThrow(() -> Errors.notFound("BUSINESS_ENTITY"));
        guard.checkWrite("BUSINESS_ENTITY", id, id, null);
        if (before.version() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, before.version());
        }
        if (p.businessRegistrationNo() != null || p.legalType() != null) {
            throw Errors.conflict("BUSINESS_IDENTITY_IMMUTABLE",
                    "사업자등록번호와 법인/개인 구분은 변경할 수 없습니다. 다른 사업자이면 새로 등록하세요.");
        }
        if (p.taxType() != null) {
            validateEnum("tax_type", p.taxType(), TAX_TYPES);
        }
        if (p.status() != null) {
            validateEnum("status", p.status(), Set.of("ACTIVE", "INACTIVE"));
            if ("INACTIVE".equals(p.status()) && !"INACTIVE".equals(before.status())) {
                int active = jdbc.sql("""
                        select (select count(*) from channel_account where business_entity_id = :id and status <> 'CLOSED')
                             + (select count(*) from shipper_account where business_entity_id = :id and status <> 'CLOSED')
                        """).param("id", id).query(Integer.class).single();
                if (active > 0) {
                    throw Errors.conflict("BUSINESS_ENTITY_HAS_ACTIVE_ACCOUNTS",
                            "운영 중인 판매계정 또는 화주계정이 있어 비활성화할 수 없습니다. 계정을 먼저 종료하거나 다른 사업자로 이관하세요.",
                            Map.of("active_account_count", active));
                }
            }
        }
        jdbc.sql("""
                update business_entity set display_name = coalesce(:dn, display_name), legal_name = coalesce(:ln, legal_name),
                       tax_type = coalesce(:tt, tax_type), status = coalesce(:st, status), version = version + 1, updated_at = now()
                 where id = :id
                """).param("dn", trim(p.displayName())).param("ln", trim(p.legalName())).param("tt", p.taxType())
                .param("st", p.status()).param("id", id).update();
        BusinessEntityView after = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "BUSINESS_ENTITY_UPDATED", "BUSINESS_ENTITY", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(id, null)
                .before(snapshot(before)).after(snapshot(after)).build());
        outbox.publish(a.workspaceId(), id, "BUSINESS_ENTITY", id, "BusinessEntityUpdated", Map.of("business_entity_id", id.toString()));
        return after;
    }

    public List<HistoryItem> history(UUID id) {
        get(id);
        CurrentActor a = guard.actor();
        return jdbc.sql("""
                select id, occurred_at, action, actor_label, before_state::text as b, after_state::text as af from audit_log
                 where workspace_id = :ws and target_type = 'BUSINESS_ENTITY' and target_id = :id and outcome = 'SUCCESS'
                 order by occurred_at desc, id desc limit 200
                """).param("ws", a.workspaceId()).param("id", id)
                .query((rs, i) -> new HistoryItem(Db.uuid(rs, "id"), Db.instant(rs, "occurred_at"), rs.getString("action"),
                        rs.getString("actor_label"), readJson(rs.getString("b")), readJson(rs.getString("af")))).list();
    }

    Optional<BusinessEntityView> find(UUID id) {
        return jdbc.sql("select * from business_entity where workspace_id = :ws and id = :id")
                .param("ws", guard.actor().workspaceId()).param("id", id).query(this::map).optional();
    }

    private BusinessEntityView map(ResultSet rs, int i) throws SQLException {
        return new BusinessEntityView(Db.uuid(rs, "id"), rs.getString("display_name"), rs.getString("legal_name"),
                rs.getString("legal_type"), rs.getString("business_registration_no"), rs.getString("tax_type"), rs.getString("status"),
                rs.getInt("version"), Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"));
    }

    private static Map<String, Object> snapshot(BusinessEntityView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("display_name", v.displayName());
        m.put("legal_name", v.legalName());
        m.put("legal_type", v.legalType());
        m.put("business_registration_no", v.businessRegistrationNo());
        m.put("tax_type", v.taxType());
        m.put("status", v.status());
        m.put("version", v.version());
        return m;
    }

    private JsonNode readJson(String s) {
        return json.read(s);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    static void validateEnum(String field, String value, Set<String> allowed) {
        if (value == null || !allowed.contains(value)) {
            throw Errors.validation(field, field + " 값이 올바르지 않습니다. 허용값: " + String.join(", ", allowed.stream().sorted().toList()));
        }
    }
}
