package com.commerceos.inventory;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.inventory.InventoryLedger.Bucket;
import com.commerceos.inventory.InventoryLedger.Position;
import com.commerceos.inventory.InventoryLedger.Stock;
import com.commerceos.inventory.InventoryLedger.Txn;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부(3PL·플랫폼) 재고 스냅샷 반영(M3 I4, FR-INV-04).
 *
 * <p>원본은 스냅샷·행으로 그대로 남기고, 내부 미러 버킷·집계는 원장(EXTERNAL_SYNC)으로만 맞춘다. 판단 규칙:
 * <ul>
 *   <li>워터마크: 마지막으로 반영한 기준시각(as_of)보다 늦지 않은 스냅샷은 보관만 하고 반영하지 않는다(역순 도착, docs/04 §9).</li>
 *   <li>수량 의미 ON_HAND(총재고): 내부 판매가능 = 외부값 − 기준시각 이후 출고 확정분.</li>
 *   <li>수량 의미 AVAILABLE/POST_OUTBOUND_ESTIMATE(가용): 기준시각 전에 물류사가 접수한 확보는 이미 빠져 있으므로 되돌려 더한다
 *       — 내부 판매가능 = 외부값 + 반영된 확보 − 기준시각 이후 출고 확정분(T08: 재차 차감 금지).</li>
 *   <li>수량 의미 미확인·기준시각 없음·오래된 스냅샷: 반영 크레딧 없이 가장 보수적으로 계산하고 판매 제한(버퍼 보류) + 업무함(T09, ARCH-005).</li>
 *   <li>외부값이 확보수량보다 적으면 확보수량까지만 맞추고(ATP 0) 판매 제한 + 업무함(과확보 의심).</li>
 *   <li>비가용: 외부 합계가 늘면 늘어난 만큼 '미확정' 버킷으로, 줄면 내부 세부분류를 임의로 지우지 않고 대사 업무만 만든다(T38).</li>
 * </ul>
 */
@Service
public class ExternalSnapshotService {
    static final String MAPPING_VERSION_FILE = "file-template-v1";
    static final String UNCLASSIFIED = "EXTERNAL_UNCLASSIFIED";
    static final List<String> RESTRICTION_REASONS = List.of("QUANTITY_SEMANTIC_UNKNOWN", "WATERMARK_MISSING", "SNAPSHOT_STALE", "EXTERNAL_SHORTAGE");

    /** 판매 제한 업무는 사유별로 따로 둔다(사유가 바뀌면 새 업무, 이전 사유 업무는 닫힘). */
    static String restrictKey(UUID positionId, String reason) {
        return "inventory:position:" + positionId + ":EXTERNAL_RESTRICTED:" + reason;
    }
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final InventoryLedger ledger;
    private final InventoryService inventory;
    private final WorkItemService workItems;
    private final AuditService audit;
    private final FileService files;
    private final Json json;
    private final Clock clock;

    public ExternalSnapshotService(JdbcClient jdbc, AccessGuard guard, InventoryLedger ledger, InventoryService inventory, WorkItemService workItems,
                                   AuditService audit, FileService files, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.ledger = ledger;
        this.inventory = inventory;
        this.workItems = workItems;
        this.audit = audit;
        this.files = files;
        this.json = json;
        this.clock = clock;
    }

    public record Line(String externalSkuCode, String lotCode, LocalDate expiresOn, String locationCode, String externalStatus, Boolean sellable,
                       Integer quantity) {
    }

    /** 사용자가 직접 넣는 스냅샷(물류사 화면·파일에서 확인한 값). sourceKey 는 같은 자료의 재전송 판별용. */
    public record Submit(UUID nodeId, UUID businessEntityId, String sourceKey, Instant asOf, Boolean complete, List<Line> lines) {
    }

    /** 연동 작업·파일·직접 입력 공통 입력. actorMemberId 가 없으면 연동(시스템) 반영. */
    public record Incoming(UUID workspaceId, UUID nodeId, UUID ownerId, UUID shipperAccountId, UUID connectionId, String source, String sourceKey,
                           Instant asOf, Instant fetchedAt, boolean complete, String mappingVersion, Integer capabilityVersion, String rawPayloadRef,
                           Object rawPayload, UUID fileId, List<Line> lines, UUID actorMemberId, String actorLabel) {
    }

    public record SnapshotView(UUID id, UUID nodeId, String nodeCode, String nodeName, UUID businessEntityId, String businessEntityName, String source,
                               String sourceKey, Instant asOf, Instant fetchedAt, boolean complete, String quantitySemantic, String mappingVersion,
                               String status, int lineCount, int issueCount, UUID fileId, String receivedByName, Instant createdAt) {
    }

    public record LineView(int lineNo, String externalSkuCode, UUID skuId, String skuCode, String lotCode, LocalDate expiresOn, String locationCode,
                           String externalStatus, boolean sellable, int quantity, String issue) {
    }

    public record ResultView(UUID skuId, String skuCode, int externalSellable, int externalNonSellable, int reflectedQuantity, int shippedAfterQuantity,
                             int committedAtApply, int sellableBefore, int sellableAfter, int nonSellableBefore, int nonSellableAfter, boolean certain,
                             String restrictionReason) {
    }

    public record SnapshotDetail(SnapshotView snapshot, List<ResultView> results, List<LineView> lines) {
    }

    record NodeRule(UUID id, String code, String inventorySource, String quantitySemantic, int buffer, int maxAgeMinutes, String status) {
    }

    record MappedLine(int lineNo, Line line, UUID skuId, String issue) {
    }

    /** SKU 하나에 대한 판단(잠금을 잡은 상태에서 계산, 그 뒤 한꺼번에 반영). */
    record Decision(Position position, int qs, int qn, int reflected, int shippedAfter, int targetSellable, String reason, int hold,
                    List<Stock> nonSellable, int nonSellableBefore) {
    }

    // ---------------------------------------------------------------- 사용자 입력

