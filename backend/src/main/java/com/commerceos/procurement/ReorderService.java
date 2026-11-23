package com.commerceos.procurement;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.Perm;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 발주 제안(FR-OPS-04, docs/01 §발주 제안, DLG-210·211). 제안만 하고 발주는 사람이 작성·승인한다.
 * 필요 수량 = 일평균 판매(최근 28일 출고) × (공급처 리드타임 + 목표 재고일수) − (가용재고 + 입고예정), 공급처 MOQ·발주단위로 올림.
 * 공급처는 사용 중인 공급 SKU 중 단가가 가장 낮은 곳(같으면 리드타임 짧은 곳).
 */
@Service
public class ReorderService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final PurchaseOrderService orders;

    public ReorderService(JdbcClient jdbc, AccessGuard guard, PurchaseOrderService orders) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.orders = orders;
    }

    public record Suggestion(UUID businessEntityId, String businessEntityName, UUID skuId, String skuCode, UUID supplierId, String supplierName, UUID supplierSkuId,
                             BigDecimal avgDailySales, int atp, int inbound, int leadTimeDays, int targetDays, BigDecimal daysOfCover, int need, int moq,
                             int orderMultiple, int suggestedQty, BigDecimal unitCost, BigDecimal amount, String reason) {
    }

    public record Pick(UUID supplierSkuId, Integer quantity) {
    }

    public record FromSuggestions(UUID nodeId, List<Pick> items) {
    }

    public List<Suggestion> suggestions(List<String> businessEntityIds, Integer coverDays) {
        guard.requirePermission(Perm.INVENTORY_READ);
        int cover = coverDays == null ? 14 : Math.max(0, Math.min(180, coverDays));
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        boolean cost = guard.actor().has(Perm.COST_READ) || guard.actor().has(Perm.PURCHASE_MANAGE);
        List<Suggestion> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("""
                with pick as (
                    select distinct on (s.business_entity_id, ss.sku_id) s.business_entity_id as be, ss.sku_id, ss.id as ssid, s.id as sid, s.name as supplier_name,
                           ss.unit_cost, ss.moq, ss.order_multiple, coalesce(ss.lead_time_days, s.lead_time_days) as lead
                      from supplier_sku ss join supplier s on s.id = ss.supplier_id
                     where s.workspace_id = :ws and s.business_entity_id in (:bes) and s.status = 'ACTIVE' and ss.status = 'ACTIVE'
                     order by s.business_entity_id, ss.sku_id, ss.unit_cost, coalesce(ss.lead_time_days, s.lead_time_days), ss.id),
                sales as (
                    select owner_business_entity_id as be, sku_id, sum(-on_hand_delta) as sold
                      from inventory_transaction where workspace_id = :ws and txn_type = 'CONSUME' and occurred_at >= now() - interval '28 days'
                       and owner_business_entity_id in (:bes) group by 1, 2),
                pos as (
                    select owner_business_entity_id as be, sku_id, greatest(sum(sellable_on_hand - committed - restricted_hold), 0) as atp
                      from inventory_position where workspace_id = :ws and owner_business_entity_id in (:bes) group by 1, 2),
                inb as (
                    select o.business_entity_id as be, l.sku_id, sum(l.quantity - l.received_qty - l.rejected_qty) as inbound
                      from purchase_order o join purchase_order_line l on l.po_id = o.id
                     where o.workspace_id = :ws and o.status in ('PLACED', 'SUPPLIER_CONFIRMED', 'PARTIALLY_RECEIVED') group by 1, 2)
                select pick.*, coalesce(sales.sold, 0) as sold, coalesce(pos.atp, 0) as atp, coalesce(inb.inbound, 0) as inbound, k.sku_code, be.display_name as be_name
                  from pick join sales on sales.be = pick.be and sales.sku_id = pick.sku_id
                  left join pos on pos.be = pick.be and pos.sku_id = pick.sku_id left join inb on inb.be = pick.be and inb.sku_id = pick.sku_id
                  join sku k on k.id = pick.sku_id join business_entity be on be.id = pick.be
                 order by be.display_name, k.sku_code
                """).param("ws", guard.actor().workspaceId()).param("bes", bes).query().listOfRows()) {
            int sold = ((Number) r.get("sold")).intValue();
            if (sold <= 0) {
                continue;
            }
            BigDecimal avg = BigDecimal.valueOf(sold).divide(BigDecimal.valueOf(28), 4, RoundingMode.HALF_UP);
            int lead = ((Number) r.get("lead")).intValue();
            int target = lead + cover;
            int atp = ((Number) r.get("atp")).intValue();
            int inbound = ((Number) r.get("inbound")).intValue();
            int demand = avg.multiply(BigDecimal.valueOf(target)).setScale(0, RoundingMode.CEILING).intValue();
            int need = demand - atp - inbound;
            if (need <= 0) {
                continue;
            }
            int moq = ((Number) r.get("moq")).intValue();
            int mult = ((Number) r.get("order_multiple")).intValue();
            int q = Math.max(need, moq);
            q = ((q + mult - 1) / mult) * mult;
            BigDecimal unit = (BigDecimal) r.get("unit_cost");
            BigDecimal days = avg.signum() == 0 ? null : BigDecimal.valueOf(atp + inbound).divide(avg, 1, RoundingMode.HALF_UP);
            out.add(new Suggestion((UUID) r.get("be"), (String) r.get("be_name"), (UUID) r.get("sku_id"), (String) r.get("sku_code"), (UUID) r.get("sid"),
                    (String) r.get("supplier_name"), (UUID) r.get("ssid"), avg.setScale(2, RoundingMode.HALF_UP), atp, inbound, lead, target, days, need, moq, mult, q,
                    cost ? unit : null, cost ? unit.multiply(BigDecimal.valueOf(q)) : null,
                    "최근 28일 일평균 " + avg.setScale(2, RoundingMode.HALF_UP).toPlainString() + "개 × (리드타임 " + lead + "일 + 목표 " + cover + "일) = " + demand
                            + "개 필요, 가용 " + atp + " + 입고예정 " + inbound + " → 부족 " + need + "개(MOQ·발주단위 반영 " + q + "개)"));
        }
        return out;
    }

    /** 선택한 제안으로 공급처별 작성 중 발주를 만든다(승인 요청은 사람이). */
    @Transactional
    public List<PurchaseOrderService.PoView> createDrafts(FromSuggestions in) {
        guard.requirePermission(Perm.PURCHASE_MANAGE);
        if (in == null || in.nodeId() == null || in.items() == null || in.items().isEmpty()) {
            throw Errors.validation("items", "입고 거점과 발주할 제안을 선택하세요.");
        }
        Map<UUID, List<PurchaseOrderService.LineInput>> bySupplier = new LinkedHashMap<>();
        for (Pick p : in.items()) {
            if (p == null || p.supplierSkuId() == null || p.quantity() == null || p.quantity() < 1) {
                throw Errors.validation("items", "제안 품목과 수량을 확인하세요.");
            }
            Map<String, Object> ss = jdbc.sql("select supplier_id, sku_id from supplier_sku where workspace_id = :ws and id = :id").param("ws", guard.actor().workspaceId())
                    .param("id", p.supplierSkuId()).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SUPPLIER_SKU"));
            bySupplier.computeIfAbsent((UUID) ss.get("supplier_id"), k -> new ArrayList<>())
                    .add(new PurchaseOrderService.LineInput((UUID) ss.get("sku_id"), p.quantity(), null, null));
        }
        List<PurchaseOrderService.PoView> out = new ArrayList<>();
        for (var e : bySupplier.entrySet()) {
            out.add(orders.create(new PurchaseOrderService.PoInput(e.getKey(), in.nodeId(), null, "발주 제안에서 작성", e.getValue())));
        }
        return out;
    }
}
