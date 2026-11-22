package com.commerceos.integration;

import com.commerceos.fulfillment.ShipmentService;
import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.ActorFactory;
import com.commerceos.integration.spi.Connector;
import com.commerceos.integration.spi.ConnectorContext;
import com.commerceos.integration.spi.ConnectorEnvelope;
import com.commerceos.integration.spi.ConnectorException;
import com.commerceos.integration.spi.LogisticsConnector;
import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.job.Job;
import com.commerceos.platform.job.JobHandler;
import com.commerceos.platform.job.JobOutcome;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.ApiException;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 3PL 출고상태·송장 수신(M4 O5, OUTBOUND_STATUS/READ · TRACKING/READ, docs/04 §9, T10).
 * <ul>
 *   <li>확인된 지원 기능만 호출한다. 원본 행은 개인정보 키를 지우고 해시와 함께 보관, 같은 원본은 한 번만 반영한다.</li>
 *   <li>행마다 따로 트랜잭션(시스템 자동 처리 주체). 반영 실패는 원본에 실패로 남기고 다음 수신에 다시 시도, 출고 반영 실패는 업무함.</li>
 *   <li>워터마크는 실패 행 이전으로는 넘기지 않는다(유실 방지). 구간 = 워터마크 − 30분 ~ 지금(첫 실행 3일).</li>
 * </ul>
 */
@Component
public class ShipmentSyncJobHandler implements JobHandler {
    public static final String TYPE = "SHIPMENT_SYNC";
    private static final Duration OVERLAP = Duration.ofMinutes(30);
    private static final Duration FIRST_WINDOW = Duration.ofDays(3);
    private static final Set<String> PII_KEYS = Set.of("recipient", "buyer", "receiver", "name", "phone", "mobile", "address", "address1", "address2", "email");
    private final JdbcClient jdbc;
    private final ConnectionService connections;
    private final ConnectorRegistry registry;
    private final CapabilityService capabilities;
    private final ShipmentService shipments;
    private final WorkItemService workItems;
    private final AccessGuard guard;
    private final ActorFactory actors;
    private final Json json;
    private final TransactionTemplate tx;

    public ShipmentSyncJobHandler(JdbcClient jdbc, ConnectionService connections, ConnectorRegistry registry, CapabilityService capabilities,
                                  ShipmentService shipments, WorkItemService workItems, AccessGuard guard, ActorFactory actors, Json json,
                                  PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.registry = registry;
        this.capabilities = capabilities;
        this.shipments = shipments;
        this.workItems = workItems;
        this.guard = guard;
        this.actors = actors;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
    }

    @Override
    public String jobType() {
        return TYPE;
    }

