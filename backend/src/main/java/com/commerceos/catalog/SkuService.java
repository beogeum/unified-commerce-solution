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
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SKU(FR-CAT-01·02·08). 재고·원가의 기준 단위. 코드는 운영그룹 안에서 유일하고 바꿀 수 없다.
 * 유형과 판매가능·구매가능·재고관리 여부는 독립 속성이며, 유형별 제약만 강제한다.
 * 옵션이 있는 상품의 SKU 는 옵션마다 값 하나씩(완전한 조합)을 가져야 하고 같은 조합은 하나만 허용한다.
 */
@Service
public class SkuService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final OutboxPublisher outbox;

    public SkuService(JdbcClient jdbc, AccessGuard guard, AuditService audit, OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.outbox = outbox;
    }

    public record OptionPick(UUID optionId, String optionName, UUID valueId, String value) {
    }

    public record Warning(String code, String message, Map<String, Object> details) {
    }

    public record SkuView(UUID id, UUID productId, String productCode, String productName, String skuCode, String name, String skuType,
                          boolean sellable, boolean purchasable, boolean inventoryManaged, String barcode, BigDecimal weightG,
                          BigDecimal lengthMm, BigDecimal widthMm, BigDecimal heightMm, boolean lotManaged, boolean expiryManaged,
                          Integer minRemainingShelfDays, List<OptionPick> options, String status, int version, Instant createdAt,
                          Instant updatedAt, List<Warning> warnings) {
        SkuView withWarnings(List<Warning> w) {
            return new SkuView(id, productId, productCode, productName, skuCode, name, skuType, sellable, purchasable, inventoryManaged,
                    barcode, weightG, lengthMm, widthMm, heightMm, lotManaged, expiryManaged, minRemainingShelfDays, options, status,
                    version, createdAt, updatedAt, w);
        }
    }

    public record Create(UUID productId, String skuCode, String name, String skuType, Boolean sellable, Boolean purchasable,
                         Boolean inventoryManaged, String barcode, BigDecimal weightG, BigDecimal lengthMm, BigDecimal widthMm,
                         BigDecimal heightMm, Boolean lotManaged, Boolean expiryManaged, Integer minRemainingShelfDays,
                         List<UUID> optionValueIds) {
    }

    public record Patch(String skuCode, String skuType, UUID productId, String name, Boolean sellable, Boolean purchasable,
                        Boolean inventoryManaged, String barcode, BigDecimal weightG, BigDecimal lengthMm, BigDecimal widthMm,
                        BigDecimal heightMm, Boolean lotManaged, Boolean expiryManaged, Integer minRemainingShelfDays, String status) {
    }

    private static final String SELECT = """
            select s.*, p.product_code, p.name as product_name from sku s
              join product p on p.workspace_id = s.workspace_id and p.id = s.product_id
            """;

    public List<SkuView> list(PageQuery page, String q, String skuType, String status, UUID productId) {
        guard.requirePermission(Perm.CATALOG_READ);
        String term = q == null || q.isBlank() ? null : q.trim();
        return withOptions(jdbc.sql(SELECT + """
                 where s.workspace_id = :ws
                   and (cast(:type as text) is null or s.sku_type = :type)
                   and (cast(:st as text) is null or s.status = :st)
                   and (cast(:pid as uuid) is null or s.product_id = :pid)
                   and (cast(:q as text) is null or lower(s.sku_code) like '%' || lower(:q) || '%' or s.name ilike '%' || :q || '%'
                        or p.name ilike '%' || :q || '%' or s.barcode = :q)
                   and (cast(:after as uuid) is null or s.id < :after)
                 order by s.id desc limit :limit
                """).param("ws", guard.actor().workspaceId()).param("type", skuType).param("st", status).param("pid", productId)
                .param("q", term).param("after", page.after()).param("limit", page.fetchSize()).query(this::map).list());
    }

    public SkuView get(UUID id) {
        guard.requirePermission(Perm.CATALOG_READ);
        SkuView v = find(id).orElseThrow(() -> Errors.notFound("SKU"));
        return v.withWarnings(barcodeWarnings(v.id(), v.barcode()));
    }

    List<SkuView> byProduct(UUID productId) {
        return withOptions(jdbc.sql(SELECT + " where s.workspace_id = :ws and s.product_id = :p order by s.sku_code")
                .param("ws", guard.actor().workspaceId()).param("p", productId).query(this::map).list());
    }

    @Transactional
    public SkuView create(Create c) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        if (c.productId() == null) {
            throw Errors.validation("product_id", "상품을 선택하세요.");
        }
        String code = CatalogRules.requireCode("sku_code", c.skuCode());
        CatalogRules.requireEnum("sku_type", c.skuType(), CatalogRules.SKU_TYPES);
        // 상품을 잠가 같은 상품의 SKU 조합 검사를 직렬화한다(유일 인덱스가 최종 보장)
        String productStatus = jdbc.sql("select status from product where workspace_id = :ws and id = :id for update")
                .param("ws", a.workspaceId()).param("id", c.productId()).query(String.class).optional()
                .orElseThrow(() -> Errors.notFound("PRODUCT"));
        if ("ARCHIVED".equals(productStatus)) {
            throw Errors.conflict("PRODUCT_ARCHIVED", "보관된 상품에는 SKU 를 추가할 수 없습니다.");
        }
        boolean[] d = CatalogRules.TYPE_DEFAULTS.get(c.skuType());
        boolean sellable = c.sellable() == null ? d[0] : c.sellable();
        boolean purchasable = c.purchasable() == null ? d[1] : c.purchasable();
        boolean inv = c.inventoryManaged() == null ? d[2] : c.inventoryManaged();
        boolean lot = Boolean.TRUE.equals(c.lotManaged());
        boolean exp = Boolean.TRUE.equals(c.expiryManaged());
        CatalogRules.checkTypeFlags(c.skuType(), sellable, inv, lot, exp, c.minRemainingShelfDays());
        checkDimensions(c.weightG(), c.lengthMm(), c.widthMm(), c.heightMm());
        String barcode = normalizeBarcode(c.barcode());
        List<OptionPick> picks = resolveOptions(a, c.productId(), c.optionValueIds());
        String signature = picks.isEmpty() ? null
                : picks.stream().map(x -> x.valueId().toString()).sorted().collect(Collectors.joining(","));
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    insert into sku (id, workspace_id, product_id, sku_code, name, sku_type, sellable, purchasable, inventory_managed, barcode,
                                     weight_g, length_mm, width_mm, height_mm, lot_managed, expiry_managed, min_remaining_shelf_days, option_signature)
                    values (:id, :ws, :p, :code, :name, :type, :s, :pu, :inv, :bc, :w, :l, :wi, :h, :lot, :exp, :min, :sig)
                    """).param("id", id).param("ws", a.workspaceId()).param("p", c.productId()).param("code", code)
                    .param("name", CatalogRules.optionalText("name", c.name(), 200)).param("type", c.skuType()).param("s", sellable)
                    .param("pu", purchasable).param("inv", inv).param("bc", barcode).param("w", c.weightG()).param("l", c.lengthMm())
                    .param("wi", c.widthMm()).param("h", c.heightMm()).param("lot", lot).param("exp", exp)
                    .param("min", c.minRemainingShelfDays()).param("sig", signature).update();
        } catch (DuplicateKeyException e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("sku_option_combination_uq")) {
                throw Errors.conflict("SKU_OPTION_COMBINATION_DUPLICATE", "이 옵션 조합의 SKU 가 이미 있습니다.");
            }
            throw Errors.conflict("SKU_CODE_DUPLICATE", "같은 SKU 코드가 이미 있습니다. SKU 코드는 운영그룹 안에서 유일해야 합니다.");
        }
        for (OptionPick op : picks) {
            jdbc.sql("insert into sku_option_value (workspace_id, sku_id, option_id, option_value_id) values (:ws, :s, :o, :v)")
                    .param("ws", a.workspaceId()).param("s", id).param("o", op.optionId()).param("v", op.valueId()).update();
        }
        SkuView v = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "SKU_CREATED", "SKU", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .after(snapshot(v)).build());
        outbox.publish(a.workspaceId(), null, "SKU", id, "SkuCreated", Map.of("sku_id", id.toString(), "product_id", c.productId().toString()));
        return v.withWarnings(barcodeWarnings(id, barcode));
    }

    @Transactional
    public SkuView patch(UUID id, int expectedVersion, Patch p) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        SkuView before = jdbc.sql(SELECT + " where s.workspace_id = :ws and s.id = :id for update of s").param("ws", a.workspaceId())
                .param("id", id).query(this::map).optional().orElseThrow(() -> Errors.notFound("SKU"));
        CatalogRules.checkVersion(expectedVersion, before.version());
        if ((p.skuCode() != null && !p.skuCode().equals(before.skuCode())) || (p.productId() != null && !p.productId().equals(before.productId()))) {
            throw Errors.conflict("SKU_IDENTITY_IMMUTABLE", "SKU 코드와 소속 상품은 바꿀 수 없습니다. 다른 SKU 이면 새로 등록하세요.");
        }
        if (p.skuType() != null && !p.skuType().equals(before.skuType())) {
            throw Errors.conflict("SKU_TYPE_IMMUTABLE", "SKU 유형은 재고·원가 처리 방식을 정하므로 바꿀 수 없습니다. 새 SKU 로 등록하세요.");
        }
        if ("ARCHIVED".equals(before.status())) {
            throw Errors.conflict("SKU_ARCHIVED", "보관된 SKU 는 수정할 수 없습니다.");
        }
        String status = p.status() == null ? before.status() : p.status();
        CatalogRules.requireEnum("status", status, Set.of("ACTIVE", "INACTIVE", "ARCHIVED"));
        boolean sellable = p.sellable() == null ? before.sellable() : p.sellable();
        boolean purchasable = p.purchasable() == null ? before.purchasable() : p.purchasable();
        boolean inv = p.inventoryManaged() == null ? before.inventoryManaged() : p.inventoryManaged();
        boolean lot = p.lotManaged() == null ? before.lotManaged() : p.lotManaged();
        boolean exp = p.expiryManaged() == null ? before.expiryManaged() : p.expiryManaged();
        Integer min = p.minRemainingShelfDays() == null ? before.minRemainingShelfDays() : p.minRemainingShelfDays();
        if (!exp) {
            min = null;
        }
        CatalogRules.checkTypeFlags(before.skuType(), sellable, inv, lot, exp, min);
        checkDimensions(p.weightG(), p.lengthMm(), p.widthMm(), p.heightMm());
        // 판매중 상품의 마지막 판매 가능 SKU 를 내리면 상품이 '판매중인데 팔 SKU 없음' 상태가 된다
        boolean losesSellable = before.sellable() && "ACTIVE".equals(before.status()) && (!sellable || !"ACTIVE".equals(status));
        if (losesSellable) {
            int remaining = jdbc.sql("""
                    select count(*) from sku s join product pr on pr.workspace_id = s.workspace_id and pr.id = s.product_id
                     where s.workspace_id = :ws and s.product_id = :p and s.id <> :id and s.status = 'ACTIVE' and s.sellable and pr.status = 'ACTIVE'
                    """).param("ws", a.workspaceId()).param("p", before.productId()).param("id", id).query(Integer.class).single();
            String productStatus = jdbc.sql("select status from product where workspace_id = :ws and id = :p")
                    .param("ws", a.workspaceId()).param("p", before.productId()).query(String.class).single();
            if ("ACTIVE".equals(productStatus) && remaining == 0) {
                throw Errors.conflict("PRODUCT_NEEDS_ACTIVE_SKU", "판매중인 상품의 마지막 판매 SKU 입니다. 상품을 먼저 판매중지하세요.");
            }
        }
        String barcode = p.barcode() == null ? before.barcode() : normalizeBarcode(p.barcode());
        jdbc.sql("""
                update sku set name = coalesce(:name, name), sellable = :s, purchasable = :pu, inventory_managed = :inv, barcode = :bc,
                       weight_g = coalesce(:w, weight_g), length_mm = coalesce(:l, length_mm), width_mm = coalesce(:wi, width_mm),
                       height_mm = coalesce(:h, height_mm), lot_managed = :lot, expiry_managed = :exp, min_remaining_shelf_days = :min,
                       status = :st, version = version + 1, updated_at = now()
                 where workspace_id = :ws and id = :id
                """).param("name", CatalogRules.optionalText("name", p.name(), 200)).param("s", sellable).param("pu", purchasable)
                .param("inv", inv).param("bc", barcode).param("w", p.weightG()).param("l", p.lengthMm()).param("wi", p.widthMm())
                .param("h", p.heightMm()).param("lot", lot).param("exp", exp).param("min", min).param("st", status)
                .param("ws", a.workspaceId()).param("id", id).update();
        SkuView after = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), status.equals(before.status()) ? "SKU_UPDATED" : "SKU_STATUS_CHANGED", "SKU", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).before(snapshot(before)).after(snapshot(after)).build());
        outbox.publish(a.workspaceId(), null, "SKU", id, "SkuUpdated", Map.of("sku_id", id.toString(), "status", after.status()));
        return after.withWarnings(barcodeWarnings(id, barcode));
    }

    /** 옵션값 검증: 이 상품의 옵션이어야 하고, 옵션마다 정확히 하나. 옵션 없는 상품은 비워야 한다. */
    private List<OptionPick> resolveOptions(CurrentActor a, UUID productId, List<UUID> valueIds) {
        List<Map<String, Object>> productOptions = jdbc.sql("select id, name from product_option where workspace_id = :ws and product_id = :p")
                .param("ws", a.workspaceId()).param("p", productId).query().listOfRows();
        List<UUID> ids = valueIds == null ? List.of() : valueIds;
        if (productOptions.isEmpty()) {
            if (!ids.isEmpty()) {
                throw Errors.unprocessable("SKU_OPTION_INVALID", "이 상품에는 옵션이 없어 옵션값을 지정할 수 없습니다.");
            }
            return List.of();
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw Errors.unprocessable("SKU_OPTION_INVALID", "같은 옵션값을 두 번 지정했습니다.");
        }
        List<OptionPick> picks = new ArrayList<>(jdbc.sql("""
                select o.id as option_id, o.name as option_name, v.id as value_id, v.value
                  from product_option_value v join product_option o on o.workspace_id = v.workspace_id and o.id = v.option_id
                 where v.workspace_id = :ws and o.product_id = :p and v.id = any(:ids)
                 order by o.sort_order
                """).param("ws", a.workspaceId()).param("p", productId).param("ids", Db.uuids(new HashSet<>(ids)))
                .query((rs, i) -> new OptionPick(Db.uuid(rs, "option_id"), rs.getString("option_name"), Db.uuid(rs, "value_id"),
                        rs.getString("value"))).list());
        if (picks.size() != ids.size()) {
            throw Errors.unprocessable("SKU_OPTION_INVALID", "이 상품의 옵션값이 아닌 값이 포함되어 있습니다.");
        }
        Set<UUID> optionsCovered = picks.stream().map(OptionPick::optionId).collect(Collectors.toSet());
        if (optionsCovered.size() != picks.size()) {
            throw Errors.unprocessable("SKU_OPTION_INVALID", "한 옵션에는 값 하나만 지정할 수 있습니다.");
        }
        if (optionsCovered.size() != productOptions.size()) {
            List<String> missing = productOptions.stream().filter(o -> !optionsCovered.contains((UUID) o.get("id")))
                    .map(o -> (String) o.get("name")).toList();
            throw Errors.unprocessable("SKU_OPTION_INCOMPLETE", "모든 옵션의 값을 지정해야 합니다.", Map.of("missing_options", missing));
        }
        return picks;
    }

    /** 바코드 중복은 경고만(같은 바코드를 묶음·낱개가 공유하는 실무 사례가 있어 차단하지 않는다, QualityCheck 시안). */
    private List<Warning> barcodeWarnings(UUID id, String barcode) {
        if (barcode == null) {
            return List.of();
        }
        List<String> others = jdbc.sql("select sku_code from sku where workspace_id = :ws and barcode = :b and id <> :id and status <> 'ARCHIVED' order by sku_code")
                .param("ws", guard.actor().workspaceId()).param("b", barcode).param("id", id).query(String.class).list();
        return others.isEmpty() ? List.of()
                : List.of(new Warning("BARCODE_DUPLICATE", "같은 바코드가 다른 SKU 에도 등록되어 있습니다. 스캔 시 SKU 를 혼동할 수 있습니다.",
                        Map.of("sku_codes", others)));
    }

    private static String normalizeBarcode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String b = raw.trim();
        if (!b.matches("^[0-9A-Za-z-]{4,40}$")) {
            throw Errors.validation("barcode", "바코드는 영문·숫자·하이픈 4~40자로 입력하세요.");
        }
        return b;
    }

    private static void checkDimensions(BigDecimal... values) {
        for (BigDecimal v : values) {
            CatalogRules.requireNonNegative("dimensions", v);
        }
    }

    Optional<SkuView> find(UUID id) {
        return jdbc.sql(SELECT + " where s.workspace_id = :ws and s.id = :id").param("ws", guard.actor().workspaceId()).param("id", id)
                .query(this::map).optional().map(v -> withOptions(List.of(v)).get(0));
    }

    private List<SkuView> withOptions(List<SkuView> list) {
        if (list.isEmpty()) {
            return list;
        }
        Map<UUID, List<OptionPick>> picks = new LinkedHashMap<>();
        jdbc.sql("""
                select sov.sku_id, o.id as option_id, o.name as option_name, v.id as value_id, v.value
                  from sku_option_value sov
                  join product_option o on o.workspace_id = sov.workspace_id and o.id = sov.option_id
                  join product_option_value v on v.workspace_id = sov.workspace_id and v.id = sov.option_value_id
                 where sov.workspace_id = :ws and sov.sku_id = any(:ids) order by o.sort_order
                """).param("ws", guard.actor().workspaceId()).param("ids", Db.uuids(list.stream().map(SkuView::id).toList()))
                .query((rs, i) -> {
                    picks.computeIfAbsent(Db.uuid(rs, "sku_id"), k -> new ArrayList<>()).add(new OptionPick(Db.uuid(rs, "option_id"),
                            rs.getString("option_name"), Db.uuid(rs, "value_id"), rs.getString("value")));
                    return null;
                }).list();
        return list.stream().map(s -> new SkuView(s.id(), s.productId(), s.productCode(), s.productName(), s.skuCode(), s.name(), s.skuType(),
                s.sellable(), s.purchasable(), s.inventoryManaged(), s.barcode(), s.weightG(), s.lengthMm(), s.widthMm(), s.heightMm(),
                s.lotManaged(), s.expiryManaged(), s.minRemainingShelfDays(), picks.getOrDefault(s.id(), List.of()), s.status(), s.version(),
                s.createdAt(), s.updatedAt(), List.of())).toList();
    }

    private SkuView map(ResultSet rs, int i) throws SQLException {
        Object min = rs.getObject("min_remaining_shelf_days");
        return new SkuView(Db.uuid(rs, "id"), Db.uuid(rs, "product_id"), rs.getString("product_code"), rs.getString("product_name"),
                rs.getString("sku_code"), rs.getString("name"), rs.getString("sku_type"), rs.getBoolean("sellable"), rs.getBoolean("purchasable"),
                rs.getBoolean("inventory_managed"), rs.getString("barcode"), rs.getBigDecimal("weight_g"), rs.getBigDecimal("length_mm"),
                rs.getBigDecimal("width_mm"), rs.getBigDecimal("height_mm"), rs.getBoolean("lot_managed"), rs.getBoolean("expiry_managed"),
                min == null ? null : ((Number) min).intValue(), List.of(), rs.getString("status"), rs.getInt("version"),
                Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"), List.of());
    }

    private static Map<String, Object> snapshot(SkuView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku_code", v.skuCode());
        m.put("sku_type", v.skuType());
        m.put("sellable", v.sellable());
        m.put("purchasable", v.purchasable());
        m.put("inventory_managed", v.inventoryManaged());
        m.put("barcode", String.valueOf(v.barcode()));
        m.put("lot_managed", v.lotManaged());
        m.put("expiry_managed", v.expiryManaged());
        m.put("status", v.status());
        m.put("version", v.version());
        return m;
    }
}
