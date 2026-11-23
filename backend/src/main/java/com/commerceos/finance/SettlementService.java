package com.commerceos.finance;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.operations.WorkItemService;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매채널 정산 원본·대사(M5 N3, docs/05 §11, DLG-161~165).
 * <ul>
 *   <li>정산 = 판매계정 + 정산번호. 원본 행은 해시로 한 번만(같은 파일·행 재업로드는 중복 없음, T29). 원본 파일·행·파서 버전 보존.</li>
 *   <li>수수료·패널티·조정 행은 실제 비용으로 기록(같은 범위 예상 대체). 주문 단위로만 온 수수료는 주문상품 순매출 비중으로 배부.</li>
 *   <li>판매·배송비·할인보전·환불 행은 대사용(손익은 출고·반품으로 이미 반영). 플랫폼 물류 주문만 정산 판매 행으로 매출을 기록.</li>
 *   <li>원주문을 못 찾은 행·주문 없는 수수료는 검토 업무. 정산이 공헌이익에 들어가는 것이 아니라 비용 확정 증빙·현금 대사 근거다.</li>
 * </ul>
 * 정산 금액은 판매자 기준 부호(+ 받을 돈, − 공제), 부가세 포함.
 */
@Service
public class SettlementService {
    static final String PARSER_VERSION = "settlement-file-v1";
    static final Map<String, String> TYPE_ALIASES = Map.ofEntries(
            Map.entry("판매", "SALE"), Map.entry("상품판매", "SALE"), Map.entry("판매대금", "SALE"), Map.entry("배송비", "SHIPPING_FEE"), Map.entry("판매수수료", "COMMISSION"),
            Map.entry("수수료", "COMMISSION"), Map.entry("결제수수료", "PAYMENT_FEE"), Map.entry("할인보전", "DISCOUNT_COMPENSATION"), Map.entry("할인지원", "DISCOUNT_COMPENSATION"),
            Map.entry("환불", "REFUND"), Map.entry("취소환불", "REFUND"), Map.entry("패널티", "PENALTY"), Map.entry("페널티", "PENALTY"), Map.entry("조정", "ADJUSTMENT"),
            Map.entry("기타", "OTHER"));
    static final Set<String> TYPES = Set.of("SALE", "SHIPPING_FEE", "COMMISSION", "PAYMENT_FEE", "DISCOUNT_COMPENSATION", "REFUND", "PENALTY", "ADJUSTMENT", "OTHER");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final Ledger ledger;
    private final UnitCosts costs;
    private final FinanceService finance;
    private final FileService files;
    private final WorkItemService workItems;
    private final AuditService audit;
    private final Json json;
    private final TransactionTemplate tx;

    public SettlementService(JdbcClient jdbc, AccessGuard guard, Ledger ledger, UnitCosts costs, FinanceService finance, FileService files, WorkItemService workItems,
                             AuditService audit, Json json, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.ledger = ledger;
        this.costs = costs;
        this.finance = finance;
        this.files = files;
        this.workItems = workItems;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
    }

    public record LineInput(Integer lineNo, String lineType, BigDecimal amount, Integer quantity, String externalOrderKey, String externalItemKey, Map<String, Object> raw) {
    }

    public record NewSettlement(UUID channelAccountId, String settlementKey, LocalDate periodFrom, LocalDate periodTo, LocalDate payoutDate, BigDecimal declaredPayout,
                                List<LineInput> lines) {
    }

    public record LineView(UUID id, int lineNo, String lineType, BigDecimal amount, Integer quantity, String externalOrderKey, String externalItemKey, UUID orderId,
                           String orderNo, UUID orderItemId, String matchLevel, String applied) {
    }

    public record OrderDiff(UUID orderId, String orderNo, BigDecimal settlement, BigDecimal ledger, BigDecimal difference) {
    }

    public record RunView(UUID id, String settlementKey, UUID businessEntityId, String businessEntityName, UUID channelAccountId, String channelAccountName,
                          LocalDate periodFrom, LocalDate periodTo, LocalDate payoutDate, BigDecimal declaredPayout, BigDecimal payout, BigDecimal deposited,
                          BigDecimal remaining, String status, String source, Map<String, BigDecimal> totals, int reviewLines, List<OrderDiff> differences,
                          List<LineView> lines, java.time.Instant createdAt) {
    }

