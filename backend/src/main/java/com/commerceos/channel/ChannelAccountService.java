package com.commerceos.channel;

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
import com.commerceos.shared.web.PageQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
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
 * 판매계정(FR-ORG-02/03/07). 같은 채널에 사업자별·사업자 내 복수 계정을 허용한다.
 * 계정 생성 시 기본 판매단위 1개와 최초 귀속 이력을 같은 트랜잭션에서 만든다(ARCH-003).
 */
@Service
public class ChannelAccountService {
    static final String DEFAULT_UNIT_KEY = "DEFAULT";
    private static final Set<String> STATUSES = Set.of("ACTIVE", "SUSPENDED", "CLOSED");

    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final ScopeAutoInclusion autoInclusion;
    private final Clock clock;

    public ChannelAccountService(JdbcClient jdbc, AccessGuard guard, AuditService audit, OutboxPublisher outbox,
                                 ScopeAutoInclusion autoInclusion, Clock clock) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.outbox = outbox;
        this.autoInclusion = autoInclusion;
        this.clock = clock;
    }

    public record ChannelAccountView(UUID id, UUID businessEntityId, String businessEntityName, String salesChannelCode,
                                     String salesChannelName, String displayName, String externalAccountKey, String status,
                                     int salesUnitCount, int version, Instant createdAt, Instant updatedAt) {
    }

    public record DefaultUnit(String externalUnitKey, String displayName) {
    }

    public record Create(UUID businessEntityId, String salesChannelCode, String displayName, String externalAccountKey,
                         DefaultUnit defaultSalesUnit) {
    }

    public record Patch(String displayName, String status, String externalAccountKey) {
    }

    public record AttributionChange(UUID businessEntityId, String reason) {
    }

    public record AttributionView(UUID id, UUID businessEntityId, String businessEntityName, Instant validFrom, Instant validTo,
                                  String reason, Instant createdAt) {
    }

    public record Filter(List<String> businessEntityIds, String salesChannelCode, String status, String q) {
    }

    public List<ChannelAccountView> list(PageQuery page, Filter f) {
        guard.requirePermission(Perm.CHANNEL_ACCOUNT_READ);
        CurrentActor a = guard.actor();
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, f.businessEntityIds());
        return jdbc.sql(SELECT + """
                 where ca.workspace_id = :ws and ca.business_entity_id = any(:bes) and ca.id = any(:cas)
                   and (cast(:channel as text) is null or ca.sales_channel_code = :channel)
                   and (cast(:status as text) is null or ca.status = :status)
                   and (cast(:q as text) is null or ca.display_name ilike '%' || :q || '%' or ca.external_account_key = :q)
                   and (cast(:after as uuid) is null or ca.id < :after)
                 order by ca.id desc limit :limit
                """).param("ws", a.workspaceId()).param("bes", Db.uuids(bes))
                .param("cas", Db.uuids(guard.scope().channelAccountIds(AccessKind.READ)))
                .param("channel", f.salesChannelCode()).param("status", f.status())
                .param("q", f.q() == null || f.q().isBlank() ? null : f.q().trim())
                .param("after", page.after()).param("limit", page.fetchSize())
                .query(this::map).list();
    }

    public ChannelAccountView get(UUID id) {
        guard.requirePermission(Perm.CHANNEL_ACCOUNT_READ);
        ChannelAccountView v = find(id).orElseThrow(() -> Errors.notFound("CHANNEL_ACCOUNT"));
        guard.checkRead("CHANNEL_ACCOUNT", id, v.businessEntityId(), id);
        return v;
    }

    @Transactional
    public ChannelAccountView create(Create c) {
        guard.requirePermission(Perm.CHANNEL_ACCOUNT_MANAGE);
        CurrentActor a = guard.actor();
        String beStatus = jdbc.sql("select status from business_entity where workspace_id = :ws and id = :id for share")
                .param("ws", a.workspaceId()).param("id", c.businessEntityId()).query(String.class).optional()
                .orElseThrow(() -> Errors.validation("business_entity_id", "사업자를 찾을 수 없습니다."));
        guard.checkWrite("BUSINESS_ENTITY", c.businessEntityId(), c.businessEntityId(), null);
        if (!"ACTIVE".equals(beStatus)) {
            throw Errors.conflict("BUSINESS_ENTITY_INACTIVE", "비활성 사업자에는 판매계정을 추가할 수 없습니다.");
        }
        if (jdbc.sql("select count(*) from sales_channel where code = :c").param("c", c.salesChannelCode()).query(Integer.class).single() == 0) {
            throw Errors.validation("sales_channel_code", "알 수 없는 판매채널입니다.");
        }
        String extKey = blankToNull(c.externalAccountKey());
        if (extKey != null && jdbc.sql("""
                select count(*) from channel_account where workspace_id = :ws and sales_channel_code = :ch
                   and external_account_key = :k and status <> 'CLOSED'
                """).param("ws", a.workspaceId()).param("ch", c.salesChannelCode()).param("k", extKey).query(Integer.class).single() > 0) {
            throw Errors.conflict("CHANNEL_ACCOUNT_DUPLICATE", "같은 판매채널 계정이 이미 등록되어 있습니다.");
        }
        UUID id = Ids.newId();
        Instant now = clock.instant();
        jdbc.sql("""
                insert into channel_account (id, workspace_id, business_entity_id, sales_channel_code, display_name, external_account_key)
                values (:id, :ws, :be, :ch, :name, :ext)
                """).param("id", id).param("ws", a.workspaceId()).param("be", c.businessEntityId()).param("ch", c.salesChannelCode())
                .param("name", c.displayName().trim()).param("ext", extKey).update();
        jdbc.sql("""
                insert into channel_account_attribution (id, workspace_id, channel_account_id, business_entity_id, valid_from, reason, created_by)
                values (:id, :ws, :ca, :be, :from, '최초 등록', :by)
                """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("ca", id).param("be", c.businessEntityId())
                .param("from", Db.ts(now)).param("by", a.memberId()).update();
        DefaultUnit du = c.defaultSalesUnit();
        String unitKey = du == null || blankToNull(du.externalUnitKey()) == null ? DEFAULT_UNIT_KEY : du.externalUnitKey().trim();
        String unitName = du == null || blankToNull(du.displayName()) == null ? "기본 판매단위" : du.displayName().trim();
        jdbc.sql("""
                insert into sales_unit (id, workspace_id, channel_account_id, external_unit_key, display_name, is_default)
                values (:id, :ws, :ca, :key, :name, true)
                """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("ca", id).param("key", unitKey).param("name", unitName).update();
        int autoIncluded = autoInclusion.onTargetCreated(a.workspaceId(), ScopeDimension.CHANNEL_ACCOUNT, id, a.memberId());
        guard.invalidateScope();
        ChannelAccountView v = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "CHANNEL_ACCOUNT_CREATED", "CHANNEL_ACCOUNT", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(c.businessEntityId(), id).after(snapshot(v))
                .metadata(Map.of("default_sales_unit_key", unitKey, "scope_auto_included", autoIncluded)).build());
        outbox.publish(a.workspaceId(), c.businessEntityId(), "CHANNEL_ACCOUNT", id, "ChannelAccountCreated",
                Map.of("channel_account_id", id.toString(), "business_entity_id", c.businessEntityId().toString()));
        return v;
    }

    @Transactional
    public ChannelAccountView patch(UUID id, int expectedVersion, Patch p) {
        guard.requirePermission(Perm.CHANNEL_ACCOUNT_MANAGE);
        CurrentActor a = guard.actor();
        ChannelAccountView before = lock(id);
        guard.checkWrite("CHANNEL_ACCOUNT", id, before.businessEntityId(), id);
        if (before.version() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, before.version());
        }
        if (p.status() != null) {
            if (!STATUSES.contains(p.status())) {
                throw Errors.validation("status", "status 는 ACTIVE, SUSPENDED, CLOSED 중 하나여야 합니다.");
            }
            if ("CLOSED".equals(before.status()) && !"CLOSED".equals(p.status())) {
                throw Errors.conflict("CHANNEL_ACCOUNT_CLOSED", "종료된 판매계정은 다시 열 수 없습니다. 새 판매계정으로 등록하세요.");
            }
        }
        String newKey = blankToNull(p.externalAccountKey());
        if (newKey != null && before.externalAccountKey() != null && !newKey.equals(before.externalAccountKey())) {
            throw Errors.conflict("EXTERNAL_KEY_IMMUTABLE", "등록된 외부 계정 식별자는 변경할 수 없습니다. 다른 계정이면 새로 등록하세요.");
        }
        jdbc.sql("""
                update channel_account set display_name = coalesce(:name, display_name), status = coalesce(:st, status),
                       external_account_key = coalesce(external_account_key, :ext), version = version + 1, updated_at = now()
                 where id = :id
                """).param("name", blankToNull(p.displayName())).param("st", p.status()).param("ext", newKey).param("id", id).update();
        ChannelAccountView after = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "CHANNEL_ACCOUNT_UPDATED", "CHANNEL_ACCOUNT", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(after.businessEntityId(), id)
                .before(snapshot(before)).after(snapshot(after)).build());
        outbox.publish(a.workspaceId(), after.businessEntityId(), "CHANNEL_ACCOUNT", id, "ChannelAccountUpdated",
                Map.of("channel_account_id", id.toString(), "status", after.status()));
        return after;
    }

    /**
     * 사업자 귀속 변경(FR-ORG-07, DEC-F06). 즉시 적용만 허용하며 과거 귀속 이력은 보존된다.
     * 거래 객체는 거래 시점 귀속(channel_account_business_at)을 스냅샷으로 가지므로 과거 주문이 재귀속되지 않는다.
     */
    @Transactional
    public ChannelAccountView changeAttribution(UUID id, int expectedVersion, AttributionChange change) {
        guard.requirePermission(Perm.CHANNEL_ACCOUNT_REASSIGN);
        CurrentActor a = guard.actor();
        ChannelAccountView before = lock(id);
        guard.checkWrite("CHANNEL_ACCOUNT", id, before.businessEntityId(), id);
        if (before.version() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, before.version());
        }
        if (change.businessEntityId() == null) {
            throw Errors.validation("business_entity_id", "새 귀속 사업자를 지정하세요.");
        }
        if (change.reason() == null || change.reason().isBlank()) {
            throw Errors.validation("reason", "귀속 변경 사유를 입력하세요.");
        }
        if (change.businessEntityId().equals(before.businessEntityId())) {
            throw Errors.conflict("ATTRIBUTION_UNCHANGED", "현재 귀속 사업자와 같습니다.");
        }
        if ("CLOSED".equals(before.status())) {
            throw Errors.conflict("CHANNEL_ACCOUNT_CLOSED", "종료된 판매계정의 귀속은 변경할 수 없습니다.");
        }
        String targetStatus = jdbc.sql("select status from business_entity where workspace_id = :ws and id = :id for share")
                .param("ws", a.workspaceId()).param("id", change.businessEntityId()).query(String.class).optional()
                .orElseThrow(() -> Errors.validation("business_entity_id", "사업자를 찾을 수 없습니다."));
        guard.checkWrite("BUSINESS_ENTITY", change.businessEntityId(), change.businessEntityId(), null);
        if (!"ACTIVE".equals(targetStatus)) {
            throw Errors.conflict("BUSINESS_ENTITY_INACTIVE", "비활성 사업자로는 귀속을 변경할 수 없습니다.");
        }
        Instant now = clock.instant();
        int closed = jdbc.sql("""
                update channel_account_attribution set valid_to = :now
                 where channel_account_id = :id and valid_to is null and valid_from < :now
                """).param("now", Db.ts(now)).param("id", id).update();
        if (closed != 1) {
            throw Errors.conflict("ATTRIBUTION_STATE_INVALID", "현재 귀속 이력을 확인할 수 없습니다. 잠시 후 다시 시도하세요.");
        }
        jdbc.sql("""
                insert into channel_account_attribution (id, workspace_id, channel_account_id, business_entity_id, valid_from, reason, created_by)
                values (:id, :ws, :ca, :be, :from, :reason, :by)
                """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("ca", id).param("be", change.businessEntityId())
                .param("from", Db.ts(now)).param("reason", change.reason().trim()).param("by", a.memberId()).update();
        jdbc.sql("update channel_account set business_entity_id = :be, version = version + 1, updated_at = now() where id = :id")
                .param("be", change.businessEntityId()).param("id", id).update();
        ChannelAccountView after = find(id).orElseThrow();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel_account_id", id.toString());
        payload.put("from_business_entity_id", before.businessEntityId().toString());
        payload.put("to_business_entity_id", change.businessEntityId().toString());
        payload.put("effective_at", now.toString());
        audit.record(AuditEntry.builder(a.workspaceId(), "CHANNEL_ACCOUNT_REASSIGNED", "CHANNEL_ACCOUNT", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(change.businessEntityId(), id)
                .reason(change.reason().trim()).before(Map.of("business_entity_id", before.businessEntityId().toString()))
                .after(Map.of("business_entity_id", change.businessEntityId().toString())).build());
        outbox.publish(a.workspaceId(), change.businessEntityId(), "CHANNEL_ACCOUNT", id, "ChannelAccountReassigned", payload);
        return after;
    }

    public List<AttributionView> attributions(UUID id) {
        get(id);
        return jdbc.sql("""
                select a.*, be.display_name as be_name from channel_account_attribution a
                  join business_entity be on be.id = a.business_entity_id
                 where a.workspace_id = :ws and a.channel_account_id = :id order by a.valid_from desc
                """).param("ws", guard.actor().workspaceId()).param("id", id)
                .query((rs, i) -> new AttributionView(Db.uuid(rs, "id"), Db.uuid(rs, "business_entity_id"), rs.getString("be_name"),
                        Db.instant(rs, "valid_from"), Db.instant(rs, "valid_to"), rs.getString("reason"), Db.instant(rs, "created_at")))
                .list();
    }

    Optional<ChannelAccountView> find(UUID id) {
        return jdbc.sql(SELECT + " where ca.workspace_id = :ws and ca.id = :id")
                .param("ws", guard.actor().workspaceId()).param("id", id).query(this::map).optional();
    }

    private ChannelAccountView lock(UUID id) {
        jdbc.sql("select id from channel_account where workspace_id = :ws and id = :id for update")
                .param("ws", guard.actor().workspaceId()).param("id", id).query(UUID.class).optional()
                .orElseThrow(() -> Errors.notFound("CHANNEL_ACCOUNT"));
        return find(id).orElseThrow();
    }

    private static final String SELECT = """
            select ca.*, be.display_name as be_name, sc.name_ko as channel_name,
                   (select count(*) from sales_unit su where su.channel_account_id = ca.id) as unit_count
              from channel_account ca
              join business_entity be on be.id = ca.business_entity_id
              join sales_channel sc on sc.code = ca.sales_channel_code
            """;

    private ChannelAccountView map(ResultSet rs, int i) throws SQLException {
        return new ChannelAccountView(Db.uuid(rs, "id"), Db.uuid(rs, "business_entity_id"), rs.getString("be_name"),
                rs.getString("sales_channel_code"), rs.getString("channel_name"), rs.getString("display_name"),
                rs.getString("external_account_key"), rs.getString("status"), rs.getInt("unit_count"), rs.getInt("version"),
                Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"));
    }

    private static Map<String, Object> snapshot(ChannelAccountView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("business_entity_id", v.businessEntityId().toString());
        m.put("sales_channel_code", v.salesChannelCode());
        m.put("display_name", v.displayName());
        m.put("external_account_key", v.externalAccountKey());
        m.put("status", v.status());
        m.put("version", v.version());
        return m;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
