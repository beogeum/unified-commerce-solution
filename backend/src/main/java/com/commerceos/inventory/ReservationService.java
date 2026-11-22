package com.commerceos.inventory;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.inventory.InventoryLedger.Position;
import com.commerceos.inventory.InventoryLedger.Stock;
import com.commerceos.inventory.InventoryLedger.Txn;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.db.TenantContext;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재고 확보(M3 I3, docs/04 §4). 한 예약은 한 시점에 한 단계만 가진다: RESERVED → ALLOCATED → PICKING → CONSUMED, 활성 단계에서 RELEASED.
 * 확보수량(committed)은 예약 생성 때 한 번 늘고 소진·해제 때 한 번 준다. 할당·피킹은 단계만 바꾼다(T06: ATP 중복 차감 0).
 * 여러 줄(가상묶음 부품 등)은 전부 확보하거나 전부 실패한다. 소유 사업자·SKU 집계를 id 순으로 잠가 동시 확보를 직렬화한다(T07).
 */
@Service
public class ReservationService {
    static final Set<String> ACTIVE = Set.of("RESERVED", "ALLOCATED", "PICKING");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final InventoryLedger ledger;
    private final InventoryService inventory;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private final CountLocks locks;

    public ReservationService(JdbcClient jdbc, AccessGuard guard, InventoryLedger ledger, InventoryService inventory, Clock clock, PlatformTransactionManager tm,
                              AuditService audit, CountLocks locks) {
        this.audit = audit;
        this.locks = locks;
        this.jdbc = jdbc;
        this.guard = guard;
        this.ledger = ledger;
        this.inventory = inventory;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    public record Line(UUID skuId, Integer quantity) {
    }

    public record Reserve(UUID businessEntityId, UUID nodeId, String demandType, String demandRef, List<Line> lines, Instant expiresAt) {
    }

    public record Transition(String reason, Boolean pickReturnConfirmed) {
    }

    public record ExternalAck(Instant acknowledgedAt, String externalRef) {
    }

    public record ReleaseByDemand(String demandType, String demandRef, String reason, Boolean pickReturnConfirmed) {
    }

    public record ReservationView(UUID id, UUID groupId, UUID businessEntityId, String businessEntityName, UUID skuId, String skuCode, UUID nodeId, String nodeCode,
                                  int quantity, String state, String demandType, String demandRef, Instant expiresAt, Instant externalAckAt,
                                  String externalRef, Instant consumedAt, Instant createdAt, Instant updatedAt, int version) {
    }

    public record Filter(List<String> businessEntityIds, UUID nodeId, UUID skuId, String state, String demandType, String demandRef) {
    }

    // ---------------------------------------------------------------- 확보

    @Transactional
    public List<ReservationView> reserve(Reserve r) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        if (r.businessEntityId() == null || r.nodeId() == null) {
            throw Errors.validation("business_entity_id", "소유 사업자와 거점을 지정하세요.");
        }
        guard.checkWrite("BUSINESS_ENTITY", r.businessEntityId(), r.businessEntityId(), null);
        String demandType = r.demandType() == null ? "" : r.demandType().trim().toUpperCase();
        if (!demandType.matches("^[A-Z][A-Z0-9_]{1,40}$")) {
            throw Errors.validation("demand_type", "수요 유형 코드를 입력하세요(예: ORDER_LINE).");
        }
        String demandRef = InventoryService.trimOrNull(r.demandRef());
        if (demandRef == null || demandRef.length() > 200) {
            throw Errors.validation("demand_ref", "수요 참조를 200자 이내로 입력하세요.");
        }
        if (r.lines() == null || r.lines().isEmpty() || r.lines().size() > 100) {
            throw Errors.validation("lines", "확보할 SKU 를 1~100줄 입력하세요.");
        }
        if (r.expiresAt() != null && !r.expiresAt().isAfter(clock.instant())) {
            throw Errors.validation("expires_at", "만료 시각은 현재 이후여야 합니다.");
        }
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        for (Line l : r.lines()) {
            if (l.skuId() == null) {
                throw Errors.validation("lines.sku_id", "SKU 를 지정하세요.");
            }
            if (wanted.put(l.skuId(), InventoryService.positive(l.quantity())) != null) {
                throw Errors.validation("lines.sku_id", "같은 SKU 를 두 줄로 나눌 수 없습니다.");
            }
            inventory.skuInfo(l.skuId());
        }
        NodeService.NodeView node = inventory.requireUsableNode(r.nodeId(), r.businessEntityId());

        // 잠금: 이 사업자·SKU 들의 모든 거점 집계(id 순). 대상 거점 집계가 없으면 먼저 만든 뒤 함께 잠근다
        for (UUID sku : wanted.keySet()) {
            jdbc.sql("""
                    insert into inventory_position (id, workspace_id, owner_business_entity_id, sku_id, node_id) values (:id, :ws, :o, :s, :n)
                    on conflict (owner_business_entity_id, sku_id, node_id) do nothing
                    """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("o", r.businessEntityId()).param("s", sku).param("n", node.id()).update();
        }
        List<Position> locked = ledger.lockPositions(a.workspaceId(), r.businessEntityId(), wanted.keySet());
        // (잠금 후 확인 — 같은 수요의 동시 재시도도 직렬화된다) 같은 수요의 활성 예약이 이미 있으면: 모두 같으면 재시도로 보고 그대로 돌려준다, 하나라도 다르면 충돌(부분 확보 금지)
        List<ReservationView> existing = jdbc.sql(SELECT + " where r.workspace_id = :ws and r.demand_type = :dt and r.demand_ref = :dr and r.state in ('RESERVED','ALLOCATED','PICKING')")
                .param("ws", a.workspaceId()).param("dt", demandType).param("dr", demandRef).query((rs, i) -> view(rs)).list();
        if (!existing.isEmpty()) {
            boolean same = existing.size() == wanted.size() && existing.stream().allMatch(e -> e.businessEntityId().equals(r.businessEntityId())
                    && e.nodeId().equals(r.nodeId()) && Integer.valueOf(e.quantity()).equals(wanted.get(e.skuId())));
            if (same) {
                return existing;
            }
            throw Errors.conflict("RESERVATION_EXISTS", "이 수요에는 이미 다른 내용의 확보가 있습니다. 먼저 해제한 뒤 다시 확보하세요.",
                    Map.of("existing", existing.stream().map(e -> Map.of("reservation_id", e.id().toString(), "sku_id", e.skuId().toString(),
                            "quantity", e.quantity(), "state", e.state())).toList()));
        }

        Map<UUID, Position> atNode = new HashMap<>();
        Map<UUID, Integer> totalAtp = new HashMap<>();
        for (Position p : locked) {
            totalAtp.merge(p.skuId(), p.atp(), Integer::sum);
            if (p.nodeId().equals(node.id())) {
                atNode.put(p.skuId(), p);
            }
        }
        List<Map<String, Object>> shortages = new ArrayList<>();
        for (var e : wanted.entrySet()) {
            Position p = atNode.get(e.getKey());
            int nodeAtp = p == null ? 0 : p.atp();
            int total = Math.max(0, totalAtp.getOrDefault(e.getKey(), 0) - inventory.safetyStock(a.workspaceId(), r.businessEntityId(), e.getKey()));
            int available = Math.min(nodeAtp, total);
            if (available < e.getValue()) {
                shortages.add(Map.of("sku_id", e.getKey().toString(), "requested", e.getValue(), "available", Math.max(0, available),
                        "node_atp", nodeAtp, "total_atp_after_safety_stock", total));
            }
        }
        if (!shortages.isEmpty()) {
            throw Errors.conflict("INSUFFICIENT_AVAILABLE_STOCK", "판매 가능 재고가 부족해 확보하지 못했습니다(일부만 확보하지 않음).", Map.of("lines", shortages));
        }
        UUID group = Ids.newId();
        List<UUID> ids = new ArrayList<>();
        for (var e : wanted.entrySet()) {
            Position p = atNode.get(e.getKey());
            UUID id = Ids.newId();
            try {
                jdbc.sql("""
                    insert into inventory_reservation (id, workspace_id, position_id, owner_business_entity_id, sku_id, node_id, quantity, state, demand_type,
                        demand_ref, group_id, expires_at, created_by)
                    values (:id, :ws, :p, :o, :s, :n, :q, 'RESERVED', :dt, :dr, :g, :exp, :by)
                    """).param("id", id).param("ws", a.workspaceId()).param("p", p.id()).param("o", p.ownerId()).param("s", p.skuId()).param("n", p.nodeId())
                    .param("q", e.getValue()).param("dt", demandType).param("dr", demandRef).param("g", group)
                    .param("exp", r.expiresAt() == null ? null : Db.ts(r.expiresAt())).param("by", a.memberId()).update();
            } catch (DuplicateKeyException ex) {
                throw Errors.conflict("RESERVATION_EXISTS", "이 수요에는 이미 진행 중인 확보가 있습니다.");
            }
            Position after = ledger.applyToPosition(p, 0, e.getValue());
            ledger.record(a.workspaceId(), new Txn("RESERVE", after, null, null, 0, 0, e.getValue(), id, null, "RESERVED", demandType, demandRef, null, a.memberId()));
            inventory.availabilityChanged(a.workspaceId(), after);
            ids.add(id);
        }
        return ids.stream().map(this::get).toList();
    }

    /**
     * 재고가 모자라면 예외 대신 빈 값(출고 배정이 다음 거점을 시도할 수 있게). 같은 빈 안에서 reserve 를 직접 불러
     * 트랜잭션 프록시를 거치지 않으므로, 실패해도 바깥 트랜잭션이 롤백 전용으로 표시되지 않는다.
     */
    @Transactional
    public java.util.Optional<List<ReservationView>> tryReserve(Reserve r) {
        try {
            return java.util.Optional.of(reserve(r));
        } catch (com.commerceos.shared.error.ApiException e) {
            if ("INSUFFICIENT_AVAILABLE_STOCK".equals(e.code()) || "STOCK_COUNT_LOCKED".equals(e.code())) {
                return java.util.Optional.empty();
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------- 단계 전환

    @Transactional
    public ReservationView allocate(UUID id, Transition t) {
        return step(id, "RESERVED", "ALLOCATED", "ALLOCATE", t, true);
    }

    @Transactional
    public ReservationView pick(UUID id, Transition t) {
        return step(id, "ALLOCATED", "PICKING", "PICK", t, false);
    }

    /** 출고 실물확정: 판매가능 실물 −Q(유통기한 빠른 LOT 부터), 확보 −Q. ATP 는 변하지 않는다. */
    @Transactional
    public ReservationView consume(UUID id, Transition t) {
        return consumeAt(id, t, null);
    }

    /**
     * 출고 실물확정 — 실물이 나간 시각(물류사 출고 시각)을 함께 남긴다(M4 O5). 외부 재고 스냅샷은 이 시각과 기준시각을 비교해
     * '이미 출고분이 반영된 외부 수량'에서 다시 빼지 않는다(CLAUDE.md 3PL 이중 차감 금지, DLG-130). 미래 시각은 지금으로 맞춘다.
     */
    @Transactional
    public ReservationView consumeAt(UUID id, Transition t, Instant physicalAt) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        Row row = lockRow(a.workspaceId(), id);
        if ("CONSUMED".equals(row.state())) {
            return get(id);
        }
        if (!"PICKING".equals(row.state())) {
            throw invalidState(row.state(), "CONSUMED");
        }
        Position p = ledger.lockPositionById(a.workspaceId(), row.positionId());
        Row r = lockReservation(a.workspaceId(), id);
        int remaining = r.quantity();
        List<Stock> all = ledger.lockSellableBucketsFefo(p);
        List<Stock> buckets = locks.unlocked(a.workspaceId(), p, all);
        if (buckets.stream().mapToInt(Stock::onHand).sum() < r.quantity() && all.stream().mapToInt(Stock::onHand).sum() >= r.quantity()) {
            throw CountLocks.locked(locks.first(a.workspaceId(), p).orElseThrow(), "출고 확정");
        }
        List<Object[]> takes = new ArrayList<>();
        for (Stock s : buckets) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(remaining, s.onHand());
            takes.add(new Object[]{s, take});
            remaining -= take;
        }
        if (remaining > 0) {
            // 집계와 버킷이 어긋난 상태(검증센터 대상). 덮어쓰지 않고 실패시킨다
            throw Errors.conflict("STOCK_BUCKET_MISMATCH", "판매가능 버킷 수량이 확보수량보다 적습니다. 재고 검증 후 다시 시도하세요.",
                    Map.of("quantity", r.quantity(), "shortage", remaining));
        }
        Instant now = clock.instant();
        Instant at = physicalAt == null || physicalAt.isAfter(now) ? now : physicalAt;
        jdbc.sql("update inventory_reservation set state = 'CONSUMED', consumed_at = :at, version = version + 1, updated_at = now() where id = :id")
                .param("at", Db.ts(at)).param("id", id).update();
        Position cur = p;
        boolean first = true;
        String reason = InventoryService.trimOrNull(t == null ? null : t.reason());
        for (Object[] take : takes) {
            Stock s = (Stock) take[0];
            int q = (Integer) take[1];
            ledger.removeFromBucket(s.id(), q);
            // 확보 감소는 첫 줄에 한 번만 적는다(원장 합계 = 확보수량)
            int committedDelta = first ? -r.quantity() : 0;
            cur = ledger.applyToPosition(cur, -q, committedDelta);
            ledger.record(a.workspaceId(), new Txn("CONSUME", cur, s.id(), "SELLABLE", -q, -q, committedDelta, id, first ? "PICKING" : null,
                    first ? "CONSUMED" : null, r.demandType(), r.demandRef(), reason, a.memberId()));
            first = false;
        }
        return get(id);
    }

    /**
     * 물류사가 출고지시를 접수한 시각 기록(I4). 외부 재고 스냅샷의 기준시각(워터마크)과 비교해, 그 전에 접수된 확보는
     * 물류사 '가용' 수량에 이미 반영된 것으로 본다(T08). 할당·피킹 단계에서 한 번만 기록하며 바꾸지 않는다.
     * M4 출고요청 연동이 이 값을 채우고, 그 전에는 물류사 화면·파일로 확인한 담당자가 기록한다.
     */
    @Transactional
    public ReservationView acknowledgeExternal(UUID id, ExternalAck ack) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        if (ack == null || ack.acknowledgedAt() == null) {
            throw Errors.validation("acknowledged_at", "물류사 접수 시각을 입력하세요.");
        }
        if (ack.acknowledgedAt().isAfter(clock.instant().plusSeconds(300))) {
            throw Errors.validation("acknowledged_at", "접수 시각이 현재보다 미래일 수 없습니다.");
        }
        String ref = InventoryService.trimOrNull(ack.externalRef());
        if (ref != null && ref.length() > 200) {
            throw Errors.validation("external_ref", "외부 참조는 200자 이내입니다.");
        }
        Row row = lockRow(a.workspaceId(), id);
        Position p = ledger.lockPositionById(a.workspaceId(), row.positionId());
        Row r = lockReservation(a.workspaceId(), id);
        ReservationView cur = get(id);
        if (cur.externalAckAt() != null) {
            if (cur.externalAckAt().equals(ack.acknowledgedAt()) && java.util.Objects.equals(cur.externalRef(), ref)) {
                return cur;
            }
            throw Errors.conflict("EXTERNAL_ACK_EXISTS", "이미 물류사 접수 시각이 기록되어 있습니다(바꿀 수 없음).",
                    Map.of("external_ack_at", cur.externalAckAt().toString()));
        }
        if (!"ALLOCATED".equals(r.state()) && !"PICKING".equals(r.state())) {
            throw invalidState(r.state(), "EXTERNAL_ACK");
        }
        jdbc.sql("update inventory_reservation set external_ack_at = :at, external_ref = :ref, version = version + 1, updated_at = now() where id = :id")
                .param("at", Db.ts(ack.acknowledgedAt())).param("ref", ref).param("id", id).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "INVENTORY_RESERVATION_EXTERNAL_ACK", "INVENTORY_RESERVATION", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(p.ownerId(), null)
                .after(Map.of("external_ack_at", ack.acknowledgedAt().toString(), "external_ref", String.valueOf(ref), "state", r.state())).build());
        return get(id);
    }

