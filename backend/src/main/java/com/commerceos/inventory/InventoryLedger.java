package com.commerceos.inventory;

import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 저수준 연산(같은 모듈 내부 전용). 모든 변화는 집계(position)·버킷(stock)·원장(transaction)을 한 트랜잭션에서 함께 바꾼다.
 * 잠금 순서: position(id 오름차순) → stock. 호출자는 항상 이 순서를 지켜 교착을 피한다.
 */
@Component
class InventoryLedger {
    private final JdbcClient jdbc;

    InventoryLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** restrictedHold: 외부 재고 판매 제한 중 판매에서 빼 둔 수량(품절 버퍼). ATP 는 0 아래로 내려가지 않는다. */
    record Position(UUID id, UUID ownerId, UUID skuId, UUID nodeId, int sellableOnHand, int committed, int restrictedHold) {
        int atp() {
            return Math.max(0, sellableOnHand - committed - restrictedHold);
        }
    }

    record Bucket(UUID ownerId, UUID skuId, UUID nodeId, UUID locationId, UUID lotId, String quality, String holdReason) {
    }

    record Stock(UUID id, Bucket bucket, int onHand) {
    }

    /** 원장 1행. 실물(onHand)·판매가능(sellable)·확보(committed) 변화를 따로 적는다. */
    record Txn(String type, Position position, UUID stockId, String quality, int onHandDelta, int sellableDelta, int committedDelta,
               UUID reservationId, String stateFrom, String stateTo, String referenceType, String referenceId, String reason, UUID actorMemberId) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Position lockPosition(UUID ws, UUID ownerId, UUID skuId, UUID nodeId) {
        jdbc.sql("""
                insert into inventory_position (id, workspace_id, owner_business_entity_id, sku_id, node_id) values (:id, :ws, :o, :s, :n)
                on conflict (owner_business_entity_id, sku_id, node_id) do nothing
                """).param("id", Ids.newId()).param("ws", ws).param("o", ownerId).param("s", skuId).param("n", nodeId).update();
        return jdbc.sql("select * from inventory_position where workspace_id = :ws and owner_business_entity_id = :o and sku_id = :s and node_id = :n for update")
                .param("ws", ws).param("o", ownerId).param("s", skuId).param("n", nodeId).query(this::position).single();
    }

    /** 집계 행이 없으면 만든다(잠그지 않음 — 이후 lockPositions 로 id 순 잠금). */
    @Transactional(propagation = Propagation.MANDATORY)
    void ensurePositions(UUID ws, UUID ownerId, java.util.Collection<UUID> skuIds, UUID nodeId) {
        for (UUID sku : skuIds) {
            jdbc.sql("""
                    insert into inventory_position (id, workspace_id, owner_business_entity_id, sku_id, node_id) values (:id, :ws, :o, :s, :n)
                    on conflict (owner_business_entity_id, sku_id, node_id) do nothing
                    """).param("id", Ids.newId()).param("ws", ws).param("o", ownerId).param("s", sku).param("n", nodeId).update();
        }
    }

