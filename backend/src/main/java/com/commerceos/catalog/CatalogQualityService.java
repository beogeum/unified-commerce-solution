package com.commerceos.catalog;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.Perm;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 상품정보 품질 점검(FR-CAT-08, FR-OPS-04, DLG-212·213). 읽기 전용 점검표 — 고치는 것은 담당자가 상품·채널상품·원가·공급처 화면에서.
 * 사업자에 묶인 점검(원가·공급처·재고)은 사용자 사업자 범위로, 원가 점검은 원가 조회 권한이 있을 때만 보인다.
 */
@Service
public class CatalogQualityService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;

    public CatalogQualityService(JdbcClient jdbc, AccessGuard guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    public record Issue(String code, String severity, String title, UUID businessEntityId, String businessEntityName, UUID skuId, String skuCode, UUID listingId,
                        String detail, String fix) {
    }

    public record Report(Map<String, Integer> counts, List<Issue> issues) {
    }

    static final Map<String, String[]> RULES = Map.of(
            "SKU_NO_COST", new String[]{"HIGH", "원가 미등록 SKU(재고·판매 있음)", "사업자 SKU 정책에서 원가를 등록하거나 입고 단가를 넣으세요. 손익의 매출원가가 0 으로 잡힙니다."},
            "LOT_STOCK_WITHOUT_LOT", new String[]{"HIGH", "LOT 관리 SKU 인데 LOT 없는 재고", "실사·조정으로 LOT 를 지정하세요. 선출고·유통기한 관리가 되지 않습니다."},
            "INACTIVE_SKU_WITH_STOCK", new String[]{"MEDIUM", "판매 중지 SKU 에 재고", "재고를 처분하거나 SKU 를 다시 활성화하세요."},
            "UNMAPPED_LISTING", new String[]{"MEDIUM", "SKU 미연결 채널상품", "채널상품에 SKU 를 연결하세요. 이 상품 주문은 보류됩니다."},
            "SKU_NO_BARCODE", new String[]{"LOW", "바코드 없는 판매 SKU", "바코드를 등록하면 스캔 피킹·검수를 쓸 수 있습니다."},
            "STOCK_NOT_LISTED", new String[]{"LOW", "재고는 있지만 채널상품 연결이 없는 SKU", "판매할 채널상품에 연결하거나 판매 여부를 확인하세요."},
            "SELLING_WITHOUT_SUPPLIER", new String[]{"LOW", "최근 판매가 있지만 공급처가 없는 SKU", "공급처·공급 SKU 를 등록하면 발주 제안을 받을 수 있습니다."});

    public Report check(List<String> businessEntityIds) {
        guard.requirePermission(Perm.CATALOG_READ);
        UUID ws = guard.actor().workspaceId();
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        boolean cost = guard.actor().has(Perm.COST_READ);
        Set<UUID> beParam = bes.isEmpty() ? Set.of(UUID.randomUUID()) : bes;
        List<Issue> out = new ArrayList<>();
        if (cost) {
            jdbc.sql("""
                    select x.be, x.sku_id, k.sku_code, be.display_name from (
                        select owner_business_entity_id as be, sku_id from inventory_stock where workspace_id = :ws and on_hand > 0 and owner_business_entity_id in (:bes)
                        union
                        select business_entity_id, sku_id from financial_entry where workspace_id = :ws and category = 'PRODUCT_COGS' and unit_cost is null and quantity > 0
                           and business_entity_id in (:bes)) x
                      join sku k on k.id = x.sku_id join business_entity be on be.id = x.be
                     where not exists (select 1 from sku_moving_cost m where m.business_entity_id = x.be and m.sku_id = x.sku_id)
                       and not exists (select 1 from sku_cost c where c.business_entity_id = x.be and c.sku_id = x.sku_id and c.effective_from <= now())
                     order by be.display_name, k.sku_code
                    """).param("ws", ws).param("bes", beParam).query().listOfRows()
                    .forEach(r -> out.add(issue("SKU_NO_COST", (UUID) r.get("be"), (String) r.get("display_name"), (UUID) r.get("sku_id"), (String) r.get("sku_code"), null, null)));
        }
        jdbc.sql("""
                select s.owner_business_entity_id as be, s.sku_id, k.sku_code, be.display_name, sum(s.on_hand) as q from inventory_stock s join sku k on k.id = s.sku_id
                  join business_entity be on be.id = s.owner_business_entity_id
                 where s.workspace_id = :ws and s.owner_business_entity_id in (:bes) and s.on_hand > 0 and k.lot_managed and s.lot_id is null
                 group by 1, 2, 3, 4 order by 4, 3
                """).param("ws", ws).param("bes", beParam).query().listOfRows()
                .forEach(r -> out.add(issue("LOT_STOCK_WITHOUT_LOT", (UUID) r.get("be"), (String) r.get("display_name"), (UUID) r.get("sku_id"), (String) r.get("sku_code"),
                        null, "LOT 없는 수량 " + r.get("q"))));
        jdbc.sql("""
                select s.owner_business_entity_id as be, s.sku_id, k.sku_code, k.status, be.display_name, sum(s.on_hand) as q from inventory_stock s join sku k on k.id = s.sku_id
                  join business_entity be on be.id = s.owner_business_entity_id
                 where s.workspace_id = :ws and s.owner_business_entity_id in (:bes) and s.on_hand > 0 and k.status <> 'ACTIVE'
                 group by 1, 2, 3, 4, 5 order by 5, 3
                """).param("ws", ws).param("bes", beParam).query().listOfRows()
                .forEach(r -> out.add(issue("INACTIVE_SKU_WITH_STOCK", (UUID) r.get("be"), (String) r.get("display_name"), (UUID) r.get("sku_id"), (String) r.get("sku_code"),
                        null, "상태 " + r.get("status") + " · 재고 " + r.get("q"))));
        if (!cas.isEmpty()) {
            jdbc.sql("""
                    select l.id, l.external_product_id, l.external_option_id, l.title, ca.business_entity_id as be, be.display_name, ca.display_name as ca_name
                      from channel_listing l join channel_account ca on ca.id = l.channel_account_id join business_entity be on be.id = ca.business_entity_id
                     where l.workspace_id = :ws and l.channel_account_id in (:cas) and ca.business_entity_id in (:bes)
                       and not exists (select 1 from channel_listing_mapping m where m.listing_id = l.id and m.valid_to is null)
                     order by be.display_name, ca.display_name, l.external_product_id limit 500
                    """).param("ws", ws).param("cas", cas).param("bes", beParam).query().listOfRows()
                    .forEach(r -> out.add(issue("UNMAPPED_LISTING", (UUID) r.get("be"), (String) r.get("display_name"), null, null, (UUID) r.get("id"),
                            r.get("ca_name") + " · " + r.get("external_product_id") + (String.valueOf(r.get("external_option_id")).isEmpty() ? "" : "/" + r.get("external_option_id"))
                                    + " " + r.get("title"))));
        }
        jdbc.sql("""
                select k.id, k.sku_code from sku k where k.workspace_id = :ws and k.status = 'ACTIVE' and k.sellable and k.inventory_managed and k.barcode is null
                 order by k.sku_code limit 500
                """).param("ws", ws).query().listOfRows()
                .forEach(r -> out.add(issue("SKU_NO_BARCODE", null, null, (UUID) r.get("id"), (String) r.get("sku_code"), null, null)));
        jdbc.sql("""
                select s.owner_business_entity_id as be, s.sku_id, k.sku_code, be.display_name from inventory_stock s join sku k on k.id = s.sku_id
                  join business_entity be on be.id = s.owner_business_entity_id
                 where s.workspace_id = :ws and s.owner_business_entity_id in (:bes) and s.on_hand > 0 and s.quality_status = 'SELLABLE' and k.sellable and k.sku_type <> 'GIFT'
                   and not exists (select 1 from channel_listing_mapping m join channel_listing l on l.id = m.listing_id join channel_account ca on ca.id = l.channel_account_id
                                    where m.sku_id = s.sku_id and m.valid_to is null and ca.business_entity_id = s.owner_business_entity_id)
                 group by 1, 2, 3, 4 order by 4, 3
                """).param("ws", ws).param("bes", beParam).query().listOfRows()
                .forEach(r -> out.add(issue("STOCK_NOT_LISTED", (UUID) r.get("be"), (String) r.get("display_name"), (UUID) r.get("sku_id"), (String) r.get("sku_code"), null, null)));
        jdbc.sql("""
                select t.owner_business_entity_id as be, t.sku_id, k.sku_code, be.display_name from inventory_transaction t join sku k on k.id = t.sku_id
                  join business_entity be on be.id = t.owner_business_entity_id
                 where t.workspace_id = :ws and t.owner_business_entity_id in (:bes) and t.txn_type = 'CONSUME' and t.occurred_at >= now() - interval '28 days'
                   and not exists (select 1 from supplier_sku ss join supplier sp on sp.id = ss.supplier_id where ss.sku_id = t.sku_id and sp.business_entity_id = t.owner_business_entity_id
                                    and ss.status = 'ACTIVE' and sp.status = 'ACTIVE')
                 group by 1, 2, 3, 4 order by 4, 3
                """).param("ws", ws).param("bes", beParam).query().listOfRows()
                .forEach(r -> out.add(issue("SELLING_WITHOUT_SUPPLIER", (UUID) r.get("be"), (String) r.get("display_name"), (UUID) r.get("sku_id"), (String) r.get("sku_code"),
                        null, null)));
        Map<String, Integer> counts = new LinkedHashMap<>();
        RULES.keySet().stream().sorted().forEach(k -> counts.put(k, 0));
        out.forEach(i -> counts.merge(i.code(), 1, Integer::sum));
        out.sort((a, b) -> rank(a.severity()) - rank(b.severity()));
        return new Report(counts, out);
    }

    private static Issue issue(String code, UUID be, String beName, UUID sku, String skuCode, UUID listing, String detail) {
        String[] r = RULES.get(code);
        return new Issue(code, r[0], r[1], be, beName, sku, skuCode, listing, detail, r[2]);
    }

    private static int rank(String s) {
        return switch (s) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }
}
