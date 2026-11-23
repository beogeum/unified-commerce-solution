package com.commerceos.order;

import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.outbox.OutboxPublisher;
import com.commerceos.shared.crypto.SecretCipher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 수집 파이프라인 앞단(M4 O1~O2, docs/04 §1): 원본 보관 → 중복 판단 → 표준 주문 → 판매단위·사업자 귀속 → 외부 옵션/SKU 연결 → 검증·보류.
 * <ul>
 *   <li>원본은 내용 해시로 한 번만 저장한다(재수신 무시, T10). 개인정보가 들어 있으므로 암호화 + 마스킹 사본(DLG-117).</li>
 *   <li>외부 갱신 시각이 저장된 값보다 이르거나 같으면 원본만 남기고 주문은 바꾸지 않는다(역순 도착, DLG-119).</li>
 *   <li>사업자 = 주문 시각 판매계정 귀속(DB 트리거로 강제, 이후 재계산 없음, DLG-118).</li>
 *   <li>취소 수량은 늘어나기만 한다. 줄어드는 외부값은 업무함으로.</li>
 * </ul>
 * 데이터 문제는 예외 대신 결과(REJECTED)로 돌려주고 원본·업무를 남긴다(작업 한 건의 다른 주문은 계속 처리).
 */
@Service
public class OrderIngestService {
    static final String NORMALIZATION_VERSION = "order-std-v1";
    private static final DateTimeFormatter NO = DateTimeFormatter.ofPattern("yyMMdd");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcClient jdbc;
    private final Json json;
    private final SecretCipher cipher;
    private final WorkItemService workItems;
    private final OutboxPublisher outbox;
    private final OrderRules rules;

    public OrderIngestService(JdbcClient jdbc, Json json, SecretCipher cipher, WorkItemService workItems, OutboxPublisher outbox, OrderRules rules) {
        this.jdbc = jdbc;
        this.json = json;
        this.cipher = cipher;
        this.workItems = workItems;
        this.outbox = outbox;
        this.rules = rules;
    }

    /** source: API·FILE·MANUAL. actorMemberId 가 없으면 연동(시스템) 반영. */
    public record Incoming(UUID workspaceId, UUID channelAccountId, UUID connectionId, UUID jobId, String source, String mappingVersion,
                           Map<String, Object> rawPayload, OrderInput order, UUID actorMemberId) {
    }

    public record Result(String outcome, UUID orderId, String detail) {
    }