    @Transactional
    public SnapshotDetail submit(Submit s) {
        CurrentActor a = requireOperator(s.businessEntityId());
        if (s.sourceKey() == null || s.sourceKey().isBlank() || s.sourceKey().length() > 200) {
            throw Errors.validation("source_key", "자료 식별 키(source_key)를 200자 이내로 입력하세요(같은 자료 재전송 판별).");
        }
        UUID shipper = shipperFor(a.workspaceId(), s.nodeId(), s.businessEntityId());
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("as_of", s.asOf() == null ? null : s.asOf().toString());
        raw.put("complete", Boolean.TRUE.equals(s.complete()));
        raw.put("lines", s.lines());
        return apply(new Incoming(a.workspaceId(), s.nodeId(), s.businessEntityId(), shipper, null, "FILE", "manual:" + s.sourceKey().trim(), s.asOf(),
                clock.instant(), Boolean.TRUE.equals(s.complete()), "manual-v1", null, null, raw, null, s.lines(), a.memberId(), a.displayName()));
    }

    /**
     * 물류사 재고 파일 업로드. 필수 열: SKU 코드, 수량, 판매가능(Y/N). 선택: 상태, LOT, 유통기한, 로케이션.
     * 원본 파일은 저장소에 보관하고, 같은 파일(해시)은 한 번만 반영한다.
     */
    @Transactional
    public SnapshotDetail upload(UUID nodeId, UUID businessEntityId, Instant asOf, boolean complete, String filename, String contentType, byte[] content) {
        CurrentActor a = requireOperator(businessEntityId);
        UUID shipper = shipperFor(a.workspaceId(), nodeId, businessEntityId);
        if (content == null || content.length == 0) {
            throw Errors.validation("file", "빈 파일입니다.");
        }
        TabularReader.Table table = TabularReader.read(content, filename, null);
        Map<String, String> col = columns(table.headers());
        for (String required : List.of("SKU코드", "수량", "판매가능")) {
            if (!col.containsKey(required)) {
                throw Errors.unprocessable("TEMPLATE_COLUMN_MISSING", "필수 열이 없습니다: " + required.replace("SKU코드", "SKU 코드"),
                        Map.of("required", List.of("SKU 코드", "수량", "판매가능(Y/N)"), "optional", List.of("상태", "LOT", "유통기한", "로케이션")));
            }
        }
        List<Line> lines = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (TabularReader.DataRow row : table.rows()) {
            Map<String, String> v = row.values();
            String code = val(v, col.get("SKU코드"));
            String qty = val(v, col.get("수량"));
            String sell = val(v, col.get("판매가능"));
            Integer q = null;
            try {
                q = qty == null ? null : Integer.valueOf(qty.replace(",", ""));
            } catch (NumberFormatException e) {
                // 아래에서 오류로 모은다
            }
            Boolean sellable = sell == null ? null : switch (sell.trim().toUpperCase(Locale.ROOT)) {
                case "Y", "YES", "TRUE", "1", "예", "가능" -> Boolean.TRUE;
                case "N", "NO", "FALSE", "0", "아니오", "불가" -> Boolean.FALSE;
                default -> null;
            };
            LocalDate exp = null;
            String expRaw = val(v, col.get("유통기한"));
            if (expRaw != null) {
                try {
                    exp = LocalDate.parse(expRaw.trim());
                } catch (DateTimeParseException e) {
                    errors.add(Map.of("row", row.rowNumber(), "message", "유통기한은 YYYY-MM-DD 형식입니다."));
                }
            }
            if (code == null || q == null || q < 0 || sellable == null) {
                errors.add(Map.of("row", row.rowNumber(), "message", "SKU 코드·수량(0 이상 정수)·판매가능(Y/N)을 확인하세요."));
                continue;
            }
            lines.add(new Line(code, val(v, col.get("LOT")), exp, val(v, col.get("로케이션")), val(v, col.get("상태")), sellable, q));
        }
        if (!errors.isEmpty()) {
            // 일부 행만 반영하면 합계가 틀어지므로 파일 전체를 거절한다
            throw Errors.unprocessable("EXTERNAL_FILE_INVALID", "파일에 잘못된 행이 있어 반영하지 않았습니다. 고친 뒤 다시 올리세요.",
                    Map.of("errors", errors.size() > 50 ? errors.subList(0, 50) : errors, "error_count", errors.size()));
        }
        String sha = sha256(content);
        Optional<UUID> same = jdbc.sql("""
                select id from external_inventory_snapshot where workspace_id = :ws and node_id = :n and owner_business_entity_id = :o and source = 'FILE'
                   and source_key = :k
                """).param("ws", a.workspaceId()).param("n", nodeId).param("o", businessEntityId).param("k", "sha256:" + sha).query(UUID.class).optional();
        if (same.isPresent()) {
            return detail(a.workspaceId(), same.get()); // 같은 파일 재업로드: 원본을 다시 저장하지 않고 기존 반영 결과를 돌려준다
        }
        FileService.FileView f = files.store(new FileService.NewFile(a.workspaceId(), businessEntityId, "EXTERNAL_INVENTORY", filename,
                contentType, content, false, a.memberId(), null));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("file_id", f.id().toString());
        raw.put("filename", filename);
        raw.put("sha256", f.sha256());
        raw.put("encoding", table.encoding());
        raw.put("headers", table.headers());
        raw.put("row_count", table.rows().size());
        return apply(new Incoming(a.workspaceId(), nodeId, businessEntityId, shipper, null, "FILE", "sha256:" + f.sha256(), asOf, clock.instant(),
                complete, MAPPING_VERSION_FILE, null, f.id().toString(), raw, f.id(), lines, a.memberId(), a.displayName()));
    }

    /** 연동 작업(INVENTORY_FETCH)이 부른다. 호출자 트랜잭션·시스템 문맥 안에서 실행. */
    @Transactional(propagation = Propagation.MANDATORY)
    public SnapshotDetail applySystem(Incoming in) {
        return apply(in);
    }

