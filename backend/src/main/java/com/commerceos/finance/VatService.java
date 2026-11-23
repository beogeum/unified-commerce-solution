package com.commerceos.finance;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.operations.imports.TabularReader;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.file.FileService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 사업자별 부가세 신고자료 수집·표준화(M6 2차, docs/15, FR-FIN-08, DLG-183~189). 국세청 자동 신고가 아니라 회계 담당자가 검증할 표준 자료다.
 * <ul>
 *   <li>묶음 = 판매계정 × 신고기간(사업자는 판매계정의 사업자). 채널은 파일명으로 추정하지 않고 묶음 선택으로 정한다.</li>
 *   <li>표준 양식 파일만(채널별 원본 양식 변환은 실계정 확인 후). 원본 파일·SHA-256·시트·행 번호·파서 버전 보존, 같은 파일은 사업자 안에서 한 번만.</li>
 *   <li>검증: 사업자등록번호 불일치 파일 거부, 기간 혼입·합계 불일치·부호 오류·세금유형 미상·면세/영세 세액·증빙-과세 불일치를 행별 문제로 표시.</li>
 *   <li>분류 규칙 vat-class-v1(상호 배타, 증빙 → 세금유형 → 결제수단 순). 세무 담당 확정 전 임시 규칙이며 버전을 남긴다.</li>
 *   <li>확정은 파일을 올리지 않은 다른 담당자가, 차단 문제가 모두 보정된 뒤에만. 확정 후에는 바꿀 수 없다.</li>
 * </ul>
 */
@Service
public class VatService {
    public static final String RULE_VERSION = "vat-class-v1";
    static final String PARSER_VERSION = "vat-standard-template-v1";
    static final Set<String> CLASSES = Set.of("CARD", "CASH_RECEIPT", "MOBILE", "OTHER", "TAX_INVOICE", "FREE_CARD", "FREE_CASH_RECEIPT", "FREE_MOBILE",
            "FREE_OTHER", "E_INVOICE", "ZERO_RATED", "REVIEW", "EXCLUDED");
    static final Map<String, String> CLASS_KO = Map.ofEntries(Map.entry("CARD", "카드매출"), Map.entry("CASH_RECEIPT", "현금영수증"), Map.entry("MOBILE", "휴대폰"),
            Map.entry("OTHER", "기타"), Map.entry("TAX_INVOICE", "세금계산서"), Map.entry("FREE_CARD", "면세카드"), Map.entry("FREE_CASH_RECEIPT", "면세현금영수증"),
            Map.entry("FREE_MOBILE", "면세휴대폰"), Map.entry("FREE_OTHER", "면세기타"), Map.entry("E_INVOICE", "전자계산서"), Map.entry("ZERO_RATED", "영세율(검토)"),
            Map.entry("REVIEW", "검토 필요"), Map.entry("EXCLUDED", "제외"));
    static final Set<String> BLOCKING = Set.of("PERIOD_OUT", "TOTAL_MISMATCH", "SIGN_MISMATCH", "TAX_TYPE_UNKNOWN", "VAT_ON_NON_TAXABLE", "EVIDENCE_TAX_MISMATCH");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final FileService files;
    private final AuditService audit;
    private final Json json;

