package com.commerceos.identity.member;

import com.commerceos.identity.access.Perm;
import com.commerceos.identity.access.SystemRoles;
import com.commerceos.shared.error.Errors;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 잠김 방지: 활성 최고관리자(접근관리 권한이 DENY 되지 않은)가 최소 1명 남아야 한다(DEC-F04). */
@Component
public class MemberGuards {
    private final JdbcClient jdbc;

    public MemberGuards(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 변경 적용 후(같은 트랜잭션) 호출한다. */
    public void requireActiveSuperAdminRemains(UUID workspaceId) {
        int n = jdbc.sql("""
                select count(*) from workspace_member m
                  join member_role mr on mr.member_id = m.id
                  join role r on r.id = mr.role_id and r.code = :superAdmin
                 where m.workspace_id = :ws and m.status = 'ACTIVE'
                   and not exists (select 1 from member_permission_override o
                                    where o.member_id = m.id and o.permission_code = :perm and o.effect = 'DENY')
                """).param("superAdmin", SystemRoles.SUPER_ADMIN).param("ws", workspaceId).param("perm", Perm.ACCESS_MANAGE)
                .query(Integer.class).single();
        if (n == 0) {
            throw Errors.conflict("LAST_SUPER_ADMIN", "활성 최고관리자가 최소 1명 있어야 합니다. 다른 최고관리자를 먼저 지정하세요.");
        }
    }
}