    // ---------------------------------------------------------------- 반영

    private SnapshotDetail apply(Incoming in) {
        UUID ws = in.workspaceId();
        Optional<UUID> existing = jdbc.sql("""
                select id from external_inventory_snapshot where workspace_id = :ws and node_id = :n and owner_business_entity_id = :o and source = :src and source_key = :k
                """).param("ws", ws).param("n", in.nodeId()).param("o", in.ownerId()).param("src", in.source()).param("k", in.sourceKey())
                .query(UUID.class).optional();
        if (existing.isPresent()) {
            return detail(ws, existing.get());
        }
        NodeRule node = nodeRule(ws, in.nodeId());
        if (!"EXTERNAL".equals(node.inventorySource())) {
            throw Errors.unprocessable("NODE_INVENTORY_INTERNAL", "내부 원장 거점에는 외부 재고 스냅샷을 반영하지 않습니다.");
        }
        if ("CLOSED".equals(node.status())) {
            throw Errors.unprocessable("NODE_NOT_ACTIVE", "닫은 거점입니다.");
        }
        List<Line> lines = in.lines() == null ? List.of() : in.lines();
        if (lines.size() > TabularReader.MAX_ROWS) {
            throw Errors.validation("lines", "한 번에 " + TabularReader.MAX_ROWS + "행까지 반영할 수 있습니다.");
        }
        if (lines.isEmpty() && !in.complete()) {
            throw Errors.validation("lines", "반영할 행이 없습니다(전체 스냅샷이면 complete 를 true 로).");
        }
        for (Line l : lines) {
            if (l.externalSkuCode() == null || l.externalSkuCode().isBlank() || l.externalSkuCode().length() > 100) {
                throw Errors.validation("lines.external_sku_code", "외부 SKU 코드가 비어 있거나 너무 깁니다.");
            }
            if (l.quantity() == null || l.quantity() < 0 || l.sellable() == null) {
                throw Errors.validation("lines.quantity", "수량(0 이상)과 판매가능 여부를 모든 행에 넣으세요.");
            }
        }
        List<MappedLine> mapped = map(ws, lines);
        Instant now = clock.instant();
        Instant lastAsOf = jdbc.sql("""
                select max(as_of) as m from external_inventory_snapshot where workspace_id = :ws and node_id = :n and owner_business_entity_id = :o
                   and status in ('APPLIED', 'APPLIED_RESTRICTED')
                """).param("ws", ws).param("n", in.nodeId()).param("o", in.ownerId()).query((rs, i) -> Db.instant(rs, "m")).optional().orElse(null);
        UUID sid = Ids.newId();
        int issues = (int) mapped.stream().filter(m -> m.issue() != null).count();

        if (in.asOf() != null && lastAsOf != null && !in.asOf().isAfter(lastAsOf)) {
            insertSnapshot(in, node, sid, "IGNORED_OUT_OF_ORDER", lines.size(), issues);
            insertLines(ws, sid, mapped);
            audit.record(entry(in, "EXTERNAL_INVENTORY_SNAPSHOT_IGNORED", sid)
                    .metadata(Map.of("as_of", in.asOf().toString(), "last_applied_as_of", lastAsOf.toString())).build());
            return detail(ws, sid);
        }

        String baseReason = "UNKNOWN".equals(node.quantitySemantic()) ? "QUANTITY_SEMANTIC_UNKNOWN"
                : in.asOf() == null ? "WATERMARK_MISSING"
                : in.asOf().isBefore(now.minus(Duration.ofMinutes(node.maxAgeMinutes()))) ? "SNAPSHOT_STALE" : null;
        boolean reflectionCredit = baseReason == null || "SNAPSHOT_STALE".equals(baseReason);
        // 기준시각이 없으면 언제까지의 출고가 반영됐는지 모른다 → 가장 이른 그럴듯한 시점 이후 출고를 모두 뺀다(보수적)
        Instant shippedWindow = in.asOf() != null ? in.asOf() : (lastAsOf != null ? lastAsOf : now.minus(Duration.ofMinutes(node.maxAgeMinutes())));

        Map<UUID, int[]> totals = new LinkedHashMap<>();
        for (MappedLine m : mapped) {
            if (m.skuId() != null) {
                int[] t = totals.computeIfAbsent(m.skuId(), k -> new int[2]);
                t[m.line().sellable() ? 0 : 1] += m.line().quantity();
            }
        }
        Set<UUID> skus = new LinkedHashSet<>(totals.keySet());
        if (in.complete()) {
            // 전체 스냅샷: 목록에 없는 SKU 는 외부 수량 0 으로 본다
            skus.addAll(jdbc.sql("""
                    select sku_id from inventory_position where workspace_id = :ws and node_id = :n and owner_business_entity_id = :o
                    union select sku_id from inventory_stock where workspace_id = :ws and node_id = :n and owner_business_entity_id = :o and on_hand > 0
                    """).param("ws", ws).param("n", in.nodeId()).param("o", in.ownerId()).query(UUID.class).list());
        }

        List<Decision> decisions = new ArrayList<>();
        if (!skus.isEmpty()) {
            for (UUID sku : skus) {
                jdbc.sql("""
                        insert into inventory_position (id, workspace_id, owner_business_entity_id, sku_id, node_id) values (:id, :ws, :o, :s, :n)
                        on conflict (owner_business_entity_id, sku_id, node_id) do nothing
                        """).param("id", Ids.newId()).param("ws", ws).param("o", in.ownerId()).param("s", sku).param("n", in.nodeId()).update();
            }
            List<Position> locked = ledger.lockPositions(ws, in.ownerId(), skus).stream().filter(p -> p.nodeId().equals(in.nodeId()))
                    .sorted(Comparator.comparing(Position::id)).toList();
            for (Position p : locked) {
                int[] t = totals.getOrDefault(p.skuId(), new int[2]);
                int shippedAfter = sumQty("""
                        select coalesce(sum(quantity), 0) from inventory_reservation where position_id = :p and state = 'CONSUMED' and consumed_at > :w
                        """, p.id(), shippedWindow);
                int reflected = 0;
                boolean availableSemantic = "AVAILABLE".equals(node.quantitySemantic()) || "POST_OUTBOUND_ESTIMATE".equals(node.quantitySemantic());
                if (availableSemantic && reflectionCredit && in.asOf() != null) {
                    reflected = sumQty("""
                            select coalesce(sum(quantity), 0) from inventory_reservation where position_id = :p and external_ack_at <= :w
                               and (state in ('RESERVED', 'ALLOCATED', 'PICKING') or (state = 'CONSUMED' and consumed_at > :w))
                            """, p.id(), in.asOf());
                }
                int target = Math.max(0, t[0] + reflected - shippedAfter);
                String reason = baseReason;
                if (target < p.committed()) {
                    reason = "EXTERNAL_SHORTAGE";
                    target = p.committed();
                }
                List<Stock> nonSellable = jdbc.sql("""
                        select * from inventory_stock where owner_business_entity_id = :o and sku_id = :s and node_id = :n and quality_status <> 'SELLABLE'
                           and on_hand > 0 order by id for update
                        """).param("o", p.ownerId()).param("s", p.skuId()).param("n", p.nodeId()).query((rs, i) -> stockOf(rs)).list();
                int nonBefore = nonSellable.stream().mapToInt(Stock::onHand).sum();
                decisions.add(new Decision(p, t[0], t[1], reflected, shippedAfter, target, reason, reason == null ? 0 : node.buffer(), nonSellable, nonBefore));
            }
        }
        boolean anyRestricted = baseReason != null || decisions.stream().anyMatch(d -> d.reason() != null);
        insertSnapshot(in, node, sid, anyRestricted ? "APPLIED_RESTRICTED" : "APPLIED", lines.size(), issues);
        insertLines(ws, sid, mapped);

        String semanticKo = switch (node.quantitySemantic()) {
            case "ON_HAND" -> "총재고 기준";
            case "AVAILABLE" -> "가용 기준";
            case "POST_OUTBOUND_ESTIMATE" -> "출고 후 예상 기준";
            default -> "수량 의미 미확인";
        };
        for (Decision d : decisions) {
            Position p = d.position();
            int atpBefore = p.atp();
            boolean wasRestricted = jdbc.sql("select restricted from inventory_position where id = :id").param("id", p.id()).query(Boolean.class).single();
            String why = "외부 재고 동기화(" + semanticKo + ")";
            int delta = d.targetSellable() - p.sellableOnHand();
            Position cur = p;
            if (delta > 0) {
                UUID stockId = ledger.addToBucket(ws, new Bucket(p.ownerId(), p.skuId(), p.nodeId(), null, null, "SELLABLE", null), delta);
                cur = ledger.applyToPosition(cur, delta, 0);
                ledger.record(ws, new Txn("EXTERNAL_SYNC", cur, stockId, "SELLABLE", delta, delta, 0, null, null, null, "EXTERNAL_SNAPSHOT", sid.toString(),
                        why, in.actorMemberId()));
            } else if (delta < 0) {
                int remaining = -delta;
                for (Stock s : ledger.lockSellableBucketsFefo(p)) {
                    if (remaining == 0) {
                        break;
                    }
                    int take = Math.min(remaining, s.onHand());
                    ledger.removeFromBucket(s.id(), take);
                    cur = ledger.applyToPosition(cur, -take, 0);
                    ledger.record(ws, new Txn("EXTERNAL_SYNC", cur, s.id(), "SELLABLE", -take, -take, 0, null, null, null, "EXTERNAL_SNAPSHOT", sid.toString(),
                            why, in.actorMemberId()));
                    remaining -= take;
                }
                if (remaining > 0) {
                    throw Errors.conflict("STOCK_BUCKET_MISMATCH", "판매가능 버킷과 집계가 어긋나 외부 재고를 반영하지 못했습니다. 재고 검증을 먼저 확인하세요.");
                }
            }
            int nonAfter = reconcileNonSellable(ws, in, sid, d, why);
            jdbc.sql("""
                    update inventory_position set external_snapshot_id = :sid, external_as_of = :asof, restricted = :r, restriction_reason = cast(:reason as text),
                           restricted_hold = :hold, version = version + 1, updated_at = now() where id = :id
                    """).param("sid", sid).param("asof", Db.ts(in.asOf())).param("r", d.reason() != null).param("reason", d.reason())
                    .param("hold", d.hold()).param("id", p.id()).update();
            jdbc.sql("""
                    insert into external_inventory_result (workspace_id, snapshot_id, sku_id, position_id, external_sellable, external_non_sellable, reflected_quantity,
                        shipped_after_quantity, committed_at_apply, sellable_before, sellable_after, non_sellable_before, non_sellable_after, certain, restriction_reason)
                    values (:ws, :sid, :sku, :pid, :qs, :qn, :rf, :sa, :c, :sb, :saf, :nb, :na, :certain, cast(:reason as text))
                    """).param("ws", ws).param("sid", sid).param("sku", p.skuId()).param("pid", p.id()).param("qs", d.qs()).param("qn", d.qn())
                    .param("rf", d.reflected()).param("sa", d.shippedAfter()).param("c", p.committed()).param("sb", p.sellableOnHand())
                    .param("saf", d.targetSellable()).param("nb", d.nonSellableBefore()).param("na", nonAfter).param("certain", d.reason() == null)
                    .param("reason", d.reason()).update();
            String restrictKey = restrictKey(p.id(), d.reason());
            for (String r : RESTRICTION_REASONS) {
                if (!r.equals(d.reason())) {
                    workItems.resolveByKey(ws, restrictKey(p.id(), r));
                }
            }
            if (d.reason() != null) {
                workItems.raise(new WorkItemService.Raise(ws, p.ownerId(), null, in.connectionId(), "EXTERNAL_INVENTORY_RESTRICTED",
                        "EXTERNAL_SHORTAGE".equals(d.reason()) ? "HIGH" : "MEDIUM", "외부 재고 판매 제한: " + skuCode(p.skuId()) + " · " + node.code(),
                        restrictionCauseKo(d.reason()), restrictionActionKo(d.reason()), restrictKey,
                        related(p, sid, Map.of("reason", d.reason(), "external_sellable", d.qs(), "committed", p.committed(), "hold", d.hold()))));
            }
            Position after = new Position(p.id(), p.ownerId(), p.skuId(), p.nodeId(), d.targetSellable(), p.committed(), d.hold());
            if (after.atp() != atpBefore || wasRestricted != (d.reason() != null) || delta != 0) {
                inventory.availabilityChanged(ws, after);
            }
        }

        String unmappedKey = "inventory:node:" + in.nodeId() + ":be:" + in.ownerId() + ":EXTERNAL_SKU_UNMAPPED";
        List<String> unmapped = mapped.stream().filter(m -> m.issue() != null).map(m -> m.line().externalSkuCode()).distinct().toList();
        if (!unmapped.isEmpty()) {
            workItems.raise(new WorkItemService.Raise(ws, in.ownerId(), null, in.connectionId(), "EXTERNAL_SKU_UNMAPPED", "MEDIUM",
                    "물류사 재고에 연결되지 않은 SKU " + unmapped.size() + "건 · " + node.code(),
                    "물류사 재고 자료의 SKU 코드가 우리 SKU 코드·바코드와 맞지 않거나 재고관리 대상이 아닙니다. 해당 행은 재고에 반영하지 않았습니다.",
                    "물류사에 등록된 상품코드를 우리 SKU 코드와 맞추거나 SKU 바코드를 등록한 뒤 다음 동기화를 확인하세요.", unmappedKey,
                    Map.of("snapshot_id", sid.toString(), "node_id", in.nodeId().toString(), "external_sku_codes", unmapped.size() > 50 ? unmapped.subList(0, 50) : unmapped)));
        } else if (in.complete()) {
            workItems.resolveByKey(ws, unmappedKey);
        }
        audit.record(entry(in, "EXTERNAL_INVENTORY_SNAPSHOT_APPLIED", sid)
                .metadata(Map.of("status", anyRestricted ? "APPLIED_RESTRICTED" : "APPLIED", "sku_count", decisions.size(), "issue_count", issues,
                        "quantity_semantic", node.quantitySemantic(), "as_of", String.valueOf(in.asOf()))).build());
        return detail(ws, sid);
    }

