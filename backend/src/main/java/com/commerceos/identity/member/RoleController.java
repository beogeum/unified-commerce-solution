package com.commerceos.identity.member;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.identity.access.ResolvedScope;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.web.ApiResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RoleController {
    private final JdbcClient jdbc;
    private final AccessGuard guard;

    public RoleController(JdbcClient jdbc, AccessGuard guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    public record RoleView(UUID id, String code, String nameKo, boolean isSystem, List<String> permissions) {
    }

    public record PermissionView(String code, String category, String nameKo, boolean sensitive) {
    }

    public record MeView(UUID userId, UUID memberId, UUID workspaceId, String displayName, List<String> roleCodes,
                         List<String> permissions, int accessPolicyVersion, Map<String, Object> scope) {
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleView>> roles() {
        CurrentActor a = guard.actor();
        if (!a.has(Perm.ACCESS_MANAGE)) {
            guard.requirePermission(Perm.MEMBER_READ);
        }
        return ApiResponse.of(jdbc.sql("select id, code, name_ko, is_system from role where workspace_id = :ws order by is_system desc, code")
                .param("ws", a.workspaceId())
                .query((rs, i) -> new RoleView(Db.uuid(rs, "id"), rs.getString("code"), rs.getString("name_ko"), rs.getBoolean("is_system"),
                        jdbc.sql("select permission_code from role_permission where role_id = :r order by 1")
                                .param("r", rs.getObject("id", UUID.class)).query(String.class).list()))
                .list());
    }

    @GetMapping("/permissions")
    public ApiResponse<List<PermissionView>> permissions() {
        guard.actor();
        return ApiResponse.of(jdbc.sql("select code, category, name_ko, sensitive from permission order by category, code")
                .query((rs, i) -> new PermissionView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4))).list());
    }

    /** 화면 메뉴 노출·사업자 필터 구성을 위한 내 권한·범위 요약. */
    @GetMapping("/me")
    public ApiResponse<MeView> me() {
        CurrentActor a = guard.actor();
        ResolvedScope s = guard.scope();
        Map<String, Object> scope = new LinkedHashMap<>();
        for (AccessKind k : AccessKind.values()) {
            scope.put(k.name().toLowerCase(), Map.of(
                    "unrestricted", s.unrestricted(k),
                    "business_entity_ids", s.businessEntityIds(k).stream().sorted().toList(),
                    "channel_account_count", s.channelAccountIds(k).size()));
        }
        return ApiResponse.of(new MeView(a.userId(), a.memberId(), a.workspaceId(), a.displayName(),
                a.roleCodes().stream().sorted().toList(), a.permissions().stream().sorted().toList(), a.accessPolicyVersion(), scope));
    }
}
