package com.commerceos.claim;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.inventory.InventoryService;
import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반품 입고·검수(M4 O6, docs/04 §5, docs/13 §5, T16·T20·T37, DLG-142~144).
 * <ul>
 *   <li>입고: 클레임·원주문과 SKU·수량이 맞는 줄만 '검수대기'로 받는다. 맞지 않거나 원주문이 없으면 '미확정'(보류 UNIDENTIFIED_RETURN) + 확인 업무 — 판매가능 재고로 넣지 않는다.</li>
 *   <li>검수: 줄마다 남은 수량 전부를 판정(판정 합 = 입고 수량). 자체 재고 거점은 판정대로 품질 버킷 이동(정상만 판매가능). 3PL 거점은 재고를 덮어쓰지 않고 보완정보로만 기록.</li>
 *   <li>3PL 반품검수 API 가 확인된 연동이 없으면 '미확정'으로 두고 수동·파일 검수(T20).</li>
 * </ul>
 */
@Service
public class ReturnService {
    static final Set<String> CONDITIONS = Set.of("SELLABLE", "REPACK", "DEFECTIVE", "DAMAGED", "EXPIRED", "DISPOSAL_PENDING", "UNDETERMINED");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final InventoryService inventory;
    private final ClaimService claims;
    private final WorkItemService workItems;
    private final AuditService audit;
    private final Clock clock;