    /** 비가용 대사. @return 반영 후 내부 비가용 합계 */
    private int reconcileNonSellable(UUID ws, Incoming in, UUID sid, Decision d, String why) {
        Position p = d.position();
        String unclassifiedKey = "inventory:position:" + p.id() + ":EXTERNAL_QUALITY_UNCLASSIFIED";
        String mismatchKey = "inventory:position:" + p.id() + ":EXTERNAL_QUALITY_MISMATCH";
        int before = d.nonSellableBefore();
        int after;
        if (d.qn() == before) {
            workItems.resolveByKey(ws, mismatchKey);
            after = before;
        } else if (d.qn() > before) {
            int add = d.qn() - before;
            UUID stockId = ledger.addToBucket(ws, new Bucket(p.ownerId(), p.skuId(), p.nodeId(), null, null, "UNDETERMINED", UNCLASSIFIED), add);
            ledger.record(ws, new Txn("EXTERNAL_SYNC", p, stockId, "UNDETERMINED", add, 0, 0, null, null, null, "EXTERNAL_SNAPSHOT", sid.toString(),
                    why + " · 비가용 증가분 미확정", in.actorMemberId()));
            workItems.resolveByKey(ws, mismatchKey);
            workItems.raise(new WorkItemService.Raise(ws, p.ownerId(), null, in.connectionId(), "EXTERNAL_INVENTORY_QUALITY", "MEDIUM",
                    "물류사 비가용 재고 분류 필요: " + skuCode(p.skuId()), "물류사 자료의 비가용 수량이 " + before + " → " + d.qn()
                    + " 로 늘었습니다. 늘어난 " + add + "개를 '미확정'으로 두었습니다(판매 불가).",
                    "물류사 화면·검수 자료로 불량·파손 등 세부 상태를 확인해 품질 변경으로 분류하세요.", unclassifiedKey,
                    related(p, sid, Map.of("external_non_sellable", d.qn(), "internal_non_sellable", before))));
            return d.qn();
        } else if (d.nonSellable().stream().allMatch(s -> "UNDETERMINED".equals(s.bucket().quality()) && UNCLASSIFIED.equals(s.bucket().holdReason()))) {
            // 외부 비가용이 줄었고 내부에 세부분류 없이 '미확정'만 있다 → 줄어든 만큼 줄여도 임의 삭제가 아니다
            int remaining = before - d.qn();
            for (Stock s : d.nonSellable()) {
                int take = Math.min(remaining, s.onHand());
                if (take > 0) {
                    ledger.removeFromBucket(s.id(), take);
                    ledger.record(ws, new Txn("EXTERNAL_SYNC", p, s.id(), "UNDETERMINED", -take, 0, 0, null, null, null, "EXTERNAL_SNAPSHOT", sid.toString(),
                            why + " · 미확정 비가용 감소", in.actorMemberId()));
                    remaining -= take;
                }
            }
            workItems.resolveByKey(ws, mismatchKey);
            after = d.qn();
        } else {
            // 외부 비가용이 줄었는데 내부 세부분류 중 무엇이 빠졌는지 알 수 없다 → 바꾸지 않고 대사 업무(T38)
            Map<String, Integer> detail = new LinkedHashMap<>();
            for (Stock s : d.nonSellable()) {
                detail.merge(s.bucket().quality(), s.onHand(), Integer::sum);
            }
            workItems.raise(new WorkItemService.Raise(ws, p.ownerId(), null, in.connectionId(), "EXTERNAL_INVENTORY_QUALITY", "HIGH",
                    "물류사 비가용 재고 대사 필요: " + skuCode(p.skuId()), "물류사 자료의 비가용 수량은 " + d.qn() + "개인데 내부 세부분류는 " + before
                    + "개입니다. 어느 분류가 빠졌는지 알 수 없어 내부 기록을 바꾸지 않았습니다.",
                    "물류사에 폐기·반송·정상 전환 내역을 확인하고, 확인된 처리를 재고에 반영하세요.", mismatchKey,
                    related(p, sid, Map.of("external_non_sellable", d.qn(), "internal_non_sellable", before, "internal_detail", detail))));
            return before;
        }
        boolean unclassifiedLeft = jdbc.sql("""
                select count(*) from inventory_stock where owner_business_entity_id = :o and sku_id = :s and node_id = :n and hold_reason = :h and on_hand > 0
                """).param("o", p.ownerId()).param("s", p.skuId()).param("n", p.nodeId()).param("h", UNCLASSIFIED).query(Integer.class).single() > 0;
        if (!unclassifiedLeft) {
            workItems.resolveByKey(ws, unclassifiedKey);
        }
        return after;
    }

