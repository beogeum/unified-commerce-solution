package com.commerceos.identity.access;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새 사업자/판매계정 생성 시 범위 자동 포함(DEC-F03, DEC-F05).
 * mode=ALL 은 자동 포함되므로 할 일이 없고, mode=SELECTED 이면서 auto_include_new=true 인 구성원과
 * 생성자 본인(SELECTED 인 경우)에게 ALLOW 항목을 같은 트랜잭션에서 추가한다.
 */
@Component
public class ScopeAutoInclusion {
    private final JdbcClient jdbc;

    public ScopeAutoInclusion(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 자동 포함된 (구성원, 접근종류) 행 수 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int onTargetCreated(UUID workspaceId, ScopeDimension dimension, UUID targetId, UUID creatorMemberId) {
        List<Object[]> rows = jdbc.sql("""
                select member_id, access_kind from member_scope_setting
                 where workspace_id = :ws and dimension = :d and mode = 'SELECTED'
                   and (auto_include_new or member_id = :creator)
                """).param("ws", workspaceId).param("d", dimension.name()).param("creator", creatorMemberId)
                .query((rs, i) -> new Object[]{rs.getObject("member_id", UUID.class), rs.getString("access_kind")}).list();
        int n = 0;
        for (Object[] r : rows) {
            n += jdbc.sql("""
                    insert into member_scope_entry (workspace_id, member_id, dimension, access_kind, target_id, effect, created_by)
                    values (:ws, :m, :d, :k, :t, 'ALLOW', :creator) on conflict do nothing
                    """).param("ws", workspaceId).param("m", r[0]).param("d", dimension.name()).param("k", r[1])
                    .param("t", targetId).param("creator", creatorMemberId).update();
            jdbc.sql("update workspace_member set access_policy_version = access_policy_version + 1 where id = :m")
                    .param("m", r[0]).update();
        }
        return n;
    }
}
