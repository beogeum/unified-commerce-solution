package com.commerceos.catalog;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.outbox.OutboxPublisher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.web.PageQuery;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품마스터(FR-CAT-01). 상품은 고객·브랜드 관점의 묶음이고 재고·원가 기준은 SKU 다.
 * 초안(DRAFT)은 불완전해도 되고, 게시(ACTIVE)에는 브랜드·카테고리·과세유형·판매 가능한 SKU 1개 이상이 필요하다.
 */
@Service
public class ProductService {
    /** 허용 상태 전이. ARCHIVED 는 종료 상태. */
    static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("ACTIVE", "ARCHIVED"),
            "ACTIVE", Set.of("INACTIVE"),
            "INACTIVE", Set.of("ACTIVE", "ARCHIVED"),
            "ARCHIVED", Set.of());

    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final SkuService skus;

    public ProductService(JdbcClient jdbc, AccessGuard guard, AuditService audit, OutboxPublisher outbox, SkuService skus) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.outbox = outbox;
        this.skus = skus;
    }

    public record ProductView(UUID id, String productCode, String name, String internalName, UUID brandId, String brandName,
                              UUID categoryId, String categoryName, String taxType, String status, String manufacturer,
                              String originCountry, String description, List<String> tags, int skuCount, int activeSkuCount,
                              int version, Instant createdAt, Instant updatedAt) {
    }

    public record OptionValueView(UUID id, String value, int sortOrder) {
    }

    public record OptionView(UUID id, String name, int sortOrder, List<OptionValueView> values) {
    }

    public record ProductDetail(ProductView product, List<OptionView> options, List<SkuService.SkuView> skus) {
    }

    public record NewOption(String name, List<String> values) {
    }

    public record Create(String productCode, String name, String internalName, UUID brandId, UUID categoryId, String taxType,
                         String manufacturer, String originCountry, String description, List<String> tags, List<NewOption> options) {
    }

    public record Patch(String productCode, String name, String internalName, UUID brandId, UUID categoryId, String taxType,
                        String manufacturer, String originCountry, String description, List<String> tags, String status) {
    }

    private static final String SELECT = """
            select p.*, b.name as brand_name, c.name as category_name,
                   (select count(*) from sku s where s.workspace_id = p.workspace_id and s.product_id = p.id and s.status <> 'ARCHIVED') as sku_count,
                   (select count(*) from sku s where s.workspace_id = p.workspace_id and s.product_id = p.id and s.status = 'ACTIVE' and s.sellable) as active_sku_count
              from product p
              left join brand b on b.workspace_id = p.workspace_id and b.id = p.brand_id
              left join category c on c.workspace_id = p.workspace_id and c.id = p.category_id
            """;

    public List<ProductView> list(PageQuery page, String q, String status, UUID brandId, UUID categoryId) {
        guard.requirePermission(Perm.CATALOG_READ);
        String term = q == null || q.isBlank() ? null : q.trim();
        return jdbc.sql(SELECT + """
                 where p.workspace_id = :ws
                   and (cast(:st as text) is null or p.status = :st)
                   and (cast(:brand as uuid) is null or p.brand_id = :brand)
                   and (cast(:cat as uuid) is null or p.category_id = :cat)
                   and (cast(:q as text) is null or p.name ilike '%' || :q || '%' or p.internal_name ilike '%' || :q || '%'
                        or lower(p.product_code) = lower(:q)
                        or exists (select 1 from sku s where s.workspace_id = p.workspace_id and s.product_id = p.id
                                     and (lower(s.sku_code) = lower(:q) or s.barcode = :q)))
                   and (cast(:after as uuid) is null or p.id < :after)
                 order by p.id desc limit :limit
                """).param("ws", guard.actor().workspaceId()).param("st", status).param("brand", brandId).param("cat", categoryId)
                .param("q", term).param("after", page.after()).param("limit", page.fetchSize())
                .query(this::map).list();
    }

    public ProductDetail get(UUID id) {
        guard.requirePermission(Perm.CATALOG_READ);
        ProductView p = find(id).orElseThrow(() -> Errors.notFound("PRODUCT"));
        return new ProductDetail(p, options(id), skus.byProduct(id));
    }

    @Transactional
    public ProductDetail create(Create c) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        String code = CatalogRules.requireCode("product_code", c.productCode());
        String name = CatalogRules.requireText("name", c.name(), 200);
        if (c.taxType() != null) {
            CatalogRules.requireEnum("tax_type", c.taxType(), CatalogRules.TAX_TYPES);
        }
        requireActiveBrand(a, c.brandId());
        requireCategory(a, c.categoryId());
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    insert into product (id, workspace_id, product_code, name, internal_name, brand_id, category_id, tax_type,
                                         manufacturer, origin_country, description, tags)
                    values (:id, :ws, :code, :name, :iname, :brand, :cat, :tax, :mfr, :origin, :desc, :tags)
                    """).param("id", id).param("ws", a.workspaceId()).param("code", code).param("name", name)
                    .param("iname", CatalogRules.optionalText("internal_name", c.internalName(), 200)).param("brand", c.brandId())
                    .param("cat", c.categoryId()).param("tax", c.taxType())
                    .param("mfr", CatalogRules.optionalText("manufacturer", c.manufacturer(), 100))
                    .param("origin", CatalogRules.optionalText("origin_country", c.originCountry(), 60))
                    .param("desc", CatalogRules.optionalText("description", c.description(), 20000)).param("tags", tags(c.tags())).update();
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("PRODUCT_CODE_DUPLICATE", "같은 상품코드가 이미 있습니다. 상품코드는 운영그룹 안에서 유일해야 합니다.");
        }
        if (c.options() != null) {
            for (NewOption o : c.options()) {
                insertOption(a, id, o);
            }
        }
        ProductView v = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "PRODUCT_CREATED", "PRODUCT", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .after(snapshot(v)).build());
        outbox.publish(a.workspaceId(), null, "PRODUCT", id, "ProductCreated", Map.of("product_id", id.toString()));
        return new ProductDetail(v, options(id), List.of());
    }

    @Transactional
    public ProductDetail patch(UUID id, int expectedVersion, Patch p) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        ProductView before = jdbc.sql(SELECT + " where p.workspace_id = :ws and p.id = :id for update of p")
                .param("ws", a.workspaceId()).param("id", id).query(this::map).optional()
                .orElseThrow(() -> Errors.notFound("PRODUCT"));
        CatalogRules.checkVersion(expectedVersion, before.version());
        if (p.productCode() != null && !p.productCode().equals(before.productCode())) {
            throw Errors.conflict("PRODUCT_CODE_IMMUTABLE", "상품코드는 바꿀 수 없습니다. 다른 상품이면 새로 등록하세요.");
        }
        if ("ARCHIVED".equals(before.status())) {
            throw Errors.conflict("PRODUCT_ARCHIVED", "보관된 상품은 수정할 수 없습니다.");
        }
        if (p.taxType() != null) {
            CatalogRules.requireEnum("tax_type", p.taxType(), CatalogRules.TAX_TYPES);
        }
        if (p.brandId() != null) {
            requireActiveBrand(a, p.brandId());
        }
        if (p.categoryId() != null) {
            requireCategory(a, p.categoryId());
        }
        String nextStatus = p.status() == null ? before.status() : p.status();
        if (p.status() != null && !p.status().equals(before.status())) {
            CatalogRules.requireEnum("status", p.status(), TRANSITIONS.keySet());
            if (!TRANSITIONS.get(before.status()).contains(p.status())) {
                throw Errors.conflict("INVALID_STATE_TRANSITION", "상품 상태를 " + before.status() + " 에서 " + p.status() + " 로 바꿀 수 없습니다.",
                        Map.of("from", before.status(), "to", p.status(), "allowed", TRANSITIONS.get(before.status()).stream().sorted().toList()));
            }
        }
        jdbc.sql("""
                update product set name = coalesce(:name, name), internal_name = coalesce(:iname, internal_name),
                       brand_id = coalesce(:brand, brand_id), category_id = coalesce(:cat, category_id), tax_type = coalesce(:tax, tax_type),
                       manufacturer = coalesce(:mfr, manufacturer), origin_country = coalesce(:origin, origin_country),
                       description = coalesce(:desc, description), tags = coalesce(:tags, tags),
                       status = :st, version = version + 1, updated_at = now()
                 where workspace_id = :ws and id = :id
                """).param("name", p.name() == null ? null : CatalogRules.requireText("name", p.name(), 200))
                .param("iname", CatalogRules.optionalText("internal_name", p.internalName(), 200)).param("brand", p.brandId())
                .param("cat", p.categoryId()).param("tax", p.taxType())
                .param("mfr", CatalogRules.optionalText("manufacturer", p.manufacturer(), 100))
                .param("origin", CatalogRules.optionalText("origin_country", p.originCountry(), 60))
                .param("desc", CatalogRules.optionalText("description", p.description(), 20000))
                .param("tags", p.tags() == null ? null : tags(p.tags())).param("st", before.status())
                .param("ws", a.workspaceId()).param("id", id).update();
        if (!nextStatus.equals(before.status())) {
            if ("ACTIVE".equals(nextStatus)) {
                requirePublishable(find(id).orElseThrow());
            }
            jdbc.sql("update product set status = :st where workspace_id = :ws and id = :id")
                    .param("st", nextStatus).param("ws", a.workspaceId()).param("id", id).update();
        }
        ProductView after = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), nextStatus.equals(before.status()) ? "PRODUCT_UPDATED" : "PRODUCT_STATUS_CHANGED",
                "PRODUCT", id).actor(ActorType.USER, a.memberId(), a.displayName()).before(snapshot(before)).after(snapshot(after)).build());
        outbox.publish(a.workspaceId(), null, "PRODUCT", id, "ProductUpdated", Map.of("product_id", id.toString(), "status", after.status()));
        return new ProductDetail(after, options(id), skus.byProduct(id));
    }

    @Transactional
    public OptionView addOption(UUID productId, NewOption o) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        ProductView p = lockProduct(a, productId);
        if ("ARCHIVED".equals(p.status())) {
            throw Errors.conflict("PRODUCT_ARCHIVED", "보관된 상품은 수정할 수 없습니다.");
        }
        if (skus.byProduct(productId).stream().anyMatch(s -> !"ARCHIVED".equals(s.status()))) {
            // 기존 SKU 의 옵션 조합이 불완전해지므로 새 옵션 축은 SKU 가 없을 때만 추가한다
            throw Errors.conflict("PRODUCT_HAS_SKUS", "SKU 가 있는 상품에는 새 옵션을 추가할 수 없습니다. 기존 옵션에 값을 추가하거나 새 상품으로 등록하세요.");
        }
        UUID id = insertOption(a, productId, o);
        audit.record(AuditEntry.builder(a.workspaceId(), "PRODUCT_OPTION_ADDED", "PRODUCT", productId)
                .actor(ActorType.USER, a.memberId(), a.displayName()).after(Map.of("option", o.name(), "values", o.values() == null ? List.of() : o.values())).build());
        return options(productId).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public OptionView addOptionValue(UUID productId, UUID optionId, String value) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        lockProduct(a, productId);
        if (jdbc.sql("select count(*) from product_option where workspace_id = :ws and product_id = :p and id = :o")
                .param("ws", a.workspaceId()).param("p", productId).param("o", optionId).query(Integer.class).single() == 0) {
            throw Errors.notFound("PRODUCT_OPTION");
        }
        insertValue(a, optionId, value, nextSort("product_option_value", "option_id", optionId));
        audit.record(AuditEntry.builder(a.workspaceId(), "PRODUCT_OPTION_VALUE_ADDED", "PRODUCT", productId)
                .actor(ActorType.USER, a.memberId(), a.displayName()).after(Map.of("option_id", optionId.toString(), "value", value)).build());
        return options(productId).stream().filter(x -> x.id().equals(optionId)).findFirst().orElseThrow();
    }

    /** 게시 조건(docs/13 §1, docs/03 §3). 빠진 항목을 모두 알려 준다. */
    private void requirePublishable(ProductView p) {
        List<String> missing = new ArrayList<>();
        if (p.brandId() == null) {
            missing.add("brand");
        }
        if (p.categoryId() == null) {
            missing.add("category");
        }
        if (p.taxType() == null) {
            missing.add("tax_type");
        }
        if (p.activeSkuCount() == 0) {
            missing.add("active_sellable_sku");
        }
        if (!missing.isEmpty()) {
            throw Errors.unprocessable("PUBLISH_REQUIREMENTS_NOT_MET",
                    "판매중으로 바꾸려면 브랜드·카테고리·과세유형과 판매 가능한 SKU 가 1개 이상 있어야 합니다.", Map.of("missing", missing));
        }
    }

    private ProductView lockProduct(CurrentActor a, UUID productId) {
        return jdbc.sql(SELECT + " where p.workspace_id = :ws and p.id = :id for update of p").param("ws", a.workspaceId()).param("id", productId)
                .query(this::map).optional().orElseThrow(() -> Errors.notFound("PRODUCT"));
    }

    private UUID insertOption(CurrentActor a, UUID productId, NewOption o) {
        String name = CatalogRules.requireText("option_name", o == null ? null : o.name(), 50);
        UUID id = Ids.newId();
        try {
            jdbc.sql("insert into product_option (id, workspace_id, product_id, name, sort_order) values (:id, :ws, :p, :n, :s)")
                    .param("id", id).param("ws", a.workspaceId()).param("p", productId).param("n", name)
                    .param("s", nextSort("product_option", "product_id", productId)).update();
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("PRODUCT_OPTION_DUPLICATE", "같은 이름의 옵션이 이미 있습니다.");
        }
        int sort = 0;
        for (String v : o.values() == null ? List.<String>of() : o.values()) {
            insertValue(a, id, v, sort++);
        }
        return id;
    }

    private void insertValue(CurrentActor a, UUID optionId, String value, int sort) {
        String v = CatalogRules.requireText("option_value", value, 100);
        try {
            jdbc.sql("insert into product_option_value (id, workspace_id, option_id, value, sort_order) values (:id, :ws, :o, :v, :s)")
                    .param("id", Ids.newId()).param("ws", a.workspaceId()).param("o", optionId).param("v", v).param("s", sort).update();
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("PRODUCT_OPTION_VALUE_DUPLICATE", "같은 옵션값 '" + v + "' 이(가) 이미 있습니다.");
        }
    }

    private int nextSort(String table, String parentColumn, UUID parentId) {
        return jdbc.sql("select coalesce(max(sort_order) + 1, 0) from " + table + " where " + parentColumn + " = :p")
                .param("p", parentId).query(Integer.class).single();
    }

    private void requireActiveBrand(CurrentActor a, UUID brandId) {
        if (brandId != null && jdbc.sql("select count(*) from brand where workspace_id = :ws and id = :id and status = 'ACTIVE'")
                .param("ws", a.workspaceId()).param("id", brandId).query(Integer.class).single() == 0) {
            throw Errors.unprocessable("BRAND_NOT_AVAILABLE", "브랜드를 찾을 수 없거나 사용 중지된 브랜드입니다.");
        }
    }

    private void requireCategory(CurrentActor a, UUID categoryId) {
        if (categoryId != null && jdbc.sql("select count(*) from category where workspace_id = :ws and id = :id and status = 'ACTIVE'")
                .param("ws", a.workspaceId()).param("id", categoryId).query(Integer.class).single() == 0) {
            throw Errors.unprocessable("CATEGORY_NOT_AVAILABLE", "카테고리를 찾을 수 없거나 사용 중지된 카테고리입니다.");
        }
    }

    List<OptionView> options(UUID productId) {
        UUID ws = guard.actor().workspaceId();
        List<OptionView> out = new ArrayList<>();
        for (Map<String, Object> o : jdbc.sql("select id, name, sort_order from product_option where workspace_id = :ws and product_id = :p order by sort_order, name")
                .param("ws", ws).param("p", productId).query().listOfRows()) {
            UUID oid = (UUID) o.get("id");
            List<OptionValueView> values = jdbc.sql("select id, value, sort_order from product_option_value where workspace_id = :ws and option_id = :o order by sort_order, value")
                    .param("ws", ws).param("o", oid)
                    .query((rs, i) -> new OptionValueView(Db.uuid(rs, "id"), rs.getString("value"), rs.getInt("sort_order"))).list();
            out.add(new OptionView(oid, (String) o.get("name"), (Integer) o.get("sort_order"), values));
        }
        return out;
    }

    Optional<ProductView> find(UUID id) {
        return jdbc.sql(SELECT + " where p.workspace_id = :ws and p.id = :id").param("ws", guard.actor().workspaceId()).param("id", id)
                .query(this::map).optional();
    }

    private ProductView map(ResultSet rs, int i) throws SQLException {
        Array t = rs.getArray("tags");
        List<String> tagList = t == null ? List.of() : Arrays.asList((String[]) t.getArray());
        return new ProductView(Db.uuid(rs, "id"), rs.getString("product_code"), rs.getString("name"), rs.getString("internal_name"),
                Db.uuid(rs, "brand_id"), rs.getString("brand_name"), Db.uuid(rs, "category_id"), rs.getString("category_name"),
                rs.getString("tax_type"), rs.getString("status"), rs.getString("manufacturer"), rs.getString("origin_country"),
                rs.getString("description"), tagList, rs.getInt("sku_count"), rs.getInt("active_sku_count"), rs.getInt("version"),
                Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"));
    }

    private static String[] tags(List<String> tags) {
        if (tags == null) {
            return new String[0];
        }
        if (tags.size() > 30) {
            throw Errors.validation("tags", "태그는 30개까지 입력할 수 있습니다.");
        }
        return tags.stream().map(String::trim).filter(x -> !x.isEmpty()).map(x -> x.length() > 30 ? x.substring(0, 30) : x)
                .distinct().toArray(String[]::new);
    }

    private static Map<String, Object> snapshot(ProductView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product_code", v.productCode());
        m.put("name", v.name());
        m.put("brand_id", String.valueOf(v.brandId()));
        m.put("category_id", String.valueOf(v.categoryId()));
        m.put("tax_type", String.valueOf(v.taxType()));
        m.put("status", v.status());
        m.put("version", v.version());
        return m;
    }
}