    // ---------------------------------------------------------------- 오래된 스냅샷 감시

    /** 외부 거점 집계 중 마지막 스냅샷이 유효 시간을 넘은 것을 판매 제한으로 돌린다. @return 새로 제한한 수 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int restrictStale(UUID ws) {
        Instant now = clock.instant();
        List<Map<String, Object>> rows = jdbc.sql("""
                select p.id, p.owner_business_entity_id, p.sku_id, n.code, n.restricted_sell_buffer, p.external_as_of
                  from inventory_position p join fulfillment_node n on n.id = p.node_id
                 where p.workspace_id = :ws and n.inventory_source = 'EXTERNAL' and n.status <> 'CLOSED' and not p.restricted
                   and p.external_as_of is not null and p.external_as_of < :now - make_interval(mins => n.snapshot_max_age_minutes)
                 order by p.id for update of p
                """).param("ws", ws).param("now", Db.ts(now)).query().listOfRows();
        for (Map<String, Object> r : rows) {
            UUID pid = (UUID) r.get("id");
            int hold = ((Number) r.get("restricted_sell_buffer")).intValue();
            jdbc.sql("update inventory_position set restricted = true, restriction_reason = 'SNAPSHOT_STALE', restricted_hold = :h, version = version + 1, updated_at = now() where id = :id")
                    .param("h", hold).param("id", pid).update();
            Position p = ledger.lockPositionById(ws, pid);
            workItems.raise(new WorkItemService.Raise(ws, p.ownerId(), null, null, "EXTERNAL_INVENTORY_RESTRICTED", "MEDIUM",
                    "외부 재고 판매 제한: " + skuCode(p.skuId()) + " · " + r.get("code"), restrictionCauseKo("SNAPSHOT_STALE"), restrictionActionKo("SNAPSHOT_STALE"),
                    restrictKey(pid, "SNAPSHOT_STALE"), Map.of("position_id", pid.toString(), "reason", "SNAPSHOT_STALE")));
            inventory.availabilityChanged(ws, p);
        }
        return rows.size();
    }

    // ---------------------------------------------------------------- 조회

    public List<SnapshotView> list(List<String> businessEntityIds, UUID nodeId) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        String sql = SELECT + " where s.workspace_id = :ws and s.owner_business_entity_id in (:bes)" + (nodeId == null ? "" : " and s.node_id = :n")
                + " order by s.id desc limit 200";
        var q = jdbc.sql(sql).param("ws", guard.actor().workspaceId()).param("bes", bes);
        if (nodeId != null) {
            q = q.param("n", nodeId);
        }
        return q.query((rs, i) -> view(rs)).list();
    }

    public SnapshotDetail get(UUID id) {
        guard.requirePermission(Perm.INVENTORY_READ);
        SnapshotDetail d = detail(guard.actor().workspaceId(), id);
        guard.checkRead("EXTERNAL_INVENTORY_SNAPSHOT", id, d.snapshot().businessEntityId(), null);
        return d;
    }

    private SnapshotDetail detail(UUID ws, UUID id) {
        SnapshotView v = jdbc.sql(SELECT + " where s.workspace_id = :ws and s.id = :id").param("ws", ws).param("id", id).query((rs, i) -> view(rs)).optional()
                .orElseThrow(() -> Errors.notFound("EXTERNAL_INVENTORY_SNAPSHOT"));
        List<ResultView> results = jdbc.sql("""
                select r.*, k.sku_code from external_inventory_result r join sku k on k.id = r.sku_id where r.snapshot_id = :id order by k.sku_code
                """).param("id", id).query((rs, i) -> new ResultView(Db.uuid(rs, "sku_id"), rs.getString("sku_code"), rs.getInt("external_sellable"),
                rs.getInt("external_non_sellable"), rs.getInt("reflected_quantity"), rs.getInt("shipped_after_quantity"), rs.getInt("committed_at_apply"),
                rs.getInt("sellable_before"), rs.getInt("sellable_after"), rs.getInt("non_sellable_before"), rs.getInt("non_sellable_after"),
                rs.getBoolean("certain"), rs.getString("restriction_reason"))).list();
        List<LineView> lines = jdbc.sql("""
                select l.*, k.sku_code from external_inventory_line l left join sku k on k.id = l.sku_id where l.snapshot_id = :id order by l.line_no limit 1000
                """).param("id", id).query((rs, i) -> {
                    java.sql.Date exp = rs.getDate("expires_on");
                    return new LineView(rs.getInt("line_no"), rs.getString("external_sku_code"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"),
                            rs.getString("lot_code"), exp == null ? null : exp.toLocalDate(), rs.getString("location_code"), rs.getString("external_status"),
                            rs.getBoolean("sellable"), rs.getInt("quantity"), rs.getString("issue"));
                }).list();
        return new SnapshotDetail(v, results, lines);
    }

    // ---------------------------------------------------------------- 내부

    private CurrentActor requireOperator(UUID businessEntityId) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        if (businessEntityId == null) {
            throw Errors.validation("business_entity_id", "소유 사업자를 지정하세요.");
        }
        guard.checkWrite("BUSINESS_ENTITY", businessEntityId, businessEntityId, null);
        return guard.actor();
    }

    /** 3PL·플랫폼 거점의 해당 사업자 화주계정(연결 없으면 그 사업자 재고를 받을 수 없음, T05). */
    private UUID shipperFor(UUID ws, UUID nodeId, UUID ownerId) {
        if (nodeId == null) {
            throw Errors.validation("node_id", "거점을 지정하세요.");
        }
        NodeRule n = nodeRule(ws, nodeId);
        if (!"EXTERNAL".equals(n.inventorySource())) {
            throw Errors.unprocessable("NODE_INVENTORY_INTERNAL", "내부 원장 거점에는 외부 재고 스냅샷을 반영하지 않습니다.");
        }
        return jdbc.sql("""
                select l.shipper_account_id from fulfillment_node_shipper l join shipper_account s on s.id = l.shipper_account_id
                 where l.node_id = :n and l.business_entity_id = :b and s.status = 'ACTIVE'
                """).param("n", nodeId).param("b", ownerId).query(UUID.class).optional()
                .orElseThrow(() -> Errors.unprocessable("OWNER_NOT_ON_NODE", "이 사업자는 해당 거점에 화주계정이 연결되어 있지 않습니다."));
    }