    public record ImportResult(int runs, int newLines, int duplicateLines, List<UUID> runIds) {
    }

    // ---------------------------------------------------------------- 가져오기

    public ImportResult importFile(UUID channelAccountId, String filename, String contentType, byte[] content) {
        guard.requirePermission(Perm.SETTLEMENT_MANAGE);
        CurrentActor a = guard.actor();
        UUID be = accountBe(a.workspaceId(), channelAccountId);
        if (content == null || content.length == 0) {
            throw Errors.validation("file", "빈 파일입니다.");
        }
        TabularReader.Table table = TabularReader.read(content, filename, null);
        Map<String, String> col = new LinkedHashMap<>();
        for (String h : table.headers()) {
            String k = h.replaceAll("\\s", "");
            switch (k) {
                case "정산번호" -> col.put("key", h);
                case "정산기간시작" -> col.put("from", h);
                case "정산기간종료" -> col.put("to", h);
                case "지급예정일", "지급일" -> col.putIfAbsent("payout", h);
                case "지급액", "정산금액" -> col.putIfAbsent("declared", h);
                case "주문번호", "외부주문번호" -> col.putIfAbsent("order", h);
                case "상품주문번호" -> col.put("item", h);
                case "항목", "구분" -> col.putIfAbsent("type", h);
                case "금액" -> col.put("amount", h);
                case "수량" -> col.put("qty", h);
                default -> {
                }
            }
        }
        for (String req : List.of("key", "type", "amount")) {
            if (!col.containsKey(req)) {
                throw Errors.unprocessable("TEMPLATE_COLUMN_MISSING", "필수 열이 없습니다: 정산번호 · 항목 · 금액",
                        Map.of("required", List.of("정산번호", "항목", "금액"), "optional", List.of("정산기간시작", "정산기간종료", "지급예정일", "지급액", "주문번호", "상품주문번호", "수량")));
            }
        }
        Map<String, NewSettlement> runs = new LinkedHashMap<>();
        Map<String, List<LineInput>> lines = new LinkedHashMap<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (TabularReader.DataRow r : table.rows()) {
            Map<String, String> v = r.values();
            String key = val(v, col.get("key"));
            String type = resolveType(val(v, col.get("type")));
            BigDecimal amount;
            try {
                amount = val(v, col.get("amount")) == null ? null : new BigDecimal(val(v, col.get("amount")).replace(",", "")).setScale(0, RoundingMode.UNNECESSARY);
            } catch (NumberFormatException | ArithmeticException e) {
                amount = null;
            }
            if (key == null || type == null || amount == null) {
                errors.add(Map.of("row", r.rowNumber(), "message", "정산번호·항목·금액(원 단위 정수)을 확인하세요."));
                continue;
            }
            try {
                NewSettlement head = runs.get(key);
                if (head == null) {
                    head = new NewSettlement(channelAccountId, key, date(val(v, col.get("from"))), date(val(v, col.get("to"))), date(val(v, col.get("payout"))),
                            val(v, col.get("declared")) == null ? null : new BigDecimal(val(v, col.get("declared")).replace(",", "")), List.of());
                    runs.put(key, head);
                }
            } catch (DateTimeParseException | NumberFormatException e) {
                errors.add(Map.of("row", r.rowNumber(), "message", "날짜(YYYY-MM-DD)·지급액을 확인하세요."));
                continue;
            }
            Integer qty = null;
            if (val(v, col.get("qty")) != null) {
                try {
                    qty = Integer.valueOf(val(v, col.get("qty")));
                } catch (NumberFormatException e) {
                    errors.add(Map.of("row", r.rowNumber(), "message", "수량은 정수입니다."));
                    continue;
                }
            }
            Map<String, Object> raw = new LinkedHashMap<>(v);
            raw.put("_row", r.rowNumber());
            lines.computeIfAbsent(key, k -> new ArrayList<>()).add(new LineInput(r.rowNumber(), type, amount, qty, val(v, col.get("order")), val(v, col.get("item")), raw));
        }
        if (!errors.isEmpty()) {
            throw Errors.unprocessable("SETTLEMENT_FILE_INVALID", "파일에 잘못된 행이 있어 반영하지 않았습니다. 고친 뒤 다시 올리세요.",
                    Map.of("errors", errors.size() > 50 ? errors.subList(0, 50) : errors, "error_count", errors.size()));
        }
        FileService.FileView stored = tx.execute(s -> files.store(new FileService.NewFile(a.workspaceId(), be, "SETTLEMENT_SOURCE", filename, contentType, content, false,
                a.memberId(), null)));
        int newLines = 0;
        int dup = 0;
        List<UUID> ids = new ArrayList<>();
        for (var e : runs.entrySet()) {
            NewSettlement h = e.getValue();
            NewSettlement full = new NewSettlement(h.channelAccountId(), h.settlementKey(), h.periodFrom(), h.periodTo(), h.payoutDate(), h.declaredPayout(),
                    lines.getOrDefault(e.getKey(), List.of()));
            int[] counts = new int[2];
            UUID id = tx.execute(s -> ingest(full, "FILE", stored.id(), counts));
            ids.add(id);
            newLines += counts[0];
            dup += counts[1];
        }
        return new ImportResult(runs.size(), newLines, dup, ids);
    }

