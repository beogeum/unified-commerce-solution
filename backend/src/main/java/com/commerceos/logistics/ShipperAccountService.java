package com.commerceos.logistics;

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
import com.commerceos.shared.web.PageQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 화주계정(FR-ORG-04, FR-INV-06). 같은 물류사업자에 사업자 A/B 가 각자 화주계정을 가진다(T05).
 * 센터·출고거점과 재고 소유 관계는 M3 에서 연결한다.
 */
@Service
public class ShipperAccountService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;

    public ShipperAccountService(JdbcClient jdbc, AccessGuard guard, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    public record ShipperAccountView(UUID id, UUID businessEntityId, String businessEntityName, String logisticsProviderCode,
                                     String logisticsProviderName, String displayName, String externalShipperKey, String status,
                                     int version, Instant createdAt) {
    }

    public record Create(UUID businessEntityId, String logisticsProviderCode, String displayName, String externalShipperKey) {
    }

    public record Patch(String displayName, String status) {
    }

    public List<ShipperAccountView> list(PageQuery page, List<String> businessEntityIds, String providerCode) {
        guard.requirePermission(Perm.LOGISTICS_ACCOUNT_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        return jdbc.sql(SELECT + """
                 where s.workspace_id = :ws and s.business_entity_id = any(:bes)
                   and (cast(:p as text) is null or s.logistics_provider_code = :p)
                   and (cast(:after as uuid) is null or s.id < :after)
                 order by s.id desc limit :limit
                """).param("ws", guard.actor().workspaceId()).param("bes", Db.uuids(bes)).param("p", providerCode)
                .param("after", page.after()).param("limit", page.fetchSize()).query(this::map).list();
    }

    public ShipperAccountView get(UUID id) {
        guard.requirePermission(Perm.LOGISTICS_ACCOUNT_READ);
        ShipperAccountView v = find(id);
        guard.checkRead("SHIPPER_ACCOUNT", id, v.businessEntityId(), null);
        return v;
    }

    @Transactional
    public ShipperAccountView create(Create c) {
        guard.requirePermission(Perm.LOGISTICS_ACCOUNT_MANAGE);
        CurrentActor a = guard.actor();
        String beStatus = jdbc.sql("select status from business_entity where workspace_id = :ws and id = :id for share")
                .param("ws", a.workspaceId()).param("id", c.businessEntityId()).query(String.class).optional()
                .orElseThrow(() -> Errors.validation("business_entity_id", "사업자를 찾을 수 없습니다."));
        guard.checkWrite("BUSINESS_ENTITY", c.businessEntityId(), c.businessEntityId(), null);
        if (!"ACTIVE".equals(beStatus)) {
            throw Errors.conflict("BUSINESS_ENTITY_INACTIVE", "비활성 사업자에는 화주계정을 추가할 수 없습니다.");
        }
        if (jdbc.sql("select count(*) from logistics_provider where code = :c").param("c", c.logisticsProviderCode()).query(Integer.class).single() == 0) {
            throw Errors.validation("logistics_provider_code", "알 수 없는 물류사업자입니다.");
        }
        String ext = c.externalShipperKey() == null || c.externalShipperKey().isBlank() ? null : c.externalShipperKey().trim();
        if (ext != null && jdbc.sql("""
                select count(*) from shipper_account where workspace_id = :ws and logistics_provider_code = :p
                   and external_shipper_key = :k and status <> 'CLOSED'
                """).param("ws", a.workspaceId()).param("p", c.logisticsProviderCode()).param("k", ext).query(Integer.class).single() > 0) {
            throw Errors.conflict("SHIPPER_ACCOUNT_DUPLICATE", "같은 물류사업자의 같은 화주계정이 이미 등록되어 있습니다.");
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into shipper_account (id, workspace_id, business_entity_id, logistics_provider_code, display_name, external_shipper_key)
                values (:id, :ws, :be, :p, :n, :k)
                """).param("id", id).param("ws", a.workspaceId()).param("be", c.businessEntityId()).param("p", c.logisticsProviderCode())
                .param("n", c.displayName().trim()).param("k", ext).update();
        ShipperAccountView v = find(id);
        audit.record(AuditEntry.builder(a.workspaceId(), "SHIPPER_ACCOUNT_CREATED", "SHIPPER_ACCOUNT", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(c.businessEntityId(), null)
                .after(snapshot(v)).build());
        return v;
    }

    @Transactional
    public ShipperAccountView patch(UUID id, int expectedVersion, Patch p) {
        guard.requirePermission(Perm.LOGISTICS_ACCOUNT_MANAGE);
        CurrentActor a = guard.actor();
        jdbc.sql("select id from shipper_account where workspace_id = :ws and id = :id for update")
                .param("ws", a.workspaceId()).param("id", id).query(UUID.class).optional().orElseThrow(() -> Errors.notFound("SHIPPER_ACCOUNT"));
        ShipperAccountView before = find(id);
        guard.checkWrite("SHIPPER_ACCOUNT", id, before.businessEntityId(), null);
        if (before.version() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, before.version());
        }
        if (p.status() != null) {
            if (!Set.of("ACTIVE", "SUSPENDED", "CLOSED").contains(p.status())) {
                throw Errors.validation("status", "status 는 ACTIVE, SUSPENDED, CLOSED 중 하나여야 합니다.");
            }
            if ("CLOSED".equals(before.status()) && !"CLOSED".equals(p.status())) {
                throw Errors.conflict("SHIPPER_ACCOUNT_CLOSED", "종료된 화주계정은 다시 열 수 없습니다.");
            }
        }
        jdbc.sql("""
                update shipper_account set display_name = coalesce(:n, display_name), status = coalesce(:s, status),
                       version = version + 1, updated_at = now() where id = :id
                """).param("n", p.displayName() == null || p.displayName().isBlank() ? null : p.displayName().trim())
                .param("s", p.status()).param("id", id).update();
        ShipperAccountView after = find(id);
        audit.record(AuditEntry.builder(a.workspaceId(), "SHIPPER_ACCOUNT_UPDATED", "SHIPPER_ACCOUNT", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(after.businessEntityId(), null)
                .before(snapshot(before)).after(snapshot(after)).build());
        return after;
    }

    private ShipperAccountView find(UUID id) {
        return jdbc.sql(SELECT + " where s.workspace_id = :ws and s.id = :id").param("ws", guard.actor().workspaceId()).param("id", id)
                .query(this::map).optional().orElseThrow(() -> Errors.notFound("SHIPPER_ACCOUNT"));
    }

    private static final String SELECT = """
            select s.*, be.display_name as be_name, lp.name_ko as provider_name from shipper_account s
              join business_entity be on be.id = s.business_entity_id
              join logistics_provider lp on lp.code = s.logistics_provider_code
            """;

    private ShipperAccountView map(ResultSet rs, int i) throws SQLException {
        return new ShipperAccountView(Db.uuid(rs, "id"), Db.uuid(rs, "business_entity_id"), rs.getString("be_name"),
                rs.getString("logistics_provider_code"), rs.getString("provider_name"), rs.getString("display_name"),
                rs.getString("external_shipper_key"), rs.getString("status"), rs.getInt("version"), Db.instant(rs, "created_at"));
    }

    private static Map<String, Object> snapshot(ShipperAccountView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("business_entity_id", v.businessEntityId().toString());
        m.put("logistics_provider_code", v.logisticsProviderCode());
        m.put("display_name", v.displayName());
        m.put("external_shipper_key", v.externalShipperKey());
        m.put("status", v.status());
        return m;
    }
}
