package com.commerceos.channel;

import com.commerceos.identity.access.AccessGuard;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 판매단위(FR-ORG-03). 외부 단위키(shop_no 등)는 계정 안에서 유일하고 생성 후 변경 불가. */
@Service
public class SalesUnitService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final ChannelAccountService accounts;

    public SalesUnitService(JdbcClient jdbc, AccessGuard guard, AuditService audit, ChannelAccountService accounts) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.accounts = accounts;
    }

    public record SalesUnitView(UUID id, UUID channelAccountId, UUID businessEntityId, String externalUnitKey, String displayName,
                                boolean isDefault, String status, int version, Instant createdAt) {
    }

    public record Create(String externalUnitKey, String displayName) {
    }

    public record Patch(String displayName, String status) {
    }

    public List<SalesUnitView> list(UUID channelAccountId) {
        ChannelAccountService.ChannelAccountView ca = accounts.get(channelAccountId);
        return jdbc.sql("select * from sales_unit where workspace_id = :ws and channel_account_id = :ca order by is_default desc, created_at")
                .param("ws", guard.actor().workspaceId()).param("ca", channelAccountId)
                .query((rs, i) -> map(rs, ca.businessEntityId())).list();
    }

    @Transactional
    public SalesUnitView create(UUID channelAccountId, Create c) {
        guard.requirePermission(Perm.CHANNEL_ACCOUNT_MANAGE);
        CurrentActor a = guard.actor();
        ChannelAccountService.ChannelAccountView ca = accounts.get(channelAccountId);
        guard.checkWrite("CHANNEL_ACCOUNT", channelAccountId, ca.businessEntityId(), channelAccountId);
        if ("CLOSED".equals(ca.status())) {
            throw Errors.conflict("CHANNEL_ACCOUNT_CLOSED", "종료된 판매계정에는 판매단위를 추가할 수 없습니다.");
        }
        boolean supportsSubUnits = jdbc.sql("select supports_sub_units from sales_channel where code = :c")
                .param("c", ca.salesChannelCode()).query(Boolean.class).single();
        if (!supportsSubUnits) {
            throw Errors.unprocessable("SUB_UNITS_NOT_SUPPORTED",
                    "이 판매채널은 하위 판매단위(멀티쇼핑몰)를 사용하지 않습니다. 기본 판매단위만 사용할 수 있습니다.");
        }
        String key = ChannelAccountService.blankToNull(c.externalUnitKey());
        if (key == null || key.length() > 100) {
            throw Errors.validation("external_unit_key", "외부 판매단위 키(예: shop_no)를 1~100자로 입력하세요.");
        }
        if (jdbc.sql("select count(*) from sales_unit where channel_account_id = :ca and external_unit_key = :k")
                .param("ca", channelAccountId).param("k", key).query(Integer.class).single() > 0) {
            throw Errors.conflict("SALES_UNIT_DUPLICATE", "같은 외부 판매단위 키가 이미 등록되어 있습니다.");
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into sales_unit (id, workspace_id, channel_account_id, external_unit_key, display_name, is_default)
                values (:id, :ws, :ca, :k, :n, false)
                """).param("id", id).param("ws", a.workspaceId()).param("ca", channelAccountId).param("k", key)
                .param("n", c.displayName() == null || c.displayName().isBlank() ? key : c.displayName().trim()).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "SALES_UNIT_CREATED", "SALES_UNIT", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(ca.businessEntityId(), channelAccountId)
                .after(Map.of("external_unit_key", key)).build());
        return one(id, ca.businessEntityId());
    }

    @Transactional
    public SalesUnitView patch(UUID channelAccountId, UUID unitId, int expectedVersion, Patch p) {
        guard.requirePermission(Perm.CHANNEL_ACCOUNT_MANAGE);
        CurrentActor a = guard.actor();
        ChannelAccountService.ChannelAccountView ca = accounts.get(channelAccountId);
        guard.checkWrite("CHANNEL_ACCOUNT", channelAccountId, ca.businessEntityId(), channelAccountId);
        SalesUnitView before = jdbc.sql("select * from sales_unit where workspace_id = :ws and channel_account_id = :ca and id = :id for update")
                .param("ws", a.workspaceId()).param("ca", channelAccountId).param("id", unitId)
                .query((rs, i) -> map(rs, ca.businessEntityId())).optional().orElseThrow(() -> Errors.notFound("SALES_UNIT"));
        if (before.version() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, before.version());
        }
        if (p.status() != null) {
            if (!Set.of("ACTIVE", "SUSPENDED", "CLOSED").contains(p.status())) {
                throw Errors.validation("status", "status 는 ACTIVE, SUSPENDED, CLOSED 중 하나여야 합니다.");
            }
            if (before.isDefault() && !"ACTIVE".equals(p.status()) && !"CLOSED".equals(ca.status())) {
                throw Errors.conflict("DEFAULT_SALES_UNIT", "기본 판매단위는 판매계정이 운영 중인 동안 중지할 수 없습니다.");
            }
        }
        jdbc.sql("""
                update sales_unit set display_name = coalesce(:n, display_name), status = coalesce(:s, status),
                       version = version + 1, updated_at = now() where id = :id
                """).param("n", ChannelAccountService.blankToNull(p.displayName())).param("s", p.status()).param("id", unitId).update();
        SalesUnitView after = one(unitId, ca.businessEntityId());
        audit.record(AuditEntry.builder(a.workspaceId(), "SALES_UNIT_UPDATED", "SALES_UNIT", unitId)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(ca.businessEntityId(), channelAccountId)
                .before(Map.of("display_name", before.displayName(), "status", before.status()))
                .after(Map.of("display_name", after.displayName(), "status", after.status())).build());
        return after;
    }

    private SalesUnitView one(UUID id, UUID be) {
        return jdbc.sql("select * from sales_unit where id = :id").param("id", id).query((rs, i) -> map(rs, be)).single();
    }

    private SalesUnitView map(ResultSet rs, UUID be) throws SQLException {
        return new SalesUnitView(Db.uuid(rs, "id"), Db.uuid(rs, "channel_account_id"), be, rs.getString("external_unit_key"),
                rs.getString("display_name"), rs.getBoolean("is_default"), rs.getString("status"), rs.getInt("version"),
                Db.instant(rs, "created_at"));
    }
}
