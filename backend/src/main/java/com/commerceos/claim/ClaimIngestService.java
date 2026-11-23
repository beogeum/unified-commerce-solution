package com.commerceos.claim;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.operations.WorkItemService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매채널 클레임 수집 반영(M4 O6, CLAIM_FETCH, DLG-140).
 * 표준 행: {external_claim_key, external_order_key, claim_type(CANCEL·RETURN·EXCHANGE), status(REQUESTED·APPROVED·REJECTED·WITHDRAWN·COMPLETED),
 * reason_code, items[{external_item_key, quantity}], refund_amount, refunded_at, refund_ref, updated_at, return_carrier_code, return_tracking_no}.
 * 채널이 이미 결정한 승인·거부·철회는 그대로 따르고(채널 반영 불필요), 채널 환불 완료는 확정 환불로 한 번만 기록한다.
 * 같은 원본은 한 번만, 외부 갱신 시각이 이전 것 이하이면 원본만 보관(T10). 개인정보 키는 원본 보관 전에 지운다.
 */
@Service
public class ClaimIngestService {
    private static final Set<String> PII_KEYS = Set.of("recipient", "buyer", "receiver", "name", "phone", "mobile", "address", "address1", "address2", "email",
            "return_address");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final ClaimService claims;
    private final WorkItemService workItems;
    private final Json json;