    @Override
    public JobOutcome handle(Job job) {
        UUID ws = job.workspaceId();
        Optional<ConnectionService.ConnectionView> found = connections.findSystem(ws, job.connectionId());
        if (found.isEmpty()) {
            return JobOutcome.failed("CONNECTION_NOT_FOUND", "연동 연결을 찾을 수 없습니다.");
        }
        ConnectionService.ConnectionView c = found.get();
        if ("PAUSED".equals(c.status()) || "AUTH_EXPIRED".equals(c.status())) {
            return JobOutcome.failed("CONNECTION_" + c.status(), "연결이 일시중지되었거나 인증이 만료되었습니다.");
        }
        if (c.shipperAccountId() == null) {
            return JobOutcome.failed("CONNECTION_TARGET_INVALID", "출고상태·송장 수신은 화주계정 연결에서만 실행할 수 있습니다.");
        }
        boolean statusOk = capabilities.executableSystem(ws, c.id(), "OUTBOUND_STATUS", "READ");
        boolean trackOk = capabilities.executableSystem(ws, c.id(), "TRACKING", "READ");
        if (!statusOk && !trackOk) {
            return JobOutcome.failed("CAPABILITY_NOT_AVAILABLE", "출고상태·송장 조회 기능이 확인되지 않은 연결입니다(T39).");
        }
        Optional<Connector> connector = registry.find(c.connectorCode()).filter(x -> x instanceof LogisticsConnector);
        if (connector.isEmpty()) {
            return JobOutcome.reviewRequired("CONNECTOR_NOT_AVAILABLE", "물류 연동 모듈을 사용할 수 없습니다.");
        }
        Optional<Map<String, String>> credential = connections.activeCredential(c.id());
        if (credential.isEmpty()) {
            return JobOutcome.failed("CREDENTIAL_MISSING", "등록된 인증정보가 없습니다.");
        }
        UUID be = jdbc.sql("select business_entity_id from shipper_account where id = :id").param("id", c.shipperAccountId()).query(UUID.class).single();
        String externalKey = jdbc.sql("select external_shipper_key from shipper_account where id = :id").param("id", c.shipperAccountId()).query(String.class)
                .optional().orElse(null);
        ConnectorContext ctx = new ConnectorContext(ws, c.id(), c.targetType(), c.shipperAccountId(), externalKey, c.apiVersion(), credential.get());
        LogisticsConnector logistics = (LogisticsConnector) connector.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connection_id", c.id().toString());
        try {
            // 출고상태를 먼저(송장 생성) → 배송 단계
            if (statusOk) {
                out.put("outbound_status", sync(ws, c.id(), be, "OUTBOUND_STATUS", (cur, from, to) -> logistics.fetchOutboundStatus(ctx, cur, from, to)));
            }
            if (trackOk) {
                out.put("tracking", sync(ws, c.id(), be, "TRACKING", (cur, from, to) -> logistics.fetchTracking(ctx, cur, from, to)));
            }
        } catch (ConnectorException e) {
            return switch (e.kind()) {
                case AUTH_EXPIRED -> JobOutcome.failed("AUTH_EXPIRED", "물류사 인증이 만료되었습니다. 인증정보를 갱신한 뒤 다시 실행하세요.");
                case PERMISSION_DENIED -> JobOutcome.failed("PERMISSION_DENIED", "물류사 출고상태·송장 조회 권한이 없습니다.");
                case RATE_LIMITED -> JobOutcome.retry("RATE_LIMITED", "호출 제한으로 잠시 후 다시 시도합니다.", e.retryAfterSeconds() == null ? 60 : e.retryAfterSeconds());
                case UNSUPPORTED -> JobOutcome.reviewRequired("UNSUPPORTED", "연동 모듈이 출고상태·송장 조회를 지원하지 않습니다.");
                default -> JobOutcome.retry("CONNECTOR_TRANSIENT", "물류사 응답이 지연되어 다시 시도합니다.", 30L);
            };
        }
        return JobOutcome.succeeded(out);
    }

    @FunctionalInterface
    interface Fetch {
        ConnectorEnvelope<Map<String, Object>> page(String cursor, Instant from, Instant to);
    }