    NodeRule nodeRule(UUID ws, UUID nodeId) {
        return jdbc.sql("""
                select id, code, inventory_source, quantity_semantic, restricted_sell_buffer, snapshot_max_age_minutes, status from fulfillment_node
                 where workspace_id = :ws and id = :id
                """).param("ws", ws).param("id", nodeId).query((rs, i) -> new NodeRule(Db.uuid(rs, "id"), rs.getString("code"), rs.getString("inventory_source"),
                rs.getString("quantity_semantic"), rs.getInt("restricted_sell_buffer"), rs.getInt("snapshot_max_age_minutes"), rs.getString("status")))
                .optional().orElseThrow(() -> Errors.notFound("FULFILLMENT_NODE"));
    }

    /** 외부 SKU 코드 → 우리 SKU(코드 대소문자 무시 → 바코드 순). 재고관리 대상이 아니면 반영하지 않는다. */
    private List<MappedLine> map(UUID ws, List<Line> lines) {
        Set<String> codes = new LinkedHashSet<>();
        for (Line l : lines) {
            codes.add(l.externalSkuCode().trim());
        }
        Map<String, UUID[]> byCode = new HashMap<>();
        Map<String, UUID[]> byBarcode = new HashMap<>();
        if (!codes.isEmpty()) {
            List<String> lower = codes.stream().map(c -> c.toLowerCase(Locale.ROOT)).toList();
            jdbc.sql("""
                    select id, sku_code, barcode, inventory_managed from sku where workspace_id = :ws and status <> 'ARCHIVED'
                       and (lower(sku_code) in (:lc) or barcode in (:c))
                    """).param("ws", ws).param("lc", lower).param("c", codes).query().listOfRows().forEach(r -> {
                UUID[] v = {(UUID) r.get("id"), Boolean.TRUE.equals(r.get("inventory_managed")) ? (UUID) r.get("id") : null};
                byCode.put(((String) r.get("sku_code")).toLowerCase(Locale.ROOT), v);
                if (r.get("barcode") != null) {
                    byBarcode.putIfAbsent((String) r.get("barcode"), v);
                }
            });
        }
        List<MappedLine> out = new ArrayList<>();
        int no = 1;
        for (Line l : lines) {
            String code = l.externalSkuCode().trim();
            UUID[] hit = byCode.getOrDefault(code.toLowerCase(Locale.ROOT), byBarcode.get(code));
            String issue = hit == null ? "SKU_UNMAPPED" : hit[1] == null ? "SKU_NOT_INVENTORY_MANAGED" : null;
            out.add(new MappedLine(no++, l, issue == null ? hit[1] : null, issue));
        }
        return out;
    }