    /** 입력 자체의 형식 오류(없으면 빈 목록). API 경로는 저장 전에 이것으로 422 를 낸다. */
    public static List<String> problems(OrderInput input) {
        OrderInput o = input.withDefaults();
        List<String> p = new ArrayList<>();
        if (o.externalOrderKey() == null || o.externalOrderKey().length() > 200) {
            p.add("외부 주문번호가 없습니다.");
        }
        if (o.orderedAt() == null) {
            p.add("주문 시각이 없습니다.");
        }
        if (o.totalAmount() == null || o.totalAmount().signum() < 0 || o.totalAmount().stripTrailingZeros().scale() > 0) {
            p.add("결제 금액(원 단위 정수)이 없습니다.");
        }
        if (o.items() == null || o.items().isEmpty()) {
            p.add("주문상품이 없습니다.");
        } else {
            Set<String> keys = new LinkedHashSet<>();
            int line = 1;
            for (OrderInput.Item i : o.items()) {
                String key = i.externalItemKey() == null ? String.valueOf(line) : i.externalItemKey();
                if (!keys.add(key)) {
                    p.add(line + "번 상품: 상품주문번호가 중복됩니다.");
                }
                if (i.externalProductId() == null) {
                    p.add(line + "번 상품: 외부 상품번호가 없습니다.");
                }
                if (i.quantity() == null || i.quantity() < 1) {
                    p.add(line + "번 상품: 수량은 1 이상입니다.");
                }
                int canceled = i.canceledQuantity() == null ? 0 : i.canceledQuantity();
                if (canceled < 0 || (i.quantity() != null && canceled > i.quantity())) {
                    p.add(line + "번 상품: 취소 수량이 주문 수량을 넘습니다.");
                }
                if (i.unitPrice() == null || i.unitPrice().signum() < 0 || i.lineAmount() == null || i.lineAmount().signum() < 0) {
                    p.add(line + "번 상품: 단가·금액(0 이상)이 없습니다.");
                }
                line++;
            }
        }
        if (o.sourceType() != null && !Set.of("ORDER", "PLATFORM_FULFILLMENT", "MANUAL").contains(o.sourceType())) {
            p.add("원천 유형은 ORDER·PLATFORM_FULFILLMENT·MANUAL 중 하나입니다.");
        }
        return p;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result ingest(Incoming in) {
        UUID ws = in.workspaceId();
        OrderInput o = in.order().withDefaults();
        String sourceType = o.sourceType() == null ? "ORDER" : o.sourceType();
        String key = o.externalOrderKey() == null ? "(없음)" : o.externalOrderKey();
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:k))").param("k", "order:" + in.channelAccountId() + ":" + sourceType + ":" + key).query().listOfRows();
        String hash = sha256(json.write(in.rawPayload()));
        boolean duplicate = jdbc.sql("""
                select count(*) from order_raw where channel_account_id = :ca and source_type = :st and external_order_key = :k and payload_hash = :h
                """).param("ca", in.channelAccountId()).param("st", sourceType).param("k", key).param("h", hash).query(Integer.class).single() > 0;
        if (duplicate) {
            return new Result("DUPLICATE", findOrderId(in.channelAccountId(), sourceType, key).orElse(null), "같은 원본을 이미 받았습니다.");
        }
        List<String> problems = problems(o);
        String unitKey = o.salesUnitKey() == null ? "DEFAULT" : o.salesUnitKey();
        UUID salesUnit = jdbc.sql("select id from sales_unit where workspace_id = :ws and channel_account_id = :ca and external_unit_key = :u")
                .param("ws", ws).param("ca", in.channelAccountId()).param("u", unitKey).query(UUID.class).optional().orElse(null);
        if (problems.isEmpty() && salesUnit == null) {
            problems.add("판매단위 '" + unitKey + "' 가 이 판매계정에 없습니다.");
        }
        UUID be = null;
        if (problems.isEmpty()) {
            be = jdbc.sql("select channel_account_business_at(:ca, :at)").param("ca", in.channelAccountId()).param("at", Db.ts(o.orderedAt()))
                    .query(UUID.class).optional().orElse(null);
            if (be == null) {
                problems.add("주문 시각에 이 판매계정이 어느 사업자에도 귀속되어 있지 않습니다.");
            }
        }
        if (!problems.isEmpty()) {
            UUID rawId = insertRaw(in, salesUnit, sourceType, key, hash, o.externalUpdatedAt(), "REJECTED", String.join(" / ", problems), null);
            UUID accountBe = jdbc.sql("select business_entity_id from channel_account where id = :ca").param("ca", in.channelAccountId()).query(UUID.class).single();
            workItems.raise(new WorkItemService.Raise(ws, accountBe, in.channelAccountId(), in.connectionId(), "ORDER_INGEST_REJECTED", "HIGH",
                    "주문을 받지 못했습니다: " + key, String.join(" / ", problems),
                    "판매채널 원본을 확인하고, 판매단위·귀속·상품 정보를 바로잡은 뒤 다시 수집하세요.", "order-ingest:" + in.channelAccountId() + ":" + key,
                    Map.of("raw_id", rawId.toString(), "external_order_key", key)));
            return new Result("REJECTED", null, String.join(" / ", problems));
        }

