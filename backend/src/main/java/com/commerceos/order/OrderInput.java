package com.commerceos.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 표준 주문(커넥터 매핑 결과 = 채널 원본을 표준 필드로 옮긴 것). 금액은 부가세 포함 원 단위.
 * 외부 키 구성(DLG-112): externalOrderKey(주문번호) + 주문상품 externalItemKey(상품주문번호 또는 원천 줄 번호).
 */
public record OrderInput(String externalOrderKey, String sourceType, String salesUnitKey, Instant orderedAt, Instant paidAt, String externalStatus,
                         Instant externalUpdatedAt, BigDecimal shippingFee, BigDecimal totalAmount, Party buyer, Recipient recipient, List<Item> items) {

    public record Party(String name, String phone, String email) {
    }

    public record Recipient(String name, String phone, String postalCode, String address1, String address2, String memo) {
    }

    public record Item(String externalItemKey, String externalProductId, String externalOptionId, String productName, String optionName, Integer quantity,
                       Integer canceledQuantity, BigDecimal unitPrice, BigDecimal lineAmount, BigDecimal sellerDiscount, BigDecimal channelDiscount,
                       String externalStatus, Boolean gift, String channelCampaignRef) {
    }

    /** 빈 구매자·수령인·상품 목록을 빈 값 객체로 채운다(이후 단계가 null 을 다루지 않게). */
    public OrderInput withDefaults() {
        return new OrderInput(externalOrderKey, sourceType, salesUnitKey, orderedAt, paidAt, externalStatus, externalUpdatedAt, shippingFee, totalAmount,
                buyer == null ? new Party(null, null, null) : buyer, recipient == null ? new Recipient(null, null, null, null, null, null) : recipient,
                items == null ? List.of() : items);
    }

    /** 커넥터가 돌려준 표준 필드 Map(snake_case) → OrderInput. */
    @SuppressWarnings("unchecked")
    public static OrderInput fromMap(Map<String, Object> m) {
        List<Item> items = new ArrayList<>();
        Object raw = m.get("items");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> i = (Map<String, Object>) o;
                items.add(new Item(str(i.get("external_item_key")), str(i.get("external_product_id")), str(i.get("external_option_id")),
                        str(i.get("product_name")), str(i.get("option_name")), integer(i.get("quantity")), integer(i.get("canceled_quantity")),
                        money(i.get("unit_price")), money(i.get("line_amount")), money(i.get("seller_discount")), money(i.get("channel_discount")),
                        str(i.get("external_status")), Boolean.TRUE.equals(i.get("gift")) || "true".equals(String.valueOf(i.get("gift"))),
                        str(i.get("channel_campaign_ref"))));
            }
        }
        Map<String, Object> b = m.get("buyer") instanceof Map<?, ?> x ? (Map<String, Object>) x : Map.of();
        Map<String, Object> r = m.get("recipient") instanceof Map<?, ?> x ? (Map<String, Object>) x : Map.of();
        return new OrderInput(str(m.get("external_order_key")), str(m.get("source_type")), str(m.get("sales_unit_key")), instant(m.get("ordered_at")),
                instant(m.get("paid_at")), str(m.get("external_status")), instant(m.get("external_updated_at")), money(m.get("shipping_fee")),
                money(m.get("total_amount")), new Party(str(b.get("name")), str(b.get("phone")), str(b.get("email"))),
                new Recipient(str(r.get("name")), str(r.get("phone")), str(r.get("postal_code")), str(r.get("address1")), str(r.get("address2")),
                        str(r.get("memo"))), items);
    }

    /** 원본 보관용 마스킹 사본: 개인정보 키의 값을 가린다. */
    @SuppressWarnings("unchecked")
    static Object maskPayload(Object v, String key) {
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, val) -> out.put(String.valueOf(k), maskPayload(val, String.valueOf(k))));
            return out;
        }
        if (v instanceof List<?> list) {
            return list.stream().map(x -> maskPayload(x, key)).toList();
        }
        if (v != null && key != null && Pii.isPiiKey(key)) {
            return Pii.maskGeneric(String.valueOf(v));
        }
        return v;
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).trim();
    }

    private static Integer integer(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal money(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return new BigDecimal(o.toString().trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant instant(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return o instanceof Instant i ? i : java.time.OffsetDateTime.parse(o.toString()).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            try {
                return Instant.parse(o.toString());
            } catch (java.time.format.DateTimeParseException e2) {
                return null;
            }
        }
    }
}