    public ReturnService(JdbcClient jdbc, AccessGuard guard, InventoryService inventory, ClaimService claims, WorkItemService workItems, AuditService audit,
                         Clock clock) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.inventory = inventory;
        this.claims = claims;
        this.workItems = workItems;
        this.audit = audit;
        this.clock = clock;
    }

    public record LineInput(UUID skuId, Integer quantity, UUID claimItemId, String lotCode, LocalDate expiresOn) {
    }

    public record NewReceipt(UUID nodeId, UUID claimId, UUID businessEntityId, Instant receivedAt, String carrierCode, String trackingNo, String note,
                             List<LineInput> lines) {
    }

    public record Decision(String condition, Integer quantity, String reason) {
    }

    public record LineInspection(UUID receiptLineId, List<Decision> decisions) {
    }

    public record Inspect(List<LineInspection> lines) {
    }

    public record InspectionView(String condition, int quantity, String reason, String source, boolean appliedToStock, Instant inspectedAt) {
    }

    public record LineView(UUID id, int lineNo, UUID skuId, String skuCode, int quantity, UUID claimItemId, boolean matched, UUID stockId, int inspectedQty,
                           List<InspectionView> inspections) {
    }

    public record ReceiptView(UUID id, String receiptNo, UUID nodeId, String nodeName, UUID businessEntityId, String businessEntityName, UUID claimId, String claimNo,
                              UUID orderId, String orderNo, String identification, String status, boolean externalInventory, String inspectionSupport,
                              String carrierCode, String trackingNo, String note, Instant receivedAt, Instant createdAt, List<LineView> lines, int version) {
    }

    // ---------------------------------------------------------------- 입고

    @Transactional
    public ReceiptView receive(NewReceipt r) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        UUID ws = a.workspaceId();
        if (r == null || r.nodeId() == null || r.lines() == null || r.lines().isEmpty()) {
            throw Errors.validation("lines", "입고 거점과 받은 SKU·수량을 입력하세요.");
        }
        Map<String, Object> claim = null;
        UUID be = r.businessEntityId();
        if (r.claimId() != null) {
            claim = claims.lock(ws, r.claimId());
            guard.checkWrite("CLAIM", r.claimId(), (UUID) claim.get("business_entity_id"), (UUID) claim.get("channel_account_id"));
            if ("CANCEL".equals(claim.get("claim_type")) || !ClaimService.OPEN.contains((String) claim.get("status"))) {
                throw Errors.conflict("CLAIM_NOT_RECEIVABLE", "회수 입고를 받을 수 없는 클레임입니다(취소 클레임이거나 종료됨).", Map.of("status", claim.get("status")));
            }
            if (be != null && !be.equals(claim.get("business_entity_id"))) {
                throw Errors.validation("business_entity_id", "클레임의 사업자와 다릅니다.");
            }
            be = (UUID) claim.get("business_entity_id");
        }
        if (be == null) {
            throw Errors.validation("business_entity_id", "원주문을 모르는 반품은 재고 소유 사업자를 지정하세요.");
        }
        guard.checkWrite("BUSINESS_ENTITY", be, be, null);
        guard.checkNode(AccessKind.WRITE, "FULFILLMENT_NODE", r.nodeId(), r.nodeId());
        Map<String, Object> node = jdbc.sql("select * from fulfillment_node where workspace_id = :ws and id = :id").param("ws", ws).param("id", r.nodeId()).query()
                .listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("FULFILLMENT_NODE"));
        boolean external = "EXTERNAL".equals(node.get("inventory_source"));
        Instant receivedAt = r.receivedAt() == null ? clock.instant() : r.receivedAt();
        if (receivedAt.isAfter(clock.instant().plusSeconds(300))) {
            throw Errors.validation("received_at", "입고 시각이 미래입니다.");
        }
        UUID id = Ids.newId();
        String no = number(ws);
        // 줄 판정: 클레임 상품 구성과 SKU 가 맞고 남은 기대 수량 안이면 일치
        Map<String, Integer> remaining = new LinkedHashMap<>();
        Map<UUID, List<UUID>> itemsBySku = new LinkedHashMap<>();
        if (claim != null) {
            for (Map<String, Object> e : jdbc.sql("""
                    select ci.id as claim_item_id, c.component_sku_id, ci.quantity * c.quantity_per_unit as expected,
                           coalesce((select sum(l.quantity) from return_receipt_line l where l.claim_item_id = ci.id and l.sku_id = c.component_sku_id and l.matched), 0) as got
                      from claim_item ci join order_item_component c on c.order_item_id = ci.order_item_id where ci.claim_id = :c order by ci.line_no
                    """).param("c", r.claimId()).query().listOfRows()) {
                UUID ci = (UUID) e.get("claim_item_id");
                UUID sku = (UUID) e.get("component_sku_id");
                remaining.put(ci + "|" + sku, ((Number) e.get("expected")).intValue() - ((Number) e.get("got")).intValue());
                itemsBySku.computeIfAbsent(sku, k -> new ArrayList<>()).add(ci);
            }
        }
        List<Object[]> lines = new ArrayList<>();
        boolean allMatched = claim != null;
        for (LineInput in : r.lines()) {
            if (in == null || in.skuId() == null || in.quantity() == null || in.quantity() < 1) {
                throw Errors.validation("lines", "받은 SKU 와 수량(1 이상)을 확인하세요.");
            }
            if (jdbc.sql("select count(*) from sku where workspace_id = :ws and id = :id").param("ws", ws).param("id", in.skuId()).query(Integer.class).single() == 0) {
                throw Errors.validation("lines", "SKU 를 찾을 수 없습니다.");
            }
            UUID matchItem = null;
            if (claim != null) {
                List<UUID> candidates = in.claimItemId() != null ? List.of(in.claimItemId()) : itemsBySku.getOrDefault(in.skuId(), List.of());
                for (UUID ci : candidates) {
                    Integer left = remaining.get(ci + "|" + in.skuId());
                    if (left != null && left >= in.quantity()) {
                        matchItem = ci;
                        remaining.put(ci + "|" + in.skuId(), left - in.quantity());
                        break;
                    }
                }
            }
            if (matchItem == null) {
                allMatched = false;
            }
            lines.add(new Object[]{in, matchItem});
        }
        String identification = claim == null ? "UNIDENTIFIED" : allMatched ? "MATCHED" : "MISMATCH";
        jdbc.sql("""
                insert into return_receipt (id, workspace_id, receipt_no, node_id, business_entity_id, claim_id, order_id, identification, status, external_inventory,
                                            inspection_support, carrier_code, tracking_no, note, received_at, created_by)
                values (:id, :ws, :no, :n, :be, cast(:c as uuid), cast(:o as uuid), :idf, 'RECEIVED', :ext, :sup, cast(:cc as text), cast(:t as text), cast(:note as text),
                        :at, :by)
                """).param("id", id).param("ws", ws).param("no", no).param("n", r.nodeId()).param("be", be).param("c", r.claimId())
                .param("o", claim == null ? null : claim.get("order_id")).param("idf", identification).param("ext", external)
                .param("sup", external ? "MANUAL_ONLY" : "INTERNAL").param("cc", r.carrierCode() == null ? null : r.carrierCode().trim().toUpperCase(java.util.Locale.ROOT))
                .param("t", ClaimService.trim(r.trackingNo(), 40)).param("note", ClaimService.trim(r.note(), 500)).param("at", Db.ts(receivedAt)).param("by", a.memberId())
                .update();
        int lineNo = 1;
        for (Object[] l : lines) {
            LineInput in = (LineInput) l[0];
            UUID matchItem = (UUID) l[1];
            UUID stockId = null;
            if (!external) {
                // 검수 전에는 판매가능이 아니다(T16). 원주문 불명·불일치는 미확정 보류(T37)
                InventoryService.StockView st = inventory.receive(new InventoryService.Receipt(be, in.skuId(), r.nodeId(), in.quantity(),
                        matchItem != null ? "PENDING_INSPECTION" : "UNDETERMINED", matchItem != null ? null : "UNIDENTIFIED_RETURN", in.lotCode(), null, in.expiresOn(),
                        null, "RETURN_RECEIPT", no, "반품 입고 " + no));
                stockId = st.id();
            }
            jdbc.sql("""
                    insert into return_receipt_line (id, workspace_id, receipt_id, line_no, sku_id, quantity, claim_item_id, matched, stock_id)
                    values (:id, :ws, :r, :ln, :s, :q, cast(:ci as uuid), :m, cast(:st as uuid))
                    """).param("id", Ids.newId()).param("ws", ws).param("r", id).param("ln", lineNo++).param("s", in.skuId()).param("q", in.quantity())
                    .param("ci", matchItem).param("m", matchItem != null).param("st", stockId).update();
            if (matchItem != null) {
                jdbc.sql("update claim_item set received_qty = received_qty + :q, version = version + 1 where id = :id").param("q", in.quantity()).param("id", matchItem)
                        .update();
            }
        }
        if (!"MATCHED".equals(identification)) {
            workItems.raise(new WorkItemService.Raise(ws, be, claim == null ? null : (UUID) claim.get("channel_account_id"), null, "UNIDENTIFIED_RETURN", "HIGH",
                    ("UNIDENTIFIED".equals(identification) ? "원주문 없는 반품 입고: " : "클레임과 다른 반품 입고: ") + no,
                    "UNIDENTIFIED".equals(identification) ? "원주문·클레임을 알 수 없는 반품이 들어왔습니다." : "클레임 상품·수량과 맞지 않는 줄이 있습니다(다른 SKU 또는 수량 초과).",
                    "고객·판매채널에 확인해 원주문을 찾고, 확인되기 전에는 미확정 재고로 두세요. 판매가능 재고로 자동 반영하지 않습니다.", "return:" + id + ":IDENTIFY",
                    Map.of("return_receipt_id", id.toString())));
        }
        if (claim != null) {
            int got = jdbc.sql("select coalesce(sum(received_qty), 0) from claim_item where claim_id = :c").param("c", r.claimId()).query(Integer.class).single();
            if (got > 0 && Set.of("REQUESTED", "IN_TRANSIT").contains((String) claim.get("return_status"))) {
                jdbc.sql("update claim set return_status = 'RECEIVED', version = version + 1, updated_at = now() where id = :id").param("id", r.claimId()).update();
                claims.event(a, r.claimId(), "RETURN_RECEIVED", (String) claim.get("return_status"), "RECEIVED", Map.of("receipt_no", no));
            }
            claims.onReturnReceived(r.claimId());
        }
        audit.record(AuditEntry.builder(ws, "RETURN_RECEIVED", "RETURN_RECEIPT", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, claim == null ? null : (UUID) claim.get("channel_account_id"))
                .after(Map.of("receipt_no", no, "identification", identification, "lines", lines.size(), "external_inventory", external)).build());
        return get(id);
    }

    // ---------------------------------------------------------------- 검수

    @Transactional
    public ReceiptView inspect(UUID receiptId, Inspect in) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        UUID ws = a.workspaceId();
        Map<String, Object> rc = jdbc.sql("select * from return_receipt where workspace_id = :ws and id = :id for update").param("ws", ws).param("id", receiptId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("RETURN_RECEIPT"));
        guard.checkWrite("RETURN_RECEIPT", receiptId, (UUID) rc.get("business_entity_id"), null);
        guard.checkNode(AccessKind.WRITE, "RETURN_RECEIPT", receiptId, (UUID) rc.get("node_id"));
        if (in == null || in.lines() == null || in.lines().isEmpty()) {
            throw Errors.validation("lines", "검수할 줄과 판정을 입력하세요.");
        }
        boolean external = Boolean.TRUE.equals(rc.get("external_inventory"));
        String receiptNo = (String) rc.get("receipt_no");
        Set<UUID> touchedClaimItems = new java.util.LinkedHashSet<>();
        for (LineInspection li : in.lines()) {
            Map<String, Object> line = jdbc.sql("select * from return_receipt_line where receipt_id = :r and id = :id for update").param("r", receiptId)
                    .param("id", li.receiptLineId()).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.validation("receipt_line_id", "이 입고의 줄이 아닙니다."));
            if (!Boolean.TRUE.equals(line.get("matched"))) {
                throw Errors.unprocessable("RETURN_LINE_UNIDENTIFIED", "원주문·클레임과 맞지 않는 줄은 반품 검수로 처리하지 않습니다. 원주문을 확인한 뒤 재고 품질 변경으로 처리하세요.",
                        Map.of("line_no", line.get("line_no")));
            }
            int left = ((Number) line.get("quantity")).intValue() - ((Number) line.get("inspected_qty")).intValue();
            if (left == 0) {
                throw Errors.conflict("RETURN_LINE_INSPECTED", "이미 검수를 마친 줄입니다.", Map.of("line_no", line.get("line_no")));
            }
            int sum = 0;
            for (Decision d : li.decisions() == null ? List.<Decision>of() : li.decisions()) {
                if (d == null || d.condition() == null || !CONDITIONS.contains(d.condition()) || d.quantity() == null || d.quantity() < 1) {
                    throw Errors.validation("decisions", "판정(정상·재포장·불량·파손·만료·폐기대기·미확정)과 수량을 확인하세요.");
                }
                sum += d.quantity();
            }
            if (sum != left) {
                throw Errors.unprocessable("INSPECTION_QUANTITY_MISMATCH", "판정 수량의 합이 입고 수량과 같아야 합니다.", Map.of("line_no", line.get("line_no"),
                        "expected", left, "given", sum));
            }
            UUID stockId = (UUID) line.get("stock_id");
            for (Decision d : li.decisions()) {
                UUID toStock = null;
                String reason = ClaimService.trim(d.reason(), 500);
                if (!external && !"PENDING_INSPECTION".equals(d.condition())) {
                    List<InventoryService.StockView> moved = inventory.changeQuality(new InventoryService.QualityChange(stockId, d.quantity(), d.condition(), null,
                            "반품 검수 " + receiptNo + (reason == null ? "" : " · " + reason)));
                    toStock = moved.get(moved.size() - 1).id();
                }
                jdbc.sql("""
                        insert into return_inspection (id, workspace_id, receipt_line_id, condition, quantity, reason, source, applied_to_stock, to_stock_id, inspected_by)
                        values (:id, :ws, :l, :c, :q, cast(:r as text), :src, :ap, cast(:ts as uuid), :by)
                        """).param("id", Ids.newId()).param("ws", ws).param("l", li.receiptLineId()).param("c", d.condition()).param("q", d.quantity()).param("r", reason)
                        .param("src", external ? "MANUAL" : "INTERNAL").param("ap", !external).param("ts", toStock).param("by", a.memberId()).update();
            }
            jdbc.sql("update return_receipt_line set inspected_qty = quantity where id = :id").param("id", li.receiptLineId()).update();
            UUID ci = (UUID) line.get("claim_item_id");
            jdbc.sql("update claim_item set inspected_qty = inspected_qty + :q, version = version + 1 where id = :id").param("q", left).param("id", ci).update();
            touchedClaimItems.add(ci);
        }
        int open = jdbc.sql("select count(*) from return_receipt_line where receipt_id = :r and matched and inspected_qty < quantity").param("r", receiptId)
                .query(Integer.class).single();
        jdbc.sql("update return_receipt set status = :s, version = version + 1 where id = :id").param("s", open == 0 ? "INSPECTED" : "INSPECTING").param("id", receiptId)
                .update();
        UUID claimId = (UUID) rc.get("claim_id");
        if (claimId != null) {
            updateReturnedQty(claimId);
            int pending = jdbc.sql("select count(*) from claim_item where claim_id = :c and inspected_qty < expected_return_qty").param("c", claimId).query(Integer.class)
                    .single();
            Map<String, Object> c = claims.lock(ws, claimId);
            if (pending == 0 && !"INSPECTED".equals(c.get("return_status"))) {
                jdbc.sql("update claim set return_status = 'INSPECTED', version = version + 1, updated_at = now() where id = :id").param("id", claimId).update();
                claims.event(a, claimId, "RETURN_INSPECTED", (String) c.get("return_status"), "INSPECTED", Map.of("receipt_no", receiptNo));
            }
            claims.refresh(claimId);
        }
        audit.record(AuditEntry.builder(ws, "RETURN_INSPECTED", "RETURN_RECEIPT", receiptId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) rc.get("business_entity_id"), null).after(Map.of("receipt_no", receiptNo, "lines", in.lines().size(), "applied_to_stock", !external)).build());
        return get(receiptId);
    }

    /** 주문상품 반품 확정 수량 = 검수까지 마친 회수 수량(구성품 모두 돌아온 단위), 출고 수량 이하. */
    private void updateReturnedQty(UUID claimId) {
        for (UUID item : jdbc.sql("select order_item_id from claim_item where claim_id = :c").param("c", claimId).query(UUID.class).list()) {
            Integer units = jdbc.sql("""
                    select min(floor(coalesce((select sum(l.inspected_qty) from return_receipt_line l join claim_item ci on ci.id = l.claim_item_id
                                                join claim cl on cl.id = ci.claim_id
                                               where ci.order_item_id = c.order_item_id and l.sku_id = c.component_sku_id and l.matched
                                                 and cl.status not in ('REJECTED', 'CANCELLED')), 0) / c.quantity_per_unit))::int
                      from order_item_component c where c.order_item_id = :i
                    """).param("i", item).query(Integer.class).optional().orElse(null);
            if (units == null) {
                continue;
            }
            jdbc.sql("update order_item set returned_qty = least(shipped_qty, :u), version = version + 1, updated_at = now() where id = :i and returned_qty < least(shipped_qty, :u)")
                    .param("u", units).param("i", item).update();
        }
    }

    // ---------------------------------------------------------------- 조회

    public List<ReceiptView> list(List<String> businessEntityIds, String status, String identification, UUID claimId) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> nodes = guard.nodeFilter(AccessKind.READ, null);
        if (bes.isEmpty() || nodes.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT + " where r.workspace_id = :ws and r.business_entity_id in (:bes) and r.node_id in (:nodes)");
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "bes", bes, "nodes", nodes));
        if (status != null && !status.isBlank()) {
            sql.append(" and r.status = :st");
            p.put("st", status);
        }
        if (identification != null && !identification.isBlank()) {
            sql.append(" and r.identification = :idf");
            p.put("idf", identification);
        }
        if (claimId != null) {
            sql.append(" and r.claim_id = :c");
            p.put("c", claimId);
        }
        sql.append(" order by r.created_at desc limit 300");
        return jdbc.sql(sql.toString()).params(p).query((rs, i) -> view(rs)).list().stream().map(this::withLines).toList();
    }

    public ReceiptView get(UUID id) {
        guard.requirePermission(Perm.INVENTORY_READ);
        ReceiptView v = jdbc.sql(SELECT + " where r.workspace_id = :ws and r.id = :id").param("ws", guard.actor().workspaceId()).param("id", id).query((rs, i) -> view(rs))
                .optional().orElseThrow(() -> Errors.notFound("RETURN_RECEIPT"));
        guard.checkRead("RETURN_RECEIPT", id, v.businessEntityId(), null);
        guard.checkNode(AccessKind.READ, "RETURN_RECEIPT", id, v.nodeId());
        return withLines(v);
    }

    private String number(UUID ws) {
        LocalDate day = LocalDate.now(KST);
        int n = jdbc.sql("""
                insert into claim_number_counter (workspace_id, kind, day, last_value) values (:ws, 'RETURN_RECEIPT', :d, 1)
                on conflict (workspace_id, kind, day) do update set last_value = claim_number_counter.last_value + 1 returning last_value
                """).param("ws", ws).param("d", day).query(Integer.class).single();
        return "R" + day.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")) + "-" + String.format("%05d", n);
    }

    private static final String SELECT = """
            select r.*, n.name as node_name, be.display_name as be_name, c.claim_no, so.order_no from return_receipt r
              join fulfillment_node n on n.id = r.node_id join business_entity be on be.id = r.business_entity_id
              left join claim c on c.id = r.claim_id left join sales_order so on so.id = r.order_id
            """;

    private static ReceiptView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReceiptView(Db.uuid(rs, "id"), rs.getString("receipt_no"), Db.uuid(rs, "node_id"), rs.getString("node_name"), Db.uuid(rs, "business_entity_id"),
                rs.getString("be_name"), Db.uuid(rs, "claim_id"), rs.getString("claim_no"), Db.uuid(rs, "order_id"), rs.getString("order_no"),
                rs.getString("identification"), rs.getString("status"), rs.getBoolean("external_inventory"), rs.getString("inspection_support"),
                rs.getString("carrier_code"), rs.getString("tracking_no"), rs.getString("note"), Db.instant(rs, "received_at"), Db.instant(rs, "created_at"), List.of(),
                rs.getInt("version"));
    }

    private ReceiptView withLines(ReceiptView v) {
        List<LineView> lines = new ArrayList<>();
        for (Map<String, Object> l : jdbc.sql("select l.*, s.sku_code from return_receipt_line l join sku s on s.id = l.sku_id where l.receipt_id = :r order by l.line_no")
                .param("r", v.id()).query().listOfRows()) {
            List<InspectionView> ins = jdbc.sql("select * from return_inspection where receipt_line_id = :l order by inspected_at").param("l", l.get("id"))
                    .query((rs, i) -> new InspectionView(rs.getString("condition"), rs.getInt("quantity"), rs.getString("reason"), rs.getString("source"),
                            rs.getBoolean("applied_to_stock"), Db.instant(rs, "inspected_at"))).list();
            lines.add(new LineView((UUID) l.get("id"), ((Number) l.get("line_no")).intValue(), (UUID) l.get("sku_id"), (String) l.get("sku_code"),
                    ((Number) l.get("quantity")).intValue(), (UUID) l.get("claim_item_id"), Boolean.TRUE.equals(l.get("matched")), (UUID) l.get("stock_id"),
                    ((Number) l.get("inspected_qty")).intValue(), ins));
        }
        return new ReceiptView(v.id(), v.receiptNo(), v.nodeId(), v.nodeName(), v.businessEntityId(), v.businessEntityName(), v.claimId(), v.claimNo(), v.orderId(),
                v.orderNo(), v.identification(), v.status(), v.externalInventory(), v.inspectionSupport(), v.carrierCode(), v.trackingNo(), v.note(), v.receivedAt(),
                v.createdAt(), lines, v.version());
    }
}