    public VatService(JdbcClient jdbc, AccessGuard guard, FileService files, AuditService audit, Json json) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.files = files;
        this.audit = audit;
        this.json = json;
    }

    public record NewBatch(UUID channelAccountId, LocalDate periodFrom, LocalDate periodTo) {
    }

    public record ClassTotal(String classification, String label, int rows, BigDecimal supply, BigDecimal vat, BigDecimal total) {
    }

    public record FileView(UUID id, UUID fileId, String filename, String sha256, String sheetName, String parserVersion, int rowCount, int newRows, int duplicateRows,
                           String importedByName, java.time.Instant importedAt) {
    }

    public record RowView(UUID id, int rowNo, String fileName, LocalDate txnDate, String sourceKey, String salesType, String taxType, String paymentMethod,
                          String evidenceType, BigDecimal supply, BigDecimal vat, BigDecimal total, String classification, String effectiveClassification,
                          List<String> issues, boolean blocking, String overrideReason) {
    }

    public record Recon(BigDecimal vatSales, BigDecimal vatRefunds, BigDecimal vatTotal, BigDecimal ledgerSales, BigDecimal ledgerRefunds, BigDecimal ledgerTotal,
                        BigDecimal settlementTotal, boolean settlementImported, BigDecimal diffVsLedger, BigDecimal diffVsSettlement, String note) {
    }

    public record BatchView(UUID id, UUID businessEntityId, String businessEntityName, String businessRegistrationNo, UUID channelAccountId, String channelAccountName,
                            String salesChannelCode, LocalDate periodFrom, LocalDate periodTo, String status, String ruleVersion, int rows, int blockingRows,
                            int warningRows, List<ClassTotal> totals, Recon recon, List<FileView> files, List<RowView> issueRows, String confirmedByName,
                            java.time.Instant confirmedAt, java.time.Instant createdAt) {
    }

    public record ImportResult(UUID batchId, int rowCount, int newRows, int duplicateRows, int blockingRows, int warningRows) {
    }

    public record EntityReport(UUID businessEntityId, String businessEntityName, String businessRegistrationNo, LocalDate periodFrom, LocalDate periodTo,
                               String ruleVersion, boolean allConfirmed, List<ClassTotal> totals, List<BatchView> batches, String note) {
    }

    // ---------------------------------------------------------------- 묶음

    @Transactional
    public BatchView createBatch(NewBatch in) {
        guard.requirePermission(Perm.VAT_MANAGE);
        CurrentActor a = guard.actor();
        if (in == null || in.channelAccountId() == null || in.periodFrom() == null || in.periodTo() == null) {
            throw Errors.validation("channel_account_id", "판매계정과 신고기간을 입력하세요.");
        }
        if (in.periodTo().isBefore(in.periodFrom()) || in.periodFrom().plusMonths(12).isBefore(in.periodTo())) {
            throw Errors.validation("period_to", "신고기간을 확인하세요(시작 ≤ 끝, 12개월 이내).");
        }
        UUID be = jdbc.sql("select business_entity_id from channel_account where workspace_id = :ws and id = :id").param("ws", a.workspaceId())
                .param("id", in.channelAccountId()).query(UUID.class).optional().orElseThrow(() -> Errors.notFound("CHANNEL_ACCOUNT"));
        guard.checkWrite("CHANNEL_ACCOUNT", in.channelAccountId(), be, in.channelAccountId());
        UUID existing = jdbc.sql("select id from vat_batch where channel_account_id = :ca and period_from = :f and period_to = :t").param("ca", in.channelAccountId())
                .param("f", in.periodFrom()).param("t", in.periodTo()).query(UUID.class).optional().orElse(null);
        if (existing != null) {
            throw Errors.conflict("VAT_BATCH_EXISTS", "같은 판매계정·신고기간의 신고자료 묶음이 이미 있습니다.", Map.of("batch_id", existing.toString()));
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into vat_batch (id, workspace_id, business_entity_id, channel_account_id, period_from, period_to, status, rule_version, created_by)
                values (:id, :ws, :be, :ca, :f, :t, 'DRAFT', :rv, :by)
                """).param("id", id).param("ws", a.workspaceId()).param("be", be).param("ca", in.channelAccountId()).param("f", in.periodFrom()).param("t", in.periodTo())
                .param("rv", RULE_VERSION).param("by", a.memberId()).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "VAT_BATCH_CREATED", "VAT_BATCH", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, in.channelAccountId()).after(Map.of("period_from", in.periodFrom().toString(), "period_to", in.periodTo().toString())).build());
        return get(id);
    }

    // ---------------------------------------------------------------- 가져오기

    @Transactional
    public ImportResult importFile(UUID batchId, String filename, String contentType, byte[] content) {
        guard.requirePermission(Perm.VAT_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> b = lockBatch(a.workspaceId(), batchId);
        UUID be = (UUID) b.get("business_entity_id");
        UUID ca = (UUID) b.get("channel_account_id");
        guard.checkWrite("VAT_BATCH", batchId, be, ca);
        requireDraft(b);
        if (content == null || content.length == 0) {
            throw Errors.validation("file", "빈 파일입니다.");
        }
        String sha = sha256(content);
        Map<String, Object> dup = jdbc.sql("select batch_id, original_filename from vat_source_file where business_entity_id = :be and sha256 = :h").param("be", be)
                .param("h", sha).query().listOfRows().stream().findFirst().orElse(null);
        if (dup != null) {
            throw Errors.conflict("VAT_FILE_DUPLICATE", "같은 파일을 이미 올렸습니다(" + dup.get("original_filename") + ").", Map.of("batch_id", dup.get("batch_id").toString()));
        }
        TabularReader.Table table = TabularReader.read(content, filename, null);
        Map<String, String> col = columns(table.headers());
        for (String req : List.of("date", "sales_type", "tax_type", "payment", "evidence", "supply", "vat")) {
            if (!col.containsKey(req)) {
                throw Errors.unprocessable("TEMPLATE_COLUMN_MISSING", "필수 열이 없습니다: 거래일 · 매출유형 · 세금유형 · 결제수단 · 증빙유형 · 공급가 · 세액",
                        Map.of("required", List.of("거래일", "매출유형", "세금유형", "결제수단", "증빙유형", "공급가", "세액"), "optional", List.of("주문번호", "합계", "사업자등록번호")));
            }
        }
        String brn = (String) b.get("brn");
        LocalDate pf = ((java.sql.Date) b.get("period_from")).toLocalDate();
        LocalDate pt = ((java.sql.Date) b.get("period_to")).toLocalDate();
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> parsed = new ArrayList<>();
        for (TabularReader.DataRow r : table.rows()) {
            Map<String, String> v = r.values();
            try {
                String rowBrn = val(v, col.get("brn"));
                if (rowBrn != null && !rowBrn.replaceAll("[^0-9]", "").equals(brn)) {
                    throw Errors.unprocessable("VAT_ENTITY_MISMATCH", "파일의 사업자등록번호가 이 판매계정의 사업자와 다릅니다. 다른 사업자 자료는 섞을 수 없습니다.",
                            Map.of("row", r.rowNumber()));
                }
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("row_no", r.rowNumber());
                p.put("date", LocalDate.parse(val(v, col.get("date")).replace('/', '-').replace('.', '-').substring(0, 10)));
                p.put("source_key", val(v, col.get("key")));
                p.put("sales_type", code(val(v, col.get("sales_type")), SALES_TYPES, "매출유형"));
                p.put("tax_type", code(val(v, col.get("tax_type")), TAX_TYPES, "세금유형"));
                p.put("payment", code(val(v, col.get("payment")), PAYMENTS, "결제수단"));
                p.put("evidence", val(v, col.get("evidence")) == null ? "NONE" : code(val(v, col.get("evidence")), EVIDENCES, "증빙유형"));
                BigDecimal supply = won(val(v, col.get("supply")));
                BigDecimal vat = won(val(v, col.get("vat")));
                p.put("supply", supply);
                p.put("vat", vat);
                p.put("total", val(v, col.get("total")) == null ? supply.add(vat) : won(val(v, col.get("total"))));
                Map<String, Object> raw = new LinkedHashMap<>(v);
                raw.put("_row", r.rowNumber());
                raw.put("_parser", PARSER_VERSION);
                raw.put("_sheet", table.sheetName());
                p.put("raw", raw);
                parsed.add(p);
            } catch (com.commerceos.shared.error.ApiException e) {
                if ("VAT_ENTITY_MISMATCH".equals(e.code())) {
                    throw e;
                }
                errors.add(Map.of("row", r.rowNumber(), "message", e.getMessage()));
            } catch (RuntimeException e) {
                errors.add(Map.of("row", r.rowNumber(), "message", "거래일(YYYY-MM-DD)·금액(원 단위 정수)을 확인하세요."));
            }
        }
        if (!errors.isEmpty()) {
            throw Errors.unprocessable("VAT_FILE_INVALID", "파일에 잘못된 행이 있어 반영하지 않았습니다. 고친 뒤 다시 올리세요.",
                    Map.of("errors", errors.size() > 50 ? errors.subList(0, 50) : errors, "error_count", errors.size()));
        }
        FileService.FileView stored = files.store(new FileService.NewFile(a.workspaceId(), be, "VAT_SOURCE", filename, contentType, content, false, a.memberId(), null));
        UUID sfId = Ids.newId();
        // 원본 파일 기록을 먼저(행이 참조), 행 수는 마지막에 확정 값으로 — 추가 전용이므로 계산 후 한 번에 넣는다
        List<Object[]> toInsert = new ArrayList<>();
        Map<String, Integer> occurrence = new java.util.HashMap<>();
        int dupRows = 0;
        int blocking = 0;
        int warning = 0;
        for (Map<String, Object> p : parsed) {
            String content2 = p.get("date") + "|" + n((String) p.get("source_key")) + "|" + p.get("sales_type") + "|" + p.get("tax_type") + "|" + p.get("payment") + "|"
                    + p.get("evidence") + "|" + ((BigDecimal) p.get("supply")).toPlainString() + "|" + ((BigDecimal) p.get("vat")).toPlainString() + "|"
                    + ((BigDecimal) p.get("total")).toPlainString();
            String hash = sha256((content2 + "|#" + occurrence.merge(content2, 1, Integer::sum)).getBytes(StandardCharsets.UTF_8));
            if (jdbc.sql("select count(*) from vat_row where batch_id = :b and row_hash = :h").param("b", batchId).param("h", hash).query(Integer.class).single() > 0) {
                dupRows++;
                continue;
            }
            List<String> issues = issues(p, pf, pt);
            if (issues.stream().anyMatch(BLOCKING::contains)) {
                blocking++;
            } else if (!issues.isEmpty()) {
                warning++;
            }
            toInsert.add(new Object[]{p, issues, hash});
        }
        jdbc.sql("""
                insert into vat_source_file (id, workspace_id, batch_id, business_entity_id, file_id, sha256, original_filename, sheet_name, parser_version, row_count,
                                             new_rows, duplicate_rows, imported_by)
                values (:id, :ws, :b, :be, :f, :h, :fn, cast(:sh as text), :pv, :rc, :nr, :dr, :by)
                """).param("id", sfId).param("ws", a.workspaceId()).param("b", batchId).param("be", be).param("f", stored.id()).param("h", sha).param("fn", filename)
                .param("sh", table.sheetName()).param("pv", PARSER_VERSION).param("rc", parsed.size()).param("nr", toInsert.size()).param("dr", dupRows)
                .param("by", a.memberId()).update();
        for (Object[] x : toInsert) {
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) x[0];
            @SuppressWarnings("unchecked") List<String> issues = (List<String>) x[1];
            jdbc.sql("""
                    insert into vat_row (id, workspace_id, batch_id, source_file_id, row_no, txn_date, source_key, sales_type, tax_type, payment_method, evidence_type,
                                         supply_amount, vat_amount, total_amount, classification, issues, row_hash, raw)
                    values (:id, :ws, :b, :sf, :rn, :d, cast(:k as text), :st, :tt, :pm, :ev, :s, :v, :t, :c, cast(:i as jsonb), :h, cast(:raw as jsonb))
                    """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("b", batchId).param("sf", sfId).param("rn", p.get("row_no")).param("d", p.get("date"))
                    .param("k", p.get("source_key")).param("st", p.get("sales_type")).param("tt", p.get("tax_type")).param("pm", p.get("payment"))
                    .param("ev", p.get("evidence")).param("s", p.get("supply")).param("v", p.get("vat")).param("t", p.get("total"))
                    .param("c", classify((String) p.get("tax_type"), (String) p.get("payment"), (String) p.get("evidence"))).param("i", json.write(issues))
                    .param("h", x[2]).param("raw", json.write(p.get("raw"))).update();
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "VAT_FILE_IMPORTED", "VAT_BATCH", batchId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, ca).after(Map.of("file_id", stored.id().toString(), "sha256", sha, "rows", parsed.size(), "new_rows", toInsert.size(), "duplicate_rows", dupRows,
                        "parser", PARSER_VERSION)).build());
        return new ImportResult(batchId, parsed.size(), toInsert.size(), dupRows, blocking, warning);
    }

    /** 분류 규칙 vat-class-v1: 증빙 → 세금유형 → 결제수단(상호 배타, 이중 합산 없음). */
    static String classify(String taxType, String payment, String evidence) {
        boolean taxInvoice = "TAX_INVOICE".equals(evidence) || "E_TAX_INVOICE".equals(evidence);
        boolean invoice = "INVOICE".equals(evidence) || "E_INVOICE".equals(evidence);
        if ("OTHER".equals(taxType)) {
            return "REVIEW";
        }
        if (taxInvoice) {
            return "TAX_FREE".equals(taxType) ? "REVIEW" : "TAX_INVOICE";
        }
        if (invoice) {
            return "TAX_FREE".equals(taxType) ? "E_INVOICE" : "REVIEW";
        }
        if ("ZERO_RATED".equals(taxType)) {
            return "ZERO_RATED";
        }
        String base = switch (payment) {
            case "CARD" -> "CARD";
            case "CASH_RECEIPT" -> "CASH_RECEIPT";
            case "MOBILE" -> "MOBILE";
            default -> "OTHER";
        };
        return "TAX_FREE".equals(taxType) ? "FREE_" + base : base;
    }

    static List<String> issues(Map<String, Object> p, LocalDate pf, LocalDate pt) {
        List<String> out = new ArrayList<>();
        LocalDate d = (LocalDate) p.get("date");
        BigDecimal supply = (BigDecimal) p.get("supply");
        BigDecimal vat = (BigDecimal) p.get("vat");
        BigDecimal total = (BigDecimal) p.get("total");
        String st = (String) p.get("sales_type");
        String tt = (String) p.get("tax_type");
        String ev = (String) p.get("evidence");
        if (d.isBefore(pf) || d.isAfter(pt)) {
            out.add("PERIOD_OUT");
        }
        if (supply.add(vat).compareTo(total) != 0) {
            out.add("TOTAL_MISMATCH");
        }
        if (("SALE".equals(st) && total.signum() < 0) || ("REFUND".equals(st) && total.signum() > 0)) {
            out.add("SIGN_MISMATCH");
        }
        if ("OTHER".equals(tt)) {
            out.add("TAX_TYPE_UNKNOWN");
        }
        if (("TAX_FREE".equals(tt) || "ZERO_RATED".equals(tt)) && vat.signum() != 0) {
            out.add("VAT_ON_NON_TAXABLE");
        }
        if ((("TAX_INVOICE".equals(ev) || "E_TAX_INVOICE".equals(ev)) && "TAX_FREE".equals(tt)) || (("INVOICE".equals(ev) || "E_INVOICE".equals(ev))
                && ("TAXABLE".equals(tt) || "ZERO_RATED".equals(tt)))) {
            out.add("EVIDENCE_TAX_MISMATCH");
        }
        if ("TAXABLE".equals(tt) && supply.divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP).subtract(vat).abs().compareTo(BigDecimal.ONE) > 0) {
            out.add("VAT_RATE_DEVIATION"); // 경고(부가세 10% 와 1원 넘게 차이)
        }
        return out;
    }

    // ---------------------------------------------------------------- 보정·확정

    @Transactional
    public RowView override(UUID rowId, String classification, String reason) {
        guard.requirePermission(Perm.VAT_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> r = jdbc.sql("select batch_id from vat_row where workspace_id = :ws and id = :id").param("ws", a.workspaceId()).param("id", rowId).query()
                .listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("VAT_ROW"));
        UUID batchId = (UUID) r.get("batch_id");
        Map<String, Object> b = lockBatch(a.workspaceId(), batchId);
        guard.checkWrite("VAT_BATCH", batchId, (UUID) b.get("business_entity_id"), (UUID) b.get("channel_account_id"));
        requireDraft(b);
        if (classification == null || !CLASSES.contains(classification) || "REVIEW".equals(classification)) {
            throw Errors.validation("classification", "보정할 분류를 선택하세요(제외 포함).");
        }
        String why = reason == null ? null : reason.trim();
        if (why == null || why.isEmpty() || why.length() > 500) {
            throw Errors.validation("reason", "보정 사유를 500자 이내로 입력하세요.");
        }
        jdbc.sql("insert into vat_row_override (id, workspace_id, row_id, classification, reason, created_by) values (:id, :ws, :r, :c, :why, :by)").param("id", Ids.newId())
                .param("ws", a.workspaceId()).param("r", rowId).param("c", classification).param("why", why).param("by", a.memberId()).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "VAT_ROW_OVERRIDDEN", "VAT_ROW", rowId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) b.get("business_entity_id"), (UUID) b.get("channel_account_id")).reason(why).after(Map.of("classification", classification)).build());
        return rows(batchId, "r.id = :rid", Map.of("rid", rowId)).get(0);
    }

    @Transactional
    public BatchView confirm(UUID batchId) {
        guard.requirePermission(Perm.VAT_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> b = lockBatch(a.workspaceId(), batchId);
        guard.checkWrite("VAT_BATCH", batchId, (UUID) b.get("business_entity_id"), (UUID) b.get("channel_account_id"));
        requireDraft(b);
        int fileCount = jdbc.sql("select count(*) from vat_source_file where batch_id = :b").param("b", batchId).query(Integer.class).single();
        if (fileCount == 0) {
            throw Errors.unprocessable("VAT_BATCH_EMPTY", "가져온 신고자료가 없습니다.");
        }
        if (jdbc.sql("select count(*) from vat_source_file where batch_id = :b and imported_by = :m").param("b", batchId).param("m", a.memberId()).query(Integer.class)
                .single() > 0) {
            throw Errors.unprocessable("VAT_SELF_CONFIRM_FORBIDDEN", "자료를 올린 담당자는 확정할 수 없습니다. 다른 회계 담당자가 검토 후 확정하세요.");
        }
        long open = rows(batchId, "true", Map.of()).stream().filter(r -> r.blocking() || "REVIEW".equals(r.effectiveClassification())).count();
        if (open > 0) {
            throw Errors.unprocessable("VAT_BATCH_HAS_ISSUES", "보정하지 않은 문제 행 " + open + "건이 있습니다. 분류를 보정하거나 제외한 뒤 확정하세요.", Map.of("open_rows", open));
        }
        jdbc.sql("update vat_batch set status = 'CONFIRMED', confirmed_by = :by, confirmed_at = now(), version = version + 1 where id = :id").param("by", a.memberId())
                .param("id", batchId).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "VAT_BATCH_CONFIRMED", "VAT_BATCH", batchId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) b.get("business_entity_id"), (UUID) b.get("channel_account_id")).after(Map.of("rule_version", RULE_VERSION)).build());
        return get(batchId);
    }

    // ---------------------------------------------------------------- 조회

    public List<BatchView> list(List<String> businessEntityIds, LocalDate from, LocalDate to) {
        guard.requirePermission(Perm.SETTLEMENT_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        if (bes.isEmpty() || cas.isEmpty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "bes", bes, "cas", cas));
        String sql = "select id from vat_batch where workspace_id = :ws and business_entity_id in (:bes) and channel_account_id in (:cas)";
        if (from != null) {
            sql += " and period_to >= :f";
            p.put("f", from);
        }
        if (to != null) {
            sql += " and period_from <= :t";
            p.put("t", to);
        }
        return jdbc.sql(sql + " order by period_from desc, created_at desc limit 200").params(p).query(UUID.class).list().stream().map(id -> view(id, false)).toList();
    }

    public BatchView get(UUID id) {
        guard.requirePermission(Perm.SETTLEMENT_READ);
        Map<String, Object> b = jdbc.sql("select business_entity_id, channel_account_id from vat_batch where workspace_id = :ws and id = :id")
                .param("ws", guard.actor().workspaceId()).param("id", id).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("VAT_BATCH"));
        guard.checkRead("VAT_BATCH", id, (UUID) b.get("business_entity_id"), (UUID) b.get("channel_account_id"));
        return view(id, true);
    }

    /** 사업자 1곳 × 기간의 신고자료(판매계정 묶음 합계). 여러 사업자를 합치지 않는다. */
    public EntityReport entityReport(UUID businessEntityId, LocalDate from, LocalDate to) {
        guard.requirePermission(Perm.SETTLEMENT_READ);
        if (businessEntityId == null || from == null || to == null) {
            throw Errors.validation("business_entity_id", "사업자 한 곳과 신고기간을 지정하세요(여러 사업자 자료는 합치지 않습니다).");
        }
        guard.checkRead("BUSINESS_ENTITY", businessEntityId, businessEntityId, null);
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        Map<String, Object> be = jdbc.sql("select display_name, business_registration_no from business_entity where id = :id").param("id", businessEntityId).query()
                .listOfRows().get(0);
        List<BatchView> batches = jdbc.sql("""
                select id from vat_batch where workspace_id = :ws and business_entity_id = :be and period_from >= :f and period_to <= :t and channel_account_id in (:cas)
                 order by period_from, created_at
                """).param("ws", guard.actor().workspaceId()).param("be", businessEntityId).param("f", from).param("t", to)
                .param("cas", cas.isEmpty() ? Set.of(UUID.randomUUID()) : cas).query(UUID.class).list().stream().map(id -> view(id, false)).toList();
        Map<String, ClassTotal> sum = new java.util.TreeMap<>();
        for (BatchView bv : batches) {
            for (ClassTotal t : bv.totals()) {
                sum.merge(t.classification(), t, (x, y) -> new ClassTotal(x.classification(), x.label(), x.rows() + y.rows(), x.supply().add(y.supply()),
                        x.vat().add(y.vat()), x.total().add(y.total())));
            }
        }
        boolean allConfirmed = !batches.isEmpty() && batches.stream().allMatch(x -> "CONFIRMED".equals(x.status()));
        return new EntityReport(businessEntityId, (String) be.get("display_name"), (String) be.get("business_registration_no"), from, to, RULE_VERSION, allConfirmed,
                List.copyOf(sum.values()), batches, "분류 규칙 " + RULE_VERSION + "은 세무 담당 확정 전 임시 규칙입니다. 국세청 신고는 회계·세무 프로그램에서 하세요."
                        + (allConfirmed ? "" : " 확정되지 않은 묶음이 포함되어 있습니다."));
    }

    BatchView view(UUID id, boolean detail) {
        Map<String, Object> b = jdbc.sql("""
                select b.*, be.display_name as be_name, be.business_registration_no as brn, ca.display_name as ca_name, ca.sales_channel_code,
                       (select u.display_name from workspace_member m join user_account u on u.id = m.user_id where m.id = b.confirmed_by) as confirmed_by_name
                  from vat_batch b join business_entity be on be.id = b.business_entity_id join channel_account ca on ca.id = b.channel_account_id where b.id = :id
                """).param("id", id).query().listOfRows().get(0);
        List<RowView> all = rows(id, "true", Map.of());
        Map<String, ClassTotal> totals = new java.util.TreeMap<>();
        int blocking = 0;
        int warning = 0;
        for (RowView r : all) {
            if (r.blocking()) {
                blocking++;
            } else if (!r.issues().isEmpty()) {
                warning++;
            }
            totals.merge(r.effectiveClassification(), new ClassTotal(r.effectiveClassification(), CLASS_KO.get(r.effectiveClassification()), 1, r.supply(), r.vat(),
                    r.total()), (x, y) -> new ClassTotal(x.classification(), x.label(), x.rows() + 1, x.supply().add(y.supply()), x.vat().add(y.vat()),
                    x.total().add(y.total())));
        }
        LocalDate pf = ((java.sql.Date) b.get("period_from")).toLocalDate();
        LocalDate pt = ((java.sql.Date) b.get("period_to")).toLocalDate();
        Recon recon = recon((UUID) b.get("channel_account_id"), pf, pt, all);
        List<FileView> fl = !detail ? List.of() : jdbc.sql("""
                select f.*, (select u.display_name from workspace_member m join user_account u on u.id = m.user_id where m.id = f.imported_by) as by_name
                  from vat_source_file f where f.batch_id = :b order by f.imported_at
                """).param("b", id).query((rs, i) -> new FileView(Db.uuid(rs, "id"), Db.uuid(rs, "file_id"), rs.getString("original_filename"), rs.getString("sha256"),
                rs.getString("sheet_name"), rs.getString("parser_version"), rs.getInt("row_count"), rs.getInt("new_rows"), rs.getInt("duplicate_rows"),
                rs.getString("by_name"), rs.getTimestamp("imported_at").toInstant())).list();
        List<RowView> issueRows = !detail ? List.of() : all.stream().filter(r -> !r.issues().isEmpty() || "REVIEW".equals(r.classification()) || r.overrideReason() != null)
                .limit(500).toList();
        return new BatchView(id, (UUID) b.get("business_entity_id"), (String) b.get("be_name"), (String) b.get("brn"), (UUID) b.get("channel_account_id"),
                (String) b.get("ca_name"), (String) b.get("sales_channel_code"), pf, pt, (String) b.get("status"), (String) b.get("rule_version"), all.size(), blocking,
                warning, List.copyOf(totals.values()), recon, fl, issueRows, (String) b.get("confirmed_by_name"), PostingService.instant(b.get("confirmed_at")),
                PostingService.instant(b.get("created_at")));
    }

    /** 세무용 자료 ↔ 주문 기준 인식 매출(원장, 부가세 포함) ↔ 정산 매출 대사(docs/15 검증). */
    Recon recon(UUID ca, LocalDate pf, LocalDate pt, List<RowView> rows) {
        BigDecimal vs = BigDecimal.ZERO;
        BigDecimal vr = BigDecimal.ZERO;
        for (RowView r : rows) {
            if ("EXCLUDED".equals(r.effectiveClassification())) {
                continue;
            }
            if ("SALE".equals(r.salesType())) {
                vs = vs.add(r.total());
            } else {
                vr = vr.add(r.total());
            }
        }
        Map<String, Object> l = jdbc.sql("""
                select coalesce(sum(supply_amount + vat_amount) filter (where category <> 'SALES_RETURN'), 0) as sales,
                       coalesce(sum(supply_amount + vat_amount) filter (where category = 'SALES_RETURN'), 0) as refunds
                  from financial_entry where channel_account_id = :ca and period_date between :f and :t and not intercompany and entry_class = 'REVENUE'
                   and category in ('SALES', 'SELLER_DISCOUNT', 'SALES_RETURN', 'SHIPPING_FEE', 'EXCHANGE_PRICE_DIFFERENCE', 'CUSTOMER_RETURN_FEE')
                """).param("ca", ca).param("f", pf).param("t", pt).query().listOfRows().get(0);
        Map<String, Object> s = jdbc.sql("""
                select count(distinct r.id) as runs, coalesce(sum(l.amount) filter (where l.line_type in ('SALE', 'SHIPPING_FEE', 'DISCOUNT_COMPENSATION', 'REFUND')), 0) as amt
                  from settlement_run r left join settlement_line l on l.run_id = r.id
                 where r.channel_account_id = :ca and coalesce(r.period_to, r.payout_date, r.created_at::date) between :f and :t
                """).param("ca", ca).param("f", pf).param("t", pt).query().listOfRows().get(0);
        BigDecimal ls = (BigDecimal) l.get("sales");
        BigDecimal lr = (BigDecimal) l.get("refunds");
        BigDecimal vt = vs.add(vr);
        BigDecimal lt = ls.add(lr);
        BigDecimal st = (BigDecimal) s.get("amt");
        boolean imported = ((Number) s.get("runs")).intValue() > 0;
        return new Recon(vs, vr, vt, ls, lr, lt, imported ? st : null, imported, vt.subtract(lt), imported ? vt.subtract(st) : null,
                "원장은 출고·반품 검수일, 세무 자료는 거래(결제)일, 정산은 정산기간 기준이라 시점 차이가 날 수 있습니다. 채널 부담 할인 보전은 세무 자료에 따라 포함 여부가 다릅니다."
                        + (imported ? "" : " 이 기간 정산을 가져오지 않아 정산 대사는 미확인입니다."));
    }

    List<RowView> rows(UUID batchId, String where, Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>(extra);
        p.put("b", batchId);
        return jdbc.sql("""
                select r.*, f.original_filename, o.classification as ov_class, o.reason as ov_reason
                  from vat_row r join vat_source_file f on f.id = r.source_file_id
                  left join lateral (select classification, reason from vat_row_override x where x.row_id = r.id order by x.created_at desc limit 1) o on true
                 where r.batch_id = :b and\s""" + where + " order by f.imported_at, r.row_no").params(p).query((rs, i) -> {
            JsonNode iss = json.read(rs.getString("issues"));
            List<String> issues = new ArrayList<>();
            iss.forEach(n -> issues.add(n.asString()));
            String ov = rs.getString("ov_class");
            String eff = ov == null ? rs.getString("classification") : ov;
            boolean blocking = ov == null && issues.stream().anyMatch(BLOCKING::contains);
            return new RowView(Db.uuid(rs, "id"), rs.getInt("row_no"), rs.getString("original_filename"), rs.getObject("txn_date", LocalDate.class), rs.getString("source_key"),
                    rs.getString("sales_type"), rs.getString("tax_type"), rs.getString("payment_method"), rs.getString("evidence_type"), rs.getBigDecimal("supply_amount"),
                    rs.getBigDecimal("vat_amount"), rs.getBigDecimal("total_amount"), rs.getString("classification"), eff, issues, blocking, rs.getString("ov_reason"));
        }).list();
    }

    // ---------------------------------------------------------------- 내부

    private Map<String, Object> lockBatch(UUID ws, UUID id) {
        return jdbc.sql("""
                select b.*, be.business_registration_no as brn from vat_batch b join business_entity be on be.id = b.business_entity_id
                 where b.workspace_id = :ws and b.id = :id for update of b
                """).param("ws", ws).param("id", id).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("VAT_BATCH"));
    }

    private static void requireDraft(Map<String, Object> b) {
        if (!"DRAFT".equals(b.get("status"))) {
            throw Errors.conflict("VAT_BATCH_CONFIRMED", "확정된 신고자료 묶음은 바꿀 수 없습니다.");
        }
    }

    static final Map<String, String> SALES_TYPES = Map.of("판매", "SALE", "매출", "SALE", "SALE", "SALE", "환불", "REFUND", "취소", "REFUND", "반품", "REFUND",
            "REFUND", "REFUND", "조정", "ADJUSTMENT", "ADJUSTMENT", "ADJUSTMENT");
    static final Map<String, String> TAX_TYPES = Map.of("과세", "TAXABLE", "TAXABLE", "TAXABLE", "면세", "TAX_FREE", "TAX_FREE", "TAX_FREE", "영세", "ZERO_RATED",
            "영세율", "ZERO_RATED", "ZERO_RATED", "ZERO_RATED", "기타", "OTHER", "OTHER", "OTHER");
    static final Map<String, String> PAYMENTS = Map.ofEntries(Map.entry("카드", "CARD"), Map.entry("신용카드", "CARD"), Map.entry("체크카드", "CARD"),
            Map.entry("CARD", "CARD"), Map.entry("현금영수증", "CASH_RECEIPT"), Map.entry("CASH_RECEIPT", "CASH_RECEIPT"), Map.entry("휴대폰", "MOBILE"),
            Map.entry("휴대폰결제", "MOBILE"), Map.entry("MOBILE", "MOBILE"), Map.entry("기타", "OTHER"), Map.entry("계좌이체", "OTHER"), Map.entry("무통장", "OTHER"),
            Map.entry("포인트", "OTHER"), Map.entry("OTHER", "OTHER"));
    static final Map<String, String> EVIDENCES = Map.of("세금계산서", "TAX_INVOICE", "전자세금계산서", "E_TAX_INVOICE", "계산서", "INVOICE", "전자계산서", "E_INVOICE",
            "없음", "NONE", "TAX_INVOICE", "TAX_INVOICE", "E_TAX_INVOICE", "E_TAX_INVOICE", "INVOICE", "INVOICE", "E_INVOICE", "E_INVOICE", "NONE", "NONE");

    private static Map<String, String> columns(List<String> headers) {
        Map<String, String> col = new LinkedHashMap<>();
        for (String h : headers) {
            switch (h.replaceAll("\\s", "")) {
                case "거래일", "거래일자", "결제일" -> col.putIfAbsent("date", h);
                case "주문번호", "원천거래키", "거래번호" -> col.putIfAbsent("key", h);
                case "매출유형", "구분" -> col.putIfAbsent("sales_type", h);
                case "세금유형", "과세유형" -> col.putIfAbsent("tax_type", h);
                case "결제수단" -> col.put("payment", h);
                case "증빙유형", "증빙" -> col.putIfAbsent("evidence", h);
                case "공급가", "공급가액" -> col.putIfAbsent("supply", h);
                case "세액", "부가세" -> col.putIfAbsent("vat", h);
                case "합계", "총액", "합계금액" -> col.putIfAbsent("total", h);
                case "사업자등록번호" -> col.put("brn", h);
                default -> {
                }
            }
        }
        return col;
    }

    private static String code(String raw, Map<String, String> map, String field) {
        String k = raw == null ? null : raw.replaceAll("\\s", "");
        String c = k == null ? null : map.getOrDefault(k, map.get(k.toUpperCase(java.util.Locale.ROOT)));
        if (c == null) {
            throw Errors.validation(field, field + " 값을 알 수 없습니다: " + raw);
        }
        return c;
    }

    private static BigDecimal won(String s) {
        return new BigDecimal(s.replace(",", "")).setScale(0, RoundingMode.UNNECESSARY);
    }

    private static String val(Map<String, String> v, String col) {
        if (col == null) {
            return null;
        }
        String s = v.get(col);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
