package com.commerceos.catalog;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 브랜드(DLG-021: 상품 속성·집계 차원, 접근 범위 차원 아님)와 카테고리(계층). 운영그룹 공통. */
@Service
public class BrandCategoryService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;

    public BrandCategoryService(JdbcClient jdbc, AccessGuard guard, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    public record BrandView(UUID id, String name, String status, int productCount, int version, Instant createdAt) {
    }

    public record CategoryView(UUID id, UUID parentId, String name, String path, int sortOrder, String status, int version) {
    }

    public List<BrandView> brands(String status) {
        guard.requirePermission(Perm.CATALOG_READ);
        return jdbc.sql("""
                select b.*, (select count(*) from product p where p.workspace_id = b.workspace_id and p.brand_id = b.id) as product_count
                  from brand b where b.workspace_id = :ws and (cast(:st as text) is null or b.status = :st) order by lower(b.name)
                """).param("ws", guard.actor().workspaceId()).param("st", status)
                .query((rs, i) -> new BrandView(Db.uuid(rs, "id"), rs.getString("name"), rs.getString("status"), rs.getInt("product_count"),
                        rs.getInt("version"), Db.instant(rs, "created_at"))).list();
    }

    @Transactional
    public BrandView createBrand(String name) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        String n = CatalogRules.requireText("name", name, 100);
        UUID id = Ids.newId();
        try {
            jdbc.sql("insert into brand (id, workspace_id, name) values (:id, :ws, :n)").param("id", id).param("ws", a.workspaceId())
                    .param("n", n).update();
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("BRAND_DUPLICATE", "같은 이름의 브랜드가 이미 있습니다.");
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "BRAND_CREATED", "BRAND", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .after(Map.of("name", n)).build());
        return brands(null).stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public BrandView patchBrand(UUID id, int expectedVersion, String name, String status) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> before = jdbc.sql("select name, status, version from brand where workspace_id = :ws and id = :id for update")
                .param("ws", a.workspaceId()).param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> Errors.notFound("BRAND"));
        CatalogRules.checkVersion(expectedVersion, (Integer) before.get("version"));
        if (status != null) {
            CatalogRules.requireEnum("status", status, Set.of("ACTIVE", "INACTIVE"));
        }
        String n = name == null ? null : CatalogRules.requireText("name", name, 100);
        try {
            jdbc.sql("update brand set name = coalesce(:n, name), status = coalesce(:st, status), version = version + 1, updated_at = now() where id = :id")
                    .param("n", n).param("st", status).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("BRAND_DUPLICATE", "같은 이름의 브랜드가 이미 있습니다.");
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "BRAND_UPDATED", "BRAND", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .before(Map.of("name", before.get("name"), "status", before.get("status")))
                .after(Map.of("name", n == null ? before.get("name") : n, "status", status == null ? before.get("status") : status)).build());
        return brands(null).stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    public List<CategoryView> categories() {
        guard.requirePermission(Perm.CATALOG_READ);
        return jdbc.sql("""
                with recursive tree as (
                    select id, parent_id, name, sort_order, status, version, name::text as path, 1 as depth
                      from category where workspace_id = :ws and parent_id is null
                    union all
                    select c.id, c.parent_id, c.name, c.sort_order, c.status, c.version, t.path || ' > ' || c.name, t.depth + 1
                      from category c join tree t on c.parent_id = t.id
                     where c.workspace_id = :ws and t.depth < 10
                )
                select * from tree order by path
                """).param("ws", guard.actor().workspaceId())
                .query((rs, i) -> new CategoryView(Db.uuid(rs, "id"), Db.uuid(rs, "parent_id"), rs.getString("name"), rs.getString("path"),
                        rs.getInt("sort_order"), rs.getString("status"), rs.getInt("version"))).list();
    }

    @Transactional
    public CategoryView createCategory(String name, UUID parentId) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        String n = CatalogRules.requireText("name", name, 100);
        if (parentId != null && jdbc.sql("select count(*) from category where workspace_id = :ws and id = :p")
                .param("ws", a.workspaceId()).param("p", parentId).query(Integer.class).single() == 0) {
            throw Errors.unprocessable("CATEGORY_PARENT_NOT_FOUND", "상위 카테고리를 찾을 수 없습니다.");
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("insert into category (id, workspace_id, parent_id, name) values (:id, :ws, :p, :n)")
                    .param("id", id).param("ws", a.workspaceId()).param("p", parentId).param("n", n).update();
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("CATEGORY_DUPLICATE", "같은 위치에 같은 이름의 카테고리가 이미 있습니다.");
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "CATEGORY_CREATED", "CATEGORY", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .after(Map.of("name", n, "parent_id", String.valueOf(parentId))).build());
        return categories().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
}
