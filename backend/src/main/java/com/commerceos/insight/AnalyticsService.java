package com.commerceos.insight;

import com.commerceos.finance.UnitCosts;
import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.Perm;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 운영 리포트(M6 1차, docs/14 §2·§3, DLG-177·178): 재고(가용·비가용·재고일수·장기재고·만료 임박·장부가), 판매 KPI(주문·순판매수량·취소/반품/교환율),
 * 물류(거점별 출고·분할출고·리드타임·SLA·주문당 배송비). 읽기 전용, 사용자 접근 범위(사업자·판매계정·거점)로 계산한다.
 * 분모를 항상 함께 돌려주고, 계산할 수 없는 값은 null('계산불가')로 둔다.
 */
@Service
public class AnalyticsService {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final UnitCosts costs;

    public AnalyticsService(JdbcClient jdbc, AccessGuard guard, UnitCosts costs) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.costs = costs;
    }

    public record InventoryRow(UUID businessEntityId, String businessEntityName, UUID skuId, String skuCode, String skuName, int sellableOnHand, int nonSellableOnHand,
                               int committed, int atp, int sold28d, BigDecimal avgDailySales, BigDecimal daysOfCover, java.time.Instant lastSaleAt,
                               LocalDate nearestExpiry, BigDecimal unitCost, BigDecimal bookValue, boolean stockoutRisk, boolean slowMoving, boolean expiryRisk) {
    }

    public record InventoryReport(LocalDate asOf, int coverDays, int slowDays, int expiryDays, List<InventoryRow> rows, Map<String, Object> totals) {
    }

    public record SalesRow(String key, String label, int orders, int originalUnits, int canceledUnits, int shippedUnits, int returnedUnits, int exchangeUnits,
                           int netUnits, BigDecimal orderAmount, BigDecimal cancelRatePct, BigDecimal returnRatePct, BigDecimal exchangeRatePct) {
    }

    public record SalesReport(LocalDate from, LocalDate to, String groupBy, List<SalesRow> rows, SalesRow total, String note) {
    }

    public record LogisticsRow(UUID nodeId, String nodeCode, String nodeName, int fulfillmentOrders, int parcels, int orders, int splitOrders, BigDecimal splitRatePct,
                               BigDecimal avgLeadHours, Integer slaHours, Integer onTime, BigDecimal onTimeRatePct, BigDecimal shippingCost,
                               BigDecimal shippingCostPerParcel, BigDecimal estimatedShare) {
    }

    public record LogisticsReport(LocalDate from, LocalDate to, boolean costVisible, List<LogisticsRow> rows) {
    }

    // ---------------------------------------------------------------- 재고

    public InventoryReport inventory(List<String> businessEntityIds, Integer coverDays, Integer slowDays, Integer expiryDays) {
        guard.requirePermission(Perm.INVENTORY_READ);
        int cover = clamp(coverDays, 14, 1, 120);
        int slow = clamp(slowDays, 90, 30, 365);
        int exp = clamp(expiryDays, 60, 1, 365);
        LocalDate today = LocalDate.now(KST);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> nodes = guard.nodeFilter(AccessKind.READ, null);
        if (bes.isEmpty() || nodes.isEmpty()) {
            return new InventoryReport(today, cover, slow, exp, List.of(), Map.of());
        }
        List<Map<String, Object>> rows = jdbc.sql("""
                with st as (
                    select s.owner_business_entity_id as be, s.sku_id,
                           sum(case when s.quality_status = 'SELLABLE' then s.on_hand else 0 end) as sellable,
                           sum(case when s.quality_status <> 'SELLABLE' then s.on_hand else 0 end) as non_sellable,
                           min(case when s.on_hand > 0 then l.expires_on end) as nearest_expiry
                      from inventory_stock s left join inventory_lot l on l.id = s.lot_id
                     where s.workspace_id = :ws and s.owner_business_entity_id in (:bes) and s.node_id in (:nodes)
                     group by 1, 2),
                pos as (
                    select owner_business_entity_id as be, sku_id, sum(committed) as committed, sum(restricted_hold) as restricted
                      from inventory_position where workspace_id = :ws and owner_business_entity_id in (:bes) and node_id in (:nodes) group by 1, 2),
                sales as (
                    select owner_business_entity_id as be, sku_id,
                           coalesce(sum(-on_hand_delta) filter (where occurred_at >= now() - interval '28 days'), 0) as sold28,
                           max(occurred_at) as last_sale
                      from inventory_transaction where workspace_id = :ws and txn_type = 'CONSUME' and owner_business_entity_id in (:bes) and node_id in (:nodes)
                     group by 1, 2),
                first_in as (
                    select owner_business_entity_id as be, sku_id, min(occurred_at) as first_receipt
                      from inventory_transaction where workspace_id = :ws and on_hand_delta > 0 and owner_business_entity_id in (:bes) group by 1, 2)
                select st.*, coalesce(pos.committed, 0) as committed, coalesce(pos.restricted, 0) as restricted, coalesce(sales.sold28, 0) as sold28, sales.last_sale,
                       fi.first_receipt, be.display_name as be_name, k.sku_code, k.name as sku_name
                  from st left join pos on pos.be = st.be and pos.sku_id = st.sku_id left join sales on sales.be = st.be and sales.sku_id = st.sku_id
                  left join first_in fi on fi.be = st.be and fi.sku_id = st.sku_id
                  join business_entity be on be.id = st.be join sku k on k.id = st.sku_id
                 where st.sellable + st.non_sellable > 0 or coalesce(sales.sold28, 0) > 0
                 order by be.display_name, k.sku_code
                """).param("ws", guard.actor().workspaceId()).param("bes", bes).param("nodes", nodes).query().listOfRows();
        List<InventoryRow> out = new java.util.ArrayList<>();
        BigDecimal totalBook = BigDecimal.ZERO;
        int risk = 0;
        int slowCount = 0;
        int expCount = 0;
        java.time.Instant now = java.time.Instant.now();
        for (Map<String, Object> r : rows) {
            UUID be = (UUID) r.get("be");
            UUID sku = (UUID) r.get("sku_id");
            int sellable = ((Number) r.get("sellable")).intValue();
            int non = ((Number) r.get("non_sellable")).intValue();
            int committed = ((Number) r.get("committed")).intValue();
            int atp = Math.max(0, sellable - committed - ((Number) r.get("restricted")).intValue());
            int sold = ((Number) r.get("sold28")).intValue();
            BigDecimal avg = BigDecimal.valueOf(sold).divide(BigDecimal.valueOf(28), 2, RoundingMode.HALF_UP);
            BigDecimal days = sold == 0 ? null : BigDecimal.valueOf(atp).multiply(BigDecimal.valueOf(28)).divide(BigDecimal.valueOf(sold), 1, RoundingMode.HALF_UP);
            java.time.Instant last = r.get("last_sale") == null ? null : ((java.sql.Timestamp) r.get("last_sale")).toInstant();
            java.time.Instant first = r.get("first_receipt") == null ? null : ((java.sql.Timestamp) r.get("first_receipt")).toInstant();
            java.time.Instant slowCut = now.minus(java.time.Duration.ofDays(slow));
            boolean slowMoving = sellable + non > 0 && first != null && first.isBefore(slowCut) && (last == null || last.isBefore(slowCut));
            LocalDate expiry = r.get("nearest_expiry") == null ? null : ((java.sql.Date) r.get("nearest_expiry")).toLocalDate();
            boolean expiryRisk = expiry != null && !expiry.isAfter(today.plusDays(exp));
            boolean stockout = days != null && days.compareTo(BigDecimal.valueOf(cover)) < 0;
            BigDecimal unit = costs.current(be, sku).orElse(null);
            BigDecimal book = unit == null ? null : unit.multiply(BigDecimal.valueOf(sellable + non)).setScale(0, RoundingMode.HALF_UP);
            if (book != null) {
                totalBook = totalBook.add(book);
            }
            risk += stockout ? 1 : 0;
            slowCount += slowMoving ? 1 : 0;
            expCount += expiryRisk ? 1 : 0;
            out.add(new InventoryRow(be, (String) r.get("be_name"), sku, (String) r.get("sku_code"), (String) r.get("sku_name"), sellable, non, committed, atp, sold, avg,
                    days, last, expiry, unit, book, stockout, slowMoving, expiryRisk));
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("sku_rows", out.size());
        totals.put("book_value", totalBook);
        totals.put("stockout_risk", risk);
        totals.put("slow_moving", slowCount);
        totals.put("expiry_risk", expCount);
        totals.put("book_value_note", "장부가 = 보유 수량 × 현재 원가(이동평균, 없으면 등록 원가). 원가 미등록 SKU 는 제외. 평가손실 인식액과 다를 수 있습니다.");
        return new InventoryReport(today, cover, slow, exp, out, totals);
    }

    // ---------------------------------------------------------------- 판매 KPI

    public SalesReport sales(List<String> businessEntityIds, LocalDate from, LocalDate to, String groupBy) {
        guard.requirePermission(Perm.ORDER_READ);
        LocalDate t = to == null ? LocalDate.now(KST) : to;
        LocalDate f = from == null ? t.withDayOfMonth(1) : from;
        if (f.isAfter(t)) {
            throw Errors.validation("from", "시작일이 종료일보다 늦습니다.");
        }
        String g = groupBy == null ? "channel_account" : groupBy;
        String key = switch (g) {
            case "business_entity" -> "so.business_entity_id::text";
            case "channel_account" -> "so.channel_account_id::text";
            case "sku" -> "coalesce(oi.sku_id::text, '-')";
            default -> throw Errors.validation("group_by", "묶음 기준은 business_entity·channel_account·sku 중 하나입니다.");
        };
        String label = switch (g) {
            case "business_entity" -> "max(be.display_name)";
            case "channel_account" -> "max(be.display_name) || ' · ' || max(ca.display_name)";
            default -> "coalesce(max(k.sku_code), 'SKU 미연결')";
        };
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        String note = "사은품 줄은 제외. 순판매수량 = 주문 수량 − 취소 − 반품. 교환 출고는 판매로 세지 않고 교환율로만 표시. 취소율 분모 = 주문 수량, 반품·교환율 분모 = 출고 수량. "
                + "주문금액은 판매자 할인 후·부가세 포함이며 매출(손익)·정산액과 다릅니다.";
        if (bes.isEmpty() || cas.isEmpty()) {
            return new SalesReport(f, t, g, List.of(), salesRow("TOTAL", "합계", 0, 0, 0, 0, 0, 0, BigDecimal.ZERO), note);
        }
        List<SalesRow> rows = jdbc.sql("select " + key + " as k, " + label + """
                 as label, count(distinct so.id) filter (where oi.item_kind = 'ORIGINAL') as orders,
                       coalesce(sum(oi.original_qty) filter (where oi.item_kind = 'ORIGINAL'), 0) as original,
                       coalesce(sum(oi.canceled_qty) filter (where oi.item_kind = 'ORIGINAL'), 0) as canceled,
                       coalesce(sum(oi.shipped_qty) filter (where oi.item_kind = 'ORIGINAL'), 0) as shipped,
                       coalesce(sum(oi.returned_qty) filter (where oi.item_kind = 'ORIGINAL'), 0) as returned,
                       coalesce(sum(oi.original_qty) filter (where oi.item_kind = 'REPLACEMENT'), 0) as exchanged,
                       coalesce(sum(oi.line_amount - oi.seller_discount) filter (where oi.item_kind = 'ORIGINAL'), 0) as amount
                  from sales_order so join order_item oi on oi.order_id = so.id join business_entity be on be.id = so.business_entity_id
                  join channel_account ca on ca.id = so.channel_account_id left join sku k on k.id = oi.sku_id
                 where so.workspace_id = :ws and so.business_entity_id in (:bes) and so.channel_account_id in (:cas) and not oi.is_gift
                   and so.ordered_at >= cast(:f as date)::timestamp at time zone 'Asia/Seoul' and so.ordered_at < (cast(:t as date) + 1)::timestamp at time zone 'Asia/Seoul'
                 group by 1 order by 2
                """).param("ws", guard.actor().workspaceId()).param("bes", bes).param("cas", cas).param("f", f).param("t", t)
                .query((rs, i) -> salesRow(rs.getString("k"), rs.getString("label"), rs.getInt("orders"), rs.getInt("original"), rs.getInt("canceled"),
                        rs.getInt("shipped"), rs.getInt("returned"), rs.getInt("exchanged"), rs.getBigDecimal("amount"))).list();
        int o = 0, orig = 0, c = 0, s = 0, r = 0, e = 0;
        BigDecimal amt = BigDecimal.ZERO;
        for (SalesRow x : rows) {
            o += x.orders();
            orig += x.originalUnits();
            c += x.canceledUnits();
            s += x.shippedUnits();
            r += x.returnedUnits();
            e += x.exchangeUnits();
            amt = amt.add(x.orderAmount());
        }
        // 합계 주문수는 묶음 사이 중복(한 주문에 여러 SKU)을 빼고 다시 센다
        if ("sku".equals(g)) {
            o = jdbc.sql("""
                    select count(distinct so.id) from sales_order so join order_item oi on oi.order_id = so.id
                     where so.workspace_id = :ws and so.business_entity_id in (:bes) and so.channel_account_id in (:cas) and not oi.is_gift and oi.item_kind = 'ORIGINAL'
                       and so.ordered_at >= cast(:f as date)::timestamp at time zone 'Asia/Seoul' and so.ordered_at < (cast(:t as date) + 1)::timestamp at time zone 'Asia/Seoul'
                    """).param("ws", guard.actor().workspaceId()).param("bes", bes).param("cas", cas).param("f", f).param("t", t).query(Integer.class).single();
        }
        return new SalesReport(f, t, g, rows, salesRow("TOTAL", "합계", o, orig, c, s, r, e, amt), note);
    }

    static SalesRow salesRow(String key, String label, int orders, int original, int canceled, int shipped, int returned, int exchanged, BigDecimal amount) {
        return new SalesRow(key, label, orders, original, canceled, shipped, returned, exchanged, original - canceled - returned, amount, pct(canceled, original),
                pct(returned, shipped), pct(exchanged, shipped));
    }

    // ---------------------------------------------------------------- 물류

    public LogisticsReport logistics(List<String> businessEntityIds, LocalDate from, LocalDate to) {
        guard.requirePermission(Perm.ORDER_READ);
        boolean costVisible = guard.actor().has(Perm.COST_READ);
        LocalDate t = to == null ? LocalDate.now(KST) : to;
        LocalDate f = from == null ? t.withDayOfMonth(1) : from;
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> nodes = guard.nodeFilter(AccessKind.READ, null);
        if (bes.isEmpty() || nodes.isEmpty()) {
            return new LogisticsReport(f, t, costVisible, List.of());
        }
        List<LogisticsRow> rows = jdbc.sql("""
                with fo as (
                    select f.* from fulfillment_order f where f.workspace_id = :ws and f.business_entity_id in (:bes) and f.node_id in (:nodes) and f.status = 'SHIPPED'
                       and f.shipped_at >= cast(:f as date)::timestamp at time zone 'Asia/Seoul' and f.shipped_at < (cast(:t as date) + 1)::timestamp at time zone 'Asia/Seoul'),
                split as (
                    select o.order_id from fulfillment_order o where o.order_id in (select order_id from fo) and o.status <> 'CANCELLED' group by o.order_id having count(*) > 1)
                select n.id, n.code, n.name, n.tracking_sla_hours,
                       count(fo.id) as fos,
                       (select count(*) from shipment s where s.fo_id in (select id from fo x where x.node_id = n.id)) as parcels,
                       count(distinct fo.order_id) as orders,
                       count(distinct fo.order_id) filter (where fo.order_id in (select order_id from split)) as split_orders,
                       avg(extract(epoch from (fo.shipped_at - fo.created_at)) / 3600) as lead_hours,
                       count(fo.id) filter (where n.tracking_sla_hours is not null
                                              and fo.shipped_at - coalesce(fo.accepted_at, fo.created_at) <= make_interval(hours => n.tracking_sla_hours)) as on_time,
                       (select coalesce(sum(e.supply_amount), 0) from financial_entry e where e.category = 'OUTBOUND_SHIPPING' and e.fo_id in (select id from fo x where x.node_id = n.id)) as cost,
                       (select coalesce(sum(e.supply_amount), 0) from financial_entry e where e.category = 'OUTBOUND_SHIPPING' and e.basis = 'ESTIMATED'
                           and e.fo_id in (select id from fo x where x.node_id = n.id)) as est
                  from fulfillment_node n join fo on fo.node_id = n.id
                 group by n.id, n.code, n.name, n.tracking_sla_hours order by n.code
                """).param("ws", guard.actor().workspaceId()).param("bes", bes).param("nodes", nodes).param("f", f).param("t", t).query((rs, i) -> {
            int fos = rs.getInt("fos");
            int parcels = rs.getInt("parcels");
            int orders = rs.getInt("orders");
            Integer sla = (Integer) rs.getObject("tracking_sla_hours");
            BigDecimal cost = rs.getBigDecimal("cost");
            BigDecimal est = rs.getBigDecimal("est");
            BigDecimal lead = rs.getBigDecimal("lead_hours");
            return new LogisticsRow(Db.uuid(rs, "id"), rs.getString("code"), rs.getString("name"), fos, parcels, orders, rs.getInt("split_orders"),
                    pct(rs.getInt("split_orders"), orders), lead == null ? null : lead.setScale(1, RoundingMode.HALF_UP), sla, sla == null ? null : rs.getInt("on_time"),
                    sla == null ? null : pct(rs.getInt("on_time"), fos), costVisible ? cost : null,
                    costVisible && parcels > 0 ? cost.divide(BigDecimal.valueOf(parcels), 0, RoundingMode.HALF_UP) : null,
                    costVisible && cost.signum() != 0 ? est.multiply(BigDecimal.valueOf(100)).divide(cost, 1, RoundingMode.HALF_UP) : null);
        }).list();
        return new LogisticsReport(f, t, costVisible, rows);
    }

    static BigDecimal pct(int n, int d) {
        return d <= 0 ? null : BigDecimal.valueOf(n).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(d), 2, RoundingMode.HALF_UP);
    }

    static int clamp(Integer v, int def, int min, int max) {
        return v == null ? def : Math.max(min, Math.min(max, v));
    }
}