        Optional<Map<String, Object>> existing = jdbc.sql("""
                select id, external_updated_at, status from sales_order where channel_account_id = :ca and sales_unit_id = :su and source_type = :st
                   and external_order_key = :k for update
                """).param("ca", in.channelAccountId()).param("su", salesUnit).param("st", sourceType).param("k", key).query().listOfRows().stream().findFirst();
        if (existing.isEmpty()) {
            return create(in, o, sourceType, key, salesUnit, be, hash);
        }
        UUID orderId = (UUID) existing.get().get("id");
        Instant stored = OrderRules.instant(existing.get().get("external_updated_at"));
        if (o.externalUpdatedAt() != null && stored != null && !o.externalUpdatedAt().isAfter(stored)) {
            UUID rawId = insertRaw(in, salesUnit, sourceType, key, hash, o.externalUpdatedAt(), "IGNORED_STALE",
                    "외부 갱신 시각 " + o.externalUpdatedAt() + " ≤ 반영된 " + stored, orderId);
            event(ws, orderId, null, "RAW_IGNORED_STALE", stored.toString(), o.externalUpdatedAt().toString(), Map.of(), rawId, in);
            return new Result("IGNORED_STALE", orderId, "이미 반영한 것보다 오래된 원본입니다.");
        }
        return update(in, o, orderId, salesUnit, sourceType, key, hash);
    }

    // ---------------------------------------------------------------- 생성

    private Result create(Incoming in, OrderInput o, String sourceType, String key, UUID salesUnit, UUID be, String hash) {
        UUID ws = in.workspaceId();
        UUID orderId = Ids.newId();
        String orderNo = nextOrderNo(ws, o.orderedAt());
        BigDecimal items = sum(o.items(), OrderInput.Item::lineAmount);
        BigDecimal sellerDiscount = sum(o.items(), OrderInput.Item::sellerDiscount);
        BigDecimal channelDiscount = sum(o.items(), OrderInput.Item::channelDiscount);
        SecretCipher.Encrypted pii = cipher.encrypt(json.write(piiMap(o)));
        jdbc.sql("""
                insert into sales_order (id, workspace_id, order_no, business_entity_id, channel_account_id, sales_unit_id, source_type, external_order_key,
                    ordered_at, paid_at, status, payment_status, external_status, external_updated_at, items_amount, seller_discount, channel_discount,
                    shipping_fee, total_amount, buyer_name_masked, recipient_name_masked, recipient_phone_masked, address_masked, pii_cipher, pii_key_id,
                    mapping_version)
                values (:id, :ws, :no, :be, :ca, :su, :st, :k, :ordered, :paid, 'RECEIVED', :pay, cast(:es as text), :eu, :items, :sd, :cd, :ship, :total,
                        cast(:bn as text), cast(:rn as text), cast(:rp as text), cast(:addr as text), :pii, :kid, :mv)
                """).param("id", orderId).param("ws", ws).param("no", orderNo).param("be", be).param("ca", in.channelAccountId()).param("su", salesUnit)
                .param("st", sourceType).param("k", key).param("ordered", Db.ts(o.orderedAt())).param("paid", Db.ts(o.paidAt()))
                .param("pay", o.paidAt() == null ? "PENDING" : "PAID").param("es", o.externalStatus()).param("eu", Db.ts(o.externalUpdatedAt()))
                .param("items", items).param("sd", sellerDiscount).param("cd", channelDiscount).param("ship", nz(o.shippingFee())).param("total", o.totalAmount())
                .param("bn", Pii.name(o.buyer() == null ? null : o.buyer().name())).param("rn", Pii.name(o.recipient().name()))
                .param("rp", Pii.phone(o.recipient().phone())).param("addr", Pii.address(o.recipient().address1()))
                .param("pii", pii.cipherText()).param("kid", pii.keyId()).param("mv", in.mappingVersion()).update();
        int line = 1;
        for (OrderInput.Item i : o.items()) {
            insertItem(ws, orderId, line, i);
            line++;
        }
        UUID rawId = insertRaw(in, salesUnit, sourceType, key, hash, o.externalUpdatedAt(), "CREATED", null, orderId);
        jdbc.sql("update sales_order set last_raw_id = :r where id = :id").param("r", rawId).param("id", orderId).update();
        event(ws, orderId, null, "ORDER_CREATED", null, orderNo, Map.of("source", in.source(), "external_order_key", key), rawId, in);
        rules.mapItems(ws, orderId, in.actorMemberId());
        rules.reevaluate(ws, orderId, in.actorMemberId(), in.connectionId());
        outbox.publish(ws, be, "SALES_ORDER", orderId, "OrderReceived", Map.of("order_id", orderId.toString(), "business_entity_id", be.toString(),
                "channel_account_id", in.channelAccountId().toString()));
        return new Result("CREATED", orderId, orderNo);
    }

    private void insertItem(UUID ws, UUID orderId, int line, OrderInput.Item i) {
        String itemKey = i.externalItemKey() == null ? String.valueOf(line) : i.externalItemKey();
        jdbc.sql("""
                insert into order_item (id, workspace_id, order_id, line_no, external_item_key, external_product_id, external_option_id, product_name, option_name,
                    mapping_status, original_qty, canceled_qty, unit_price, line_amount, seller_discount, channel_discount, external_status, is_gift, gift_source,
                    channel_campaign_ref)
                values (:id, :ws, :o, :line, :k, :p, :opt, :name, cast(:on as text), 'UNMAPPED', :q, :c, :up, :la, :sd, :cd, cast(:es as text), :gift,
                        cast(:gs as text), cast(:ref as text))
                """).param("id", Ids.newId()).param("ws", ws).param("o", orderId).param("line", line).param("k", itemKey).param("p", i.externalProductId())
                .param("opt", i.externalOptionId() == null ? "" : i.externalOptionId()).param("name", i.productName() == null ? i.externalProductId() : i.productName())
                .param("on", i.optionName()).param("q", i.quantity()).param("c", i.canceledQuantity() == null ? 0 : i.canceledQuantity()).param("up", i.unitPrice())
                .param("la", i.lineAmount()).param("sd", nz(i.sellerDiscount())).param("cd", nz(i.channelDiscount())).param("es", i.externalStatus())
                .param("gift", Boolean.TRUE.equals(i.gift())).param("gs", Boolean.TRUE.equals(i.gift()) ? "CHANNEL" : null).param("ref", i.channelCampaignRef()).update();
    }

    // ---------------------------------------------------------------- 갱신

    private Result update(Incoming in, OrderInput o, UUID orderId, UUID salesUnit, String sourceType, String key, String hash) {
        UUID ws = in.workspaceId();
        Map<String, Object> cur = jdbc.sql("select * from sales_order where id = :id").param("id", orderId).query().listOfRows().get(0);
        UUID rawId = insertRaw(in, salesUnit, sourceType, key, hash, o.externalUpdatedAt(), "UPDATED", null, orderId);
        List<String> changes = new ArrayList<>();
        if (o.paidAt() != null && cur.get("paid_at") == null) {
            jdbc.sql("update sales_order set paid_at = :p, payment_status = 'PAID' where id = :id").param("p", Db.ts(o.paidAt())).param("id", orderId).update();
            event(ws, orderId, null, "PAYMENT_CONFIRMED", "PENDING", "PAID", Map.of(), rawId, in);
            changes.add("결제");
        }
        if (!Objects.equals(o.externalStatus(), cur.get("external_status"))) {
            event(ws, orderId, null, "EXTERNAL_STATUS_CHANGED", (String) cur.get("external_status"), o.externalStatus(), Map.of(), rawId, in);
            changes.add("외부상태");
        }
        String newPii = json.write(piiMap(o));
        String oldPii = cur.get("pii_cipher") == null ? null : cipher.decrypt((byte[]) cur.get("pii_cipher"), (String) cur.get("pii_key_id"));
        if (!newPii.equals(oldPii)) {
            SecretCipher.Encrypted pii = cipher.encrypt(newPii);
            jdbc.sql("""
                    update sales_order set pii_cipher = :c, pii_key_id = :k, buyer_name_masked = cast(:bn as text), recipient_name_masked = cast(:rn as text),
                           recipient_phone_masked = cast(:rp as text), address_masked = cast(:addr as text) where id = :id
                    """).param("c", pii.cipherText()).param("k", pii.keyId()).param("bn", Pii.name(o.buyer() == null ? null : o.buyer().name()))
                    .param("rn", Pii.name(o.recipient().name())).param("rp", Pii.phone(o.recipient().phone())).param("addr", Pii.address(o.recipient().address1()))
                    .param("id", orderId).update();
            event(ws, orderId, null, "RECIPIENT_CHANGED", null, null, Map.of("address_masked", String.valueOf(Pii.address(o.recipient().address1()))), rawId, in);
            changes.add("수령인");
        }
        // 주문상품: 취소 수량은 늘어나기만, 새 상품주문은 추가
        Map<String, Map<String, Object>> items = new LinkedHashMap<>();
        jdbc.sql("select * from order_item where order_id = :o order by line_no for update").param("o", orderId).query().listOfRows()
                .forEach(r -> items.put((String) r.get("external_item_key"), r));
        int line = items.size() + 1;
        int position = 1;
        for (OrderInput.Item i : o.items()) {
            String itemKey = i.externalItemKey() == null ? String.valueOf(position) : i.externalItemKey();
            position++;
            Map<String, Object> e = items.get(itemKey);
            if (e == null) {
                insertItem(ws, orderId, line++, new OrderInput.Item(itemKey, i.externalProductId(), i.externalOptionId(), i.productName(), i.optionName(),
                        i.quantity(), i.canceledQuantity(), i.unitPrice(), i.lineAmount(), i.sellerDiscount(), i.channelDiscount(), i.externalStatus(), i.gift(),
                        i.channelCampaignRef()));
                event(ws, orderId, null, "ITEM_ADDED", null, itemKey, Map.of(), rawId, in);
                changes.add("상품 추가");
                continue;
            }
            UUID itemId = (UUID) e.get("id");
            int curCanceled = ((Number) e.get("canceled_qty")).intValue();
            int newCanceled = i.canceledQuantity() == null ? 0 : i.canceledQuantity();
            int original = ((Number) e.get("original_qty")).intValue();
            if (i.quantity() != null && i.quantity() != original) {
                raiseAnomaly(in, orderId, "외부 주문 수량이 " + original + " → " + i.quantity() + " 로 바뀌었습니다(반영하지 않음).", itemKey);
            }
            if (newCanceled > curCanceled) {
                int shipped = ((Number) e.get("shipped_qty")).intValue();
                int allowed = Math.min(newCanceled, original - shipped);
                if (allowed > curCanceled) {
                    jdbc.sql("update order_item set canceled_qty = :c, external_status = cast(:es as text), version = version + 1, updated_at = now() where id = :id")
                            .param("c", allowed).param("es", i.externalStatus()).param("id", itemId).update();
                    event(ws, orderId, itemId, "ITEM_CANCELED", String.valueOf(curCanceled), String.valueOf(allowed), Map.of(), rawId, in);
                    changes.add("취소");
                }
                if (allowed < newCanceled) {
                    raiseAnomaly(in, orderId, "이미 출고된 수량보다 많은 취소가 들어왔습니다(" + newCanceled + "개 중 " + allowed + "개만 반영).", itemKey);
                }
            } else if (newCanceled < curCanceled) {
                raiseAnomaly(in, orderId, "외부 취소 수량이 " + curCanceled + " → " + newCanceled + " 로 줄었습니다(취소 철회는 자동 반영하지 않음).", itemKey);
            }
        }
        jdbc.sql("update sales_order set external_status = cast(:es as text), external_updated_at = :eu, last_raw_id = :r, version = version + 1, updated_at = now() where id = :id")
                .param("es", o.externalStatus()).param("eu", Db.ts(o.externalUpdatedAt())).param("r", rawId).param("id", orderId).update();
        rules.mapItems(ws, orderId, in.actorMemberId());
        rules.reevaluate(ws, orderId, in.actorMemberId(), in.connectionId());
        UUID be = (UUID) cur.get("business_entity_id");
        outbox.publish(ws, be, "SALES_ORDER", orderId, "OrderUpdated", Map.of("order_id", orderId.toString(), "changes", changes));
        if (changes.contains("취소")) {
            // 진행 중 출고지시가 취소된 수량까지 담고 있으면 클레임 모듈이 정리한다(M4 O6, DLG-139)
            outbox.publish(ws, be, "SALES_ORDER", orderId, "OrderItemsCanceledExternally", Map.of("order_id", orderId.toString()));
        }
        return new Result("UPDATED", orderId, String.join(", ", changes));
    }

    private void raiseAnomaly(Incoming in, UUID orderId, String cause, String itemKey) {
        Map<String, Object> o = jdbc.sql("select order_no, business_entity_id, channel_account_id from sales_order where id = :id").param("id", orderId).query().listOfRows().get(0);
        workItems.raise(new WorkItemService.Raise(in.workspaceId(), (UUID) o.get("business_entity_id"), (UUID) o.get("channel_account_id"), in.connectionId(),
                "ORDER_EXTERNAL_ANOMALY", "MEDIUM", "주문 외부 변경 확인 필요: " + o.get("order_no"), cause,
                "판매채널 관리자 화면에서 실제 주문 상태를 확인하고 필요하면 클레임으로 처리하세요.", "order:" + orderId + ":ANOMALY:" + itemKey,
                Map.of("order_id", orderId.toString(), "external_item_key", itemKey)));
    }

    // ---------------------------------------------------------------- 공통

    private UUID insertRaw(Incoming in, UUID salesUnit, String sourceType, String key, String hash, Instant externalUpdatedAt, String result, String detail, UUID orderId) {
        UUID id = Ids.newId();
        SecretCipher.Encrypted enc = cipher.encrypt(json.write(in.rawPayload()));
        jdbc.sql("""
                insert into order_raw (id, workspace_id, channel_account_id, sales_unit_id, connection_id, job_id, source, source_type, external_order_key, payload_hash,
                    payload_cipher, payload_key_id, payload_masked, mapping_version, external_updated_at, result, result_detail, order_id)
                values (:id, :ws, :ca, cast(:su as uuid), cast(:conn as uuid), cast(:job as uuid), :src, :st, :k, :h, :c, :kid, cast(:masked as jsonb), :mv, :eu, :res,
                        cast(:detail as text), cast(:o as uuid))
                """).param("id", id).param("ws", in.workspaceId()).param("ca", in.channelAccountId()).param("su", salesUnit).param("conn", in.connectionId())
                .param("job", in.jobId()).param("src", in.source()).param("st", sourceType).param("k", key).param("h", hash).param("c", enc.cipherText())
                .param("kid", enc.keyId()).param("masked", json.write(OrderInput.maskPayload(in.rawPayload(), null))).param("mv", in.mappingVersion())
                .param("eu", Db.ts(externalUpdatedAt)).param("res", result).param("detail", detail).param("o", orderId).update();
        return id;
    }

    void event(UUID ws, UUID orderId, UUID itemId, String type, String from, String to, Map<String, Object> detail, UUID rawId, Incoming in) {
        jdbc.sql("""
                insert into order_event (id, workspace_id, order_id, order_item_id, event_type, from_value, to_value, detail, raw_id, actor_type, actor_member_id)
                values (:id, :ws, :o, cast(:i as uuid), :t, cast(:f as text), cast(:to as text), cast(:d as jsonb), cast(:r as uuid), :at, cast(:m as uuid))
                """).param("id", Ids.newId()).param("ws", ws).param("o", orderId).param("i", itemId).param("t", type).param("f", from).param("to", to)
                .param("d", json.write(detail)).param("r", rawId).param("at", in == null || in.actorMemberId() == null ? "INTEGRATION" : "USER")
                .param("m", in == null ? null : in.actorMemberId()).update();
    }

    private String nextOrderNo(UUID ws, Instant orderedAt) {
        LocalDate day = LocalDate.now(KST);
        int seq = jdbc.sql("""
                insert into order_number_counter (workspace_id, day, last_value) values (:ws, :d, 1)
                on conflict (workspace_id, day) do update set last_value = order_number_counter.last_value + 1 returning last_value
                """).param("ws", ws).param("d", day).query(Integer.class).single();
        return "O" + NO.format(day) + "-" + String.format("%05d", seq);
    }

    private Optional<UUID> findOrderId(UUID ca, String sourceType, String key) {
        return jdbc.sql("select id from sales_order where channel_account_id = :ca and source_type = :st and external_order_key = :k limit 1")
                .param("ca", ca).param("st", sourceType).param("k", key).query(UUID.class).optional();
    }

    private static Map<String, Object> piiMap(OrderInput o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("buyer", o.buyer());
        m.put("recipient", o.recipient());
        return m;
    }

    private static BigDecimal sum(List<OrderInput.Item> items, java.util.function.Function<OrderInput.Item, BigDecimal> f) {
        return items.stream().map(f).map(OrderIngestService::nz).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