    @Transactional
    public RunView create(NewSettlement in) {
        guard.requirePermission(Perm.SETTLEMENT_MANAGE);
        int[] counts = new int[2];
        UUID id = ingest(in, "MANUAL", null, counts);
        return get(id);
    }

    /** 정산 반영(새 행만). counts = [새 행, 중복 행]. */
    private UUID ingest(NewSettlement in, String source, UUID fileId, int[] counts) {
        CurrentActor a = guard.actor();
        UUID ws = a.workspaceId();
        if (in == null || in.channelAccountId() == null || in.settlementKey() == null || in.settlementKey().isBlank()) {
            throw Errors.validation("settlement_key", "판매계정과 정산번호를 입력하세요.");
        }
        UUID be = accountBe(ws, in.channelAccountId());
        String key = in.settlementKey().trim();
        // 같은 정산번호 동시 업로드: 생성은 한 번(on conflict), 이후 행 반영은 정산 행 잠금으로 직렬화
        jdbc.sql("""
                insert into settlement_run (id, workspace_id, business_entity_id, channel_account_id, settlement_key, period_from, period_to, payout_date, declared_payout,
                                            source, file_id, status, created_by)
                values (:id, :ws, :be, :ca, :k, :pf, :pt, :pd, cast(:dp as numeric), :src, cast(:f as uuid), 'IMPORTED', :by)
                on conflict (channel_account_id, settlement_key) do nothing
                """).param("id", Ids.newId()).param("ws", ws).param("be", be).param("ca", in.channelAccountId()).param("k", key).param("pf", in.periodFrom())
                .param("pt", in.periodTo()).param("pd", in.payoutDate()).param("dp", in.declaredPayout()).param("src", source).param("f", fileId).param("by", a.memberId())
                .update();
        UUID runId = jdbc.sql("select id from settlement_run where channel_account_id = :ca and settlement_key = :k for update").param("ca", in.channelAccountId())
                .param("k", key).query(UUID.class).single();
        int next = jdbc.sql("select coalesce(max(line_no), 0) from settlement_line where run_id = :r").param("r", runId).query(Integer.class).single();
        String runKey = key;
        Map<String, Integer> occurrence = new java.util.HashMap<>();
        for (LineInput l : in.lines() == null ? List.<LineInput>of() : in.lines()) {
            if (l == null || l.lineType() == null || !TYPES.contains(l.lineType()) || l.amount() == null) {
                throw Errors.validation("lines", "정산 행의 항목·금액을 확인하세요.");
            }
            // 행 식별: 내용 + 같은 내용의 순번(행 번호가 바뀐 재발행 파일도 중복 없이, 내용이 같은 별개 행은 각각)
            String content = in.channelAccountId() + "|" + runKey + "|" + l.lineType() + "|" + l.amount().toPlainString() + "|" + n(l.externalOrderKey()) + "|"
                    + n(l.externalItemKey()) + "|" + (l.quantity() == null ? "" : l.quantity());
            String hash = sha256(content + "|#" + occurrence.merge(content, 1, Integer::sum));
            if (jdbc.sql("select count(*) from settlement_line where workspace_id = :ws and row_hash = :h").param("ws", ws).param("h", hash).query(Integer.class).single() > 0) {
                counts[1]++;
                continue;
            }
            Match mt = matchOrder(ws, in.channelAccountId(), l.externalOrderKey(), l.externalItemKey());
            UUID orderId = mt.orderId();
            UUID itemId = mt.itemId();
            String level = mt.level();
            UUID lineId = Ids.newId();
            int lineNo = ++next;
            String applied = apply(ws, be, in.channelAccountId(), lineId, runKey, l, orderId, itemId, level);
            jdbc.sql("""
                    insert into settlement_line (id, workspace_id, run_id, line_no, line_type, amount, quantity, external_order_key, external_item_key, order_id, order_item_id,
                                                 match_level, applied, row_hash, raw)
                    values (:id, :ws, :r, :ln, :t, :a, cast(:q as integer), cast(:ok as text), cast(:ik as text), cast(:o as uuid), cast(:i as uuid), :ml, :ap, :h, cast(:raw as jsonb))
                    """).param("id", lineId).param("ws", ws).param("r", runId).param("ln", lineNo).param("t", l.lineType()).param("a", l.amount()).param("q", l.quantity())
                    .param("ok", l.externalOrderKey()).param("ik", l.externalItemKey()).param("o", orderId).param("i", itemId).param("ml", level).param("ap", applied)
                    .param("h", hash).param("raw", json.write(l.raw() == null ? Map.of("parser", PARSER_VERSION) : withParser(l.raw()))).update();
            counts[0]++;
        }
        refreshStatus(ws, runId);
        audit.record(AuditEntry.builder(ws, "SETTLEMENT_IMPORTED", "SETTLEMENT_RUN", runId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, in.channelAccountId()).after(Map.of("settlement_key", key, "new_lines", counts[0], "duplicate_lines", counts[1], "source", source)).build());
        return runId;
    }

