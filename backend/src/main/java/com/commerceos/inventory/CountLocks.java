package com.commerceos.inventory;

import com.commerceos.inventory.InventoryLedger.Bucket;
import com.commerceos.inventory.InventoryLedger.Position;
import com.commerceos.inventory.InventoryLedger.Stock;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 실사 잠금(CONF-012). 카운트 중(COUNTING)인 실사의 범위에 드는 버킷은 기준 스냅샷부터 카운트 제출까지
 * 입고 적치·품질 변경·조정·출고 배정·피킹·출고 확정·이동 출고를 막는다. 소유 사업자별 실사이므로 다른 사업자 재고는 잠그지 않는다.
 */
@Component
class CountLocks {
    private final JdbcClient jdbc;

    CountLocks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record ActiveCount(UUID id, String countNo, String scope, String zone, Set<UUID> skuIds) {
        boolean covers(UUID skuId, String locationZone) {
            return switch (scope) {
                case "ALL" -> true;
                case "SKU" -> skuIds.contains(skuId);
                case "ZONE" -> zone.equals(locationZone);
                default -> false;
            };
        }
    }

    List<ActiveCount> active(UUID ws, UUID ownerId, UUID nodeId) {
        return jdbc.sql("""
                select id, count_no, scope, zone, sku_ids from stock_count where workspace_id = :ws and business_entity_id = :o and node_id = :n and status = 'COUNTING'
                """).param("ws", ws).param("o", ownerId).param("n", nodeId).query((rs, i) -> {
                    Array arr = rs.getArray("sku_ids");
                    Set<UUID> skus = arr == null ? Set.of() : Arrays.stream((Object[]) arr.getArray()).map(o -> (UUID) o).collect(Collectors.toSet());
                    return new ActiveCount(Db.uuid(rs, "id"), rs.getString("count_no"), rs.getString("scope"), rs.getString("zone"), skus);
                }).list();
    }

    /** 특정 버킷(또는 입고 위치)이 잠겨 있으면 409. opKo 는 사용자 안내에 쓰는 작업 이름. */
    void requireUnlocked(UUID ws, UUID ownerId, UUID nodeId, UUID skuId, UUID locationId, String opKo) {
        List<ActiveCount> counts = active(ws, ownerId, nodeId);
        if (counts.isEmpty()) {
            return;
        }
        String zone = zoneOf(locationId);
        for (ActiveCount c : counts) {
            if (c.covers(skuId, zone)) {
                throw locked(c, opKo);
            }
        }
    }

    boolean isLocked(List<ActiveCount> counts, Bucket b) {
        if (counts.isEmpty()) {
            return false;
        }
        String zone = zoneOf(b.locationId());
        return counts.stream().anyMatch(c -> c.covers(b.skuId(), zone));
    }

    /** 집계의 판매가능 실물 중 잠긴 버킷 수량. */
    int lockedSellable(UUID ws, Position p) {
        List<ActiveCount> counts = active(ws, p.ownerId(), p.nodeId());
        if (counts.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                select s.location_id, s.on_hand, l.zone from inventory_stock s left join node_location l on l.id = s.location_id
                 where s.owner_business_entity_id = :o and s.sku_id = :sku and s.node_id = :n and s.quality_status = 'SELLABLE' and s.on_hand > 0
                """).param("o", p.ownerId()).param("sku", p.skuId()).param("n", p.nodeId()).query().listOfRows().stream()
                .filter(r -> counts.stream().anyMatch(c -> c.covers(p.skuId(), (String) r.get("zone"))))
                .mapToInt(r -> ((Number) r.get("on_hand")).intValue()).sum();
    }

    /** 출고 배정·피킹: 잠기지 않은 판매가능 실물이 확보 수량보다 적으면 거부. */
    void requireUnlockedQuantity(UUID ws, Position p, int quantity, String opKo) {
        int locked = lockedSellable(ws, p);
        if (locked > 0 && p.sellableOnHand() - locked < quantity) {
            ActiveCount c = active(ws, p.ownerId(), p.nodeId()).get(0);
            throw locked(c, opKo);
        }
    }

    List<Stock> unlocked(UUID ws, Position p, List<Stock> buckets) {
        List<ActiveCount> counts = active(ws, p.ownerId(), p.nodeId());
        return counts.isEmpty() ? buckets : buckets.stream().filter(s -> !isLocked(counts, s.bucket())).toList();
    }

    Optional<ActiveCount> first(UUID ws, Position p) {
        return active(ws, p.ownerId(), p.nodeId()).stream().findFirst();
    }

    static RuntimeException locked(ActiveCount c, String opKo) {
        return Errors.conflict("STOCK_COUNT_LOCKED", "실사 " + c.countNo() + " 카운트 중인 재고라 " + opKo + "할 수 없습니다. 카운트 제출 후 다시 시도하세요.",
                Map.of("count_id", c.id().toString(), "count_no", c.countNo()));
    }

    private String zoneOf(UUID locationId) {
        if (locationId == null) {
            return null;
        }
        return jdbc.sql("select zone from node_location where id = :id").param("id", locationId).query(String.class).optional().orElse(null);
    }
}
