package com.commerceos.catalog;

import static com.commerceos.catalog.ProductSkuImport.err;
import static com.commerceos.catalog.ProductSkuImport.v;
import static com.commerceos.catalog.ProductSkuImport.warn;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.Perm;
import com.commerceos.identity.access.ResolvedScope;
import com.commerceos.operations.imports.ImportTypeHandler;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 채널상품 일괄 등록·연결 양식 v1. 판매계정은 판매채널 코드 + 판매계정 외부키로 찾는다(외부 ID 를 내부 PK 로 쓰지 않음).
 * 다른 SKU 에 연결된 이력이 있는 채널상품의 재연결은 파일로 하지 않는다(화면에서 확인·사유, DLG-069).
 */
@Component
public class ChannelListingImport implements ImportTypeHandler {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final ChannelListingService listings;

    public ChannelListingImport(JdbcClient jdbc, AccessGuard guard, ChannelListingService listings) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.listings = listings;
    }

    @Override
    public String importType() {
        return "CHANNEL_LISTING";
    }

    @Override
    public String templateVersion() {
        return "v1";
    }

    @Override
    public String labelKo() {
        return "채널상품 등록·연결";
    }

    @Override
    public String requiredPermission() {
        return Perm.CATALOG_MANAGE;
    }

    @Override
    public List<Field> fields() {
        return List.of(
                new Field("sales_channel_code", "판매채널 코드", true, List.of("채널", "channel"), "NAVER_SMARTSTORE"),
                new Field("account_key", "판매계정 외부키", true, List.of("판매계정키", "account key", "판매자 ID"), "seller-001"),
                new Field("unit_key", "판매단위 키", false, List.of("shop_no", "판매단위"), ""),
                new Field("external_product_id", "외부 상품ID", true, List.of("상품번호", "product id"), "9921"),
                new Field("external_option_id", "외부 옵션ID", false, List.of("옵션번호", "option id"), "2289101"),
                new Field("title", "채널 상품명", true, List.of("상품명", "title"), "비타 앰플 30ml"),
                new Field("option_text", "옵션명", false, List.of("option"), "30ml"),
                new Field("barcode", "바코드", false, List.of("gtin"), ""),
                new Field("external_sku_code", "판매자 관리코드", false, List.of("외부 sku 코드", "관리코드"), "AMP-30"),
                new Field("channel_price", "채널가", false, List.of("판매가", "price"), "33000"),
                new Field("channel_stock", "채널 재고", false, List.of("재고", "stock"), ""),
                new Field("sku_code", "연결 SKU 코드", false, List.of("내부 sku", "sku 코드"), "AMP-30"),
                new Field("quantity", "연결 수량", false, List.of("수량"), "1"));
    }

    record Account(UUID id, UUID businessEntityId) {
    }

    @Override
    public List<RowCheck> validate(List<Map<String, String>> rows) {
        UUID ws = guard.actor().workspaceId();
        ResolvedScope scope = guard.scope();
        Set<String> seen = new HashSet<>();
        List<RowCheck> out = new ArrayList<>();
        for (Map<String, String> r : rows) {
            List<Message> m = new ArrayList<>();
            Map<String, String> n = new LinkedHashMap<>();
            String channel = v(r, "sales_channel_code").toUpperCase(Locale.ROOT);
            String key = v(r, "account_key");
            Optional<Account> acc = channel.isEmpty() || key.isEmpty() ? Optional.empty() : jdbc.sql("""
                    select id, business_entity_id from channel_account where workspace_id = :ws and sales_channel_code = :c and external_account_key = :k
                       and status <> 'CLOSED'
                    """).param("ws", ws).param("c", channel).param("k", key)
                    .query((rs, i) -> new Account((UUID) rs.getObject("id"), (UUID) rs.getObject("business_entity_id"))).optional();
            // 범위 밖 계정과 없는 계정을 구분해 알려 주지 않는다(존재 여부 비노출, DEC-F01)
            if (acc.isEmpty() || !scope.allows(AccessKind.WRITE, acc.get().businessEntityId(), acc.get().id())) {
                m.add(err("account_key", "판매계정을 찾을 수 없거나 수정 권한이 없습니다."));
            }
            UUID unitId = null;
            if (acc.isPresent()) {
                String unitKey = v(r, "unit_key");
                unitId = (unitKey.isEmpty()
                        ? jdbc.sql("select id from sales_unit where channel_account_id = :ca and is_default").param("ca", acc.get().id())
                        : jdbc.sql("select id from sales_unit where channel_account_id = :ca and external_unit_key = :k").param("ca", acc.get().id()).param("k", unitKey))
                        .query(UUID.class).optional().orElse(null);
                if (unitId == null) {
                    m.add(err("unit_key", "판매단위 '" + unitKey + "' 를 찾을 수 없습니다."));
                }
            }
            String productId = v(r, "external_product_id");
            String optionId = v(r, "external_option_id");
            if (productId.isEmpty()) {
                m.add(err("external_product_id", "외부 상품ID 는 필수입니다."));
            }
            if (v(r, "title").isEmpty()) {
                m.add(err("title", "채널 상품명은 필수입니다."));
            }
            if (unitId != null && !productId.isEmpty() && !seen.add(unitId + "|" + productId + "|" + optionId)) {
                m.add(err("external_product_id", "파일 안에서 같은 판매단위·외부 상품·옵션이 반복됩니다."));
            }
            String price = v(r, "channel_price").replace(",", "");
            if (!price.isEmpty()) {
                try {
                    BusinessSkuPolicyService.amount("channel_price", new BigDecimal(price));
                } catch (RuntimeException e) {
                    m.add(err("channel_price", "채널가는 0 이상의 원 단위 정수로 입력하세요."));
                }
            }
            String stock = v(r, "channel_stock");
            if (!stock.isEmpty() && !stock.matches("^\\d{1,9}$")) {
                m.add(err("channel_stock", "채널 재고는 0 이상의 정수로 입력하세요."));
            }
            String skuCode = v(r, "sku_code");
            UUID skuId = null;
            if (!skuCode.isEmpty()) {
                skuId = jdbc.sql("""
                        select s.id from sku s join product p on p.id = s.product_id
                         where s.workspace_id = :ws and lower(s.sku_code) = lower(:c) and s.status = 'ACTIVE' and s.sellable and p.status <> 'ARCHIVED'
                        """).param("ws", ws).param("c", skuCode).query(UUID.class).optional().orElse(null);
                if (skuId == null) {
                    m.add(err("sku_code", "연결할 SKU '" + skuCode + "' 가 없거나 판매할 수 없는 상태입니다."));
                }
            }
            String qty = v(r, "quantity");
            if (!qty.isEmpty() && !(qty.matches("^\\d{1,4}$") && Integer.parseInt(qty) >= 1 && Integer.parseInt(qty) <= 1000)) {
                m.add(err("quantity", "연결 수량은 1~1000 입니다."));
            }
            if (unitId != null && !productId.isEmpty()) {
                UUID existing = jdbc.sql("select id from channel_listing where sales_unit_id = :su and external_product_id = :p and external_option_id = :o")
                        .param("su", unitId).param("p", productId).param("o", optionId).query(UUID.class).optional().orElse(null);
                if (existing != null) {
                    UUID current = jdbc.sql("select sku_id from channel_listing_mapping where listing_id = :l and valid_to is null").param("l", existing)
                            .query(UUID.class).optional().orElse(null);
                    boolean otherHistory = skuId != null && jdbc.sql("select count(*) from channel_listing_mapping where listing_id = :l and sku_id <> :s")
                            .param("l", existing).param("s", skuId).query(Integer.class).single() > 0;
                    if (skuId == null) {
                        m.add(warn("external_product_id", "이미 등록된 채널상품입니다 — 건너뜁니다."));
                    } else if (skuId.equals(current)) {
                        m.add(warn("sku_code", "이미 같은 SKU 에 연결되어 있습니다 — 변경 없음."));
                    } else if (otherHistory) {
                        m.add(err("sku_code", "다른 SKU 에 연결된 이력이 있습니다. [채널상품·연결] 화면에서 확인·사유를 남기고 다시 연결하세요."));
                    } else {
                        m.add(warn("external_product_id", "이미 등록된 채널상품입니다 — 연결만 반영합니다."));
                    }
                }
            }
            n.put("sales_unit_id", unitId == null ? "" : unitId.toString());
            n.put("external_product_id", productId);
            n.put("external_option_id", optionId);
            n.put("title", v(r, "title"));
            n.put("option_text", v(r, "option_text"));
            n.put("barcode", v(r, "barcode"));
            n.put("external_sku_code", v(r, "external_sku_code"));
            n.put("channel_price", price);
            n.put("channel_stock", stock);
            n.put("sku_id", skuId == null ? "" : skuId.toString());
            n.put("quantity", qty.isEmpty() ? "1" : qty);
            out.add(RowCheck.of(m, n));
        }
        return out;
    }

    @Override
    public String apply(Map<String, String> n, BatchContext ctx) {
        UUID unit = UUID.fromString(n.get("sales_unit_id"));
        UUID listingId = jdbc.sql("select id from channel_listing where sales_unit_id = :su and external_product_id = :p and external_option_id = :o")
                .param("su", unit).param("p", n.get("external_product_id")).param("o", n.get("external_option_id")).query(UUID.class).optional()
                .orElse(null);
        if (listingId == null) {
            listingId = listings.create(new ChannelListingService.Create(unit, n.get("external_product_id"), n.get("external_option_id"),
                    ProductSkuImport.empty(n.get("external_sku_code")), n.get("title"), ProductSkuImport.empty(n.get("option_text")),
                    ProductSkuImport.empty(n.get("barcode")), n.get("channel_price").isEmpty() ? null : new BigDecimal(n.get("channel_price")),
                    n.get("channel_stock").isEmpty() ? null : Integer.valueOf(n.get("channel_stock")), null)).listing().id();
        }
        if (!n.get("sku_id").isEmpty()) {
            listings.map(listingId, new ChannelListingService.MapRequest(UUID.fromString(n.get("sku_id")), Integer.valueOf(n.get("quantity")), "FILE",
                    null, false));
        }
        return n.get("external_product_id") + (n.get("external_option_id").isEmpty() ? "" : "/" + n.get("external_option_id"));
    }
}
