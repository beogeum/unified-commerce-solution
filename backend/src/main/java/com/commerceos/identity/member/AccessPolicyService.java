package com.commerceos.identity.member;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.AccessScopeService;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.identity.access.PermissionLoader;
import com.commerceos.identity.access.ResolvedScope;
import com.commerceos.identity.access.ScopeDimension;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자별 접근정책(FR-ORG-05, docs/08 §2). PUT 은 전체 교체이며 생략된 (차원, 종류)는 기본값 ALL 로 돌아간다.
 */
@Service
public class AccessPolicyService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AccessScopeService scopes;
    private final PermissionLoader permissions;
    private final MemberGuards guards;
    private final AuditService audit;

    public AccessPolicyService(JdbcClient jdbc, AccessGuard guard, AccessScopeService scopes, PermissionLoader permissions,
                               MemberGuards guards, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.scopes = scopes;
        this.permissions = permissions;
        this.guards = guards;
        this.audit = audit;
    }

    public record Override(String permissionCode, String effect, String reason) {
    }

    public record ScopeEntry(UUID targetId, String effect) {
    }

    public record Scope(ScopeDimension dimension, AccessKind accessKind, String mode, Boolean autoIncludeNew, List<ScopeEntry> entries) {
    }

    public record PolicyInput(List<Override> permissionOverrides, List<Scope> scopes) {
    }

    public record Effective(List<String> permissions, Map<String, List<UUID>> businessEntities,
                            Map<String, List<UUID>> channelAccounts, Map<String, Boolean> unrestricted, Map<String, List<UUID>> fulfillmentNodes) {
    }

    public record PolicyView(UUID userId, UUID memberId, int accessPolicyVersion, List<Override> permissionOverrides,
                             List<Scope> scopes, Effective effective) {
    }

    record Member(UUID id, int policyVersion) {
    }

    public PolicyView get(UUID userId) {
        CurrentActor a = guard.actor();
        if (!a.userId().equals(userId)) {
            guard.requirePermission(Perm.ACCESS_MANAGE);
        }
        Member m = member(a.workspaceId(), userId, false);
        return view(a.workspaceId(), userId, m);
    }

    @Transactional
    public PolicyView replace(UUID userId, int expectedVersion, PolicyInput input) {
        CurrentActor a = guard.actor();
        guard.requirePermission(Perm.ACCESS_MANAGE);
        Member m = member(a.workspaceId(), userId, true);
        if (m.policyVersion() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, m.policyVersion());
        }
        PolicyView before = view(a.workspaceId(), userId, m);
        List<Override> overrides = input.permissionOverrides() == null ? List.of() : input.permissionOverrides();
        List<Scope> scopeInputs = input.scopes() == null ? List.of() : input.scopes();
        validate(a, overrides, scopeInputs);

        jdbc.sql("delete from member_permission_override where member_id = :m").param("m", m.id()).update();
        jdbc.sql("delete from member_scope_entry where member_id = :m").param("m", m.id()).update();
        jdbc.sql("delete from member_scope_setting where member_id = :m").param("m", m.id()).update();
        for (Override o : overrides) {
            jdbc.sql("""
                    insert into member_permission_override (workspace_id, member_id, permission_code, effect, reason, created_by)
                    values (:ws, :m, :p, :e, :r, :by)
                    """).param("ws", a.workspaceId()).param("m", m.id()).param("p", o.permissionCode()).param("e", o.effect())
                    .param("r", o.reason()).param("by", a.memberId()).update();
        }
        for (Scope s : scopeInputs) {
            jdbc.sql("""
                    insert into member_scope_setting (workspace_id, member_id, dimension, access_kind, mode, auto_include_new)
                    values (:ws, :m, :d, :k, :mode, :auto)
                    """).param("ws", a.workspaceId()).param("m", m.id()).param("d", s.dimension().name())
                    .param("k", s.accessKind().name()).param("mode", s.mode())
                    .param("auto", s.autoIncludeNew() == null || s.autoIncludeNew()).update();
            for (ScopeEntry e : s.entries() == null ? List.<ScopeEntry>of() : s.entries()) {
                jdbc.sql("""
                        insert into member_scope_entry (workspace_id, member_id, dimension, access_kind, target_id, effect, created_by)
                        values (:ws, :m, :d, :k, :t, :e, :by)
                        """).param("ws", a.workspaceId()).param("m", m.id()).param("d", s.dimension().name())
                        .param("k", s.accessKind().name()).param("t", e.targetId()).param("e", e.effect()).param("by", a.memberId())
                        .update();
            }
        }
        jdbc.sql("update workspace_member set access_policy_version = access_policy_version + 1, updated_at = now() where id = :m")
                .param("m", m.id()).update();
        guards.requireActiveSuperAdminRemains(a.workspaceId());
        guard.invalidateScope();
        Member updated = member(a.workspaceId(), userId, false);
        PolicyView after = view(a.workspaceId(), userId, updated);
        audit.record(AuditEntry.builder(a.workspaceId(), "ACCESS_POLICY_REPLACED", "USER", userId)
                .actor(ActorType.USER, a.memberId(), a.displayName())
                .before(snapshot(before)).after(snapshot(after)).build());
        return after;
    }

    private void validate(CurrentActor a, List<Override> overrides, List<Scope> scopeInputs) {
        Set<String> known = new HashSet<>(jdbc.sql("select code from permission").query(String.class).list());
        Set<String> seenPerm = new HashSet<>();
        for (Override o : overrides) {
            if (o.permissionCode() == null || !known.contains(o.permissionCode())) {
                throw Errors.validation("permission_overrides", "존재하지 않는 권한입니다: " + o.permissionCode());
            }
            if (!"ALLOW".equals(o.effect()) && !"DENY".equals(o.effect())) {
                throw Errors.validation("permission_overrides", "effect 는 ALLOW 또는 DENY 여야 합니다.");
            }
            if (!seenPerm.add(o.permissionCode())) {
                throw Errors.validation("permission_overrides", "같은 권한이 중복되었습니다: " + o.permissionCode());
            }
            if ("ALLOW".equals(o.effect()) && !a.isSuperAdmin() && !a.has(o.permissionCode())) {
                throw Errors.unprocessable("PRIVILEGE_ESCALATION", "본인에게 없는 권한은 부여할 수 없습니다.",
                        Map.of("permission_code", o.permissionCode()));
            }
        }
        ResolvedScope actorScope = guard.scope();
        Set<String> seenScope = new HashSet<>();
        for (Scope s : scopeInputs) {
            if (s.dimension() == null || s.accessKind() == null) {
                throw Errors.validation("scopes", "dimension 과 access_kind 는 필수입니다.");
            }
            if (!seenScope.add(s.dimension() + "/" + s.accessKind())) {
                throw Errors.validation("scopes", "같은 차원·종류가 중복되었습니다: " + s.dimension() + "/" + s.accessKind());
            }
            if (!"ALL".equals(s.mode()) && !"SELECTED".equals(s.mode())) {
                throw Errors.validation("scopes", "mode 는 ALL 또는 SELECTED 여야 합니다.");
            }
            boolean actorUnrestricted = switch (s.dimension()) {
                case BUSINESS_ENTITY -> actorScope.businessEntityUnrestricted().get(s.accessKind());
                case CHANNEL_ACCOUNT -> actorScope.channelAccountUnrestricted().get(s.accessKind());
                case FULFILLMENT_NODE -> actorScope.fulfillmentNodeUnrestricted().get(s.accessKind());
            };
            if ("ALL".equals(s.mode()) && !a.isSuperAdmin() && !actorUnrestricted) {
                throw Errors.unprocessable("PRIVILEGE_ESCALATION", "본인 범위가 제한된 사용자는 '전체' 범위를 부여할 수 없습니다.");
            }
            Set<UUID> seenTarget = new HashSet<>();
            for (ScopeEntry e : s.entries() == null ? List.<ScopeEntry>of() : s.entries()) {
                if (e.targetId() == null || !seenTarget.add(e.targetId())) {
                    throw Errors.validation("scopes", "대상이 비었거나 중복되었습니다.");
                }
                if ("ALLOW".equals(e.effect())) {
                    if ("ALL".equals(s.mode())) {
                        throw Errors.validation("scopes", "'전체' 범위에는 허용 목록을 지정할 수 없습니다. 제외할 대상은 DENY 로 지정하세요.");
                    }
                } else if (!"DENY".equals(e.effect())) {
                    throw Errors.validation("scopes", "effect 는 ALLOW 또는 DENY 여야 합니다.");
                }
                String table = switch (s.dimension()) {
                    case BUSINESS_ENTITY -> "business_entity";
                    case CHANNEL_ACCOUNT -> "channel_account";
                    case FULFILLMENT_NODE -> "fulfillment_node";
                };
                int exists = jdbc.sql("select count(*) from " + table + " where workspace_id = :ws and id = :id")
                        .param("ws", a.workspaceId()).param("id", e.targetId()).query(Integer.class).single();
                if (exists == 0) {
                    throw Errors.validation("scopes", "대상을 찾을 수 없습니다: " + e.targetId());
                }
                Set<UUID> actorAllowed = switch (s.dimension()) {
                    case BUSINESS_ENTITY -> actorScope.businessEntityIds(s.accessKind());
                    case CHANNEL_ACCOUNT -> actorScope.channelAccountIds(s.accessKind());
                    case FULFILLMENT_NODE -> actorScope.fulfillmentNodeIds(s.accessKind());
                };
                if ("ALLOW".equals(e.effect()) && !a.isSuperAdmin() && !actorAllowed.contains(e.targetId())) {
                    throw Errors.unprocessable("PRIVILEGE_ESCALATION", "본인 범위 밖의 대상은 허용할 수 없습니다.",
                            Map.of("target_id", e.targetId().toString()));
                }
            }
        }
    }

    private Member member(UUID ws, UUID userId, boolean lock) {
        return jdbc.sql("select id, access_policy_version from workspace_member where workspace_id = :ws and user_id = :u"
                        + (lock ? " for update" : ""))
                .param("ws", ws).param("u", userId)
                .query((rs, i) -> new Member(Db.uuid(rs, "id"), rs.getInt("access_policy_version"))).optional()
                .orElseThrow(() -> Errors.notFound("USER"));
    }

    private PolicyView view(UUID ws, UUID userId, Member m) {
        List<Override> overrides = jdbc.sql("""
                select permission_code, effect, reason from member_permission_override where member_id = :m order by permission_code
                """).param("m", m.id()).query((rs, i) -> new Override(rs.getString(1), rs.getString(2), rs.getString(3))).list();
        record SettingRow(String d, String k, String mode, boolean auto) {
        }
        List<SettingRow> settings = jdbc.sql("""
                select dimension, access_kind, mode, auto_include_new from member_scope_setting where member_id = :m
                """).param("m", m.id()).query((rs, i) -> new SettingRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4))).list();
        record EntryRow(String d, String k, UUID t, String e) {
        }
        List<EntryRow> entries = jdbc.sql("""
                select dimension, access_kind, target_id, effect from member_scope_entry where member_id = :m order by target_id
                """).param("m", m.id()).query((rs, i) -> new EntryRow(rs.getString(1), rs.getString(2), Db.uuid(rs, "target_id"), rs.getString(4))).list();
        Map<String, Scope> byKey = new LinkedHashMap<>();
        for (SettingRow s : settings) {
            byKey.put(s.d() + "/" + s.k(), new Scope(ScopeDimension.valueOf(s.d()), AccessKind.valueOf(s.k()), s.mode(), s.auto(), new ArrayList<>()));
        }
        for (EntryRow e : entries) {
            Scope s = byKey.computeIfAbsent(e.d() + "/" + e.k(),
                    key -> new Scope(ScopeDimension.valueOf(e.d()), AccessKind.valueOf(e.k()), "ALL", true, new ArrayList<>()));
            s.entries().add(new ScopeEntry(e.t(), e.e()));
        }
        List<Scope> scopeList = byKey.values().stream()
                .sorted(Comparator.comparing((Scope s) -> s.dimension().ordinal()).thenComparing(s -> s.accessKind().ordinal()))
                .toList();
        ResolvedScope resolved = scopes.resolve(ws, m.id());
        Map<String, List<UUID>> be = new LinkedHashMap<>();
        Map<String, List<UUID>> ca = new LinkedHashMap<>();
        Map<String, List<UUID>> nodes = new LinkedHashMap<>();
        Map<String, Boolean> open = new LinkedHashMap<>();
        for (AccessKind k : AccessKind.values()) {
            nodes.put(k.name(), resolved.fulfillmentNodeIds(k).stream().sorted().toList());
            be.put(k.name(), resolved.businessEntityIds(k).stream().sorted().toList());
            ca.put(k.name(), resolved.channelAccountIds(k).stream().sorted().toList());
            open.put(k.name(), resolved.unrestricted(k));
        }
        List<String> perms = permissions.effectivePermissions(ws, m.id()).stream().sorted().toList();
        return new PolicyView(userId, m.id(), m.policyVersion(), overrides, scopeList, new Effective(perms, be, ca, open, nodes));
    }

    private static Map<String, Object> snapshot(PolicyView v) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("version", v.accessPolicyVersion());
        s.put("permission_overrides", v.permissionOverrides().stream().map(o -> o.permissionCode() + ":" + o.effect()).toList());
        s.put("scopes", v.scopes().stream().map(x -> Map.of("dimension", x.dimension().name(), "access_kind", x.accessKind().name(),
                "mode", x.mode(), "entries", x.entries().stream().map(e -> e.targetId() + ":" + e.effect()).toList())).toList());
        return s;
    }
}
