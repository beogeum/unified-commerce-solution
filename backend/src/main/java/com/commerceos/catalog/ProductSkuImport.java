package com.commerceos.catalog;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.Perm;
import com.commerceos.operations.imports.ImportTypeHandler;
import com.commerceos.shared.error.ApiException;
import com.commerceos.shared.error.Errors;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 상품·SKU 일괄 등록 양식 v1(BulkJobs 시안). 새 상품은 파일 안 같은 상품코드 행들의 옵션을 모아 한 번에 만든다. */
@Component
public class ProductSkuImport implements ImportTypeHandler {
    static final Map<String, String> SKU_TYPE_KO = Map.of("일반", "NORMAL", "가상묶음", "BUNDLE", "묶음", "BUNDLE", "실물세트", "KIT", "세트", "KIT",
            "부자재", "MATERIAL", "증정품", "GIFT", "샘플", "SAMPLE", "서비스", "SERVICE", "디지털", "DIGITAL");
    static final Map<String, String> TAX_KO = Map.of("과세", "TAXABLE", "면세", "TAX_FREE", "영세율", "ZERO_RATED", "영세", "ZERO_RATED");

    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final ProductService products;
    private final SkuService skus;

    public ProductSkuImport(JdbcClient jdbc, AccessGuard guard, ProductService products, SkuService skus) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.products = products;
        this.skus = skus;
    }

    @Override
    public String importType() {
        return "PRODUCT_SKU";
    }

    @Override
    public String templateVersion() {
        return "v1";
    }

    @Override
    public String labelKo() {
        return "상품·SKU 등록";
    }

    @Override
    public String requiredPermission() {
        return Perm.CATALOG_MANAGE;
    }

    @Override
    public List<Field> fields() {
        return List.of(
                new Field("product_code", "상품코드", true, List.of("product code", "상품 코드"), "AMP"),
                new Field("product_name", "상품명", true, List.of("product name", "상품 이름"), "비타 앰플"),
                new Field("brand", "브랜드", false, List.of("brand"), "루미에르"),
                new Field("category", "카테고리", false, List.of("category", "분류"), "스킨케어 > 앰플"),
                new Field("tax_type", "과세유형", false, List.of("과세", "tax"), "과세"),
                new Field("option1_name", "옵션1", false, List.of("옵션1명", "option1"), "용량"),
                new Field("option1_value", "옵션1 값", false, List.of("옵션1값", "option1 value"), "30ml"),
                new Field("option2_name", "옵션2", false, List.of("옵션2명", "option2"), ""),
                new Field("option2_value", "옵션2 값", false, List.of("옵션2값", "option2 value"), ""),
                new Field("sku_code", "SKU 코드", true, List.of("sku", "sku code", "sku코드"), "AMP-30"),
                new Field("sku_name", "SKU명", false, List.of("sku 이름", "sku name"), "비타 앰플 30ml"),
                new Field("sku_type", "SKU 유형", false, List.of("유형", "sku type"), "일반"),
                new Field("barcode", "바코드", false, List.of("gtin", "barcode"), "8801234567001"),
                new Field("weight_g", "중량(g)", false, List.of("중량", "weight"), "120"),
                new Field("lot_managed", "LOT 관리", false, List.of("lot"), "N"),
                new Field("expiry_managed", "유통기한 관리", false, List.of("유통기한"), "N"),
                new Field("min_shelf_days", "남은 유통기한 최소일", false, List.of("최소 유통기한일"), ""));
    }

    record ExistingProduct(UUID id, String name, List<String> optionNames, Set<String> combinations) {
    }

    @Override
    public List<RowCheck> validate(List<Map<String, String>> rows) {
        UUID ws = guard.actor().workspaceId();
        Map<String, UUID> brands = new HashMap<>();
        jdbc.sql("select id, name from brand where workspace_id = :ws and status = 'ACTIVE'").param("ws", ws)
                .query((rs, i) -> brands.put(norm(rs.getString("name")), (UUID) rs.getObject("id"))).list();
        Map<String, UUID> categoryPath = new HashMap<>();
        Map<String, List<UUID>> categoryLeaf = new HashMap<>();
        jdbc.sql("""
                with recursive t as (select id, parent_id, name, name::text as path from category where workspace_id = :ws and parent_id is null and status = 'ACTIVE'
                  union all select c.id, c.parent_id, c.name, t.path || ' > ' || c.name from category c join t on c.parent_id = t.id where c.status = 'ACTIVE')
                select id, name, path from t
                """).param("ws", ws).query((rs, i) -> {
                    categoryPath.put(norm(rs.getString("path")), (UUID) rs.getObject("id"));
                    categoryLeaf.computeIfAbsent(norm(rs.getString("name")), k -> new ArrayList<>()).add((UUID) rs.getObject("id"));
                    return null;
                }).list();
        Set<String> existingSkus = new HashSet<>(jdbc.sql("select lower(sku_code) from sku where workspace_id = :ws").param("ws", ws).query(String.class).list());
        Map<String, String> barcodeOwner = new HashMap<>();
        jdbc.sql("select barcode, sku_code from sku where workspace_id = :ws and barcode is not null and status <> 'ARCHIVED'").param("ws", ws)
                .query((rs, i) -> barcodeOwner.put(rs.getString("barcode"), rs.getString("sku_code"))).list();

        Map<String, ExistingProduct> existing = new HashMap<>();
        Map<String, Map<String, String>> firstRowOfProduct = new HashMap<>();
        Map<String, Integer> skuSeen = new HashMap<>();
        Map<String, Integer> comboSeen = new HashMap<>();
        Map<String, String> barcodeSeen = new HashMap<>();
        List<RowCheck> out = new ArrayList<>();
        for (int idx = 0; idx < rows.size(); idx++) {
            Map<String, String> r = rows.get(idx);
            List<Message> m = new ArrayList<>();
            Map<String, String> n = new LinkedHashMap<>();
            String productCode = v(r, "product_code");
            String productName = v(r, "product_name");
            String skuCode = v(r, "sku_code");
            if (productCode.isEmpty()) {
                m.add(err("product_code", "상품코드는 필수입니다."));
            } else if (!productCode.matches(CatalogRules.CODE_PATTERN)) {
                m.add(err("product_code", "상품코드 형식이 올바르지 않습니다(영문·숫자·점·하이픈·밑줄, 64자)."));
            }
            if (productName.isEmpty()) {
                m.add(err("product_name", "상품명은 필수입니다."));
            }
            if (skuCode.isEmpty()) {
                m.add(err("sku_code", "SKU 코드는 필수입니다."));
            } else if (!skuCode.matches(CatalogRules.CODE_PATTERN)) {
                m.add(err("sku_code", "SKU 코드 형식이 올바르지 않습니다(영문·숫자·점·하이픈·밑줄, 64자)."));
            } else if (existingSkus.contains(skuCode.toLowerCase(Locale.ROOT))) {
                m.add(err("sku_code", "SKU 코드가 이미 있습니다(운영그룹 안에서 유일해야 함)."));
            } else if (skuSeen.containsKey(skuCode.toLowerCase(Locale.ROOT))) {
                m.add(err("sku_code", "파일 안에서 같은 SKU 코드가 반복됩니다(" + skuSeen.get(skuCode.toLowerCase(Locale.ROOT)) + "번째 데이터 행)."));
            }
            skuSeen.putIfAbsent(skuCode.toLowerCase(Locale.ROOT), idx + 1);

            String typeRaw = v(r, "sku_type");
            String type = typeRaw.isEmpty() ? "NORMAL" : SKU_TYPE_KO.getOrDefault(typeRaw, typeRaw.toUpperCase(Locale.ROOT));
            if (!CatalogRules.SKU_TYPES.contains(type)) {
                m.add(err("sku_type", "SKU 유형 '" + typeRaw + "' 을(를) 알 수 없습니다. 일반·가상묶음·실물세트·부자재·증정품·샘플·서비스·디지털 중 하나."));
                type = "NORMAL";
            }
            String taxRaw = v(r, "tax_type");
            String tax = taxRaw.isEmpty() ? "" : TAX_KO.getOrDefault(taxRaw, taxRaw.toUpperCase(Locale.ROOT));
            if (!tax.isEmpty() && !CatalogRules.TAX_TYPES.contains(tax)) {
                m.add(err("tax_type", "과세유형은 과세·면세·영세율 중 하나여야 합니다."));
            }
            String brand = v(r, "brand");
            UUID brandId = brand.isEmpty() ? null : brands.get(norm(brand));
            if (!brand.isEmpty() && brandId == null) {
                m.add(err("brand", "브랜드 '" + brand + "' 가 없습니다. [설정 > 브랜드·카테고리]에서 먼저 만드세요."));
            }
            String cat = v(r, "category");
            UUID categoryId = null;
            if (!cat.isEmpty()) {
                categoryId = categoryPath.get(norm(cat));
                if (categoryId == null) {
                    List<UUID> leaf = categoryLeaf.getOrDefault(norm(cat), List.of());
                    if (leaf.size() == 1) {
                        categoryId = leaf.get(0);
                    } else if (leaf.size() > 1) {
                        m.add(err("category", "같은 이름의 카테고리가 여러 개입니다. '상위 > 하위' 경로로 적어 주세요."));
                    } else {
                        m.add(err("category", "카테고리 '" + cat + "' 가 없습니다."));
                    }
                }
            }
            boolean lot = yes(r, "lot_managed", m);
            boolean exp = yes(r, "expiry_managed", m);
            Integer minDays = integer(r, "min_shelf_days", m);
            boolean[] d = CatalogRules.TYPE_DEFAULTS.get(type);
            try {
                CatalogRules.checkTypeFlags(type, d[0], d[2], lot, exp, minDays);
            } catch (ApiException e) {
                m.add(err("sku_type", e.getMessage()));
            }
            String weight = v(r, "weight_g");
            if (!weight.isEmpty()) {
                try {
                    if (new BigDecimal(weight).signum() < 0) {
                        m.add(err("weight_g", "중량은 0 이상이어야 합니다."));
                    }
                } catch (NumberFormatException e) {
                    m.add(err("weight_g", "중량은 숫자로 입력하세요."));
                }
            } else if (d[2]) {
                m.add(warn("weight_g", "중량 미입력 — 배송비 예상 계산이 부정확할 수 있습니다."));
            }
            String barcode = v(r, "barcode");
            if (!barcode.isEmpty()) {
                if (!barcode.matches("^[0-9A-Za-z-]{4,40}$")) {
                    m.add(err("barcode", "바코드는 영문·숫자·하이픈 4~40자입니다."));
                } else if (barcodeOwner.containsKey(barcode)) {
                    m.add(warn("barcode", "바코드가 " + barcodeOwner.get(barcode) + " 와(과) 중복됩니다."));
                } else if (barcodeSeen.containsKey(barcode)) {
                    m.add(warn("barcode", "파일 안에서 바코드가 " + barcodeSeen.get(barcode) + " 와(과) 중복됩니다."));
                }
                barcodeSeen.putIfAbsent(barcode, skuCode);
            }
            String name = (v(r, "sku_name") + " " + productName);
            if ("NORMAL".equals(type) && (name.contains("세트") || name.toLowerCase(Locale.ROOT).contains("set"))) {
                m.add(warn("sku_type", "세트로 보이지만 유형이 '일반'입니다. 가상묶음이면 유형을 바꾸고 BOM 을 등록하세요."));
            }
            // 옵션
            List<String> optionNames = new ArrayList<>();
            List<String> optionValues = new ArrayList<>();
            for (int k = 1; k <= 2; k++) {
                String on = v(r, "option" + k + "_name");
                String ov = v(r, "option" + k + "_value");
                if (on.isEmpty() != ov.isEmpty()) {
                    m.add(err("option" + k + "_name", "옵션" + k + " 이름과 값은 함께 입력하세요."));
                } else if (!on.isEmpty()) {
                    optionNames.add(on);
                    optionValues.add(ov);
                }
            }
            // 같은 상품코드 행끼리 상품 정보·옵션 이름 일치
            if (!productCode.isEmpty()) {
                String key = productCode.toLowerCase(Locale.ROOT);
                Map<String, String> first = firstRowOfProduct.get(key);
                if (first == null) {
                    firstRowOfProduct.put(key, r);
                } else {
                    if (!v(first, "product_name").equals(productName)) {
                        m.add(err("product_name", "같은 상품코드인데 상품명이 앞 행과 다릅니다."));
                    }
                    if (!(v(first, "option1_name") + "|" + v(first, "option2_name")).equals(v(r, "option1_name") + "|" + v(r, "option2_name"))) {
                        m.add(err("option1_name", "같은 상품코드인데 옵션 이름이 앞 행과 다릅니다."));
                    }
                }
                ExistingProduct ep = existing.computeIfAbsent(key, k -> loadProduct(ws, productCode));
                if (ep != null) {
                    if (!new HashSet<>(ep.optionNames()).equals(new HashSet<>(optionNames))) {
                        m.add(err("option1_name", "기존 상품의 옵션(" + (ep.optionNames().isEmpty() ? "없음" : String.join(", ", ep.optionNames()))
                                + ")과 맞지 않습니다. 기존 상품에는 새 옵션 축을 추가할 수 없습니다."));
                    }
                    if (!ep.name().equals(productName)) {
                        m.add(warn("product_name", "기존 상품명('" + ep.name() + "')과 다릅니다 — 기존 값을 유지합니다."));
                    }
                }
                String combo = key + "|" + comboKey(optionNames, optionValues);
                if (!optionNames.isEmpty()) {
                    if (ep != null && ep.combinations().contains(comboKey(optionNames, optionValues))) {
                        m.add(err("option1_value", "이 옵션 조합의 SKU 가 이미 있습니다."));
                    } else if (comboSeen.containsKey(combo)) {
                        m.add(err("option1_value", "파일 안에서 같은 상품·옵션 조합이 반복됩니다."));
                    }
                    comboSeen.putIfAbsent(combo, idx + 1);
                }
            }
            n.put("product_code", productCode);
            n.put("product_name", productName);
            n.put("brand_id", brandId == null ? "" : brandId.toString());
            n.put("category_id", categoryId == null ? "" : categoryId.toString());
            n.put("tax_type", tax);
            n.put("option_names", String.join("\u001f", optionNames));
            n.put("option_values", String.join("\u001f", optionValues));
            n.put("sku_code", skuCode);
            n.put("sku_name", v(r, "sku_name"));
            n.put("sku_type", type);
            n.put("barcode", barcode);
            n.put("weight_g", weight);
            n.put("lot_managed", String.valueOf(lot));
            n.put("expiry_managed", String.valueOf(exp));
            n.put("min_shelf_days", minDays == null ? "" : String.valueOf(minDays));
            out.add(RowCheck.of(m, n));
        }
        return out;
    }

    @Override
    public String apply(Map<String, String> n, BatchContext ctx) {
        UUID ws = guard.actor().workspaceId();
        String code = n.get("product_code");
        List<String> optionNames = split(n.get("option_names"));
        List<String> optionValues = split(n.get("option_values"));
        UUID productId = jdbc.sql("select id from product where workspace_id = :ws and lower(product_code) = lower(:c)").param("ws", ws).param("c", code)
                .query(UUID.class).optional().orElse(null);
        if (productId == null) {
            // 새 상품: 같은 상품코드의 모든 실행 행에서 옵션 값을 모아 한 번에 만든다(SKU 생성 후 옵션 축 추가 불가, DLG-060)
            List<ProductService.NewOption> options = new ArrayList<>();
            for (int k = 0; k < optionNames.size(); k++) {
                Set<String> values = new LinkedHashSet<>();
                for (Map<String, String> other : ctx.executableRows()) {
                    if (other.get("product_code").equalsIgnoreCase(code)) {
                        List<String> ov = split(other.get("option_values"));
                        if (k < ov.size()) {
                            values.add(ov.get(k));
                        }
                    }
                }
                options.add(new ProductService.NewOption(optionNames.get(k), new ArrayList<>(values)));
            }
            productId = products.create(new ProductService.Create(code, n.get("product_name"), null, uuid(n.get("brand_id")), uuid(n.get("category_id")),
                    empty(n.get("tax_type")), null, null, null, List.of(), options)).product().id();
        }
        List<UUID> valueIds = new ArrayList<>();
        List<ProductService.OptionView> opts = products.options(productId);
        for (int k = 0; k < optionNames.size(); k++) {
            String on = optionNames.get(k);
            ProductService.OptionView opt = opts.stream().filter(o -> o.name().equalsIgnoreCase(on)).findFirst()
                    .orElseThrow(() -> Errors.unprocessable("SKU_OPTION_INVALID", "상품에 옵션 '" + on + "' 이(가) 없습니다."));
            String value = optionValues.get(k);
            UUID vid = opt.values().stream().filter(x -> x.value().equalsIgnoreCase(value)).map(ProductService.OptionValueView::id).findFirst().orElse(null);
            if (vid == null) {
                vid = products.addOptionValue(productId, opt.id(), value).values().stream().filter(x -> x.value().equalsIgnoreCase(value))
                        .map(ProductService.OptionValueView::id).findFirst().orElseThrow();
            }
            valueIds.add(vid);
        }
        skus.create(new SkuService.Create(productId, n.get("sku_code"), empty(n.get("sku_name")), n.get("sku_type"), null, null, null,
                empty(n.get("barcode")), n.get("weight_g").isEmpty() ? null : new BigDecimal(n.get("weight_g")), null, null, null,
                Boolean.parseBoolean(n.get("lot_managed")), Boolean.parseBoolean(n.get("expiry_managed")),
                n.get("min_shelf_days").isEmpty() ? null : Integer.valueOf(n.get("min_shelf_days")), valueIds));
        return n.get("sku_code");
    }

    private ExistingProduct loadProduct(UUID ws, String code) {
        return jdbc.sql("select id, name from product where workspace_id = :ws and lower(product_code) = lower(:c)").param("ws", ws).param("c", code)
                .query((rs, i) -> (UUID) rs.getObject("id")).optional().map(id -> {
                    String name = jdbc.sql("select name from product where id = :id").param("id", id).query(String.class).single();
                    List<String> optionNames = jdbc.sql("select name from product_option where product_id = :id order by sort_order").param("id", id)
                            .query(String.class).list();
                    Set<String> combos = new HashSet<>();
                    Map<UUID, Map<String, String>> bySku = new HashMap<>();
                    jdbc.sql("""
                            select sov.sku_id, o.name, v.value from sku_option_value sov join product_option o on o.id = sov.option_id
                              join product_option_value v on v.id = sov.option_value_id join sku s on s.id = sov.sku_id
                             where s.product_id = :id and s.status <> 'ARCHIVED'
                            """).param("id", id).query((rs, i) -> bySku.computeIfAbsent((UUID) rs.getObject("sku_id"), k -> new HashMap<>())
                            .put(rs.getString("name"), rs.getString("value"))).list();
                    for (Map<String, String> o : bySku.values()) {
                        combos.add(comboKey(new ArrayList<>(o.keySet()), new ArrayList<>(o.values())));
                    }
                    return new ExistingProduct(id, name, optionNames, combos);
                }).orElse(null);
    }

    /** 옵션 이름 순서와 무관한 조합 키(이름·값 대소문자 무시). */
    static String comboKey(List<String> names, List<String> values) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            parts.add(names.get(i).toLowerCase(Locale.ROOT) + "=" + values.get(i).toLowerCase(Locale.ROOT));
        }
        parts.sort(String::compareTo);
        return String.join("&", parts);
    }

    private static boolean yes(Map<String, String> r, String key, List<Message> m) {
        String s = v(r, key).toUpperCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("N") || s.equals("NO") || s.equals("아니오") || s.equals("FALSE")) {
            return false;
        }
        if (s.equals("Y") || s.equals("YES") || s.equals("예") || s.equals("TRUE")) {
            return true;
        }
        m.add(err(key, "Y 또는 N 으로 입력하세요."));
        return false;
    }

    private static Integer integer(Map<String, String> r, String key, List<Message> m) {
        String s = v(r, key);
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            m.add(err(key, "정수로 입력하세요."));
            return null;
        }
    }

    static String v(Map<String, String> r, String key) {
        String s = r.get(key);
        return s == null ? "" : s.trim();
    }

    static Message err(String field, String text) {
        return new Message("ERROR", field, text);
    }

    static Message warn(String field, String text) {
        return new Message("WARNING", field, text);
    }

    static String norm(String s) {
        return s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    static List<String> split(String s) {
        return s == null || s.isEmpty() ? List.of() : List.of(s.split("\u001f", -1));
    }

    static UUID uuid(String s) {
        return s == null || s.isEmpty() ? null : UUID.fromString(s);
    }

    static String empty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
