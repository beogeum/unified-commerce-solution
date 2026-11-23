package com.commerceos.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 운영그룹 합계의 사업자 간 내부거래 제거(DLG-033·175, T35). 조회 범위에 보낸·받은 사업자가 모두 있을 때만 적용한다.
 * <ul>
 *   <li>내부 매출 제거: 순매출 −R(기간 내 내부 매출 공급가).</li>
 *   <li>매출원가: −R + Δ미실현이익. 미실현이익 = 받은 사업자가 아직 보유한 이전 재고에 담긴 내부 이익(이전 단가 − 보낸 사업자 원가).
 *       받은 사업자의 이동평균과 같은 방식으로, 보유 수량이 줄 때 같은 비율로 줄어든다(재고 원장 재생).</li>
 * </ul>
 * 결과: 모두 외부 판매되면 그룹 매출원가 = 보낸 사업자 원가, 보유 중이면 그룹 손익 영향 0. 사업자별 행은 바꾸지 않는다.
 */
@Component
public class GroupElimination {
    static final Set<String> INFLOW = Set.of("RECEIPT", "ADJUSTMENT", "EXTERNAL_SYNC", "ASSEMBLY_IN", "OWNERSHIP_TRANSFER_IN");
    static final Set<String> OUTFLOW = Set.of("CONSUME", "DISPOSAL", "ADJUSTMENT", "EXTERNAL_SYNC", "ASSEMBLY_OUT", "OWNERSHIP_TRANSFER_OUT");
    private final JdbcClient jdbc;

    public GroupElimination(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Elimination(BigDecimal internalSales, BigDecimal unrealizedAtStart, BigDecimal unrealizedAtEnd, BigDecimal cogsAdjustment, String note) {
    }

    /** @return 범위 안 내부거래가 없으면 null */
    public Elimination compute(UUID ws, Set<UUID> bes, LocalDate from, LocalDate to) {
        if (bes.size() < 2) {
            return null;
        }
        List<Map<String, Object>> transfers = jdbc.sql("""
                select id, to_business_entity_id, sku_id, quantity, unit_price, unit_cost, completed_at from intercompany_transfer
                 where workspace_id = :ws and status = 'COMPLETED' and from_business_entity_id in (:bes) and to_business_entity_id in (:bes)
                   and completed_at < (cast(:t as date) + 1)::timestamp at time zone 'Asia/Seoul'
                """).param("ws", ws).param("bes", bes).param("t", to).query().listOfRows();
        if (transfers.isEmpty()) {
            return null;
        }
        BigDecimal r = jdbc.sql("""
                select coalesce(sum(supply_amount), 0) from financial_entry where workspace_id = :ws and intercompany and category = 'SALES'
                   and business_entity_id in (:bes) and counterparty_business_entity_id in (:bes) and period_date between :f and :t
                """).param("ws", ws).param("bes", bes).param("f", from).param("t", to).query(BigDecimal.class).single();
        Map<String, BigDecimal> markup = new HashMap<>();
        Map<String, List<Object>> pairs = new HashMap<>();
        for (Map<String, Object> t : transfers) {
            int q = ((Number) t.get("quantity")).intValue();
            BigDecimal price = (BigDecimal) t.get("unit_price");
            BigDecimal cost = t.get("unit_cost") == null ? BigDecimal.ZERO : (BigDecimal) t.get("unit_cost");
            markup.put(t.get("id").toString(), price.subtract(cost).multiply(BigDecimal.valueOf(q)));
            pairs.computeIfAbsent(t.get("to_business_entity_id") + "|" + t.get("sku_id"), k -> new ArrayList<>()).add(t);
        }
        BigDecimal start = BigDecimal.ZERO;
        BigDecimal end = BigDecimal.ZERO;
        for (String key : pairs.keySet()) {
            String[] k = key.split("\\|");
            start = start.add(unrealized(ws, UUID.fromString(k[0]), UUID.fromString(k[1]), markup, from.minusDays(1)));
            end = end.add(unrealized(ws, UUID.fromString(k[0]), UUID.fromString(k[1]), markup, to));
        }
        start = start.setScale(0, RoundingMode.HALF_UP);
        end = end.setScale(0, RoundingMode.HALF_UP);
        BigDecimal cogs = end.subtract(start).subtract(r);
        return new Elimination(r, start, end, cogs, "운영그룹 합계에서 사업자 간 내부 매출 " + r.toPlainString() + "원을 빼고, 매출원가를 " + cogs.toPlainString()
                + "원 조정했습니다(받은 사업자가 보유 중인 이전 재고의 내부 이익 " + end.toPlainString() + "원은 손익에서 제외). 사업자별 행은 그대로입니다.");
    }

    /** d 일 종료 시점에 받은 사업자가 보유한 이전 재고의 내부 이익(이동평균 비례 감소). */
    BigDecimal unrealized(UUID ws, UUID owner, UUID sku, Map<String, BigDecimal> markup, LocalDate d) {
        BigDecimal pool = BigDecimal.ZERO;
        long onHand = 0;
        for (Map<String, Object> t : jdbc.sql("""
                select txn_type, on_hand_delta, reference_type, reference_id from inventory_transaction
                 where workspace_id = :ws and owner_business_entity_id = :o and sku_id = :s and on_hand_delta <> 0
                   and occurred_at < (cast(:d as date) + 1)::timestamp at time zone 'Asia/Seoul'
                 order by occurred_at, id
                """).param("ws", ws).param("o", owner).param("s", sku).param("d", d).query().listOfRows()) {
            String type = (String) t.get("txn_type");
            int delta = ((Number) t.get("on_hand_delta")).intValue();
            if (delta > 0 && INFLOW.contains(type)) {
                if ("OWNERSHIP_TRANSFER_IN".equals(type) && markup.containsKey(String.valueOf(t.get("reference_id")))) {
                    pool = pool.add(markup.get(String.valueOf(t.get("reference_id"))));
                }
                onHand += delta;
            } else if (delta < 0 && OUTFLOW.contains(type)) {
                long out = Math.min(-delta, onHand);
                if (onHand > 0) {
                    pool = pool.subtract(pool.multiply(BigDecimal.valueOf(out)).divide(BigDecimal.valueOf(onHand), 8, RoundingMode.HALF_UP));
                }
                onHand = Math.max(0, onHand + delta);
            }
        }
        return pool;
    }
}
