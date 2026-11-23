package com.commerceos.order;

import com.commerceos.operations.WorkItemService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 상품 연결·검증·보류·상태 파생(M4 O2, docs/04 §1~3, DLG-113).
 * 시스템 보류 사유는 검증할 때마다 다시 계산하고, 사람이 건 보류(MANUAL)는 사람이 풀 때까지 유지한다.
 */
@Component
public class OrderRules {
    static final Set<String> SYSTEM_REASONS = Set.of("UNMAPPED_ITEM", "AMOUNT_MISMATCH", "RECIPIENT_MISSING", "SKU_NOT_SELLABLE", "GIFT_OUT_OF_STOCK", "BOM_MISSING");
    private final JdbcClient jdbc;
    private final WorkItemService workItems;
    private final Json json;
    private final OrderComposer composer;

    public OrderRules(JdbcClient jdbc, WorkItemService workItems, Json json, OrderComposer composer) {
        this.composer = composer;
        this.jdbc = jdbc;
        this.workItems = workItems;
        this.json = json;
    }

    /**
     * 미연결 주문상품을 채널상품 매핑으로 연결한다: 주문 시각에 유효했던 매핑, 그때 매핑이 없었으면 현재 열린 매핑.
     * 채널상품이 아직 없으면 미연결 채널상품으로 만들어 채널상품 화면(C3)에서 연결할 수 있게 한다. @return 새로 연결한 줄 수
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int mapItems(UUID ws, UUID orderId, UUID actor) {
        Map<String, Object> o = jdbc.sql("select channel_account_id, sales_unit_id, ordered_at from sales_order where id = :id").param("id", orderId).query().listOfRows().get(0);
        UUID ca = (UUID) o.get("channel_account_id");
        UUID su = (UUID) o.get("sales_unit_id");
        Instant orderedAt = instant(o.get("ordered_at"));
        int mapped = 0;
        for (Map<String, Object> i : jdbc.sql("select * from order_item where order_id = :o and mapping_status = 'UNMAPPED' order by line_no for update")
                .param("o", orderId).query().listOfRows()) {
            String product = (String) i.get("external_product_id");
            String option = (String) i.get("external_option_id");
            jdbc.sql("""
                    insert into channel_listing (id, workspace_id, channel_account_id, sales_unit_id, external_product_id, external_option_id, title, option_text, source,
                        source_ref)
                    values (:id, :ws, :ca, :su, :p, :o, :t, cast(:ot as text), 'API', 'order')
                    on conflict (sales_unit_id, external_product_id, external_option_id) do nothing
                    """).param("id", Ids.newId()).param("ws", ws).param("ca", ca).param("su", su).param("p", product).param("o", option)
                    .param("t", i.get("product_name")).param("ot", i.get("option_name")).update();
            UUID listing = jdbc.sql("select id from channel_listing where sales_unit_id = :su and external_product_id = :p and external_option_id = :o")
                    .param("su", su).param("p", product).param("o", option).query(UUID.class).single();
            List<Map<String, Object>> m = jdbc.sql("""
                    select id, sku_id, quantity, case when valid_from <= :at and (valid_to is null or valid_to > :at) then 0 else 1 end as pref
                      from channel_listing_mapping where listing_id = :l
                       and ((valid_from <= :at and (valid_to is null or valid_to > :at)) or valid_to is null)
                     order by pref limit 1
                    """).param("l", listing).param("at", Db.ts(orderedAt)).query().listOfRows();
            if (m.isEmpty()) {
                jdbc.sql("update order_item set listing_id = :l where id = :id").param("l", listing).param("id", i.get("id")).update();
                continue;
            }
            Map<String, Object> hit = m.get(0);
            UUID sku = (UUID) hit.get("sku_id");
            String taxType = jdbc.sql("select p.tax_type from sku s join product p on p.id = s.product_id where s.id = :s").param("s", sku).query(String.class)
                    .optional().orElse(null);
            jdbc.sql("""
                    update order_item set listing_id = :l, mapping_id = :m, sku_id = :s, sku_qty_per_unit = :q, mapping_status = 'MAPPED', tax_type = cast(:tt as text),
                           version = version + 1, updated_at = now() where id = :id
                    """).param("l", listing).param("m", hit.get("id")).param("s", sku).param("q", ((Number) hit.get("quantity")).intValue()).param("tt", taxType)
                    .param("id", i.get("id")).update();
            event(ws, orderId, (UUID) i.get("id"), "ITEM_MAPPED", null, sku.toString(),
                    Map.of("mapping_id", hit.get("id").toString(), "basis", ((Number) hit.get("pref")).intValue() == 0 ? "AT_ORDER_TIME" : "CURRENT_MAPPING"), actor);
            mapped++;
        }
        return mapped;
    }

    /** 검증 → 보류 사유 재계산 → 상태 파생 → 업무함. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reevaluate(UUID ws, UUID orderId, UUID actor, UUID connectionId) {
        Map<String, Object> o = jdbc.sql("select * from sales_order where id = :id for update").param("id", orderId).query().listOfRows().get(0);
        List<Map<String, Object>> items = jdbc.sql("""
                select i.*, s.sellable, s.status as sku_status, p.sell_enabled, p.min_price from order_item i left join sku s on s.id = i.sku_id
                  left join business_sku_policy p on p.business_entity_id = :be and p.sku_id = i.sku_id
                 where i.order_id = :o order by i.line_no
                """).param("be", o.get("business_entity_id")).param("o", orderId).query().listOfRows();
        Set<String> reasons = new LinkedHashSet<>();
        List<String> causes = new ArrayList<>();
        BigDecimal lines = BigDecimal.ZERO;
        BigDecimal discounts = BigDecimal.ZERO;
        boolean anyActive = false;
        List<String> belowMin = new ArrayList<>();
        for (Map<String, Object> i : items) {
            int active = ((Number) i.get("original_qty")).intValue() - ((Number) i.get("canceled_qty")).intValue();
            lines = lines.add((BigDecimal) i.get("line_amount"));
            discounts = discounts.add((BigDecimal) i.get("seller_discount")).add((BigDecimal) i.get("channel_discount"));
            if (active <= 0) {
                continue;
            }
            anyActive = true;
            if ("UNMAPPED".equals(i.get("mapping_status"))) {
                reasons.add("UNMAPPED_ITEM");
                causes.add(i.get("line_no") + "번 상품(" + i.get("product_name") + ")이 SKU 에 연결되지 않았습니다.");
            } else if (!Boolean.TRUE.equals(i.get("is_gift"))
                    && (!Boolean.TRUE.equals(i.get("sellable")) || !"ACTIVE".equals(i.get("sku_status")) || Boolean.FALSE.equals(i.get("sell_enabled")))) {
                // 사은품 줄은 판매용 SKU 가 아니어도 된다(무상 증정)
                reasons.add("SKU_NOT_SELLABLE");
                causes.add(i.get("line_no") + "번 상품의 SKU 가 판매 불가 상태이거나 사업자 판매 설정이 꺼져 있습니다.");
            }
            BigDecimal min = (BigDecimal) i.get("min_price");
            // 교환 대체품(0원)은 판매가 검사 대상이 아니다(원판매와 분리, DLG-141)
            if (min != null && !"EXCHANGE_REPLACEMENT".equals(i.get("item_kind"))) {
                int qty = ((Number) i.get("original_qty")).intValue();
                BigDecimal net = ((BigDecimal) i.get("line_amount")).subtract((BigDecimal) i.get("seller_discount"));
                if (net.compareTo(min.multiply(BigDecimal.valueOf(qty))) < 0) {
                    belowMin.add(i.get("line_no") + "번 상품");
                }
            }
        }
        BigDecimal expected = lines.subtract(discounts).add((BigDecimal) o.get("shipping_fee"));
        if (expected.compareTo((BigDecimal) o.get("total_amount")) != 0) {
            reasons.add("AMOUNT_MISMATCH");
            causes.add("상품 금액 − 할인 + 배송비(" + expected.toPlainString() + "원)와 결제 금액(" + ((BigDecimal) o.get("total_amount")).toPlainString() + "원)이 다릅니다.");
        }
        if (!"PLATFORM_FULFILLMENT".equals(o.get("source_type")) && anyActive
                && (o.get("recipient_name_masked") == null || o.get("recipient_phone_masked") == null || o.get("address_masked") == null)) {
            reasons.add("RECIPIENT_MISSING");
            causes.add("수령인 이름·연락처·주소가 빠져 있습니다.");
        }
        // O3: 문제가 없고 결제된 주문은 사은품 지급·구성 확정(한 번만)
        if (reasons.isEmpty() && anyActive && o.get("paid_at") != null && "PENDING".equals(o.get("composition_status"))) {
            OrderComposer.Outcome out = composer.compose(ws, orderId, actor, this);
            if (out == OrderComposer.Outcome.GIFT_HOLD) {
                reasons.add("GIFT_OUT_OF_STOCK");
                causes.add("사은품 재고가 없어 행사 설정(주문 보류)에 따라 보류했습니다. 사은품이 입고되면 다시 연결·검증하세요.");
            } else if (out == OrderComposer.Outcome.BOM_MISSING) {
                reasons.add("BOM_MISSING");
                causes.add("가상묶음 SKU 에 주문 시각에 유효한 판매묶음 BOM 이 없습니다. BOM 을 게시한 뒤 다시 연결·검증하세요.");
            }
        }
        String[] prev = textArray(o.get("hold_reasons"));
        Set<String> next = new LinkedHashSet<>(reasons);
        for (String r : prev) {
            if (!SYSTEM_REASONS.contains(r)) {
                next.add(r); // MANUAL 등 사람이 건 보류는 유지
            }
        }
        String status = (String) o.get("status");
        String newStatus = status;
        if (!anyActive) {
            newStatus = "CANCELLED";
        } else if ("RECEIVED".equals(status) && o.get("paid_at") != null) {
            newStatus = "CONFIRMED";
        }
        jdbc.sql("""
                update sales_order set hold_reasons = cast(:r as text[]), on_hold = :h, status = :st, version = version + 1, updated_at = now(),
                       closed_at = case when :st in ('CANCELLED', 'COMPLETED') then coalesce(closed_at, now()) else closed_at end where id = :id
                """).param("r", next.toArray(new String[0])).param("h", !next.isEmpty()).param("st", newStatus).param("id", orderId).update();
        Set<String> before = Set.of(prev);
        if (!before.equals(next)) {
            event(ws, orderId, null, next.isEmpty() ? "HOLD_RELEASED" : "HOLD_CHANGED", String.join(",", prev), String.join(",", next), Map.of(), actor);
        }
        if (!newStatus.equals(status)) {
            event(ws, orderId, null, "STATUS_CHANGED", status, newStatus, Map.of(), actor);
        }
        String holdKey = "order:" + orderId + ":HOLD";
        if (!reasons.isEmpty() && !"CANCELLED".equals(newStatus)) {
            workItems.raise(new WorkItemService.Raise(ws, (UUID) o.get("business_entity_id"), (UUID) o.get("channel_account_id"), connectionId, "ORDER_ON_HOLD",
                    reasons.contains("AMOUNT_MISMATCH") ? "HIGH" : "MEDIUM", "주문 보류: " + o.get("order_no"), String.join(" ", causes),
                    "미연결 상품은 채널상품·연결 화면에서 SKU 를 연결하면 자동으로 다시 검증됩니다. 금액·수령인 문제는 판매채널 원본을 확인하세요.", holdKey,
                    Map.of("order_id", orderId.toString(), "reasons", List.copyOf(reasons))));
        } else {
            workItems.resolveByKey(ws, holdKey);
        }
        String priceKey = "order:" + orderId + ":BELOW_MIN_PRICE";
        if (!belowMin.isEmpty()) {
            workItems.raise(new WorkItemService.Raise(ws, (UUID) o.get("business_entity_id"), (UUID) o.get("channel_account_id"), connectionId,
                    "ORDER_PRICE_BELOW_MIN", "MEDIUM", "최저판매가 미만 판매: " + o.get("order_no"), String.join(", ", belowMin) + " 이 사업자 최저판매가보다 낮게 팔렸습니다.",
                    "채널 판매가·할인 설정을 확인하세요(주문은 보류하지 않음).", priceKey, Map.of("order_id", orderId.toString())));
        }
    }

    /** 행 Map 의 timestamptz 값(드라이버에 따라 Timestamp·OffsetDateTime) → Instant. */
    static Instant instant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (v instanceof java.time.OffsetDateTime o) {
            return o.toInstant();
        }
        return (Instant) v;
    }

    static String[] textArray(Object v) {
        if (v == null) {
            return new String[0];
        }
        try {
            return v instanceof java.sql.Array a ? (String[]) a.getArray() : (String[]) v;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    void event(UUID ws, UUID orderId, UUID itemId, String type, String from, String to, Map<String, Object> detail, UUID actor) {
        jdbc.sql("""
                insert into order_event (id, workspace_id, order_id, order_item_id, event_type, from_value, to_value, detail, actor_type, actor_member_id)
                values (:id, :ws, :o, cast(:i as uuid), :t, cast(:f as text), cast(:to as text), cast(:d as jsonb), :at, cast(:m as uuid))
                """).param("id", Ids.newId()).param("ws", ws).param("o", orderId).param("i", itemId).param("t", type).param("f", from).param("to", to)
                .param("d", json.write(detail)).param("at", actor == null ? "SYSTEM" : "USER").param("m", actor).update();
    }
}
