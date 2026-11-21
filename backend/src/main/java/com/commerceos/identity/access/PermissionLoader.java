package com.commerceos.identity.access;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 유효 권한 = 역할 권한 ∪ 사용자 ALLOW − 사용자 DENY (명시 DENY 우선, docs/08 §2-4). */
@Component
public class PermissionLoader {
    private final JdbcClient jdbc;

    public PermissionLoader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> effectivePermissions(UUID workspaceId, UUID memberId) {
        return new HashSet<>(jdbc.sql("""
                (select rp.permission_code from member_role mr
                   join role_permission rp on rp.role_id = mr.role_id and rp.workspace_id = mr.workspace_id
                  where mr.workspace_id = :ws and mr.member_id = :m
                 union
                 select permission_code from member_permission_override
                  where workspace_id = :ws and member_id = :m and effect = 'ALLOW')
                except
                select permission_code from member_permission_override
                 where workspace_id = :ws and member_id = :m and effect = 'DENY'
                """).param("ws", workspaceId).param("m", memberId).query(String.class).list());
    }

    public Set<String> roleCodes(UUID workspaceId, UUID memberId) {
        return new HashSet<>(jdbc.sql("""
                select r.code from member_role mr join role r on r.id = mr.role_id
                 where mr.workspace_id = :ws and mr.member_id = :m
                """).param("ws", workspaceId).param("m", memberId).query(String.class).list());
    }
}
