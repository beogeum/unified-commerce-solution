package com.commerceos.order;

import com.commerceos.catalog.GwpEvaluator;
import com.commerceos.catalog.GwpService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 구성 확정(M4 O3, docs/04 §1 'GWP 확인 → 내부 GWP 적용 → BOM 구성 고정').
 * <ul>
 *   <li>사은품: C5 판정 엔진 그대로. 채널이 넣어 준 사은품(행사 번호·SKU)과 같은 내부 행사는 기본 지급하지 않는다(T13).
 *       선착순 수량은 캠페인 행을 잠가 동시 주문이 한도를 넘지 않게 한다. 사은품 재고는 사업자 전체 판매 가능 수량.</li>
 *   <li>구성: 일반·실물세트·사은품 SKU 는 그대로, 가상묶음은 주문 시각에 유효한 판매묶음 BOM 버전을 펼쳐 고정한다(중첩 묶음도 같은 시각 기준).
 *       서비스·디지털 등 재고 미관리 SKU 는 구성 없음. 확정된 구성은 바꾸지 않는다(BOM 이 바뀌어도 이 주문은 그대로).</li>
 * </ul>
 */
@Component
public class OrderComposer {
    public enum Outcome { FIXED, GIFT_HOLD, BOM_MISSING }

    private final JdbcClient jdbc;
    private final GwpService gwp;
    private final Json json;