    private Map<String, Integer> sync(UUID ws, UUID connectionId, UUID be, String kind, Fetch fetch) {
        String stateKey = TYPE + ":" + kind;
        Instant now = Instant.now();
        Instant watermark = jdbc.sql("select watermark from integration_sync_state where connection_id = :c and job_type = :t").param("c", connectionId)
                .param("t", stateKey).query((rs, i) -> Db.instant(rs, "watermark")).optional().orElse(null);
        Instant from = watermark == null ? now.minus(FIRST_WINDOW) : watermark.minus(OVERLAP);
        List<Map<String, Object>> rows = new ArrayList<>();
        String mappingVersion = "unknown";
        String cursor = null;
        for (int page = 0; page < 1000; page++) {
            ConnectorEnvelope<Map<String, Object>> env = fetch.page(cursor, from, now);
            rows.addAll(env.mappedObjects());
            mappingVersion = env.mappingVersion();
            cursor = env.nextCursor();
            if (cursor == null) {
                break;
            }
        }
        Map<String, Integer> counts = new TreeMap<>();
        Instant maxSeen = watermark;
        Instant minFailed = null;
        for (Map<String, Object> row : rows) {
            Map<String, Object> clean = stripPii(row);
            String body = json.write(new TreeMap<>(clean));
            String hash = sha256(kind + ":" + body);
            Instant at = parse(row.get("occurred_at"));
            boolean seen = Boolean.TRUE.equals(jdbc.sql("select exists (select 1 from logistics_raw where workspace_id = :ws and kind = :k and payload_hash = :h and result <> 'FAILED')")
                    .param("ws", ws).param("k", kind).param("h", hash).query(Boolean.class).single());
            if (seen) {
                counts.merge("DUPLICATE", 1, Integer::sum);
                continue;
            }
            String externalKey = "OUTBOUND_STATUS".equals(kind) ? str(row.get("dispatch_key")) : str(row.get("tracking_no"));
            String mv = mappingVersion;
            String result;
            String detail = null;
            UUID foForWork = null;
            try {
                String[] r = guard.runAs(actors.systemAutomation(ws), () -> tx.execute(s -> {
                    String[] res = "OUTBOUND_STATUS".equals(kind) ? applyOutbound(ws, be, row, at) : applyTracking(ws, be, row, at, hash);
                    insertRaw(ws, connectionId, kind, externalKey, body, hash, mv, res[0], res[1]);
                    return res;
                }));
                result = r[0];
                detail = r[1];
            } catch (ApiException e) {
                result = "FAILED";
                detail = e.getMessage();
                foForWork = "OUTBOUND_STATUS".equals(kind) ? findFo(ws, be, externalKey) : null;
            } catch (RuntimeException e) {
                result = "FAILED";
                detail = "반영 중 오류가 발생했습니다.";
            }
            if ("FAILED".equals(result)) {
                String d = detail;
                UUID fo = foForWork;
                tx.executeWithoutResult(s -> {
                    insertRaw(ws, connectionId, kind, externalKey, body, hash, mv, "FAILED", d);
                    if (fo != null) {
                        workItems.raise(new WorkItemService.Raise(ws, be, null, connectionId, "LOGISTICS_SHIPMENT_APPLY_FAILED", "HIGH",
                                "물류사 출고 반영 실패: " + externalKey, d, "재고·출고지시 상태를 확인한 뒤 송장을 직접 등록하거나, 원인을 해결하면 다음 수신에서 다시 반영됩니다.",
                                "fulfillment:" + fo + ":SHIP_APPLY", Map.of("fulfillment_order_id", fo.toString())));
                    }
                });
                if (at != null && (minFailed == null || at.isBefore(minFailed))) {
                    minFailed = at;
                }
            }
            counts.merge(result, 1, Integer::sum);
            if (at != null && (maxSeen == null || at.isAfter(maxSeen))) {
                maxSeen = at;
            }
        }
        Instant mark = maxSeen == null ? now : maxSeen;
        if (minFailed != null && minFailed.isBefore(mark)) {
            mark = minFailed;
        }
        Instant w = mark;
        tx.executeWithoutResult(s -> jdbc.sql("""
                insert into integration_sync_state (workspace_id, connection_id, job_type, watermark) values (:ws, :c, :t, :w)
                on conflict (connection_id, job_type) do update set watermark = greatest(integration_sync_state.watermark, excluded.watermark), updated_at = now()
                """).param("ws", ws).param("c", connectionId).param("t", stateKey).param("w", Db.ts(w)).update());
        counts.put("row_count", rows.size());
        return counts;
    }

