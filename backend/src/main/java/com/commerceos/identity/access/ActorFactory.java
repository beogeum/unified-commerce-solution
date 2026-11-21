package com.commerceos.identity.access;

import com.commerceos.shared.db.Db;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 백그라운드 작업이 요청자 권한으로 실행할 때 쓸 주체(실행 시점의 권한·역할을 다시 읽는다). 정지된 구성원이면 없음. */
@Component
public class ActorFactory {
    private final JdbcClient jdbc;
    private final PermissionLoader permissions;

    public ActorFactory(JdbcClient jdbc, PermissionLoader permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    public Optional<CurrentActor> forMember(UUID workspaceId, UUID memberId) {
        record Row(UUID userId, String name, int policyVersion) {
        }
        return jdbc.sql("""
                select m.user_id, u.display_name, m.access_policy_version from workspace_member m join user_account u on u.id = m.user_id
                 where m.workspace_id = :ws and m.id = :m and m.status = 'ACTIVE' and u.status = 'ACTIVE'
                """).param("ws", workspaceId).param("m", memberId)
                .query((rs, i) -> new Row(Db.uuid(rs, "user_id"), rs.getString("display_name"), rs.getInt("access_policy_version")))
                .optional()
                .map(r -> new CurrentActor(workspaceId, memberId, r.userId(), r.name(), permissions.effectivePermissions(workspaceId, memberId),
                        permissions.roleCodes(workspaceId, memberId), r.policyVersion(), null, "NOT_REQUIRED"));
    }
}
