package com.commerceos.order;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.crypto.SecretCipher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.json.Json;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.PageQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 주문 조회·보류·개인정보 열람·수동 수집(M4 O1~O2). 같은 운영그룹 주문의 기본 조회는 허용하되
 * 사용자별 사업자·판매계정 제한은 서버에서 거부한다(CLAUDE.md). 개인정보 원문은 pii.read + 사유 + 감사로그.
 */
@Service
public class OrderService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final SecretCipher cipher;
    private final Json json;
    private final OrderIngestService ingest;
    private final OrderRules rules;

    public OrderService(JdbcClient jdbc, AccessGuard guard, AuditService audit, SecretCipher cipher, Json json, OrderIngestService ingest, OrderRules rules) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.cipher = cipher;
        this.json = json;
        this.ingest = ingest;
        this.rules = rules;
    }

    public record Filter(List<String> businessEntityIds, UUID channelAccountId, String status, Boolean onHold, String holdReason, String q, Instant from,
                         Instant to) {
    }

    public record OrderRow(UUID id, String orderNo, UUID businessEntityId, String businessEntityName, UUID channelAccountId, String channelAccountName,
                           String salesChannelName, String salesUnitName, String sourceType, String externalOrderKey, Instant orderedAt, String status,
                           String paymentStatus, boolean onHold, List<String> holdReasons, BigDecimal totalAmount, int itemCount, String itemSummary,
                           String recipientNameMasked, Instant updatedAt, String orderKind, String customerName) {
    }

    public record ItemView(UUID id, int lineNo, boolean isGift, String giftSource, UUID gwpCampaignId, String externalItemKey, String externalProductId, String externalOptionId, String productName, String optionName,
                           UUID listingId, UUID skuId, String skuCode, Integer skuQtyPerUnit, String mappingStatus, int originalQty, int canceledQty, int activeQty,
                           int shippedQty, int returnedQty, BigDecimal unitPrice, BigDecimal lineAmount, BigDecimal sellerDiscount, BigDecimal channelDiscount,
                           String taxType, String externalStatus, String itemKind, UUID claimId) {
    }

    public record EventView(UUID id, String eventType, UUID orderItemId, String fromValue, String toValue, JsonNode detail, String actorType, String actorName,
                            Instant occurredAt) {
    }

    public record RawView(UUID id, String source, String result, String resultDetail, String mappingVersion, Instant externalUpdatedAt, Instant receivedAt,
                          JsonNode payloadMasked) {
    }

    public record OrderDetail(OrderRow order, Instant paidAt, String externalStatus, Instant externalUpdatedAt, BigDecimal itemsAmount, BigDecimal sellerDiscount,
                              BigDecimal channelDiscount, BigDecimal shippingFee, String buyerNameMasked, String recipientPhoneMasked, String addressMasked,
                              String holdNote, List<ItemView> items, List<EventView> events, List<RawView> raws, String piiState, String compositionStatus,
                              List<ComponentView> components, JsonNode gwpResult, String allocationStatus, String shippingStatus, Instant deliveredAt,
                              int version) {
    }

    public record ComponentView(UUID orderItemId, UUID skuId, String skuCode, int quantityPerUnit, int requiredQuantity, String source, UUID bomId,
                                Integer bomVersion, boolean splitShipmentAllowed) {
    }

    public record Import(UUID channelAccountId, List<Map<String, Object>> orders) {
    }

    public record ImportResult(int created, int updated, int duplicate, int stale, int rejected, List<Map<String, Object>> results) {
    }

    public ApiResponse<List<OrderRow>> list(Filter f, PageQuery page) {
        guard.requirePermission(Perm.ORDER_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, f.businessEntityIds());
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        guard.channelAccountFilter(AccessKind.READ, f.channelAccountId());
        if (bes.isEmpty() || cas.isEmpty()) {
            return ApiResponse.page(List.of(), null);
        }
        StringBuilder sql = new StringBuilder(SELECT + " where o.workspace_id = :ws and o.business_entity_id in (:bes) and o.channel_account_id in (:cas)");
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", ws(), "bes", bes, "cas", cas));
        if (f.channelAccountId() != null) {
            sql.append(" and o.channel_account_id = :ca");
            p.put("ca", f.channelAccountId());
        }
        if (f.status() != null && !f.status().isBlank()) {
            sql.append(" and o.status = :st");
            p.put("st", f.status());
        }
        if (f.onHold() != null) {
            sql.append(" and o.on_hold = :h");
            p.put("h", f.onHold());
        }
        if (f.holdReason() != null && !f.holdReason().isBlank()) {
            sql.append(" and :hr = any(o.hold_reasons)");
            p.put("hr", f.holdReason());
        }
        if (f.q() != null && !f.q().isBlank()) {
            sql.append(" and (o.order_no ilike :q or o.external_order_key ilike :q or exists (select 1 from order_item i where i.order_id = o.id and i.product_name ilike :q))");
            p.put("q", "%" + f.q().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (f.from() != null) {
            sql.append(" and o.ordered_at >= :from");
            p.put("from", Db.ts(f.from()));
        }
        if (f.to() != null) {
            sql.append(" and o.ordered_at < :to");
            p.put("to", Db.ts(f.to()));
        }
        if (page.after() != null) {
            sql.append(" and o.id < :after");
            p.put("after", page.after());
        }
        sql.append(" order by o.id desc limit ").append(page.fetchSize());
        return page.toResponse(jdbc.sql(sql.toString()).params(p).query((rs, i) -> row(rs)).list(), OrderRow::id);
    }

    public OrderDetail get(UUID id) {
        guard.requirePermission(Perm.ORDER_READ);
        Map<String, Object> o = load(id);
        guard.checkRead("SALES_ORDER", id, (UUID) o.get("business_entity_id"), (UUID) o.get("channel_account_id"));
        OrderRow r = jdbc.sql(SELECT + " where o.id = :id").param("id", id).query((rs, i) -> row(rs)).single();
        List<ItemView> items = jdbc.sql("select i.*, s.sku_code from order_item i left join sku s on s.id = i.sku_id where i.order_id = :o order by i.line_no")
                .param("o", id).query((rs, i) -> new ItemView(Db.uuid(rs, "id"), rs.getInt("line_no"), rs.getBoolean("is_gift"), rs.getString("gift_source"),
                        Db.uuid(rs, "gwp_campaign_id"), rs.getString("external_item_key"),
                        rs.getString("external_product_id"), rs.getString("external_option_id"), rs.getString("product_name"), rs.getString("option_name"),
                        Db.uuid(rs, "listing_id"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"), (Integer) rs.getObject("sku_qty_per_unit"),
                        rs.getString("mapping_status"), rs.getInt("original_qty"), rs.getInt("canceled_qty"), rs.getInt("original_qty") - rs.getInt("canceled_qty"),
                        rs.getInt("shipped_qty"), rs.getInt("returned_qty"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("line_amount"),
                        rs.getBigDecimal("seller_discount"), rs.getBigDecimal("channel_discount"), rs.getString("tax_type"), rs.getString("external_status"),
                        rs.getString("item_kind"), Db.uuid(rs, "claim_id"))).list();
        List<EventView> events = jdbc.sql("""
                select e.*, u.display_name as actor_name from order_event e left join workspace_member m on m.workspace_id = e.workspace_id and m.id = e.actor_member_id
                  left join user_account u on u.id = m.user_id where e.order_id = :o order by e.occurred_at desc, e.id desc limit 200
                """).param("o", id).query((rs, i) -> new EventView(Db.uuid(rs, "id"), rs.getString("event_type"), Db.uuid(rs, "order_item_id"),
                rs.getString("from_value"), rs.getString("to_value"), json.read(rs.getString("detail")), rs.getString("actor_type"), rs.getString("actor_name"),
                Db.instant(rs, "occurred_at"))).list();
        List<RawView> raws = jdbc.sql("select * from order_raw where order_id = :o order by received_at desc limit 50").param("o", id)
                .query((rs, i) -> new RawView(Db.uuid(rs, "id"), rs.getString("source"), rs.getString("result"), rs.getString("result_detail"),
                        rs.getString("mapping_version"), Db.instant(rs, "external_updated_at"), Db.instant(rs, "received_at"), json.read(rs.getString("payload_masked"))))
                .list();
        return new OrderDetail(r, ts(o.get("paid_at")), (String) o.get("external_status"), ts(o.get("external_updated_at")), (BigDecimal) o.get("items_amount"),
                (BigDecimal) o.get("seller_discount"), (BigDecimal) o.get("channel_discount"), (BigDecimal) o.get("shipping_fee"), (String) o.get("buyer_name_masked"),
                (String) o.get("recipient_phone_masked"), (String) o.get("address_masked"), (String) o.get("hold_note"), items, events, raws,
                (String) o.get("pii_state"), (String) o.get("composition_status"), components(id),
                o.get("gwp_result") == null ? null : json.read(o.get("gwp_result").toString()), (String) o.get("allocation_status"),
                (String) o.get("shipping_status"), ts(o.get("delivered_at")), ((Number) o.get("version")).intValue());
    }

    private List<ComponentView> components(UUID orderId) {
        return jdbc.sql("""
                select c.*, s.sku_code, (i.original_qty - i.canceled_qty) * c.quantity_per_unit as required from order_item_component c
                  join sku s on s.id = c.component_sku_id join order_item i on i.id = c.order_item_id where c.order_id = :o order by i.line_no, s.sku_code
                """).param("o", orderId).query((rs, i) -> new ComponentView(Db.uuid(rs, "order_item_id"), Db.uuid(rs, "component_sku_id"), rs.getString("sku_code"),
                rs.getInt("quantity_per_unit"), rs.getInt("required"), rs.getString("source"), Db.uuid(rs, "bom_id"), (Integer) rs.getObject("bom_version"),
                rs.getBoolean("split_shipment_allowed"))).list();
    }

    /** 개인정보 원문 열람(pii.read). 사유 필수, 열람마다 감사로그. */
    @Transactional
    public JsonNode revealPii(UUID id, String reason, String purpose) {
        guard.requirePermission(Perm.ORDER_READ);
        guard.requirePermission(Perm.PII_READ);
        CurrentActor a = guard.actor();
        Map<String, Object> o = load(id);
        guard.checkRead("SALES_ORDER", id, (UUID) o.get("business_entity_id"), (UUID) o.get("channel_account_id"));
        if (reason == null || reason.isBlank() || reason.length() > 200) {
            throw Errors.validation("reason", "개인정보 열람 사유를 200자 이내로 입력하세요.");
        }
        String state = (String) o.get("pii_state");
        if ("PURGED".equals(state)) {
            throw Errors.conflict("PII_PURGED", "보존기간이 지나 개인정보 원문을 파기한 주문입니다(거래 기록과 마스킹 값만 남아 있습니다).");
        }
        if ("ARCHIVED".equals(state)) {
            // 분리보관: 법정 사유와 관리 권한(지정 담당자)으로만
            if (!a.has(Perm.ACCESS_MANAGE)) {
                throw Errors.permissionDenied(Perm.ACCESS_MANAGE);
            }
            if (!java.util.Set.of("DISPUTE", "LEGAL_REQUEST", "TAX_AUDIT").contains(String.valueOf(purpose))) {
                throw Errors.unprocessable("PII_ARCHIVED", "운영 열람 기간이 지나 분리보관 중입니다. 분쟁 대응·수사기관 요청·세무 조사 중 하나의 목적으로만 열람할 수 있습니다.");
            }
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "ORDER_PII_REVEALED", "SALES_ORDER", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) o.get("business_entity_id"), (UUID) o.get("channel_account_id")).reason(reason.trim())
                .metadata(Map.of("pii_state", state, "purpose", String.valueOf(purpose))).build());
        return json.read(cipher.decrypt((byte[]) o.get("pii_cipher"), (String) o.get("pii_key_id")));
    }

    @Transactional
    public OrderDetail hold(UUID id, int expectedVersion, String note) {
        CurrentActor a = writable(id, expectedVersion);
        if (note == null || note.isBlank() || note.length() > 500) {
            throw Errors.validation("note", "보류 사유를 500자 이내로 입력하세요.");
        }
        Map<String, Object> o = load(id);
        Set<String> reasons = new LinkedHashSet<>(Arrays.asList(OrderRules.textArray(o.get("hold_reasons"))));
        if (!reasons.add("MANUAL")) {
            throw Errors.conflict("ORDER_ALREADY_HELD", "이미 직접 보류한 주문입니다.");
        }
        jdbc.sql("""
                update sales_order set hold_reasons = cast(:r as text[]), on_hold = true, hold_note = :n, held_by = :by, held_at = now(), version = version + 1,
                       updated_at = now() where id = :id
                """).param("r", reasons.toArray(new String[0])).param("n", note.trim()).param("by", a.memberId()).param("id", id).update();
        rules.event(a.workspaceId(), id, null, "HOLD_MANUAL", null, note.trim(), Map.of(), a.memberId());
        audit.record(AuditEntry.builder(a.workspaceId(), "ORDER_HELD", "SALES_ORDER", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) o.get("business_entity_id"), (UUID) o.get("channel_account_id")).reason(note.trim()).build());
        return get(id);
    }

    /** 직접 건 보류만 푼다. 시스템 사유(미연결·금액 불일치 등)는 원인이 해결돼야 풀린다. */
    @Transactional
    public OrderDetail releaseHold(UUID id, int expectedVersion) {
        CurrentActor a = writable(id, expectedVersion);
        Map<String, Object> o = load(id);
        Set<String> reasons = new LinkedHashSet<>(Arrays.asList(OrderRules.textArray(o.get("hold_reasons"))));
        if (!reasons.remove("MANUAL")) {
            throw Errors.unprocessable("ORDER_HOLD_NOT_MANUAL", "직접 건 보류가 없습니다. 시스템 보류는 원인을 해결하면 자동으로 풀립니다.",
                    Map.of("hold_reasons", List.copyOf(reasons)));
        }
        jdbc.sql("""
                update sales_order set hold_reasons = cast(:r as text[]), on_hold = :h, hold_note = null, held_by = null, held_at = null, version = version + 1,
                       updated_at = now() where id = :id
                """).param("r", reasons.toArray(new String[0])).param("h", !reasons.isEmpty()).param("id", id).update();
        rules.event(a.workspaceId(), id, null, "HOLD_MANUAL_RELEASED", "MANUAL", String.join(",", reasons), Map.of(), a.memberId());
        audit.record(AuditEntry.builder(a.workspaceId(), "ORDER_HOLD_RELEASED", "SALES_ORDER", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) o.get("business_entity_id"), (UUID) o.get("channel_account_id")).build());
        return get(id);
    }

    /** 다시 연결·검증(채널상품을 연결했거나 사업자 정책을 바꾼 뒤). */
    @Transactional
    public OrderDetail revalidate(UUID id) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        CurrentActor a = guard.actor();
        Map<String, Object> o = load(id);
        guard.checkWrite("SALES_ORDER", id, (UUID) o.get("business_entity_id"), (UUID) o.get("channel_account_id"));
        rules.mapItems(a.workspaceId(), id, a.memberId());
        rules.reevaluate(a.workspaceId(), id, a.memberId(), null);
        return get(id);
    }

    /** 파일·직접 입력 수집(물류·채널 API 가 없는 판매채널, 모의 연동 확인용). 주문마다 결과를 돌려준다. */
    @Transactional
    public ImportResult importOrders(Import req) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        CurrentActor a = guard.actor();
        if (req.channelAccountId() == null || req.orders() == null || req.orders().isEmpty() || req.orders().size() > 500) {
            throw Errors.validation("orders", "판매계정과 주문 1~500건을 보내세요.");
        }
        UUID be = jdbc.sql("select business_entity_id from channel_account where workspace_id = :ws and id = :id").param("ws", a.workspaceId())
                .param("id", req.channelAccountId()).query(UUID.class).optional().orElseThrow(() -> Errors.notFound("CHANNEL_ACCOUNT"));
        guard.checkWrite("CHANNEL_ACCOUNT", req.channelAccountId(), be, req.channelAccountId());
        int[] counts = new int[5];
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> raw : req.orders()) {
            OrderInput input = OrderInput.fromMap(raw);
            OrderIngestService.Result r = ingest.ingest(new OrderIngestService.Incoming(a.workspaceId(), req.channelAccountId(), null, null, "FILE", "std-json-v1",
                    raw, input, a.memberId()));
            switch (r.outcome()) {
                case "CREATED" -> counts[0]++;
                case "UPDATED" -> counts[1]++;
                case "DUPLICATE" -> counts[2]++;
                case "IGNORED_STALE" -> counts[3]++;
                default -> counts[4]++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("external_order_key", input.externalOrderKey());
            m.put("outcome", r.outcome());
            m.put("order_id", r.orderId() == null ? null : r.orderId().toString());
            m.put("detail", r.detail());
            results.add(m);
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "ORDER_IMPORTED", "CHANNEL_ACCOUNT", req.channelAccountId()).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, req.channelAccountId()).metadata(Map.of("created", counts[0], "updated", counts[1], "rejected", counts[4])).build());
        return new ImportResult(counts[0], counts[1], counts[2], counts[3], counts[4], results);
    }

    // ---------------------------------------------------------------- 내부

    private CurrentActor writable(UUID id, int expectedVersion) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        CurrentActor a = guard.actor();
        Map<String, Object> o = jdbc.sql("select business_entity_id, channel_account_id, version from sales_order where workspace_id = :ws and id = :id for update")
                .param("ws", a.workspaceId()).param("id", id).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SALES_ORDER"));
        guard.checkWrite("SALES_ORDER", id, (UUID) o.get("business_entity_id"), (UUID) o.get("channel_account_id"));
        int v = ((Number) o.get("version")).intValue();
        if (v != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, v);
        }
        return a;
    }

    private Map<String, Object> load(UUID id) {
        return jdbc.sql("select * from sales_order where workspace_id = :ws and id = :id").param("ws", ws()).param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> Errors.notFound("SALES_ORDER"));
    }

    private static Instant ts(Object v) {
        return OrderRules.instant(v);
    }

    private static final String SELECT = """
            select o.*, be.display_name as be_name, ca.display_name as ca_name, sc.name_ko as channel_name, su.display_name as su_name,
                   (select count(*) from order_item i where i.order_id = o.id) as item_count,
                   (select i.product_name from order_item i where i.order_id = o.id order by i.line_no limit 1) as first_item
              from sales_order o join business_entity be on be.id = o.business_entity_id join channel_account ca on ca.id = o.channel_account_id
              join sales_channel sc on sc.code = ca.sales_channel_code join sales_unit su on su.id = o.sales_unit_id
            """;

    private static OrderRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        int count = rs.getInt("item_count");
        String first = rs.getString("first_item");
        String[] reasons = OrderRules.textArray(rs.getArray("hold_reasons"));
        return new OrderRow(Db.uuid(rs, "id"), rs.getString("order_no"), Db.uuid(rs, "business_entity_id"), rs.getString("be_name"), Db.uuid(rs, "channel_account_id"),
                rs.getString("ca_name"), rs.getString("channel_name"), rs.getString("su_name"), rs.getString("source_type"), rs.getString("external_order_key"),
                Db.instant(rs, "ordered_at"), rs.getString("status"), rs.getString("payment_status"), rs.getBoolean("on_hold"), List.of(reasons),
                rs.getBigDecimal("total_amount"), count, first == null ? null : count > 1 ? first + " 외 " + (count - 1) + "건" : first,
                rs.getString("recipient_name_masked"), Db.instant(rs, "updated_at"), rs.getString("order_kind"), rs.getString("customer_name"));
    }

    private UUID ws() {
        return guard.actor().workspaceId();
    }
}
