package com.commerceos.fulfillment;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.operations.imports.TabularReader;
import com.commerceos.platform.file.FileService;
import com.commerceos.shared.error.ApiException;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 송장 파일 업로드(M4 O5, DLG-133). 물류사 화면에서 내려받은 출고·송장 파일을 출고번호 기준으로 반영한다.
 * 열: 출고번호(필수) · 택배사(코드 또는 이름) · 송장번호 · 출고일시(선택, 한국 시각). 같은 출고번호 여러 행 = 박스 여러 개(품목은 첫 박스).
 * 출고지시마다 따로 반영(한 건 실패가 다른 건을 막지 않음)하고, 같은 송장 재업로드는 중복으로 끝난다. 원본 파일·행은 보관한다.
 */
@Service
public class ShipmentImportService {
    static final String MAPPING_VERSION = "shipment-file-v1";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Map<String, String> CARRIER_ALIASES = Map.ofEntries(
            Map.entry("CJ대한통운", "CJ"), Map.entry("대한통운", "CJ"), Map.entry("CJ택배", "CJ"), Map.entry("롯데", "LOTTE"), Map.entry("롯데택배", "LOTTE"),
            Map.entry("롯데글로벌로지스", "LOTTE"), Map.entry("한진", "HANJIN"), Map.entry("한진택배", "HANJIN"), Map.entry("우체국", "EPOST"),
            Map.entry("우체국택배", "EPOST"), Map.entry("로젠", "LOGEN"), Map.entry("로젠택배", "LOGEN"), Map.entry("경동", "KDEXP"), Map.entry("경동택배", "KDEXP"),
            Map.entry("대신", "DAESIN"), Map.entry("대신택배", "DAESIN"), Map.entry("일양", "ILYANG"), Map.entry("일양로지스", "ILYANG"),
            Map.entry("GS편의점택배", "CVSNET"), Map.entry("GSPOSTBOX택배", "CVSNET"), Map.entry("CU편의점택배", "CU_POST"), Map.entry("직접배송", "DIRECT"),
            Map.entry("기타", "OTHER"));
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final ShipmentService shipments;
    private final FulfillmentService fulfillment;
    private final FileService files;
    private final Json json;
    private final TransactionTemplate tx;

    public ShipmentImportService(JdbcClient jdbc, AccessGuard guard, ShipmentService shipments, FulfillmentService fulfillment, FileService files, Json json,
                                 PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.shipments = shipments;
        this.fulfillment = fulfillment;
        this.files = files;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
    }

    public record RowResult(int row, String foNo, String result, String message) {
    }

    public record ImportResult(UUID fileId, int rowCount, int shipped, int duplicate, int failed, List<RowResult> results) {
    }

    private record Row(int rowNumber, String foNo, String carrier, String tracking, Instant shippedAt, Map<String, String> raw) {
    }