    private void insertSnapshot(Incoming in, NodeRule node, UUID sid, String status, int lineCount, int issues) {
        jdbc.sql("""
                insert into external_inventory_snapshot (id, workspace_id, node_id, owner_business_entity_id, shipper_account_id, connection_id, source, source_key,
                    as_of, fetched_at, complete, quantity_semantic, mapping_version, capability_version, raw_payload_ref, raw_payload, file_id, status, line_count,
                    issue_count, received_by)
                values (:id, :ws, :n, :o, cast(:sa as uuid), cast(:conn as uuid), :src, :k, :asof, :fetched, :complete, :sem, :mv, cast(:cv as integer),
                        cast(:ref as text), cast(:raw as jsonb), cast(:file as uuid), :st, :lc, :ic, cast(:by as uuid))
                """).param("id", sid).param("ws", in.workspaceId()).param("n", in.nodeId()).param("o", in.ownerId()).param("sa", in.shipperAccountId())
                .param("conn", in.connectionId()).param("src", in.source()).param("k", in.sourceKey()).param("asof", Db.ts(in.asOf()))
                .param("fetched", Db.ts(in.fetchedAt())).param("complete", in.complete()).param("sem", node.quantitySemantic()).param("mv", in.mappingVersion())
                .param("cv", in.capabilityVersion()).param("ref", in.rawPayloadRef()).param("raw", json.write(in.rawPayload() == null ? Map.of() : in.rawPayload()))
                .param("file", in.fileId()).param("st", status).param("lc", lineCount).param("ic", issues).param("by", in.actorMemberId()).update();
    }

