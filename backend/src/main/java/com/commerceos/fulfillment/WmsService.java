package com.commerceos.fulfillment;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.ApiException;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 자체 WMS 기본(M4 O7, FR-INV-08, DLG-147~152).
 * <ul>
 *   <li>웨이브: 자체 WMS 거점의 접수된 출고지시를 묶어, 확보마다 재고 버킷(로케이션·LOT, 유통기한 빠른 순)을 계획한다. 다른 웨이브가 계획한 수량은 다시 계획하지 않는다.</li>
 *   <li>피킹: 로케이션 + 상품 바코드(또는 SKU 코드) 스캔. 다른 로케이션·다른 상품·초과 수량은 차단하고 스캔 기록에 남긴다. 결품은 다른 버킷으로 재계획, 없으면 업무함.</li>
 *   <li>검수·포장: 출고지시 상품을 하나씩 스캔해 수량이 정확히 맞아야 포장 완료(PACKING). 출고 확정은 포장된 출고지시만, 피킹한 버킷 그대로 차감.</li>
 * </ul>
 */
@Service
public class WmsService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Set<String> ACTIVE_FO = Set.of("ACCEPTED", "PICKING", "PACKING");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final ShipmentService shipments;
    private final WorkItemService workItems;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    public WmsService(JdbcClient jdbc, AccessGuard guard, ShipmentService shipments, WorkItemService workItems, AuditService audit, Clock clock,
                      PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.shipments = shipments;
        this.workItems = workItems;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    public record NewWave(UUID nodeId, List<UUID> fulfillmentOrderIds, Integer limit) {
    }

    public record Scan(String locationCode, String barcode, Integer quantity) {
    }

    public record Short(String reason) {
    }

    public record PackScan(String barcode, Integer quantity) {
    }

    public record Pack(List<PackScan> scans) {
    }

    public record TaskView(UUID id, UUID fulfillmentOrderId, String foNo, UUID skuId, String skuCode, String barcode, String locationCode, String lotCode,
                           LocalDate expiresOn, int quantity, int pickedQty, String status, String shortReason) {
    }

    public record FoBrief(UUID id, String foNo, String status, Instant packedAt) {
    }

    public record WaveView(UUID id, String waveNo, UUID nodeId, String nodeName, String status, Instant createdAt, Instant pickedAt, List<FoBrief> orders,
                           List<TaskView> tasks, List<Map<String, Object>> skipped, int version) {
    }

    public record PackLine(UUID skuId, String skuCode, String barcode, int quantity, int picked) {
    }

    public record PackingView(UUID fulfillmentOrderId, String foNo, String status, UUID waveId, String waveNo, boolean pickComplete, Instant packedAt,
                              List<PackLine> lines) {
    }

    public record Candidates(UUID nodeId, int count) {
    }

    // ---------------------------------------------------------------- 웨이브

    public Candidates candidates(UUID nodeId) {
        guard.requirePermission(Perm.INVENTORY_READ);
        guard.checkNode(AccessKind.READ, "FULFILLMENT_NODE", nodeId, nodeId);
        int n = jdbc.sql("""
                select count(*) from fulfillment_order where workspace_id = :ws and node_id = :n and status = 'ACCEPTED' and wave_id is null and business_entity_id in (:bes)
                """).param("ws", guard.actor().workspaceId()).param("n", nodeId).param("bes", writableBes()).query(Integer.class).single();
        return new Candidates(nodeId, n);
    }

    public WaveView createWave(NewWave w) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        if (w == null || w.nodeId() == null) {
            throw Errors.validation("node_id", "출고거점을 선택하세요.");
        }
        guard.checkNode(AccessKind.WRITE, "FULFILLMENT_NODE", w.nodeId(), w.nodeId());
        List<Map<String, Object>> skipped = new ArrayList<>();
        UUID waveId = tx.execute(s -> {
            Map<String, Object> node = jdbc.sql("""
                    select n.*, p.kind from fulfillment_node n join logistics_provider p on p.code = n.logistics_provider_code where n.workspace_id = :ws and n.id = :id
                    """).param("ws", a.workspaceId()).param("id", w.nodeId()).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> Errors.notFound("FULFILLMENT_NODE"));
            if (!"SELF_WMS".equals(node.get("kind")) || !"INTERNAL".equals(node.get("inventory_source"))) {
                throw Errors.unprocessable("WMS_NODE_NOT_SELF", "자체 WMS 거점(내부 원장)에서만 웨이브를 만듭니다. 3PL 거점은 물류사가 피킹합니다.");
            }
            int limit = w.limit() == null ? 50 : Math.max(1, Math.min(200, w.limit()));
            StringBuilder sql = new StringBuilder("""
                    select id, fo_no from fulfillment_order where workspace_id = :ws and node_id = :n and status = 'ACCEPTED' and wave_id is null and business_entity_id in (:bes)
                    """);
            Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", a.workspaceId(), "n", w.nodeId(), "bes", writableBes(), "lim", limit));
            if (w.fulfillmentOrderIds() != null && !w.fulfillmentOrderIds().isEmpty()) {
                sql.append(" and id in (:ids)");
                p.put("ids", w.fulfillmentOrderIds());
            }
            sql.append(" order by accepted_at, fo_no limit :lim for update skip locked");
            List<Map<String, Object>> fos = jdbc.sql(sql.toString()).params(p).query().listOfRows();
            if (fos.isEmpty()) {
                throw Errors.unprocessable("WMS_NOTHING_TO_PICK", "웨이브에 넣을 출고지시가 없습니다(물류 접수 상태의 자체 WMS 출고지시).");
            }
            UUID id = Ids.newId();
            jdbc.sql("insert into wms_wave (id, workspace_id, wave_no, node_id, status, created_by) values (:id, :ws, :no, :n, 'PICKING', cast(:by as uuid))")
                    .param("id", id).param("ws", a.workspaceId()).param("no", number(a.workspaceId())).param("n", w.nodeId()).param("by", a.memberId()).update();
            int planned = 0;
            for (Map<String, Object> f : fos) {
                UUID foId = (UUID) f.get("id");
                Optional<List<Object[]>> plan = planFo(foId, null);
                if (plan.isEmpty()) {
                    skipped.add(Map.of("fo_no", f.get("fo_no"), "reason", "로케이션 재고 버킷이 확보 수량보다 적습니다."));
                    workItems.raise(new WorkItemService.Raise(a.workspaceId(), null, null, null, "WMS_PICK_PLAN_SHORT", "HIGH", "피킹 계획 불가: " + f.get("fo_no"),
                            "확보된 수량만큼 피킹할 로케이션 재고 버킷이 없습니다(다른 웨이브 계획·버킷 불일치).", "재고 검증센터·로케이션 재고를 확인하세요.",
                            "fulfillment:" + foId + ":PICK_PLAN", Map.of("fulfillment_order_id", foId.toString())));
                    continue;
                }
                insertTasks(a.workspaceId(), id, foId, plan.get());
                jdbc.sql("update fulfillment_order set wave_id = :w, version = version + 1, updated_at = now() where id = :id").param("w", id).param("id", foId).update();
                shipments.applyProgress(foId, "PICKING", clock.instant());
                workItems.resolveByKey(a.workspaceId(), "fulfillment:" + foId + ":PICK_PLAN");
                planned++;
            }
            if (planned == 0) {
                throw Errors.unprocessable("WMS_NOTHING_TO_PICK", "피킹을 계획할 수 있는 출고지시가 없습니다.", Map.of("skipped", skipped));
            }
            audit.record(AuditEntry.builder(a.workspaceId(), "WMS_WAVE_CREATED", "WMS_WAVE", id).actor(ActorType.USER, a.memberId(), a.displayName())
                    .after(Map.of("node_id", w.nodeId().toString(), "orders", planned, "skipped", skipped.size())).build());
            return id;
        });
        WaveView v = get(waveId);
        return new WaveView(v.id(), v.waveNo(), v.nodeId(), v.nodeName(), v.status(), v.createdAt(), v.pickedAt(), v.orders(), v.tasks(), skipped, v.version());
    }

    /** 출고지시 확보마다 버킷 계획. exclude = 결품 난 버킷. 계획이 모자라면 빈 값. 행: [reservationId, skuId, stockId, locationId, locationCode, lotId, qty, sortKey] */
    private Optional<List<Object[]>> planFo(UUID foId, UUID exclude) {
        List<Object[]> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("""
                select r.id, r.sku_id, r.owner_business_entity_id, r.node_id,
                       r.quantity - coalesce((select sum(case when t.status in ('OPEN', 'PICKED') then t.quantity when t.status = 'SHORT' then t.picked_qty else 0 end)
                                                from wms_pick_task t where t.reservation_id = r.id), 0) as need, k.sku_code
                  from inventory_reservation r join sku k on k.id = r.sku_id
                 where r.id in (select reservation_id from fulfillment_order_line where fo_id = :f) and r.state in ('ALLOCATED', 'PICKING') order by k.sku_code
                """).param("f", foId).query().listOfRows()) {
            int need = ((Number) r.get("need")).intValue();
            if (need <= 0) {
                continue;
            }
            for (Map<String, Object> b : jdbc.sql("""
                    select s.id, s.location_id, l.code as location_code, s.lot_id, s.on_hand
                           - coalesce((select sum(t.quantity) from wms_pick_task t join fulfillment_order f on f.id = t.fo_id
                                        where t.stock_id = s.id and t.status in ('OPEN', 'PICKED') and f.status in ('ACCEPTED', 'PICKING', 'PACKING')), 0) as free
                      from inventory_stock s left join node_location l on l.id = s.location_id left join inventory_lot lot on lot.id = s.lot_id
                     where s.owner_business_entity_id = :o and s.sku_id = :k and s.node_id = :n and s.quality_status = 'SELLABLE' and s.on_hand > 0
                       and (cast(:x as uuid) is null or s.id <> :x)
                     order by lot.expires_on nulls last, lot.lot_code nulls last, l.code nulls last, s.id for update of s
                    """).param("o", r.get("owner_business_entity_id")).param("k", r.get("sku_id")).param("n", r.get("node_id")).param("x", exclude).query().listOfRows()) {
                if (need == 0) {
                    break;
                }
                int free = ((Number) b.get("free")).intValue();
                if (free <= 0) {
                    continue;
                }
                int take = Math.min(free, need);
                String loc = (String) b.get("location_code");
                out.add(new Object[]{r.get("id"), r.get("sku_id"), b.get("id"), b.get("location_id"), loc, b.get("lot_id"), take,
                        (loc == null ? "~" : loc.toUpperCase(Locale.ROOT)) + "|" + r.get("sku_code")});
                need -= take;
            }
            if (need > 0) {
                return Optional.empty();
            }
        }
        return Optional.of(out);
    }

    private void insertTasks(UUID ws, UUID waveId, UUID foId, List<Object[]> plan) {
        for (Object[] t : plan) {
            jdbc.sql("""
                    insert into wms_pick_task (id, workspace_id, wave_id, fo_id, reservation_id, sku_id, stock_id, location_id, location_code, lot_id, quantity, status, sort_key)
                    values (:id, :ws, :w, :f, :r, :k, :s, cast(:l as uuid), cast(:lc as text), cast(:lot as uuid), :q, 'OPEN', :sk)
                    """).param("id", Ids.newId()).param("ws", ws).param("w", waveId).param("f", foId).param("r", t[0]).param("k", t[1]).param("s", t[2]).param("l", t[3])
                    .param("lc", t[4]).param("lot", t[5]).param("q", t[6]).param("sk", t[7]).update();
        }
    }

    // ---------------------------------------------------------------- 피킹 스캔

    public WaveView pick(UUID waveId, Scan scan) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        String barcode = scan == null || scan.barcode() == null ? "" : scan.barcode().trim();
        String location = scan == null || scan.locationCode() == null || scan.locationCode().isBlank() ? null : scan.locationCode().trim();
        int qty = scan == null || scan.quantity() == null ? 1 : scan.quantity();
        try {
            tx.executeWithoutResult(s -> {
                Map<String, Object> w = lockWave(a, waveId);
                if (!"PICKING".equals(w.get("status"))) {
                    throw Errors.conflict("WAVE_NOT_PICKING", "피킹 중인 웨이브가 아닙니다.", Map.of("status", w.get("status")));
                }
                if (barcode.isEmpty() || qty < 1) {
                    throw reject("상품 바코드와 수량(1 이상)을 스캔하세요.");
                }
                UUID sku = resolveSku(a.workspaceId(), barcode).orElseThrow(() -> reject("알 수 없는 바코드입니다: " + barcode));
                List<Map<String, Object>> tasks = jdbc.sql("""
                        select t.* from wms_pick_task t join fulfillment_order f on f.id = t.fo_id
                         where t.wave_id = :w and t.sku_id = :k and t.status = 'OPEN' and f.status in ('PICKING') order by t.sort_key, t.created_at for update of t
                        """).param("w", waveId).param("k", sku).query().listOfRows();
                if (tasks.isEmpty()) {
                    throw reject("이 웨이브에서 피킹할 상품이 아닙니다(또는 이미 다 피킹했습니다).");
                }
                List<Map<String, Object>> here = tasks.stream().filter(t -> location == null ? t.get("location_code") == null
                        : location.equalsIgnoreCase(String.valueOf(t.get("location_code")))).toList();
                if (here.isEmpty()) {
                    String expected = tasks.stream().map(t -> t.get("location_code") == null ? "로케이션 없음" : (String) t.get("location_code")).distinct().reduce((x, y) -> x + ", " + y)
                            .orElse("");
                    throw reject("로케이션이 다릅니다. 피킹 위치: " + expected);
                }
                int remaining = here.stream().mapToInt(t -> ((Number) t.get("quantity")).intValue() - ((Number) t.get("picked_qty")).intValue()).sum();
                if (qty > remaining) {
                    throw reject("피킹 수량을 넘었습니다(남은 수량 " + remaining + ").");
                }
                int left = qty;
                for (Map<String, Object> t : here) {
                    if (left == 0) {
                        break;
                    }
                    int room = ((Number) t.get("quantity")).intValue() - ((Number) t.get("picked_qty")).intValue();
                    int take = Math.min(room, left);
                    jdbc.sql("""
                            update wms_pick_task set picked_qty = picked_qty + :q, status = case when picked_qty + :q = quantity then 'PICKED' else status end,
                                   version = version + 1, updated_at = now() where id = :id
                            """).param("q", take).param("id", t.get("id")).update();
                    left -= take;
                }
                logScan(a, "PICK", waveId, null, location, barcode, qty, "OK", null);
                refreshWave(waveId);
            });
        } catch (ApiException e) {
            if ("SCAN_REJECTED".equals(e.code())) {
                tx.executeWithoutResult(s -> logScan(a, "PICK", waveId, null, location, barcode.isEmpty() ? "-" : barcode, qty, "REJECTED", e.getMessage()));
            }
            throw e;
        }
        return get(waveId);
    }

    /** 결품: 남은 수량을 다른 버킷으로 재계획, 없으면 업무함. 결품 난 버킷은 실사 대상 업무로. */
    public WaveView shortPick(UUID taskId, Short in) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        String reason = in == null || in.reason() == null || in.reason().isBlank() ? null : in.reason().trim();
        if (reason == null || reason.length() > 500) {
            throw Errors.validation("reason", "결품 사유를 입력하세요.");
        }
        UUID waveId = tx.execute(s -> {
            Map<String, Object> t = jdbc.sql("select * from wms_pick_task where workspace_id = :ws and id = :id for update").param("ws", a.workspaceId()).param("id", taskId)
                    .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("WMS_PICK_TASK"));
            UUID wave = (UUID) t.get("wave_id");
            lockWave(a, wave);
            if (!"OPEN".equals(t.get("status"))) {
                throw Errors.conflict("PICK_TASK_NOT_OPEN", "진행 중인 피킹 작업이 아닙니다.", Map.of("status", t.get("status")));
            }
            UUID foId = (UUID) t.get("fo_id");
            UUID stockId = (UUID) t.get("stock_id");
            jdbc.sql("update wms_pick_task set status = 'SHORT', short_reason = :r, version = version + 1, updated_at = now() where id = :id").param("r", reason)
                    .param("id", taskId).update();
            workItems.raise(new WorkItemService.Raise(a.workspaceId(), null, null, null, "WMS_LOCATION_DISCREPANCY", "MEDIUM",
                    "로케이션 재고 차이 의심: " + (t.get("location_code") == null ? "로케이션 없음" : t.get("location_code")),
                    "피킹 중 결품(" + reason + ")이 났습니다. 시스템 수량과 실물이 다를 수 있습니다.", "해당 로케이션을 실사해 차이를 조정하세요.",
                    "stock:" + stockId + ":DISCREPANCY", Map.of("stock_id", stockId.toString())));
            Optional<List<Object[]>> plan = planFo(foId, stockId);
            if (plan.isPresent() && !plan.get().isEmpty()) {
                insertTasks(a.workspaceId(), wave, foId, plan.get());
            } else if (plan.isEmpty()) {
                String foNo = jdbc.sql("select fo_no from fulfillment_order where id = :f").param("f", foId).query(String.class).single();
                workItems.raise(new WorkItemService.Raise(a.workspaceId(), null, null, null, "WMS_PICK_SHORT", "HIGH", "결품으로 피킹 불가: " + foNo,
                        "다른 로케이션에도 피킹할 재고가 없습니다.", "재고를 확인해 보충하거나 출고지시를 취소(피킹분 원위치 확인)하고 고객에게 안내하세요.",
                        "fulfillment:" + foId + ":PICK_SHORT", Map.of("fulfillment_order_id", foId.toString())));
            }
            refreshWave(wave);
            return wave;
        });
        return get(waveId);
    }

    // ---------------------------------------------------------------- 검수·포장

    public PackingView packing(UUID foId) {
        guard.requirePermission(Perm.INVENTORY_READ);
        CurrentActor a = guard.actor();
        Map<String, Object> f = jdbc.sql("select f.*, w.wave_no from fulfillment_order f left join wms_wave w on w.id = f.wave_id where f.workspace_id = :ws and f.id = :id")
                .param("ws", a.workspaceId()).param("id", foId).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("FULFILLMENT_ORDER"));
        guard.checkRead("FULFILLMENT_ORDER", foId, (UUID) f.get("business_entity_id"), null);
        guard.checkNode(AccessKind.READ, "FULFILLMENT_ORDER", foId, (UUID) f.get("node_id"));
        List<PackLine> lines = jdbc.sql("""
                select l.sku_id, k.sku_code, k.barcode, sum(l.quantity) as qty,
                       coalesce((select sum(t.picked_qty) from wms_pick_task t where t.fo_id = :f and t.sku_id = l.sku_id and t.status in ('PICKED', 'SHORT', 'OPEN')), 0) as picked
                  from fulfillment_order_line l join sku k on k.id = l.sku_id where l.fo_id = :f group by l.sku_id, k.sku_code, k.barcode order by k.sku_code
                """).param("f", foId).query((rs, i) -> new PackLine(Db.uuid(rs, "sku_id"), rs.getString("sku_code"), rs.getString("barcode"), rs.getInt("qty"),
                rs.getInt("picked"))).list();
        int open = jdbc.sql("select count(*) from wms_pick_task where fo_id = :f and status = 'OPEN'").param("f", foId).query(Integer.class).single();
        boolean complete = f.get("wave_id") != null && open == 0 && lines.stream().allMatch(l -> l.picked() == l.quantity());
        return new PackingView(foId, (String) f.get("fo_no"), (String) f.get("status"), (UUID) f.get("wave_id"), (String) f.get("wave_no"), complete,
                ShipmentService.instant(f.get("packed_at")), lines);
    }

    public PackingView pack(UUID foId, Pack in) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        List<PackScan> scans = in == null || in.scans() == null ? List.of() : in.scans();
        try {
            tx.executeWithoutResult(s -> {
                Map<String, Object> f = jdbc.sql("select * from fulfillment_order where workspace_id = :ws and id = :id for update").param("ws", a.workspaceId())
                        .param("id", foId).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("FULFILLMENT_ORDER"));
                guard.checkWrite("FULFILLMENT_ORDER", foId, (UUID) f.get("business_entity_id"), null);
                guard.checkNode(AccessKind.WRITE, "FULFILLMENT_ORDER", foId, (UUID) f.get("node_id"));
                if (f.get("wave_id") == null || !"PICKING".equals(f.get("status"))) {
                    throw Errors.conflict("WMS_NOT_PICKING", "웨이브 피킹 중인 출고지시만 포장할 수 있습니다.", Map.of("status", f.get("status")));
                }
                PackingView v = packing(foId);
                if (!v.pickComplete()) {
                    throw Errors.conflict("WMS_PICK_INCOMPLETE", "피킹이 끝나지 않았습니다.", Map.of("lines", v.lines()));
                }
                Map<UUID, Integer> scanned = new TreeMap<>();
                for (PackScan p : scans) {
                    if (p == null || p.barcode() == null || p.barcode().isBlank()) {
                        throw reject("바코드를 스캔하세요.");
                    }
                    int q = p.quantity() == null ? 1 : p.quantity();
                    UUID sku = resolveSku(a.workspaceId(), p.barcode().trim()).orElseThrow(() -> reject("알 수 없는 바코드입니다: " + p.barcode().trim()));
                    scanned.merge(sku, q, Integer::sum);
                }
                Map<UUID, Integer> expected = new TreeMap<>();
                v.lines().forEach(l -> expected.put(l.skuId(), l.quantity()));
                if (!scanned.equals(expected)) {
                    List<String> diff = new ArrayList<>();
                    for (PackLine l : v.lines()) {
                        int got = scanned.getOrDefault(l.skuId(), 0);
                        if (got != l.quantity()) {
                            diff.add(l.skuCode() + " " + got + "/" + l.quantity());
                        }
                    }
                    if (scanned.keySet().stream().anyMatch(k -> !expected.containsKey(k))) {
                        diff.add("출고지시에 없는 상품 포함");
                    }
                    throw reject("검수 수량이 맞지 않습니다: " + String.join(", ", diff));
                }
                for (PackScan p : scans) {
                    logScan(a, "PACK", (UUID) f.get("wave_id"), foId, null, p.barcode().trim(), p.quantity() == null ? 1 : p.quantity(), "OK", null);
                }
                jdbc.sql("update fulfillment_order set packed_at = now(), packed_by = cast(:by as uuid), version = version + 1, updated_at = now() where id = :id")
                        .param("by", a.memberId()).param("id", foId).update();
                shipments.applyProgress(foId, "PACKING", clock.instant());
                audit.record(AuditEntry.builder(a.workspaceId(), "WMS_PACKED", "FULFILLMENT_ORDER", foId).actor(ActorType.USER, a.memberId(), a.displayName())
                        .scope((UUID) f.get("business_entity_id"), (UUID) f.get("channel_account_id")).after(Map.of("fo_no", f.get("fo_no"), "scans", scans.size())).build());
            });
        } catch (ApiException e) {
            if ("SCAN_REJECTED".equals(e.code())) {
                tx.executeWithoutResult(s -> logScan(a, "PACK", null, foId, null, scans.isEmpty() ? "-" : String.valueOf(scans.get(0).barcode()), 0, "REJECTED", e.getMessage()));
            }
            throw e;
        }
        return packing(foId);
    }

    // ---------------------------------------------------------------- 조회

    public List<WaveView> list(UUID nodeId, String status) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> nodes = guard.nodeFilter(AccessKind.READ, nodeId);
        if (nodes.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("select id from wms_wave where workspace_id = :ws and node_id in (:nodes)");
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "nodes", nodes));
        if (status != null && !status.isBlank()) {
            sql.append(" and status = :s");
            p.put("s", status);
        }
        sql.append(" order by created_at desc limit 100");
        return jdbc.sql(sql.toString()).params(p).query(UUID.class).list().stream().map(this::view).toList();
    }

    public WaveView get(UUID id) {
        guard.requirePermission(Perm.INVENTORY_READ);
        UUID node = jdbc.sql("select node_id from wms_wave where workspace_id = :ws and id = :id").param("ws", guard.actor().workspaceId()).param("id", id).query(UUID.class)
                .optional().orElseThrow(() -> Errors.notFound("WMS_WAVE"));
        guard.checkNode(AccessKind.READ, "WMS_WAVE", id, node);
        return view(id);
    }

    private WaveView view(UUID id) {
        Map<String, Object> w = jdbc.sql("select w.*, n.name as node_name from wms_wave w join fulfillment_node n on n.id = w.node_id where w.id = :id").param("id", id)
                .query().listOfRows().get(0);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, null);
        List<FoBrief> orders = jdbc.sql("select id, fo_no, status, packed_at from fulfillment_order where wave_id = :w and business_entity_id in (:bes) order by fo_no")
                .param("w", id).param("bes", bes.isEmpty() ? Set.of(UUID.randomUUID()) : bes)
                .query((rs, i) -> new FoBrief(Db.uuid(rs, "id"), rs.getString("fo_no"), rs.getString("status"), Db.instant(rs, "packed_at"))).list();
        Set<UUID> visible = new java.util.HashSet<>();
        orders.forEach(o -> visible.add(o.id()));
        List<TaskView> tasks = jdbc.sql("""
                select t.*, f.fo_no, k.sku_code, k.barcode, lot.lot_code, lot.expires_on from wms_pick_task t join fulfillment_order f on f.id = t.fo_id join sku k on k.id = t.sku_id
                  left join inventory_lot lot on lot.id = t.lot_id where t.wave_id = :w order by t.sort_key, t.created_at
                """).param("w", id).query((rs, i) -> new TaskView(Db.uuid(rs, "id"), Db.uuid(rs, "fo_id"), rs.getString("fo_no"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"),
                rs.getString("barcode"), rs.getString("location_code"), rs.getString("lot_code"), rs.getObject("expires_on", LocalDate.class), rs.getInt("quantity"),
                rs.getInt("picked_qty"), rs.getString("status"), rs.getString("short_reason"))).list().stream().filter(t -> visible.contains(t.fulfillmentOrderId())).toList();
        return new WaveView(id, (String) w.get("wave_no"), (UUID) w.get("node_id"), (String) w.get("node_name"), (String) w.get("status"),
                ShipmentService.instant(w.get("created_at")), ShipmentService.instant(w.get("picked_at")), orders, tasks, List.of(), ((Number) w.get("version")).intValue());
    }

    // ---------------------------------------------------------------- 내부

    private void refreshWave(UUID waveId) {
        jdbc.sql("""
                update wms_wave set status = 'PICKED', picked_at = now(), version = version + 1 where id = :w and status = 'PICKING'
                   and not exists (select 1 from wms_pick_task t join fulfillment_order f on f.id = t.fo_id where t.wave_id = :w and t.status = 'OPEN' and f.status = 'PICKING')
                """).param("w", waveId).update();
    }

    private Map<String, Object> lockWave(CurrentActor a, UUID waveId) {
        Map<String, Object> w = jdbc.sql("select * from wms_wave where workspace_id = :ws and id = :id for update").param("ws", a.workspaceId()).param("id", waveId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("WMS_WAVE"));
        guard.checkNode(AccessKind.WRITE, "WMS_WAVE", waveId, (UUID) w.get("node_id"));
        return w;
    }

    private Optional<UUID> resolveSku(UUID ws, String barcode) {
        return jdbc.sql("select id from sku where workspace_id = :ws and (upper(sku_code) = upper(:b) or barcode = :b) order by (barcode = :b) desc limit 1")
                .param("ws", ws).param("b", barcode).query(UUID.class).optional();
    }

    private Set<UUID> writableBes() {
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.WRITE, null);
        return bes.isEmpty() ? Set.of(UUID.randomUUID()) : bes;
    }

    private static ApiException reject(String message) {
        return Errors.unprocessable("SCAN_REJECTED", message);
    }

    private void logScan(CurrentActor a, String kind, UUID waveId, UUID foId, String location, String barcode, int qty, String result, String reason) {
        jdbc.sql("""
                insert into wms_scan (id, workspace_id, kind, wave_id, fo_id, location_code, barcode, quantity, result, reason, actor_member_id)
                values (:id, :ws, :k, cast(:w as uuid), cast(:f as uuid), cast(:l as text), :b, :q, :r, cast(:re as text), cast(:m as uuid))
                """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("k", kind).param("w", waveId).param("f", foId).param("l", location)
                .param("b", barcode.length() > 80 ? barcode.substring(0, 80) : barcode).param("q", qty).param("r", result).param("re", reason).param("m", a.memberId()).update();
    }

    private String number(UUID ws) {
        LocalDate day = LocalDate.now(KST);
        int n = jdbc.sql("""
                insert into claim_number_counter (workspace_id, kind, day, last_value) values (:ws, 'WMS_WAVE', :d, 1)
                on conflict (workspace_id, kind, day) do update set last_value = claim_number_counter.last_value + 1 returning last_value
                """).param("ws", ws).param("d", day).query(Integer.class).single();
        return "W" + day.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")) + "-" + String.format("%04d", n);
    }
}