    /** 소유 사업자의 SKU 들에 대한 모든 거점 집계를 id 순으로 잠근다(동시 예약 직렬화, T07). */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Position> lockPositions(UUID ws, UUID ownerId, Collection<UUID> skuIds) {
        return jdbc.sql("""
                select * from inventory_position where workspace_id = :ws and owner_business_entity_id = :o and sku_id in (:s)
                 order by id for update
                """).param("ws", ws).param("o", ownerId).param("s", skuIds).query(this::position).list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Position lockPositionById(UUID ws, UUID positionId) {
        return jdbc.sql("select * from inventory_position where workspace_id = :ws and id = :id for update").param("ws", ws).param("id", positionId)
                .query(this::position).single();
    }

    /** 집계 변경. committed ≤ sellable 위반은 DB 제약이 최종 방어(과확보 시 409). */
    @Transactional(propagation = Propagation.MANDATORY)
    Position applyToPosition(Position p, int sellableDelta, int committedDelta) {
        if (sellableDelta == 0 && committedDelta == 0) {
            return p;
        }
        try {
            jdbc.sql("""
                    update inventory_position set sellable_on_hand = sellable_on_hand + :sd, committed = committed + :cd, version = version + 1, updated_at = now()
                     where id = :id
                    """).param("sd", sellableDelta).param("cd", committedDelta).param("id", p.id()).update();
        } catch (DataIntegrityViolationException e) {
            throw Errors.conflict("INSUFFICIENT_AVAILABLE_STOCK", "판매 가능 재고가 확보수량보다 적어질 수 없습니다.",
                    Map.of("sellable_on_hand", p.sellableOnHand(), "committed", p.committed()));
        }
        return new Position(p.id(), p.ownerId(), p.skuId(), p.nodeId(), p.sellableOnHand() + sellableDelta, p.committed() + committedDelta, p.restrictedHold());
    }

    /** 버킷에 수량을 더한다(없으면 만든다). @return 버킷 id */
    @Transactional(propagation = Propagation.MANDATORY)
    UUID addToBucket(UUID ws, Bucket b, int qty) {
        return jdbc.sql("""
                insert into inventory_stock (id, workspace_id, owner_business_entity_id, sku_id, node_id, location_id, lot_id, quality_status, hold_reason, on_hand)
                values (:id, :ws, :o, :s, :n, cast(:loc as uuid), cast(:lot as uuid), :q, cast(:h as text), :qty)
                on conflict (owner_business_entity_id, sku_id, node_id, coalesce(location_id, '00000000-0000-0000-0000-000000000000'),
                             coalesce(lot_id, '00000000-0000-0000-0000-000000000000'), quality_status, coalesce(hold_reason, ''))
                do update set on_hand = inventory_stock.on_hand + excluded.on_hand, version = inventory_stock.version + 1, updated_at = now()
                returning id
                """).param("id", Ids.newId()).param("ws", ws).param("o", b.ownerId()).param("s", b.skuId()).param("n", b.nodeId())
                .param("loc", b.locationId()).param("lot", b.lotId())
                .param("q", b.quality()).param("h", b.holdReason()).param("qty", qty)
                .query(UUID.class).single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void removeFromBucket(UUID stockId, int qty) {
        int n = jdbc.sql("update inventory_stock set on_hand = on_hand - :q, version = version + 1, updated_at = now() where id = :id and on_hand >= :q")
                .param("q", qty).param("id", stockId).update();
        if (n != 1) {
            throw Errors.conflict("STOCK_INSUFFICIENT", "재고 버킷 수량이 부족합니다.");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Stock lockStock(UUID ws, UUID stockId) {
        return jdbc.sql("select * from inventory_stock where workspace_id = :ws and id = :id for update").param("ws", ws).param("id", stockId)
                .query((rs, i) -> stock(rs)).optional().orElseThrow(() -> Errors.notFound("INVENTORY_STOCK"));
    }

    /** 버킷 키로 찾아 잠근다(없으면 빈 값). */
    @Transactional(propagation = Propagation.MANDATORY)
    java.util.Optional<Stock> findBucket(UUID ws, Bucket b) {
        return jdbc.sql("""
                select * from inventory_stock where workspace_id = :ws and owner_business_entity_id = :o and sku_id = :s and node_id = :n
                   and location_id is not distinct from cast(:loc as uuid) and lot_id is not distinct from cast(:lot as uuid) and quality_status = :q
                   and hold_reason is not distinct from cast(:h as text)
                 for update
                """).param("ws", ws).param("o", b.ownerId()).param("s", b.skuId()).param("n", b.nodeId()).param("loc", b.locationId()).param("lot", b.lotId())
                .param("q", b.quality()).param("h", b.holdReason()).query((rs, i) -> stock(rs)).optional();
    }

    /**
     * 버킷 수량을 delta 만큼 바꾸고 집계·원장을 함께 기록한다(조정·이동 공통). 판매가능 감소가 확보수량을 침범하면 409 STOCK_COMMITTED.
     * 호출자는 집계(position)를 먼저 잠가 둔다. @return 바뀐 집계
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Position changeBucket(UUID ws, Position p, Bucket b, int delta, String txnType, String referenceType, String referenceId, String reason, UUID actor) {
        boolean sellable = "SELLABLE".equals(b.quality());
        if (sellable && delta < 0 && p.sellableOnHand() + delta < p.committed()) {
            throw Errors.conflict("STOCK_COMMITTED", "확보(예약·할당·피킹)된 수량은 줄일 수 없습니다. 먼저 확보를 해제하거나 다른 거점으로 배정하세요.",
                    Map.of("sku_id", p.skuId().toString(), "sellable_on_hand", p.sellableOnHand(), "committed", p.committed(), "requested", delta));
        }
        UUID stockId;
        if (delta > 0) {
            stockId = addToBucket(ws, b, delta);
        } else {
            Stock s = findBucket(ws, b).orElseThrow(() -> Errors.conflict("STOCK_INSUFFICIENT", "재고 버킷 수량이 부족합니다."));
            removeFromBucket(s.id(), -delta);
            stockId = s.id();
        }
        Position after = applyToPosition(p, sellable ? delta : 0, 0);
        record(ws, new Txn(txnType, after, stockId, b.quality(), delta, sellable ? delta : 0, 0, null, null, null, referenceType, referenceId, reason, actor));
        return after;
    }

    /** 판매 가능 버킷을 선입선출(유통기한 빠른 LOT 먼저 — FEFO) 순서로 잠근다. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Stock> lockSellableBucketsFefo(Position p) {
        return jdbc.sql("""
                select s.* from inventory_stock s left join inventory_lot l on l.id = s.lot_id
                 where s.owner_business_entity_id = :o and s.sku_id = :sku and s.node_id = :n and s.quality_status = 'SELLABLE' and s.on_hand > 0
                 order by l.expires_on nulls last, l.lot_code nulls last, s.id
                 for update of s
                """).param("o", p.ownerId()).param("sku", p.skuId()).param("n", p.nodeId()).query((rs, i) -> stock(rs)).list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    UUID record(UUID ws, Txn t) {
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into inventory_transaction (id, workspace_id, owner_business_entity_id, sku_id, node_id, stock_id, position_id, reservation_id, txn_type,
                    quality_status, on_hand_delta, sellable_delta, committed_delta, reservation_state_from, reservation_state_to, reference_type, reference_id,
                    reason, actor_member_id)
                values (:id, :ws, :o, :s, :n, cast(:st as uuid), :p, cast(:r as uuid), :t, cast(:q as text), :oh, :sd, :cd, cast(:sf as text), cast(:sto as text),
                        cast(:rt as text), cast(:rid as text), cast(:reason as text), cast(:actor as uuid))
                """).param("id", id).param("ws", ws).param("o", t.position().ownerId()).param("s", t.position().skuId()).param("n", t.position().nodeId())
                .param("st", t.stockId()).param("p", t.position().id()).param("r", t.reservationId()).param("t", t.type()).param("q", t.quality())
                .param("oh", t.onHandDelta()).param("sd", t.sellableDelta()).param("cd", t.committedDelta()).param("sf", t.stateFrom()).param("sto", t.stateTo())
                .param("rt", t.referenceType()).param("rid", t.referenceId()).param("reason", t.reason()).param("actor", t.actorMemberId()).update();
        return id;
    }

    private Position position(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Position(Db.uuid(rs, "id"), Db.uuid(rs, "owner_business_entity_id"), Db.uuid(rs, "sku_id"), Db.uuid(rs, "node_id"),
                rs.getInt("sellable_on_hand"), rs.getInt("committed"), rs.getInt("restricted_hold"));
    }

    private Stock stock(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Stock(Db.uuid(rs, "id"), new Bucket(Db.uuid(rs, "owner_business_entity_id"), Db.uuid(rs, "sku_id"), Db.uuid(rs, "node_id"),
                Db.uuid(rs, "location_id"), Db.uuid(rs, "lot_id"), rs.getString("quality_status"), rs.getString("hold_reason")), rs.getInt("on_hand"));
    }
}