    private void insertLines(UUID ws, UUID sid, List<MappedLine> mapped) {
        for (MappedLine m : mapped) {
            Line l = m.line();
            jdbc.sql("""
                    insert into external_inventory_line (workspace_id, snapshot_id, line_no, external_sku_code, sku_id, lot_code, expires_on, location_code,
                        external_status, sellable, quantity, issue)
                    values (:ws, :sid, :no, :code, cast(:sku as uuid), cast(:lot as text), cast(:exp as date), cast(:loc as text), cast(:st as text), :sell, :q,
                            cast(:issue as text))
                    """).param("ws", ws).param("sid", sid).param("no", m.lineNo()).param("code", l.externalSkuCode().trim()).param("sku", m.skuId())
                    .param("lot", InventoryService.trimOrNull(l.lotCode())).param("exp", l.expiresOn()).param("loc", InventoryService.trimOrNull(l.locationCode()))
                    .param("st", InventoryService.trimOrNull(l.externalStatus())).param("sell", l.sellable()).param("q", l.quantity()).param("issue", m.issue())
                    .update();
        }
    }

    private int sumQty(String sql, UUID positionId, Instant at) {
        return jdbc.sql(sql).param("p", positionId).param("w", Db.ts(at)).query(Integer.class).single();
    }

    private String skuCode(UUID skuId) {
        return jdbc.sql("select sku_code from sku where id = :id").param("id", skuId).query(String.class).single();
    }

    private AuditEntry.Builder entry(Incoming in, String action, UUID sid) {
        AuditEntry.Builder b = AuditEntry.builder(in.workspaceId(), action, "EXTERNAL_INVENTORY_SNAPSHOT", sid).scope(in.ownerId(), null);
        return in.actorMemberId() != null ? b.actor(ActorType.USER, in.actorMemberId(), in.actorLabel()) : b.actor(ActorType.INTEGRATION, null, in.actorLabel());
    }

    private static Map<String, Object> related(Position p, UUID sid, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("position_id", p.id().toString());
        m.put("sku_id", p.skuId().toString());
        m.put("node_id", p.nodeId().toString());
        m.put("snapshot_id", sid.toString());
        m.putAll(extra);
        return m;
    }

    static String restrictionCauseKo(String reason) {
        return switch (reason) {
            case "QUANTITY_SEMANTIC_UNKNOWN" -> "이 거점의 물류사 재고 수량이 총재고인지 가용재고인지 확인되지 않아, 우리 주문이 이미 빠졌는지 판단할 수 없습니다.";
            case "WATERMARK_MISSING" -> "물류사 자료에 기준시각이 없어 어떤 출고지시·출고까지 반영된 수량인지 판단할 수 없습니다.";
            case "SNAPSHOT_STALE" -> "물류사 재고 자료가 유효 시간보다 오래되었습니다.";
            case "EXTERNAL_SHORTAGE" -> "물류사 재고가 우리 확보수량보다 적습니다(과확보 의심). 추가 판매를 막았습니다.";
            default -> "외부 재고 반영 여부를 판단할 수 없습니다.";
        };
    }

    static String restrictionActionKo(String reason) {
        return switch (reason) {
            case "QUANTITY_SEMANTIC_UNKNOWN" -> "물류사 문서·테스트 계정 응답으로 수량 의미를 확인해 거점 설정에 근거와 함께 입력하세요. 그 전까지 보수적 수량으로 판매합니다.";
            case "WATERMARK_MISSING" -> "기준시각이 있는 자료로 다시 받거나 파일 업로드 때 기준시각을 입력하세요.";
            case "SNAPSHOT_STALE" -> "연동 상태를 확인하고 재고 동기화를 다시 실행하세요.";
            case "EXTERNAL_SHORTAGE" -> "물류사 실재고와 확보 중인 주문을 대조해 출고 불가 주문을 다른 거점에 배정하거나 취소하세요.";
            default -> "물류사 자료를 확인하세요.";
        };
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> columns(List<String> headers) {
        Map<String, String> m = new HashMap<>();
        for (String h : headers) {
            String k = h.replace("*", "").replace(" ", "").replace("(Y/N)", "").trim();
            if (k.equalsIgnoreCase("lot") || k.equals("LOT번호")) {
                k = "LOT";
            }
            m.putIfAbsent(k, h);
        }
        return m;
    }

    private static String val(Map<String, String> v, String header) {
        if (header == null) {
            return null;
        }
        String s = v.get(header);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Stock stockOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Stock(Db.uuid(rs, "id"), new Bucket(Db.uuid(rs, "owner_business_entity_id"), Db.uuid(rs, "sku_id"), Db.uuid(rs, "node_id"),
                Db.uuid(rs, "location_id"), Db.uuid(rs, "lot_id"), rs.getString("quality_status"), rs.getString("hold_reason")), rs.getInt("on_hand"));
    }

    private static final String SELECT = """
            select s.*, n.code as node_code, n.name as node_name, be.display_name as be_name, u.display_name as received_by_name
              from external_inventory_snapshot s join fulfillment_node n on n.id = s.node_id join business_entity be on be.id = s.owner_business_entity_id
              left join workspace_member m on m.workspace_id = s.workspace_id and m.id = s.received_by left join user_account u on u.id = m.user_id
            """;

    private static SnapshotView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SnapshotView(Db.uuid(rs, "id"), Db.uuid(rs, "node_id"), rs.getString("node_code"), rs.getString("node_name"),
                Db.uuid(rs, "owner_business_entity_id"), rs.getString("be_name"), rs.getString("source"), rs.getString("source_key"), Db.instant(rs, "as_of"),
                Db.instant(rs, "fetched_at"), rs.getBoolean("complete"), rs.getString("quantity_semantic"), rs.getString("mapping_version"),
                rs.getString("status"), rs.getInt("line_count"), rs.getInt("issue_count"), Db.uuid(rs, "file_id"), rs.getString("received_by_name"),
                Db.instant(rs, "created_at"));
    }
}