    public ImportResult upload(String filename, String contentType, byte[] content) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        if (content == null || content.length == 0) {
            throw Errors.validation("file", "빈 파일입니다.");
        }
        TabularReader.Table table = TabularReader.read(content, filename, null);
        Map<String, String> col = new LinkedHashMap<>();
        for (String h : table.headers()) {
            String k = h.replaceAll("\\s", "");
            switch (k) {
                case "출고번호" -> col.put("fo", h);
                case "택배사", "택배사코드" -> col.putIfAbsent("carrier", h);
                case "송장번호", "운송장번호" -> col.putIfAbsent("tracking", h);
                case "출고일시", "출고시각", "출고일" -> col.putIfAbsent("at", h);
                default -> {
                }
            }
        }
        for (String req : List.of("fo", "carrier", "tracking")) {
            if (!col.containsKey(req)) {
                throw Errors.unprocessable("TEMPLATE_COLUMN_MISSING", "필수 열이 없습니다: 출고번호 · 택배사 · 송장번호",
                        Map.of("required", List.of("출고번호", "택배사", "송장번호"), "optional", List.of("출고일시")));
            }
        }
        FileService.FileView stored = tx.execute(s -> files.store(new FileService.NewFile(a.workspaceId(), null, "SHIPMENT_IMPORT", filename, contentType, content,
                false, a.memberId(), null)));
        List<RowResult> results = new ArrayList<>();
        Map<String, List<Row>> byFo = new LinkedHashMap<>();
        for (TabularReader.DataRow r : table.rows()) {
            Map<String, String> v = r.values();
            String fo = val(v, col.get("fo"));
            String carrier = resolveCarrier(val(v, col.get("carrier")));
            String tracking = val(v, col.get("tracking"));
            Instant at;
            try {
                at = parseTime(val(v, col.get("at")));
            } catch (DateTimeParseException e) {
                results.add(new RowResult(r.rowNumber(), fo, "FAILED", "출고일시는 YYYY-MM-DD HH:mm 형식(한국 시각)입니다."));
                continue;
            }
            if (fo == null || tracking == null) {
                results.add(new RowResult(r.rowNumber(), fo, "FAILED", "출고번호·송장번호를 확인하세요."));
                continue;
            }
            if (carrier == null) {
                results.add(new RowResult(r.rowNumber(), fo, "FAILED", "알 수 없는 택배사입니다: " + val(v, col.get("carrier"))));
                continue;
            }
            byFo.computeIfAbsent(fo.toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(new Row(r.rowNumber(), fo, carrier, tracking, at, v));
        }
        for (var e : byFo.entrySet()) {
            List<Row> rows = e.getValue();
            Instant at = rows.stream().map(Row::shippedAt).filter(java.util.Objects::nonNull).findFirst().orElse(Instant.now());
            String result;
            String message = null;
            try {
                result = tx.execute(s -> {
                    UUID foId = jdbc.sql("select id from fulfillment_order where workspace_id = :ws and upper(fo_no) = :no").param("ws", a.workspaceId())
                            .param("no", e.getKey()).query(UUID.class).optional().orElseThrow(() -> Errors.notFound("FULFILLMENT_ORDER"));
                    fulfillment.writable(foId);
                    String r = shipments.applyShip(foId, new ShipmentService.Ship(at, rows.stream()
                            .map(x -> new ShipmentService.Parcel(x.carrier(), x.tracking(), null)).toList()), "FILE");
                    for (Row x : rows) {
                        raw(a.workspaceId(), stored.id(), x, "SHIPPED".equals(r) ? "APPLIED" : "DUPLICATE", null);
                    }
                    return r;
                });
            } catch (ApiException ex) {
                result = "FAILED";
                message = "NOT_FOUND".equals(ex.code()) ? "출고번호를 찾을 수 없거나 권한 밖입니다." : ex.getMessage();
                String m = message;
                tx.executeWithoutResult(s -> rows.forEach(x -> raw(a.workspaceId(), stored.id(), x, "FAILED", m)));
            }
            for (Row x : rows) {
                results.add(new RowResult(x.rowNumber(), x.foNo(), result, message));
            }
        }
        results.sort(java.util.Comparator.comparingInt(RowResult::row));
        int shipped = (int) results.stream().filter(r -> "SHIPPED".equals(r.result())).count();
        int dup = (int) results.stream().filter(r -> "DUPLICATE".equals(r.result())).count();
        return new ImportResult(stored.id(), table.rows().size(), shipped, dup, results.size() - shipped - dup, results);
    }

    private void raw(UUID ws, UUID fileId, Row x, String result, String detail) {
        Map<String, Object> payload = new LinkedHashMap<>(x.raw());
        payload.put("_row", x.rowNumber());
        String body = json.write(payload);
        jdbc.sql("""
                insert into logistics_raw (id, workspace_id, file_id, kind, external_key, payload, payload_hash, mapping_version, result, result_detail)
                values (:id, :ws, :f, 'SHIPMENT_FILE_ROW', :k, cast(:p as jsonb), :h, :mv, :r, cast(:d as text))
                on conflict (workspace_id, kind, payload_hash) where result <> 'FAILED' do nothing
                """).param("id", Ids.newId()).param("ws", ws).param("f", fileId).param("k", x.foNo()).param("p", body)
                .param("h", sha256(fileId + ":" + body)).param("mv", MAPPING_VERSION).param("r", result).param("d", detail).update();
    }

    String resolveCarrier(String raw) {
        if (raw == null) {
            return null;
        }
        String k = raw.replaceAll("\\s", "");
        String upper = k.toUpperCase(Locale.ROOT);
        Optional<String> byCode = jdbc.sql("select code from carrier where active and (code = :c or replace(name_ko, ' ', '') = :n)").param("c", upper).param("n", k)
                .query(String.class).optional();
        return byCode.orElse(CARRIER_ALIASES.getOrDefault(k, CARRIER_ALIASES.get(upper)));
    }

    static Instant parseTime(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().replace('/', '-').replace('.', '-');
        try {
            return Instant.parse(t);
        } catch (DateTimeParseException ignore) {
            // 한국 시각 표기로 다시 시도
        }
        for (String p : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm")) {
            try {
                return LocalDateTime.parse(t, DateTimeFormatter.ofPattern(p)).atZone(KST).toInstant();
            } catch (DateTimeParseException ignore) {
                // 다음 형식
            }
        }
        return LocalDate.parse(t).atStartOfDay(KST).toInstant();
    }

    private static String val(Map<String, String> v, String col) {
        if (col == null) {
            return null;
        }
        String s = v.get(col);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