    /** 활성 단계에서 해제(확보 −Q, ATP +Q). 피킹 중 해제는 작업 복귀(출고 중지) 확인이 필요하다. */
    @Transactional
    public ReservationView release(UUID id, Transition t) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        return doRelease(a, id, t == null ? null : t.reason(), t != null && Boolean.TRUE.equals(t.pickReturnConfirmed()), true);
    }

    @Transactional
    public List<ReservationView> releaseByDemand(ReleaseByDemand req) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        if (req.demandType() == null || req.demandRef() == null) {
            throw Errors.validation("demand_ref", "수요 유형과 참조를 입력하세요.");
        }
        List<UUID> ids = jdbc.sql("""
                select id from inventory_reservation where workspace_id = :ws and demand_type = :dt and demand_ref = :dr and state in ('RESERVED','ALLOCATED','PICKING')
                 order by position_id
                """).param("ws", a.workspaceId()).param("dt", req.demandType().trim().toUpperCase()).param("dr", req.demandRef().trim()).query(UUID.class).list();
        boolean confirmed = Boolean.TRUE.equals(req.pickReturnConfirmed());
        // 피킹 중인 줄이 있는데 확인이 없으면 전체를 거절(일부만 해제하지 않음)
        for (UUID id : ids) {
            Row row = row(a.workspaceId(), id);
            guard.checkWrite("INVENTORY_RESERVATION", id, row.ownerId(), null);
            guard.checkNode(AccessKind.WRITE, "INVENTORY_RESERVATION", id, row.nodeId());
            if ("PICKING".equals(row.state()) && !confirmed) {
                throw pickReturnRequired();
            }
        }
        return ids.stream().map(id -> doRelease(a, id, req.reason(), confirmed, true)).toList();
    }

    /** 만료 시각이 지난 RESERVED 예약 자동 해제(할당 이후 단계는 만료로 풀지 않는다). @return 해제 건수 */
    public int releaseExpired() {
        return TenantContext.asSystem(() -> {
            List<Map<String, Object>> due = jdbc.sql("select workspace_id, id from inventory_reservation where state = 'RESERVED' and expires_at <= :now order by id limit 500")
                    .param("now", Db.ts(clock.instant())).query().listOfRows();
            int n = 0;
            for (Map<String, Object> d : due) {
                UUID ws = (UUID) d.get("workspace_id");
                UUID id = (UUID) d.get("id");
                Boolean done = tx.execute(s -> {
                    Row row = lockRow(ws, id);
                    if (!"RESERVED".equals(row.state())) {
                        return false;
                    }
                    Position p = ledger.lockPositionById(ws, row.positionId());
                    Row r = lockReservation(ws, id);
                    if (!"RESERVED".equals(r.state()) || r.expiresAt() == null || r.expiresAt().isAfter(clock.instant())) {
                        return false;
                    }
                    jdbc.sql("update inventory_reservation set state = 'RELEASED', version = version + 1, updated_at = now() where id = :id").param("id", id).update();
                    Position after = ledger.applyToPosition(p, 0, -r.quantity());
                    ledger.record(ws, new Txn("RELEASE", after, null, null, 0, 0, -r.quantity(), id, "RESERVED", "RELEASED", r.demandType(), r.demandRef(),
                            "확보 만료 자동 해제", null));
                    inventory.availabilityChanged(ws, after);
                    return true;
                });
                if (Boolean.TRUE.equals(done)) {
                    n++;
                }
            }
            return n;
        });
    }

    @Scheduled(fixedDelayString = "${commerce.inventory.reservation-expiry-interval:PT1M}", initialDelayString = "${commerce.inventory.reservation-expiry-initial-delay:PT1M}")
    public void scheduledExpiry() {
        releaseExpired();
    }

    // ---------------------------------------------------------------- 조회

    public ReservationView get(UUID id) {
        guard.requirePermission(Perm.INVENTORY_READ);
        ReservationView v = jdbc.sql(SELECT + " where r.workspace_id = :ws and r.id = :id").param("ws", guard.actor().workspaceId()).param("id", id)
                .query((rs, i) -> view(rs)).optional().orElseThrow(() -> Errors.notFound("INVENTORY_RESERVATION"));
        guard.checkRead("INVENTORY_RESERVATION", id, v.businessEntityId(), null);
        guard.checkNode(AccessKind.READ, "INVENTORY_RESERVATION", id, v.nodeId());
        return v;
    }

    public List<ReservationView> list(Filter f) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, f.businessEntityIds());
        if (bes.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT + " where r.workspace_id = :ws and r.owner_business_entity_id in (:bes) and r.node_id in (:nodes)");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ws", guard.actor().workspaceId());
        params.put("bes", bes);
        params.put("nodes", inventory.visibleNodes(f.nodeId()));
        if (f.skuId() != null) {
            sql.append(" and r.sku_id = :sku");
            params.put("sku", f.skuId());
        }
        if ("ACTIVE".equalsIgnoreCase(f.state())) {
            sql.append(" and r.state in ('RESERVED','ALLOCATED','PICKING')");
        } else if (f.state() != null && !f.state().isBlank()) {
            sql.append(" and r.state = :st");
            params.put("st", f.state());
        }
        if (f.demandType() != null && !f.demandType().isBlank()) {
            sql.append(" and r.demand_type = :dt");
            params.put("dt", f.demandType().trim().toUpperCase());
        }
        if (f.demandRef() != null && !f.demandRef().isBlank()) {
            sql.append(" and r.demand_ref = :dr");
            params.put("dr", f.demandRef().trim());
        }
        sql.append(" order by r.id desc limit 500");
        return jdbc.sql(sql.toString()).params(params).query((rs, i) -> view(rs)).list();
    }

    // ---------------------------------------------------------------- 내부

    record Row(UUID id, UUID positionId, UUID ownerId, UUID nodeId, int quantity, String state, String demandType, String demandRef, Instant expiresAt) {
    }

    private ReservationView step(UUID id, String from, String to, String txnType, Transition t, boolean checkNode) {
        guard.requirePermission(Perm.INVENTORY_OPERATE);
        CurrentActor a = guard.actor();
        Row row = lockRow(a.workspaceId(), id);
        if (to.equals(row.state())) {
            return get(id);
        }
        if (!from.equals(row.state())) {
            throw invalidState(row.state(), to);
        }
        if (checkNode) {
            // 허용 거점/소유자 재검사(docs/04 §4 RESERVED → ALLOCATED)
            inventory.requireUsableNode(row.nodeId(), row.ownerId());
        }
        Position p = ledger.lockPositionById(a.workspaceId(), row.positionId());
        Row r = lockReservation(a.workspaceId(), id);
        if (!from.equals(r.state())) {
            throw invalidState(r.state(), to);
        }
        // 실사 잠금(CONF-012): 잠기지 않은 실물로 채울 수 없으면 출고 배정·피킹을 막는다
        locks.requireUnlockedQuantity(a.workspaceId(), p, r.quantity(), "ALLOCATE".equals(txnType) ? "출고 배정" : "피킹");
        jdbc.sql("update inventory_reservation set state = :to, version = version + 1, updated_at = now() where id = :id").param("to", to).param("id", id).update();
        ledger.record(a.workspaceId(), new Txn(txnType, p, null, null, 0, 0, 0, id, from, to, r.demandType(), r.demandRef(),
                InventoryService.trimOrNull(t == null ? null : t.reason()), a.memberId()));
        return get(id);
    }

    private ReservationView doRelease(CurrentActor a, UUID id, String reason, boolean pickReturnConfirmed, boolean publish) {
        Row row = lockRow(a.workspaceId(), id);
        if ("RELEASED".equals(row.state())) {
            return get(id);
        }
        if (!ACTIVE.contains(row.state())) {
            throw invalidState(row.state(), "RELEASED");
        }
        if ("PICKING".equals(row.state()) && !pickReturnConfirmed) {
            throw pickReturnRequired();
        }
        Position p = ledger.lockPositionById(a.workspaceId(), row.positionId());
        Row r = lockReservation(a.workspaceId(), id);
        if (!ACTIVE.contains(r.state())) {
            throw invalidState(r.state(), "RELEASED");
        }
        jdbc.sql("update inventory_reservation set state = 'RELEASED', version = version + 1, updated_at = now() where id = :id").param("id", id).update();
        Position after = ledger.applyToPosition(p, 0, -r.quantity());
        ledger.record(a.workspaceId(), new Txn("RELEASE", after, null, null, 0, 0, -r.quantity(), id, r.state(), "RELEASED", r.demandType(), r.demandRef(),
                InventoryService.trimOrNull(reason), a.memberId()));
        if (publish) {
            inventory.availabilityChanged(a.workspaceId(), after);
        }
        return get(id);
    }

    /** 잠금 없이 읽고 권한 확인(잠금 순서 position → reservation 을 지키기 위해 먼저 위치만 파악). */
    private Row lockRow(UUID ws, UUID id) {
        Row row = row(ws, id);
        if (TenantContext.isSystem()) {
            return row;
        }
        guard.checkWrite("INVENTORY_RESERVATION", id, row.ownerId(), null);
        guard.checkNode(AccessKind.WRITE, "INVENTORY_RESERVATION", id, row.nodeId());
        return row;
    }

    private Row row(UUID ws, UUID id) {
        return jdbc.sql("select * from inventory_reservation where workspace_id = :ws and id = :id").param("ws", ws).param("id", id).query((rs, i) -> rowMap(rs))
                .optional().orElseThrow(() -> Errors.notFound("INVENTORY_RESERVATION"));
    }

    private Row lockReservation(UUID ws, UUID id) {
        return jdbc.sql("select * from inventory_reservation where workspace_id = :ws and id = :id for update").param("ws", ws).param("id", id)
                .query((rs, i) -> rowMap(rs)).single();
    }

    private static Row rowMap(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(Db.uuid(rs, "id"), Db.uuid(rs, "position_id"), Db.uuid(rs, "owner_business_entity_id"), Db.uuid(rs, "node_id"), rs.getInt("quantity"),
                rs.getString("state"), rs.getString("demand_type"), rs.getString("demand_ref"), Db.instant(rs, "expires_at"));
    }

    private static RuntimeException invalidState(String current, String target) {
        return Errors.conflict("RESERVATION_STATE_INVALID", "현재 단계에서는 이 처리를 할 수 없습니다.", Map.of("state", current, "target", target));
    }

    private static RuntimeException pickReturnRequired() {
        return Errors.unprocessable("PICK_RETURN_CONFIRMATION_REQUIRED", "피킹 중인 확보는 작업 복귀(출고 중지)를 확인한 뒤에만 해제할 수 있습니다.");
    }

    private static final String SELECT = """
            select r.*, be.display_name as be_name, s.sku_code, n.code as node_code from inventory_reservation r
              join business_entity be on be.id = r.owner_business_entity_id join sku s on s.id = r.sku_id join fulfillment_node n on n.id = r.node_id
            """;

    private static ReservationView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReservationView(Db.uuid(rs, "id"), Db.uuid(rs, "group_id"), Db.uuid(rs, "owner_business_entity_id"), rs.getString("be_name"),
                Db.uuid(rs, "sku_id"), rs.getString("sku_code"), Db.uuid(rs, "node_id"), rs.getString("node_code"), rs.getInt("quantity"), rs.getString("state"),
                rs.getString("demand_type"), rs.getString("demand_ref"), Db.instant(rs, "expires_at"), Db.instant(rs, "external_ack_at"),
                rs.getString("external_ref"), Db.instant(rs, "consumed_at"), Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"), rs.getInt("version"));
    }
}
