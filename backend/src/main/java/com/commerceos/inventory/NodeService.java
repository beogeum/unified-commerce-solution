package com.commerceos.inventory;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출고거점(M3 I1). 물류사업자 → 거점, 3PL·플랫폼 거점은 사업자별 화주계정으로 연결한다(T05: 같은 3PL 센터의 A/B 재고 분리).
 * 자체 WMS 거점은 화주계정 없이 여러 사업자가 함께 쓰고, 로케이션을 둔다.
 */
@Service
public class NodeService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;

    public NodeService(JdbcClient jdbc, AccessGuard guard, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    public record ShipperLink(UUID shipperAccountId, String shipperName, UUID businessEntityId, String businessEntityName) {
    }

    public record NodeView(UUID id, String code, String name, String logisticsProviderCode, String providerKind, String providerName,
                           String inventorySource, LocalTime cutoffTime, String addressNote, String status, List<ShipperLink> shippers,
                           int locationCount, int version) {
    }

    public record Create(String code, String name, String logisticsProviderCode, String inventorySource, LocalTime cutoffTime, String addressNote) {
    }

    public record Patch(String name, LocalTime cutoffTime, String addressNote, String status) {
    }

    public record LocationView(UUID id, String code, String zone, String status) {
    }

    public List<NodeView> list() {
        guard.requirePermission(Perm.LOGISTICS_ACCOUNT_READ);
        return jdbc.sql(SELECT + " where n.workspace_id = :ws order by n.code").param("ws", ws()).query(this::map).list().stream().map(this::withParts).toList();
    }

    public NodeView get(UUID id) {
        guard.requirePermission(Perm.LOGISTICS_ACCOUNT_READ);
        return find(id).orElseThrow(() -> Errors.notFound("FULFILLMENT_NODE"));
    }

    @Transactional
    public NodeView create(Create c) {
        guard.requirePermission(Perm.LOGISTICS_ACCOUNT_MANAGE);
        CurrentActor a = guard.actor();
        String code = c.code() == null ? "" : c.code().trim().toUpperCase();
        if (!code.matches("^[A-Z0-9][A-Z0-9_-]{0,39}$")) {
            throw Errors.validation("code", "거점 코드는 영문 대문자·숫자·하이픈·밑줄 40자 이내로 입력하세요.");
        }
        if (c.name() == null || c.name().isBlank() || c.name().length() > 100) {
            throw Errors.validation("name", "거점 이름을 100자 이내로 입력하세요.");
        }
        if (c.cutoffTime() == null) {
            throw Errors.validation("cutoff_time", "출고 마감 시각을 입력하세요(거점마다 필수, DLG-040).");
        }
        String kind = jdbc.sql("select kind from logistics_provider where code = :c").param("c", c.logisticsProviderCode()).query(String.class).optional()
                .orElseThrow(() -> Errors.validation("logistics_provider_code", "물류사업자를 선택하세요."));
        String source = c.inventorySource() == null ? ("SELF_WMS".equals(kind) ? "INTERNAL" : "EXTERNAL") : c.inventorySource();
        if (!Set.of("INTERNAL", "EXTERNAL").contains(source)) {
            throw Errors.validation("inventory_source", "재고 원본은 INTERNAL 또는 EXTERNAL 입니다.");
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    insert into fulfillment_node (id, workspace_id, code, name, logistics_provider_code, inventory_source, cutoff_time, address_note)
                    values (:id, :ws, :c, :n, :p, :src, :cut, :addr)
                    """).param("id", id).param("ws", a.workspaceId()).param("c", code).param("n", c.name().trim()).param("p", c.logisticsProviderCode())
                    .param("src", source).param("cut", c.cutoffTime()).param("addr", c.addressNote()).update();
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("FULFILLMENT_NODE_DUPLICATE", "같은 코드의 출고거점이 이미 있습니다.");
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "FULFILLMENT_NODE_CREATED", "FULFILLMENT_NODE", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).after(Map.of("code", code, "provider", c.logisticsProviderCode(), "source", source)).build());
        return get(id);
    }

    @Transactional
    public NodeView patch(UUID id, int expectedVersion, Patch p) {
        guard.requirePermission(Perm.LOGISTICS_ACCOUNT_MANAGE);
        CurrentActor a = guard.actor();
        NodeView before = lock(id);
        if (before.version() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, before.version());
        }
        if ("CLOSED".equals(before.status())) {
            throw Errors.conflict("NODE_CLOSED", "닫은 거점은 바꿀 수 없습니다.");
        }
        if (p.status() != null && !Set.of("ACTIVE", "SUSPENDED", "CLOSED").contains(p.status())) {
            throw Errors.validation("status", "상태 값이 올바르지 않습니다.");
        }
        if ("CLOSED".equals(p.status())) {
            int stock = jdbc.sql("select coalesce(sum(on_hand), 0) from inventory_stock where node_id = :id").param("id", id).query(Integer.class).single();
            int active = jdbc.sql("select count(*) from inventory_reservation where node_id = :id and state in ('RESERVED','ALLOCATED','PICKING')")
                    .param("id", id).query(Integer.class).single();
            if (stock > 0 || active > 0) {
                throw Errors.conflict("NODE_HAS_STOCK", "재고나 진행 중 확보가 남은 거점은 닫을 수 없습니다.", Map.of("on_hand", stock, "active_reservations", active));
            }
        }
        jdbc.sql("""
                update fulfillment_node set name = coalesce(:n, name), cutoff_time = coalesce(:cut, cutoff_time), address_note = coalesce(:addr, address_note),
                       status = coalesce(:st, status), version = version + 1, updated_at = now() where id = :id
                """).param("n", p.name()).param("cut", p.cutoffTime()).param("addr", p.addressNote()).param("st", p.status()).param("id", id).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "FULFILLMENT_NODE_UPDATED", "FULFILLMENT_NODE", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).before(Map.of("status", before.status())).after(Map.of("status", String.valueOf(p.status()))).build());
        return get(id);
    }

    /** 3PL·플랫폼 거점에 사업자의 화주계정을 연결(사업자당 1개, 물류사업자 일치). */
    @Transactional
    public NodeView linkShipper(UUID nodeId, UUID shipperAccountId) {
        guard.requirePermission(Perm.LOGISTICS_ACCOUNT_MANAGE);
        CurrentActor a = guard.actor();
        NodeView n = lock(nodeId);
        if ("SELF_WMS".equals(n.providerKind())) {
            throw Errors.unprocessable("NODE_SHIPPER_NOT_APPLICABLE", "자체 WMS 거점에는 화주계정을 연결하지 않습니다.");
        }
        Map<String, Object> s = jdbc.sql("select id, business_entity_id, logistics_provider_code, status from shipper_account where workspace_id = :ws and id = :id")
                .param("ws", a.workspaceId()).param("id", shipperAccountId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> Errors.notFound("SHIPPER_ACCOUNT"));
        UUID be = (UUID) s.get("business_entity_id");
        guard.checkWrite("SHIPPER_ACCOUNT", shipperAccountId, be, null);
        if (!n.logisticsProviderCode().equals(s.get("logistics_provider_code"))) {
            throw Errors.unprocessable("NODE_SHIPPER_PROVIDER_MISMATCH", "거점의 물류사업자와 화주계정의 물류사업자가 다릅니다.");
        }
        if (!"ACTIVE".equals(s.get("status"))) {
            throw Errors.unprocessable("SHIPPER_ACCOUNT_NOT_ACTIVE", "운영 중인 화주계정만 연결할 수 있습니다.");
        }
        try {
            jdbc.sql("insert into fulfillment_node_shipper (workspace_id, node_id, shipper_account_id, business_entity_id) values (:ws, :n, :s, :be)")
                    .param("ws", a.workspaceId()).param("n", nodeId).param("s", shipperAccountId).param("be", be).update();
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("NODE_SHIPPER_DUPLICATE", "이 사업자는 이미 이 거점에 화주계정이 연결되어 있습니다.");
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "FULFILLMENT_NODE_SHIPPER_LINKED", "FULFILLMENT_NODE", nodeId)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(be, null).after(Map.of("shipper_account_id", shipperAccountId.toString())).build());
        return get(nodeId);
    }

    public List<LocationView> locations(UUID nodeId) {
        get(nodeId);
        return jdbc.sql("select id, code, zone, status from node_location where workspace_id = :ws and node_id = :n order by code").param("ws", ws()).param("n", nodeId)
                .query((rs, i) -> new LocationView(Db.uuid(rs, "id"), rs.getString("code"), rs.getString("zone"), rs.getString("status"))).list();
    }

    @Transactional
    public LocationView addLocation(UUID nodeId, String code, String zone) {
        guard.requirePermission(Perm.LOGISTICS_ACCOUNT_MANAGE);
        CurrentActor a = guard.actor();
        NodeView n = get(nodeId);
        if (!"SELF_WMS".equals(n.providerKind())) {
            throw Errors.unprocessable("NODE_LOCATION_NOT_APPLICABLE", "로케이션은 자체 WMS 거점에서만 관리합니다(3PL 은 물류사가 관리).");
        }
        if (code == null || !code.trim().matches("^[A-Za-z0-9][A-Za-z0-9_.-]{0,39}$")) {
            throw Errors.validation("code", "로케이션 코드는 영문·숫자·점·하이픈·밑줄 40자 이내입니다.");
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("insert into node_location (id, workspace_id, node_id, code, zone) values (:id, :ws, :n, :c, :z)").param("id", id).param("ws", a.workspaceId())
                    .param("n", nodeId).param("c", code.trim()).param("z", zone == null || zone.isBlank() ? null : zone.trim()).update();
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("NODE_LOCATION_DUPLICATE", "같은 코드의 로케이션이 이미 있습니다.");
        }
        return locations(nodeId).stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow();
    }

    /** 사업자가 이 거점에 재고를 둘 수 있는지(자체 WMS 는 항상, 3PL·플랫폼은 화주계정 연결 필요). */
    boolean ownerAllowed(NodeView n, UUID businessEntityId) {
        return "SELF_WMS".equals(n.providerKind()) || n.shippers().stream().anyMatch(s -> s.businessEntityId().equals(businessEntityId));
    }

    private static final String SELECT = """
            select n.*, p.kind as provider_kind, p.name_ko as provider_name,
                   (select count(*) from node_location l where l.node_id = n.id) as location_count
              from fulfillment_node n join logistics_provider p on p.code = n.logistics_provider_code
            """;

    private NodeView lock(UUID id) {
        jdbc.sql("select id from fulfillment_node where workspace_id = :ws and id = :id for update").param("ws", ws()).param("id", id).query(UUID.class)
                .optional().orElseThrow(() -> Errors.notFound("FULFILLMENT_NODE"));
        return find(id).orElseThrow();
    }

    Optional<NodeView> find(UUID id) {
        return jdbc.sql(SELECT + " where n.workspace_id = :ws and n.id = :id").param("ws", ws()).param("id", id).query(this::map).optional().map(this::withParts);
    }

    private NodeView withParts(NodeView n) {
        var visible = guard.businessEntityFilter(AccessKind.READ, null);
        List<ShipperLink> links = jdbc.sql("""
                select l.shipper_account_id, s.display_name, l.business_entity_id, be.display_name as be_name from fulfillment_node_shipper l
                  join shipper_account s on s.id = l.shipper_account_id join business_entity be on be.id = l.business_entity_id
                 where l.node_id = :n order by be.display_name
                """).param("n", n.id()).query((rs, i) -> new ShipperLink(Db.uuid(rs, "shipper_account_id"), rs.getString("display_name"),
                Db.uuid(rs, "business_entity_id"), rs.getString("be_name"))).list()
                .stream().filter(l -> visible.contains(l.businessEntityId())).toList();
        return new NodeView(n.id(), n.code(), n.name(), n.logisticsProviderCode(), n.providerKind(), n.providerName(), n.inventorySource(), n.cutoffTime(),
                n.addressNote(), n.status(), links, n.locationCount(), n.version());
    }

    private NodeView map(ResultSet rs, int i) throws SQLException {
        java.sql.Time cut = rs.getTime("cutoff_time");
        return new NodeView(Db.uuid(rs, "id"), rs.getString("code"), rs.getString("name"), rs.getString("logistics_provider_code"), rs.getString("provider_kind"),
                rs.getString("provider_name"), rs.getString("inventory_source"), cut == null ? null : cut.toLocalTime(), rs.getString("address_note"),
                rs.getString("status"), List.of(), rs.getInt("location_count"), rs.getInt("version"));
    }

    private UUID ws() {
        return guard.actor().workspaceId();
    }
}