    record Match(UUID orderId, UUID itemId, String level) {
    }

    /** 주문 식별은 판매계정 + 판매단위 + 외부주문키. 정산 원본에 판매단위가 없으므로 같은 키가 여러 판매단위에 있으면 추정하지 않고 미매칭(검토)으로 둔다. */
    private Match matchOrder(UUID ws, UUID ca, String externalOrderKey, String externalItemKey) {
        if (externalOrderKey == null) {
            return new Match(null, null, "PERIOD");
        }
        List<UUID> found = jdbc.sql("select id from sales_order where workspace_id = :ws and channel_account_id = :ca and external_order_key = :k").param("ws", ws)
                .param("ca", ca).param("k", externalOrderKey.trim()).query(UUID.class).list();
        if (found.size() != 1) {
            return new Match(null, null, "UNMATCHED");
        }
        UUID itemId = externalItemKey == null ? null : jdbc.sql("select id from order_item where order_id = :o and external_item_key = :k").param("o", found.get(0))
                .param("k", externalItemKey.trim()).query(UUID.class).optional().orElse(null);
        return new Match(found.get(0), itemId, itemId == null ? "ORDER" : "ITEM");
    }

    /** 미매칭 행을 다시 연결(주문 수집 후). 원본 행은 그대로이고 연결 정보만 채운다. */
    @Transactional
    public RunView rematch(UUID runId) {
        guard.requirePermission(Perm.SETTLEMENT_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> r = jdbc.sql("select * from settlement_run where workspace_id = :ws and id = :id for update").param("ws", a.workspaceId()).param("id", runId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SETTLEMENT_RUN"));
        UUID be = (UUID) r.get("business_entity_id");
        UUID ca = (UUID) r.get("channel_account_id");
        guard.checkWrite("SETTLEMENT_RUN", runId, be, ca);
        String key = (String) r.get("settlement_key");
        int linked = 0;
        for (Map<String, Object> l : jdbc.sql("select * from settlement_line where run_id = :r and match_level = 'UNMATCHED' order by line_no for update").param("r", runId)
                .query().listOfRows()) {
            Match mt = matchOrder(a.workspaceId(), ca, (String) l.get("external_order_key"), (String) l.get("external_item_key"));
            if ("UNMATCHED".equals(mt.level())) {
                continue;
            }
            UUID lineId = (UUID) l.get("id");
            LineInput in = new LineInput((Integer) l.get("line_no"), (String) l.get("line_type"), (BigDecimal) l.get("amount"), (Integer) l.get("quantity"),
                    (String) l.get("external_order_key"), (String) l.get("external_item_key"), null);
            String applied = apply(a.workspaceId(), be, ca, lineId, key, in, mt.orderId(), mt.itemId(), mt.level());
            jdbc.sql("update settlement_line set order_id = :o, order_item_id = cast(:i as uuid), match_level = :ml, applied = :ap where id = :id").param("o", mt.orderId())
                    .param("i", mt.itemId()).param("ml", mt.level()).param("ap", applied).param("id", lineId).update();
            linked++;
        }
        if (jdbc.sql("select count(*) from settlement_line where run_id = :r and match_level = 'UNMATCHED'").param("r", runId).query(Integer.class).single() == 0) {
            workItems.resolveByKey(a.workspaceId(), "settlement:" + ca + ":" + key + ":UNMATCHED");
        }
        refreshStatus(a.workspaceId(), runId);
        audit.record(AuditEntry.builder(a.workspaceId(), "SETTLEMENT_REMATCHED", "SETTLEMENT_RUN", runId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, ca).after(Map.of("linked", linked)).build());
        return get(runId);
    }

    /** 원장 반영. @return LEDGER · RECON_ONLY · REVIEW */
    private String apply(UUID ws, UUID be, UUID ca, UUID lineId, String runKey, LineInput l, UUID orderId, UUID itemId, String level) {
        if ("UNMATCHED".equals(level)) {
            workItems.raise(new WorkItemService.Raise(ws, be, ca, null, "SETTLEMENT_UNMATCHED", "MEDIUM", "정산 원주문 없음: " + runKey,
                    "정산 행의 주문(" + l.externalOrderKey() + ")을 찾지 못했습니다.", "주문 수집을 확인하고, 수집 후 정산 대사를 다시 확인하세요.",
                    "settlement:" + ca + ":" + runKey + ":UNMATCHED", Map.of("settlement_key", runKey)));
            return "REVIEW";
        }
        Map<String, Object> o = orderId == null ? null : jdbc.sql("select business_entity_id, source_type, order_no from sales_order where id = :o").param("o", orderId)
                .query().listOfRows().get(0);
        UUID ownerBe = o == null ? be : (UUID) o.get("business_entity_id");
        boolean platform = o != null && "PLATFORM_FULFILLMENT".equals(o.get("source_type"));
        String ref = runKey + "#" + (l.lineNo() == null ? "" : l.lineNo());
        LocalDate period = Ledger.period(null);
        BigDecimal cost = l.amount().negate(); // 공제(−)가 비용(+)
        switch (l.lineType()) {
            case "COMMISSION", "PAYMENT_FEE" -> {
                String cat = "COMMISSION".equals(l.lineType()) ? "SELLING_FEE" : "PAYMENT_FEE";
                if (orderId == null) {
                    workItems.raise(new WorkItemService.Raise(ws, be, ca, null, "SETTLEMENT_LINE_REVIEW", "MEDIUM", "주문 없는 수수료 행: " + runKey,
                            "주문 범위 없이 들어온 수수료는 예상 비용과 겹칠 수 있어 자동 반영하지 않았습니다.", "정산 원본을 확인해 비용·원가 화면에서 실제 비용으로 기록하세요.",
                            "settlement:" + ca + ":" + runKey + ":PERIOD_FEE", Map.of("settlement_key", runKey)));
                    return "REVIEW";
                }
                Map<UUID, BigDecimal> split = itemId != null ? Map.of(itemId, cost) : allocateByNetSales(orderId, cost);
                if (split.isEmpty()) {
                    workItems.raise(new WorkItemService.Raise(ws, be, ca, null, "SETTLEMENT_LINE_REVIEW", "MEDIUM", "배부할 주문상품 없는 수수료 행: " + runKey,
                            "주문(" + l.externalOrderKey() + ")에 금액이 있는 주문상품이 없어 수수료를 배부하지 못했습니다.", "주문 내용을 확인해 비용·원가 화면에서 실제 비용으로 기록하세요.",
                            "settlement:" + ca + ":" + runKey + ":NO_BASE", Map.of("settlement_key", runKey)));
                    return "REVIEW";
                }
                int i = 0;
                for (var e : split.entrySet()) {
                    BigDecimal[] sv = Ledger.split(e.getValue(), "TAXABLE");
                    finance.applyActual(new FinanceService.ActualCost(ws, ownerBe, ca, orderId, e.getKey(), null, null, null, null, cat, "ACTUAL", sv[0], sv[1], period,
                            "SETTLEMENT", ref, "SETL:" + lineId + ":" + i++, cat + ":ITEM:" + e.getKey(), itemId == null ? "주문 단위 수수료 순매출 비중 배부" : null, null));
                }
                return "LEDGER";
            }
            case "PENALTY", "ADJUSTMENT", "OTHER" -> {
                if (cost.signum() == 0) {
                    return "RECON_ONLY";
                }
                BigDecimal[] sv = Ledger.split(cost, "TAXABLE");
                finance.applyActual(new FinanceService.ActualCost(ws, ownerBe, ca, orderId, itemId, null, null, null, null, "PENALTY".equals(l.lineType()) ? "CHANNEL_PENALTY"
                        : "OTHER", "ACTUAL", sv[0], sv[1], period, "SETTLEMENT", ref, "SETL:" + lineId, null, "정산 " + l.lineType(), null));
                return "LEDGER";
            }
            default -> {
                // 판매·배송비·할인보전·환불: 일반 주문은 대사만. 플랫폼 물류 주문은 정산이 매출 근거(DLG-155)
                if (!platform || "DISCOUNT_COMPENSATION".equals(l.lineType())) {
                    return "RECON_ONLY";
                }
                String cat = switch (l.lineType()) {
                    case "SALE" -> "SALES";
                    case "SHIPPING_FEE" -> "SHIPPING_FEE";
                    default -> "SALES_RETURN";
                };
                BigDecimal[] sv = Ledger.split(l.amount(), "TAXABLE");
                UUID sku = itemId == null ? null : jdbc.sql("select sku_id from order_item where id = :i").param("i", itemId).query(UUID.class).optional().orElse(null);
                ledger.post(new Ledger.Entry(ws, ownerBe, ca, orderId, itemId, null, null, null, sku, null, "REVENUE", cat, "ACTUAL", sv[0], sv[1], "TAXABLE", l.quantity(),
                        null, period, "SETTLEMENT", ref, "SETL:" + lineId, null, null, "플랫폼 물류 주문 정산 매출", null));
                if ("SALE".equals(l.lineType()) && sku != null && l.quantity() != null && l.quantity() > 0) {
                    Optional<BigDecimal> unit = costs.current(ownerBe, sku);
                    ledger.post(new Ledger.Entry(ws, ownerBe, ca, orderId, itemId, null, null, null, sku, null, "COST", "PRODUCT_COGS", unit.isPresent() ? "ACTUAL" : "ESTIMATED",
                            unit.map(u -> Ledger.won(u.multiply(BigDecimal.valueOf(l.quantity())))).orElse(BigDecimal.ZERO), BigDecimal.ZERO, "OUT_OF_SCOPE", l.quantity(),
                            unit.orElse(null), period, "SETTLEMENT", ref, "SETL:" + lineId + ":COGS", null, null, "플랫폼 물류 주문 원가(정산 수량)", null));
                }
                return "LEDGER";
            }
        }
    }

    /** 주문 단위 금액을 주문상품 순매출(공급가) 비중으로 배부. 잔액은 가장 큰 행에(DLG-030). */
    private Map<UUID, BigDecimal> allocateByNetSales(UUID orderId, BigDecimal amount) {
        List<Map<String, Object>> bases = jdbc.sql("""
                select order_item_id, sum(supply_amount) as base from financial_entry where order_id = :o and category in ('SALES', 'SELLER_DISCOUNT') and order_item_id is not null
                 group by order_item_id having sum(supply_amount) > 0 order by order_item_id
                """).param("o", orderId).query().listOfRows();
        if (bases.isEmpty()) {
            bases = jdbc.sql("select id as order_item_id, line_amount as base from order_item where order_id = :o and item_kind = 'ORIGINAL' and line_amount > 0 order by id")
                    .param("o", orderId).query().listOfRows();
        }
        Map<UUID, BigDecimal> out = new LinkedHashMap<>();
        if (bases.isEmpty()) {
            return out;
        }
        BigDecimal total = bases.stream().map(b -> (BigDecimal) b.get("base")).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal used = BigDecimal.ZERO;
        UUID largest = null;
        BigDecimal largestBase = null;
        for (Map<String, Object> b : bases) {
            BigDecimal share = amount.multiply((BigDecimal) b.get("base")).divide(total, 0, RoundingMode.HALF_UP);
            out.put((UUID) b.get("order_item_id"), share);
            used = used.add(share);
            if (largestBase == null || ((BigDecimal) b.get("base")).compareTo(largestBase) > 0) {
                largestBase = (BigDecimal) b.get("base");
                largest = (UUID) b.get("order_item_id");
            }
        }
        out.merge(largest, amount.subtract(used), BigDecimal::add);
        return out;
    }

    /** 상태는 조회 시점 기준(나중에 출고·반품이 반영되면 대사 차이가 사라질 수 있음). 저장 값은 마지막 반영 시점 기록. */
    static String liveStatus(int review, List<OrderDiff> diffs, BigDecimal declared, BigDecimal payout) {
        boolean payoutMismatch = declared != null && declared.compareTo(payout) != 0;
        return review == 0 && diffs.isEmpty() && !payoutMismatch ? "RECONCILED" : "ISSUES";
    }

    private void refreshStatus(UUID ws, UUID runId) {
        String st = view(runId, false).status();
        jdbc.sql("update settlement_run set status = :s, version = version + 1, updated_at = now() where id = :id").param("s", st).param("id", runId).update();
    }

    // ---------------------------------------------------------------- 조회

    public List<RunView> list(List<String> businessEntityIds, UUID channelAccountId) {
        guard.requirePermission(Perm.SETTLEMENT_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        if (bes.isEmpty() || cas.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("select id from settlement_run where workspace_id = :ws and business_entity_id in (:bes) and channel_account_id in (:cas)");
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "bes", bes, "cas", cas));
        if (channelAccountId != null) {
            sql.append(" and channel_account_id = :ca");
            p.put("ca", channelAccountId);
        }
        sql.append(" order by coalesce(payout_date, created_at::date) desc, created_at desc limit 200");
        return jdbc.sql(sql.toString()).params(p).query(UUID.class).list().stream().map(id -> view(id, false)).toList();
    }

    public RunView get(UUID id) {
        guard.requirePermission(Perm.SETTLEMENT_READ);
        Map<String, Object> r = jdbc.sql("select business_entity_id, channel_account_id from settlement_run where workspace_id = :ws and id = :id")
                .param("ws", guard.actor().workspaceId()).param("id", id).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SETTLEMENT_RUN"));
        guard.checkRead("SETTLEMENT_RUN", id, (UUID) r.get("business_entity_id"), (UUID) r.get("channel_account_id"));
        return view(id, true);
    }

    RunView view(UUID id, boolean withLines) {
        Map<String, Object> r = jdbc.sql("""
                select r.*, be.display_name as be_name, ca.display_name as ca_name from settlement_run r join business_entity be on be.id = r.business_entity_id
                  join channel_account ca on ca.id = r.channel_account_id where r.id = :id
                """).param("id", id).query().listOfRows().get(0);
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        BigDecimal payout = BigDecimal.ZERO;
        for (Map<String, Object> t : jdbc.sql("select line_type, sum(amount) as a from settlement_line where run_id = :r group by line_type order by line_type").param("r", id)
                .query().listOfRows()) {
            totals.put((String) t.get("line_type"), (BigDecimal) t.get("a"));
            payout = payout.add((BigDecimal) t.get("a"));
        }
        BigDecimal deposited = jdbc.sql("select coalesce(sum(amount), 0) from deposit_match where run_id = :r and cancelled_at is null").param("r", id)
                .query(BigDecimal.class).single();
        int review = jdbc.sql("select count(*) from settlement_line where run_id = :r and applied = 'REVIEW'").param("r", id).query(Integer.class).single();
        // 주문별: 정산(판매+배송비+할인보전+환불) vs 원장 인식 매출(상품+판매자 할인+반품+배송비, 부가세 포함). 플랫폼 물류 주문은 원장이 정산에서 와 비교 제외
        List<OrderDiff> diffs = jdbc.sql("""
                select l.order_id, so.order_no, sum(l.amount) as s,
                       coalesce((select sum(e.supply_amount + e.vat_amount) from financial_entry e where e.order_id = l.order_id
                                  and e.category in ('SALES', 'SELLER_DISCOUNT', 'SALES_RETURN', 'SHIPPING_FEE')), 0) as g
                  from settlement_line l join sales_order so on so.id = l.order_id
                 where l.run_id = :r and l.line_type in ('SALE', 'SHIPPING_FEE', 'DISCOUNT_COMPENSATION', 'REFUND') and so.source_type <> 'PLATFORM_FULFILLMENT'
                 group by l.order_id, so.order_no
                """).param("r", id).query((rs, i) -> new OrderDiff(Db.uuid(rs, "order_id"), rs.getString("order_no"), rs.getBigDecimal("s"), rs.getBigDecimal("g"),
                rs.getBigDecimal("s").subtract(rs.getBigDecimal("g")))).list().stream().filter(d -> d.difference().signum() != 0).toList();
        List<LineView> lines = !withLines ? List.of() : jdbc.sql("""
                select l.*, so.order_no from settlement_line l left join sales_order so on so.id = l.order_id where l.run_id = :r order by l.line_no
                """).param("r", id).query((rs, i) -> new LineView(Db.uuid(rs, "id"), rs.getInt("line_no"), rs.getString("line_type"), rs.getBigDecimal("amount"),
                (Integer) rs.getObject("quantity"), rs.getString("external_order_key"), rs.getString("external_item_key"), Db.uuid(rs, "order_id"), rs.getString("order_no"),
                Db.uuid(rs, "order_item_id"), rs.getString("match_level"), rs.getString("applied"))).list();
        return new RunView(id, (String) r.get("settlement_key"), (UUID) r.get("business_entity_id"), (String) r.get("be_name"), (UUID) r.get("channel_account_id"),
                (String) r.get("ca_name"), date(r.get("period_from")), date(r.get("period_to")), date(r.get("payout_date")), (BigDecimal) r.get("declared_payout"), payout,
                deposited, payout.subtract(deposited), liveStatus(review, diffs, (BigDecimal) r.get("declared_payout"), payout), (String) r.get("source"), totals, review,
                diffs, lines,
                PostingService.instant(r.get("created_at")));
    }

    // ---------------------------------------------------------------- 내부

    private UUID accountBe(UUID ws, UUID channelAccountId) {
        if (channelAccountId == null) {
            throw Errors.validation("channel_account_id", "판매계정을 선택하세요.");
        }
        UUID be = jdbc.sql("select business_entity_id from channel_account where workspace_id = :ws and id = :id").param("ws", ws).param("id", channelAccountId)
                .query(UUID.class).optional().orElseThrow(() -> Errors.notFound("CHANNEL_ACCOUNT"));
        guard.checkWrite("CHANNEL_ACCOUNT", channelAccountId, be, channelAccountId);
        return be;
    }

    static String resolveType(String raw) {
        if (raw == null) {
            return null;
        }
        String k = raw.replaceAll("\\s", "");
        String upper = k.toUpperCase(Locale.ROOT);
        if (TYPES.contains(upper)) {
            return upper;
        }
        return TYPE_ALIASES.get(k);
    }

    private static Map<String, Object> withParser(Map<String, Object> raw) {
        Map<String, Object> m = new LinkedHashMap<>(raw);
        m.put("_parser", PARSER_VERSION);
        return m;
    }

    private static LocalDate date(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : LocalDate.parse(s.replace('/', '-').replace('.', '-'));
    }

    private static String val(Map<String, String> v, String col) {
        if (col == null) {
            return null;
        }
        String s = v.get(col);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String n(String s) {
        return s == null ? "" : s.trim();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