    private String[] applyOutbound(UUID ws, UUID be, Map<String, Object> row, Instant at) {
        UUID fo = findFo(ws, be, str(row.get("dispatch_key")));
        if (fo == null) {
            return new String[]{"UNMATCHED", "출고번호와 일치하는 출고지시가 없습니다."};
        }
        if (at == null) {
            return new String[]{"IGNORED", "발생 시각이 없습니다."};
        }
        String status = str(row.get("status"));
        if ("SHIPPED".equals(status)) {
            List<ShipmentService.Parcel> parcels = new ArrayList<>();
            Object raw = row.get("parcels");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> p) {
                        List<ShipmentService.Item> items = null;
                        if (p.get("items") instanceof List<?> its && !its.isEmpty()) {
                            items = new ArrayList<>();
                            for (Object io : its) {
                                Map<?, ?> im = (Map<?, ?>) io;
                                UUID sku = jdbc.sql("select id from sku where workspace_id = :ws and upper(sku_code) = upper(:c)").param("ws", ws)
                                        .param("c", str(im.get("sku_code"))).query(UUID.class).optional()
                                        .orElseThrow(() -> com.commerceos.shared.error.Errors.unprocessable("SKU_UNKNOWN",
                                                "물류사 송장 품목의 SKU 코드를 찾을 수 없습니다: " + im.get("sku_code")));
                                items.add(new ShipmentService.Item(sku, ((Number) im.get("quantity")).intValue()));
                            }
                        }
                        parcels.add(new ShipmentService.Parcel(str(p.get("carrier_code")), str(p.get("tracking_no")), items));
                    }
                }
            }
            String r = shipments.applyShip(fo, new ShipmentService.Ship(at, parcels), "API");
            return new String[]{"SHIPPED".equals(r) ? "APPLIED" : "IGNORED", r};
        }
        String r = shipments.applyProgress(fo, status, at);
        return new String[]{r, status};
    }

    private String[] applyTracking(UUID ws, UUID be, Map<String, Object> row, Instant at, String hash) {
        String carrier = str(row.get("carrier_code"));
        String key = row.get("tracking_no") == null ? null : String.valueOf(row.get("tracking_no")).replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
        List<UUID> matches = jdbc.sql("select id from shipment where workspace_id = :ws and business_entity_id = :be and carrier_code = :c and tracking_key = :k")
                .param("ws", ws).param("be", be).param("c", carrier == null ? "" : carrier.toUpperCase(java.util.Locale.ROOT)).param("k", key == null ? "" : key)
                .query(UUID.class).list();
        if (matches.isEmpty()) {
            // 송장이 아직 등록되기 전일 수 있다 → 실패로 남겨 다음 수신에서 다시 시도
            throw com.commerceos.shared.error.Errors.conflict("SHIPMENT_NOT_YET_REGISTERED", "등록된 송장이 없습니다: " + carrier + " " + key);
        }
        String last = "IGNORED_STALE";
        for (UUID s : matches) {
            String r = shipments.applyTracking(s, str(row.get("status")), at, str(row.get("description")), str(row.get("location")), str(row.get("code")), "API",
                    "API:" + hash);
            if ("APPLIED".equals(r)) {
                last = r;
            }
        }
        return new String[]{"APPLIED".equals(last) ? "APPLIED" : "IGNORED", last};
    }

    private UUID findFo(UUID ws, UUID be, String dispatchKey) {
        if (dispatchKey == null) {
            return null;
        }
        return jdbc.sql("select id from fulfillment_order where workspace_id = :ws and business_entity_id = :be and dispatch_key = :k").param("ws", ws).param("be", be)
                .param("k", dispatchKey).query(UUID.class).optional().orElse(null);
    }

    private void insertRaw(UUID ws, UUID connectionId, String kind, String externalKey, String body, String hash, String mv, String result, String detail) {
        jdbc.sql("""
                insert into logistics_raw (id, workspace_id, connection_id, kind, external_key, payload, payload_hash, mapping_version, result, result_detail)
                values (:id, :ws, :c, :k, cast(:ek as text), cast(:p as jsonb), :h, :mv, :r, cast(:d as text))
                on conflict (workspace_id, kind, payload_hash) where result <> 'FAILED' do nothing
                """).param("id", Ids.newId()).param("ws", ws).param("c", connectionId).param("k", kind).param("ek", externalKey).param("p", body).param("h", hash)
                .param("mv", mv).param("r", result).param("d", detail).update();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> stripPii(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            if (PII_KEYS.contains(k.toLowerCase(java.util.Locale.ROOT))) {
                return;
            }
            if (v instanceof Map<?, ?> m) {
                out.put(k, stripPii((Map<String, Object>) m));
            } else if (v instanceof List<?> l) {
                out.put(k, l.stream().map(x -> x instanceof Map<?, ?> mm ? stripPii((Map<String, Object>) mm) : x).toList());
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    private static Instant parse(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
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
