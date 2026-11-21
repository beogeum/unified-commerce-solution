package com.commerceos.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerceos.shared.db.TenantContext;
import com.commerceos.shared.id.Ids;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * F8: 애플리케이션 범위 검증을 우회한 SQL 이라도 DB 가 다른 운영그룹 행을 막는다.
 * 여기서 {@code app} 은 애플리케이션이 쓰는 JdbcClient(비소유 역할 + RLS 문맥)이다.
 */
class RowLevelSecurityTest extends IntegrationTest {
    @Autowired
    JdbcClient app;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    int count(String sql, UUID id) {
        return app.sql(sql).param("id", id).query(Integer.class).single();
    }

    @Test
    void everyWorkspaceTable_hasRlsPolicy() {
        List<String> missing = jdbc.sql("""
                select c.table_name from information_schema.columns c
                  join pg_class pc on pc.relname = c.table_name and pc.relkind = 'r'
                  join pg_namespace n on n.oid = pc.relnamespace and n.nspname = 'public'
                 where c.table_schema = 'public' and c.column_name = 'workspace_id'
                   and (not pc.relrowsecurity or not exists (select 1 from pg_policies p where p.tablename = c.table_name))
                """).query(String.class).list();
        assertThat(missing).as("RLS 가 없는 운영그룹 테이블").isEmpty();
        assertThat(jdbc.sql("select relrowsecurity from pg_class where relname = 'workspace'").query(Boolean.class).single()).isTrue();
    }

    @Test
    void appConnections_runAsNonOwnerRole_andResetOnReturn() {
        TenantContext.setWorkspace(UUID.randomUUID());
        assertThat(app.sql("select current_user").query(String.class).single()).isEqualTo("commerce_app");
        TenantContext.clear();
        assertThat(jdbc.sql("select current_user").query(String.class).single()).isNotEqualTo("commerce_app");
        assertThat(jdbc.sql("select current_setting('app.workspace_id', true)").query(String.class).single()).isIn("", null);
    }

    @Test
    void otherWorkspaceRows_invisibleAndUnwritable_evenWithExplicitIds() {
        Fixtures.Ws a = fx.workspace();
        Fixtures.Ws b = fx.workspace();
        UUID beA = fx.businessEntity(a.owner(), "A 코스메틱");
        UUID beB = fx.businessEntity(b.owner(), "B 리빙");

        TenantContext.setWorkspace(a.id());
        assertThat(count("select count(*) from business_entity where id = :id", beA)).isEqualTo(1);
        assertThat(count("select count(*) from business_entity where id = :id", beB)).isZero();
        assertThat(count("select count(*) from workspace where id = :id", b.id())).isZero();
        assertThat(app.sql("update business_entity set display_name = '침범' where id = :id").param("id", beB).update()).isZero();
        assertThatThrownBy(() -> app.sql("""
                insert into work_item (id, workspace_id, item_type, severity, title_ko, cause_ko, recommended_action_ko, dedupe_key,
                                       first_occurred_at, last_occurred_at)
                values (:id, :ws, 'TEST', 'LOW', 't', 'c', 'a', :key, now(), now())
                """).param("id", Ids.newId()).param("ws", b.id()).param("key", "rls-" + UUID.randomUUID()).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");

        TenantContext.clear();
        assertThat(count("select count(*) from business_entity where id = :id", beA)).as("문맥이 없으면 기본 거부").isZero();

        int both = TenantContext.asSystem(() -> app.sql("select count(*) from business_entity where id in (:a, :b)")
                .param("a", beA).param("b", beB).query(Integer.class).single());
        assertThat(both).isEqualTo(2);
        assertThat(jdbc.sql("select display_name from business_entity where id = :id").param("id", beB).query(String.class).single())
                .isEqualTo("B 리빙");
    }
}
