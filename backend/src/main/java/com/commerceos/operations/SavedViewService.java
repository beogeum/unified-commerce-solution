package com.commerceos.operations;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 사용자별 저장된 보기(docs/01 §2). 본인 것만 조회·변경. 필터 값은 표시 설정일 뿐 권한 범위를 넓히지 않는다. */
@Service
public class SavedViewService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final Json json;

    public SavedViewService(JdbcClient jdbc, AccessGuard guard, Json json) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.json = json;
    }

    public record SavedView(UUID id, String screenKey, String name, JsonNode filters, JsonNode columns, JsonNode sort, boolean isDefault,
                            int version, Instant updatedAt) {
    }

    public record Upsert(String screenKey, String name, JsonNode filters, JsonNode columns, JsonNode sort, Boolean isDefault) {
    }

    public List<SavedView> list(String screenKey) {
        CurrentActor a = guard.actor();
        return jdbc.sql("""
                select * from saved_view where workspace_id = :ws and member_id = :m and (cast(:s as text) is null or screen_key = :s)
                 order by screen_key, name
                """).param("ws", a.workspaceId()).param("m", a.memberId()).param("s", screenKey).query(this::map).list();
    }

    @Transactional
    public SavedView create(Upsert u) {
        CurrentActor a = guard.actor();
        if (u.screenKey() == null || !u.screenKey().matches("^[a-z0-9_.-]{1,100}$")) {
            throw Errors.validation("screen_key", "screen_key 형식이 올바르지 않습니다.");
        }
        if (u.name() == null || u.name().isBlank() || u.name().length() > 100) {
            throw Errors.validation("name", "보기 이름을 1~100자로 입력하세요.");
        }
        if (jdbc.sql("select count(*) from saved_view where member_id = :m and screen_key = :s and name = :n")
                .param("m", a.memberId()).param("s", u.screenKey()).param("n", u.name().trim()).query(Integer.class).single() > 0) {
            throw Errors.conflict("SAVED_VIEW_DUPLICATE", "같은 이름의 보기가 이미 있습니다.");
        }
        if (Boolean.TRUE.equals(u.isDefault())) {
            clearDefault(a.memberId(), u.screenKey());
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into saved_view (id, workspace_id, member_id, screen_key, name, filters, columns, sort, is_default)
                values (:id, :ws, :m, :s, :n, cast(:f as jsonb), cast(:c as jsonb), cast(:o as jsonb), :d)
                """).param("id", id).param("ws", a.workspaceId()).param("m", a.memberId()).param("s", u.screenKey())
                .param("n", u.name().trim()).param("f", json.write(u.filters() == null ? json.mapper().createObjectNode() : u.filters()))
                .param("c", json.write(u.columns() == null ? json.mapper().createArrayNode() : u.columns()))
                .param("o", json.write(u.sort() == null ? json.mapper().createArrayNode() : u.sort()))
                .param("d", Boolean.TRUE.equals(u.isDefault())).update();
        return get(id);
    }

    @Transactional
    public SavedView update(UUID id, int expectedVersion, Upsert u) {
        CurrentActor a = guard.actor();
        SavedView before = get(id);
        if (before.version() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, before.version());
        }
        if (Boolean.TRUE.equals(u.isDefault())) {
            clearDefault(a.memberId(), before.screenKey());
        }
        jdbc.sql("""
                update saved_view set name = coalesce(:n, name), filters = coalesce(cast(:f as jsonb), filters),
                       columns = coalesce(cast(:c as jsonb), columns), sort = coalesce(cast(:o as jsonb), sort),
                       is_default = coalesce(:d, is_default), version = version + 1, updated_at = now()
                 where id = :id and member_id = :m
                """).param("n", u.name() == null ? null : u.name().trim()).param("f", json.write(u.filters()))
                .param("c", json.write(u.columns())).param("o", json.write(u.sort())).param("d", u.isDefault())
                .param("id", id).param("m", a.memberId()).update();
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        CurrentActor a = guard.actor();
        if (jdbc.sql("delete from saved_view where id = :id and member_id = :m and workspace_id = :ws")
                .param("id", id).param("m", a.memberId()).param("ws", a.workspaceId()).update() == 0) {
            throw Errors.notFound("SAVED_VIEW");
        }
    }

    SavedView get(UUID id) {
        CurrentActor a = guard.actor();
        return jdbc.sql("select * from saved_view where id = :id and member_id = :m and workspace_id = :ws")
                .param("id", id).param("m", a.memberId()).param("ws", a.workspaceId()).query(this::map).optional()
                .orElseThrow(() -> Errors.notFound("SAVED_VIEW"));
    }

    private void clearDefault(UUID memberId, String screenKey) {
        jdbc.sql("update saved_view set is_default = false, version = version + 1 where member_id = :m and screen_key = :s and is_default")
                .param("m", memberId).param("s", screenKey).update();
    }

    private SavedView map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new SavedView(Db.uuid(rs, "id"), rs.getString("screen_key"), rs.getString("name"), json.read(rs.getString("filters")),
                json.read(rs.getString("columns")), json.read(rs.getString("sort")), rs.getBoolean("is_default"), rs.getInt("version"),
                Db.instant(rs, "updated_at"));
    }
}
