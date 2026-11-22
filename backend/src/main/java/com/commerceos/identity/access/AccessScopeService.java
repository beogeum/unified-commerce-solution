package com.commerceos.identity.access;

import com.commerceos.shared.db.Db;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 접근 범위 엔진(docs/08 §2, DEC-F02/F03).
 * <pre>
 * allow(D,K)    = mode(D,K)=ALL ? 전체 대상 : ALLOW 항목(D,K)
 * deny(D,K)     = DENY 항목(D,K) ∪ DENY 항목(D,READ)       -- READ 거부는 모든 종류를 막는다
 * eff(D,READ)   = allow(D,READ) − deny(D,READ)
 * eff(D,K≠READ) = allow(D,K) ∩ eff(D,READ) − deny(D,K)
 * </pre>
 * 설정 행이 없으면 mode=ALL(기본 조회 허용, CONF-003).
 */
@Service
public class AccessScopeService {
    private final JdbcClient jdbc;

    public AccessScopeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Setting(ScopeDimension dimension, AccessKind kind, boolean selected) {
    }

    record Entry(ScopeDimension dimension, AccessKind kind, UUID targetId, boolean deny) {
    }

    public ResolvedScope resolve(UUID workspaceId, UUID memberId) {
        Map<String, Boolean> selected = new HashMap<>();
        jdbc.sql("select dimension, access_kind, mode from member_scope_setting where workspace_id = :ws and member_id = :m")
                .param("ws", workspaceId).param("m", memberId)
                .query((rs, i) -> new Setting(ScopeDimension.valueOf(rs.getString("dimension")),
                        AccessKind.valueOf(rs.getString("access_kind")), "SELECTED".equals(rs.getString("mode"))))
                .list()
                .forEach(s -> selected.put(s.dimension() + "/" + s.kind(), s.selected()));
        List<Entry> entries = jdbc.sql("""
                select dimension, access_kind, target_id, effect from member_scope_entry
                 where workspace_id = :ws and member_id = :m
                """)
                .param("ws", workspaceId).param("m", memberId)
                .query((rs, i) -> new Entry(ScopeDimension.valueOf(rs.getString("dimension")),
                        AccessKind.valueOf(rs.getString("access_kind")), Db.uuid(rs, "target_id"), "DENY".equals(rs.getString("effect"))))
                .list();
        Set<UUID> allBe = new HashSet<>(jdbc.sql("select id from business_entity where workspace_id = :ws")
                .param("ws", workspaceId).query(UUID.class).list());
        Set<UUID> allCa = new HashSet<>(jdbc.sql("select id from channel_account where workspace_id = :ws")
                .param("ws", workspaceId).query(UUID.class).list());
        Set<UUID> allNode = new HashSet<>(jdbc.sql("select id from fulfillment_node where workspace_id = :ws")
                .param("ws", workspaceId).query(UUID.class).list());

        Map<AccessKind, Set<UUID>> be = new EnumMap<>(AccessKind.class);
        Map<AccessKind, Set<UUID>> ca = new EnumMap<>(AccessKind.class);
        Map<AccessKind, Boolean> beOpen = new EnumMap<>(AccessKind.class);
        Map<AccessKind, Boolean> caOpen = new EnumMap<>(AccessKind.class);
        Map<AccessKind, Set<UUID>> node = new EnumMap<>(AccessKind.class);
        Map<AccessKind, Boolean> nodeOpen = new EnumMap<>(AccessKind.class);
        for (AccessKind k : AccessKind.values()) {
            node.put(k, effective(ScopeDimension.FULFILLMENT_NODE, k, allNode, selected, entries, node.get(AccessKind.READ)));
            nodeOpen.put(k, unrestricted(ScopeDimension.FULFILLMENT_NODE, k, selected, entries));
            be.put(k, effective(ScopeDimension.BUSINESS_ENTITY, k, allBe, selected, entries, be.get(AccessKind.READ)));
            ca.put(k, effective(ScopeDimension.CHANNEL_ACCOUNT, k, allCa, selected, entries, ca.get(AccessKind.READ)));
            beOpen.put(k, unrestricted(ScopeDimension.BUSINESS_ENTITY, k, selected, entries));
            caOpen.put(k, unrestricted(ScopeDimension.CHANNEL_ACCOUNT, k, selected, entries));
        }
        return new ResolvedScope(Map.copyOf(be), Map.copyOf(ca), Map.copyOf(beOpen), Map.copyOf(caOpen), Map.copyOf(node), Map.copyOf(nodeOpen));
    }

    private static Set<UUID> effective(ScopeDimension d, AccessKind k, Set<UUID> all, Map<String, Boolean> selected,
                                       List<Entry> entries, Set<UUID> effectiveRead) {
        boolean isSelected = selected.getOrDefault(d + "/" + k, false);
        Set<UUID> result = new HashSet<>();
        if (isSelected) {
            for (Entry e : entries) {
                if (e.dimension() == d && e.kind() == k && !e.deny() && all.contains(e.targetId())) {
                    result.add(e.targetId());
                }
            }
        } else {
            result.addAll(all);
        }
        for (Entry e : entries) {
            if (e.dimension() == d && e.deny() && (e.kind() == k || e.kind() == AccessKind.READ)) {
                result.remove(e.targetId());
            }
        }
        if (k != AccessKind.READ) {
            result.retainAll(effectiveRead);
        }
        return Set.copyOf(result);
    }

    private static boolean unrestricted(ScopeDimension d, AccessKind k, Map<String, Boolean> selected, List<Entry> entries) {
        if (selected.getOrDefault(d + "/" + k, false) || selected.getOrDefault(d + "/" + AccessKind.READ, false)) {
            return false;
        }
        return entries.stream().noneMatch(e -> e.dimension() == d && e.deny()
                && (e.kind() == k || e.kind() == AccessKind.READ));
    }
}