    public OrderComposer(JdbcClient jdbc, GwpService gwp, Json json) {
        this.jdbc = jdbc;
        this.gwp = gwp;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome compose(UUID ws, UUID orderId, UUID actor, OrderRules rules) {
        Map<String, Object> o = jdbc.sql("select * from sales_order where id = :id for update").param("id", orderId).query().listOfRows().get(0);
        if ("FIXED".equals(o.get("composition_status"))) {
            return Outcome.FIXED;
        }
        UUID be = (UUID) o.get("business_entity_id");
        UUID ca = (UUID) o.get("channel_account_id");
        Instant orderedAt = OrderRules.instant(o.get("ordered_at"));
        List<Map<String, Object>> items = jdbc.sql("""
                select i.*, s.sku_type, s.inventory_managed, p.brand_id from order_item i join sku s on s.id = i.sku_id join product p on p.id = s.product_id
                 where i.order_id = :o and i.mapping_status = 'MAPPED' and i.original_qty > i.canceled_qty order by i.line_no
                """).param("o", orderId).query().listOfRows();

        // 1) 사은품 판정 — 이전에 지급한 내부 사은품이 없을 때만(재시도 시 중복 지급 방지)
        boolean alreadyAwarded = items.stream().anyMatch(i -> "INTERNAL".equals(i.get("gift_source")));
        GwpEvaluator.Result result = null;
        if (!alreadyAwarded) {
            List<GwpEvaluator.Line> lines = new ArrayList<>();
            List<GwpEvaluator.ChannelGift> channelGifts = new ArrayList<>();
            for (Map<String, Object> i : items) {
                int units = active(i) * ((Number) i.get("sku_qty_per_unit")).intValue();
                if (Boolean.TRUE.equals(i.get("is_gift"))) {
                    channelGifts.add(new GwpEvaluator.ChannelGift((String) i.get("channel_campaign_ref"), (UUID) i.get("sku_id")));
                    continue;
                }
                BigDecimal net = ((BigDecimal) i.get("line_amount")).subtract((BigDecimal) i.get("seller_discount"));
                lines.add(new GwpEvaluator.Line((UUID) i.get("sku_id"), units, net, (UUID) i.get("brand_id")));
            }
            // 선착순 한도 직렬화: 한도 있는 캠페인 행 잠금 → 지급 집계
            jdbc.sql("select id from gwp_campaign where workspace_id = :ws and business_entity_id = :be and total_limit is not null and status = 'ACTIVE' order by id for update")
                    .param("ws", ws).param("be", be).query(UUID.class).list();
            Map<UUID, Integer> awarded = new HashMap<>();
            jdbc.sql("""
                    select i.gwp_campaign_id, sum((i.original_qty - i.canceled_qty) / greatest(coalesce(r.quantity, 1), 1)) as sets
                      from order_item i join sales_order so on so.id = i.order_id
                      left join gwp_reward r on r.campaign_id = i.gwp_campaign_id and r.gift_sku_id = i.sku_id
                     where i.workspace_id = :ws and i.gift_source = 'INTERNAL' and so.business_entity_id = :be group by i.gwp_campaign_id
                    """).param("ws", ws).param("be", be).query().listOfRows()
                    .forEach(r -> awarded.put((UUID) r.get("gwp_campaign_id"), ((Number) r.get("sets")).intValue()));
            List<GwpEvaluator.Campaign> campaigns = gwp.engineCampaigns(ws, be, awarded);
            Map<UUID, Long> stock = new HashMap<>();
            for (GwpEvaluator.Campaign c : campaigns) {
                for (GwpEvaluator.Reward r : c.rewards()) {
                    stock.computeIfAbsent(r.giftSkuId(), sku -> giftAtp(ws, be, sku));
                }
            }
            result = GwpEvaluator.evaluate(new GwpEvaluator.Order(be, ca, orderedAt, lines, channelGifts), campaigns, stock);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("awards", result.awards());
            summary.put("skipped", result.skipped());
            summary.put("hold", result.holdOrder());
            summary.put("evaluated_at", Instant.now().toString());
            jdbc.sql("update sales_order set gwp_result = cast(:r as jsonb), gwp_hold = :h where id = :id").param("r", json.write(summary))
                    .param("h", result.holdOrder()).param("id", orderId).update();
            if (result.holdOrder()) {
                rules.event(ws, orderId, null, "GWP_HOLD", null, null, Map.of("skipped", result.skipped()), actor);
                return Outcome.GIFT_HOLD;
            }
        }

        // 2) 구성 펼치기(가상묶음 = 주문 시각 BOM 버전, 없으면 현재 버전)
        List<Map<String, Object>> plan = new ArrayList<>();
        for (Map<String, Object> i : items) {
            Optional<List<Object[]>> comps = expand(ws, (UUID) i.get("sku_id"), (String) i.get("sku_type"), Boolean.TRUE.equals(i.get("inventory_managed")),
                    ((Number) i.get("sku_qty_per_unit")).intValue(), orderedAt, 0);
            if (comps.isEmpty()) {
                rules.event(ws, orderId, (UUID) i.get("id"), "BOM_MISSING", null, (String) i.get("product_name"), Map.of(), actor);
                return Outcome.BOM_MISSING;
            }
            for (Object[] c : comps.get()) {
                plan.add(Map.of("item", i.get("id"), "c", c));
            }
        }
        if (result != null) {
            int line = jdbc.sql("select coalesce(max(line_no), 0) from order_item where order_id = :o").param("o", orderId).query(Integer.class).single();
            for (GwpEvaluator.Award a : result.awards()) {
                UUID itemId = Ids.newId();
                String taxType = jdbc.sql("select p.tax_type from sku s join product p on p.id = s.product_id where s.id = :s").param("s", a.giftSkuId())
                        .query(String.class).optional().orElse(null);
                String skuName = jdbc.sql("select coalesce(s.name, p.name) from sku s join product p on p.id = s.product_id where s.id = :s").param("s", a.giftSkuId())
                        .query(String.class).single();
                jdbc.sql("""
                        insert into order_item (id, workspace_id, order_id, line_no, external_item_key, external_product_id, external_option_id, product_name, option_name,
                            sku_id, sku_qty_per_unit, mapping_status, original_qty, unit_price, line_amount, tax_type, is_gift, gift_source, gwp_campaign_id)
                        values (:id, :ws, :o, :line, :k, 'GWP', '', :name, :cname, :s, 1, 'MAPPED', :q, 0, 0, cast(:tt as text), true, 'INTERNAL', :c)
                        """).param("id", itemId).param("ws", ws).param("o", orderId).param("line", ++line).param("k", "GWP:" + a.campaignId() + ":" + a.giftSkuId())
                        .param("name", skuName).param("cname", "사은품 · " + a.campaignName()).param("s", a.giftSkuId()).param("q", a.quantity()).param("tt", taxType)
                        .param("c", a.campaignId()).update();
                plan.add(Map.of("item", itemId, "c", new Object[]{a.giftSkuId(), 1, "GIFT", null, null, false}));
                rules.event(ws, orderId, itemId, "GWP_AWARDED", null, a.campaignName(), Map.of("campaign_id", a.campaignId().toString(), "quantity", a.quantity(),
                        "substitute", a.substitute()), actor);
            }
        }
        Map<String, Object[]> merged = new LinkedHashMap<>();
        for (Map<String, Object> p : plan) {
            Object[] c = (Object[]) p.get("c");
            String k = p.get("item") + ":" + c[0];
            Object[] prev = merged.get(k);
            if (prev == null) {
                merged.put(k, new Object[]{p.get("item"), c[0], c[1], c[2], c[3], c[4], c[5]});
            } else {
                prev[2] = (Integer) prev[2] + (Integer) c[1];
            }
        }
        for (Object[] m : merged.values()) {
            jdbc.sql("""
                    insert into order_item_component (id, workspace_id, order_id, order_item_id, component_sku_id, quantity_per_unit, source, bom_id, bom_version,
                        split_shipment_allowed)
                    values (:id, :ws, :o, :i, :s, :q, :src, cast(:bom as uuid), cast(:v as integer), :split)
                    """).param("id", Ids.newId()).param("ws", ws).param("o", orderId).param("i", m[0]).param("s", m[1]).param("q", m[2]).param("src", m[3])
                    .param("bom", m[4]).param("v", m[5]).param("split", m[6]).update();
        }
        jdbc.sql("update sales_order set composition_status = 'FIXED', composed_at = now(), gwp_hold = false, version = version + 1 where id = :id")
                .param("id", orderId).update();
        rules.event(ws, orderId, null, "COMPOSITION_FIXED", null, String.valueOf(merged.size()), Map.of(), actor);
        return Outcome.FIXED;
    }

    /**
     * SKU 1단위(× perUnit)를 재고 SKU 로 펼친다. 반환 원소 = {skuId, quantity, source, bomId, bomVersion, splitAllowed}.
     * 가상묶음에 그 시각 BOM 이 없으면 빈 값(보류 사유).
     */
    private Optional<List<Object[]>> expand(UUID ws, UUID sku, String type, boolean inventoryManaged, int perUnit, Instant at, int depth) {
        if (!"BUNDLE".equals(type)) {
            return Optional.of(inventoryManaged ? List.<Object[]>of(new Object[]{sku, perUnit, "DIRECT", null, null, false}) : List.of());
        }
        if (depth > 10) {
            return Optional.empty();
        }
        // 주문 시각에 유효한 판매묶음 BOM 버전, 그때 BOM 이 없었으면(설정 지연) 지금 유효한 버전 — 어느 쪽이든 확정 후에는 고정
        Optional<Map<String, Object>> bom = jdbc.sql("""
                select id, version, split_shipment_allowed, case when valid_from <= :at and (valid_to is null or valid_to > :at) then 0 else 1 end as pref
                  from bom where workspace_id = :ws and parent_sku_id = :s and bom_type = 'SALES_BUNDLE' and status in ('ACTIVE', 'RETIRED')
                   and ((valid_from <= :at and (valid_to is null or valid_to > :at)) or (valid_from <= now() and (valid_to is null or valid_to > now())))
                 order by pref, version desc limit 1
                """).param("ws", ws).param("s", sku).param("at", Db.ts(at)).query().listOfRows().stream().findFirst();
        if (bom.isEmpty()) {
            return Optional.empty();
        }
        UUID bomId = (UUID) bom.get().get("id");
        int version = ((Number) bom.get().get("version")).intValue();
        boolean split = Boolean.TRUE.equals(bom.get().get("split_shipment_allowed"));
        List<Object[]> out = new ArrayList<>();
        for (Map<String, Object> c : jdbc.sql("""
                select bc.component_sku_id, bc.quantity, s.sku_type, s.inventory_managed from bom_component bc join sku s on s.id = bc.component_sku_id
                 where bc.bom_id = :b order by bc.sort_order
                """).param("b", bomId).query().listOfRows()) {
            Optional<List<Object[]>> nested = expand(ws, (UUID) c.get("component_sku_id"), (String) c.get("sku_type"), Boolean.TRUE.equals(c.get("inventory_managed")),
                    perUnit * ((Number) c.get("quantity")).intValue(), at, depth + 1);
            if (nested.isEmpty()) {
                return Optional.empty();
            }
            for (Object[] n : nested.get()) {
                out.add(new Object[]{n[0], n[1], "BOM", bomId, version, split});
            }
        }
        return Optional.of(out);
    }

    /** 사은품 재고: 사업자 전체 판매 가능 수량(거점 ATP 합 − 안전재고). */
    private long giftAtp(UUID ws, UUID be, UUID sku) {
        long atp = jdbc.sql("""
                select coalesce(sum(greatest(sellable_on_hand - committed - restricted_hold, 0)), 0) from inventory_position
                 where workspace_id = :ws and owner_business_entity_id = :b and sku_id = :s
                """).param("ws", ws).param("b", be).param("s", sku).query(Long.class).single();
        int safety = jdbc.sql("select coalesce(max(safety_stock), 0) from business_sku_policy where business_entity_id = :b and sku_id = :s").param("b", be)
                .param("s", sku).query(Integer.class).single();
        return Math.max(0, atp - safety);
    }

    private static int active(Map<String, Object> i) {
        return ((Number) i.get("original_qty")).intValue() - ((Number) i.get("canceled_qty")).intValue();
    }
}