    public ClaimIngestService(JdbcClient jdbc, AccessGuard guard, ClaimService claims, WorkItemService workItems, Json json) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.claims = claims;
        this.workItems = workItems;
        this.json = json;
    }

    public record Incoming(UUID channelAccountId, UUID connectionId, String mappingVersion, Map<String, Object> row) {
    }

    public record Result(String outcome, UUID claimId, String detail) {
    }

    /** 원본 해시(중복 판단용). */
    public String hash(Incoming in) {
        return sha256(in.channelAccountId() + ":" + json.write(new TreeMap<>(strip(in.row()))));
    }

    public boolean seen(UUID ws, UUID channelAccountId, String hash) {
        return jdbc.sql("select count(*) from claim_raw where workspace_id = :ws and channel_account_id = :ca and payload_hash = :h").param("ws", ws)
                .param("ca", channelAccountId).param("h", hash).query(Integer.class).single() > 0;
    }

    /** 호출자 트랜잭션·시스템 자동 처리 주체 안에서. 실패(검증 오류)는 예외 — 호출자가 {@link #reject} 로 원본을 남긴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result ingest(Incoming in) {
        CurrentActor a = guard.actor();
        UUID ws = a.workspaceId();
        Map<String, Object> row = in.row();
        String key = str(row.get("external_claim_key"));
        String type = str(row.get("claim_type"));
        String status = str(row.get("status"));
        Instant updatedAt = parse(row.get("updated_at"));
        if (key == null || type == null || !Set.of("CANCEL", "RETURN", "EXCHANGE").contains(type)) {
            throw Errors.validation("external_claim_key", "클레임 번호·유형이 없습니다.");
        }
        Optional<UUID> orderId = jdbc.sql("""
                select id from sales_order where workspace_id = :ws and channel_account_id = :ca and external_order_key = :k order by (source_type = 'ORDER') desc limit 1
                """).param("ws", ws).param("ca", in.channelAccountId()).param("k", str(row.get("external_order_key"))).query(UUID.class).optional();
        if (orderId.isEmpty()) {
            raw(ws, in, key, null, "UNMATCHED", "원주문을 아직 수집하지 않았습니다.");
            workItems.raise(new WorkItemService.Raise(ws, null, in.channelAccountId(), in.connectionId(), "CLAIM_ORDER_NOT_FOUND", "MEDIUM",
                    "원주문 없는 판매채널 클레임: " + key, "클레임의 원주문(" + row.get("external_order_key") + ")을 찾지 못했습니다.",
                    "주문 수집을 실행한 뒤 클레임 수집을 다시 실행하세요.", "claim-raw:" + in.channelAccountId() + ":" + key, Map.of("external_claim_key", key)));
            return new Result("UNMATCHED", null, null);
        }
        Optional<Map<String, Object>> existing = jdbc.sql("select * from claim where channel_account_id = :ca and claim_type = :t and external_claim_key = :k")
                .param("ca", in.channelAccountId()).param("t", type).param("k", key).query().listOfRows().stream().findFirst();
        UUID claimId;
        String outcome;
        if (existing.isPresent()) {
            claimId = (UUID) existing.get().get("id");
            Instant prev = existing.get().get("external_updated_at") == null ? null : ((java.sql.Timestamp) existing.get().get("external_updated_at")).toInstant();
            if (updatedAt != null && prev != null && !updatedAt.isAfter(prev)) {
                raw(ws, in, key, claimId, "IGNORED_STALE", "외부 갱신 시각이 이미 반영한 값보다 이르거나 같습니다.");
                return new Result("IGNORED_STALE", claimId, null);
            }
            outcome = "UPDATED";
        } else {
            List<ClaimService.ItemInput> items = new ArrayList<>();
            Object rawItems = row.get("items");
            if (rawItems instanceof List<?> list) {
                for (Object o : list) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    UUID item = jdbc.sql("select id from order_item where order_id = :o and external_item_key = :k").param("o", orderId.get())
                            .param("k", str(m.get("external_item_key"))).query(UUID.class).optional()
                            .orElseThrow(() -> Errors.validation("items", "클레임 상품(" + m.get("external_item_key") + ")을 주문에서 찾을 수 없습니다."));
                    items.add(new ClaimService.ItemInput(item, ((Number) m.get("quantity")).intValue(), null, null));
                }
            }
            String reason = str(row.get("reason_code"));
            claimId = claims.createInternal(new ClaimService.NewClaim(orderId.get(), type, reason != null && ClaimService.REASONS.contains(reason) ? reason : "OTHER",
                    str(row.get("reason_note")), null, items, money(row.get("return_shipping_fee")), null, null), "API", key, status,
                    updatedAt == null ? Instant.now() : updatedAt);
            outcome = "CREATED";
        }
        jdbc.sql("update claim set external_status = cast(:s as text), external_updated_at = coalesce(:u, external_updated_at), version = version + 1 where id = :id")
                .param("s", status).param("u", Db.ts(updatedAt)).param("id", claimId).update();
        String carrier = str(row.get("return_carrier_code"));
        String tracking = str(row.get("return_tracking_no"));
        if (carrier != null && tracking != null) {
            jdbc.sql("""
                    update claim set return_carrier_code = upper(:c), return_tracking_no = upper(regexp_replace(:t, '[\\s-]', '', 'g')),
                           return_status = case when return_status = 'REQUESTED' then 'IN_TRANSIT' else return_status end where id = :id
                      and exists (select 1 from carrier where code = upper(:c))
                    """).param("c", carrier).param("t", tracking).param("id", claimId).update();
        }
        // 채널 결정 따르기(채널에 다시 보낼 필요 없음)
        String cur = jdbc.sql("select status from claim where id = :id").param("id", claimId).query(String.class).single();
        if (status != null) {
            switch (status) {
                case "APPROVED", "COMPLETED" -> {
                    if (Set.of("REQUESTED", "REVIEWING").contains(cur)) {
                        claims.approveInternal(claimId, "판매채널에서 승인", false);
                    }
                }
                case "REJECTED" -> {
                    if (Set.of("REQUESTED", "REVIEWING").contains(cur)) {
                        claims.rejectInternal(claimId, "판매채널에서 거부", false);
                    }
                }
                case "WITHDRAWN" -> {
                    if (ClaimService.OPEN.contains(cur)) {
                        claims.withdrawInternal(claimId, "고객이 판매채널에서 철회");
                    }
                }
                default -> {
                }
            }
        }
        BigDecimal refund = money(row.get("refund_amount"));
        Instant refundedAt = parse(row.get("refunded_at"));
        if (refundedAt != null && refund != null && refund.signum() > 0) {
            String after = jdbc.sql("select status from claim where id = :id").param("id", claimId).query(String.class).single();
            if (Set.of("APPROVED", "PROCESSING", "COMPLETED").contains(after)) {
                claims.recordChannelRefund(claimId, refund, str(row.get("refund_ref")), refundedAt);
            }
        }
        raw(ws, in, key, claimId, outcome, null);
        return new Result(outcome, claimId, null);
    }

    /** 반영 실패 원본 보관 + 업무함(호출자가 새 트랜잭션에서). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reject(UUID ws, Incoming in, String message) {
        String key = str(in.row().get("external_claim_key"));
        raw(ws, in, key, null, "REJECTED", message);
        workItems.raise(new WorkItemService.Raise(ws, null, in.channelAccountId(), in.connectionId(), "CLAIM_INGEST_REJECTED", "HIGH",
                "판매채널 클레임 반영 실패: " + key, message, "주문 수량·상태를 확인하고 필요하면 CS 화면에서 직접 접수하세요.",
                "claim-raw:" + in.channelAccountId() + ":" + key, Map.of("external_claim_key", String.valueOf(key))));
    }

    private void raw(UUID ws, Incoming in, String key, UUID claimId, String result, String detail) {
        jdbc.sql("""
                insert into claim_raw (id, workspace_id, channel_account_id, connection_id, external_claim_key, claim_id, payload, payload_hash, mapping_version, result,
                                       result_detail)
                values (:id, :ws, :ca, cast(:c as uuid), cast(:k as text), cast(:cl as uuid), cast(:p as jsonb), :h, :mv, :r, cast(:d as text))
                on conflict (workspace_id, channel_account_id, payload_hash) do nothing
                """).param("id", Ids.newId()).param("ws", ws).param("ca", in.channelAccountId()).param("c", in.connectionId()).param("k", key).param("cl", claimId)
                .param("p", json.write(strip(in.row()))).param("h", hash(in)).param("mv", in.mappingVersion()).param("r", result).param("d", detail).update();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> strip(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            if (PII_KEYS.contains(k.toLowerCase(Locale.ROOT))) {
                return;
            }
            if (v instanceof Map<?, ?> m) {
                out.put(k, strip((Map<String, Object>) m));
            } else if (v instanceof List<?> l) {
                out.put(k, l.stream().map(x -> x instanceof Map<?, ?> mm ? strip((Map<String, Object>) mm) : x).toList());
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    private static BigDecimal money(Object v) {
        if (v == null) {
            return null;
        }
        return new BigDecimal(String.valueOf(v)).setScale(0, java.math.RoundingMode.UNNECESSARY);
    }

    private static Instant parse(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(v));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String str(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
