package com.commerceos.inventory;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.inventory.InventoryLedger.Bucket;
import com.commerceos.inventory.InventoryLedger.Position;
import com.commerceos.inventory.InventoryLedger.Stock;
import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포장 부자재 출고 차감(FR-CAT-06·CHG-011·DLG-246~249).
 * <ul>
 *   <li>자체 원장 거점에 포장 프로필을 지정하면, 출고 확정 때 출고 시각 유효 포장 BOM(버전 고정) × 박스 수만큼 그 출고지시 사업자의 부자재 재고를 차감한다.</li>
 *   <li>부자재는 확보(예약) 대상이 아니라 판매가능 실물에서 바로 차감(원장 CONSUME, 참조 FO_MATERIAL). 확보 수량은 건드리지 않는다.</li>
 *   <li>재고가 모자라도 출고는 막지 않는다 — 있는 만큼 차감하고 부족분을 기록·업무 `MATERIAL_STOCK_SHORT`(실물은 나갔으므로 실사·입고 누락 확인).</li>
 *   <li>단가는 차감 시점 이동평균(없으면 등록 원가)을 고정 기록 → 손익 MATERIAL 원가는 이 기록에서만(규칙과 중복 없음).</li>
 * </ul>
 */
@Service
public class MaterialService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final InventoryLedger ledger;
    private final WorkItemService workItems;
    private final AuditService audit;

    public MaterialService(JdbcClient jdbc, AccessGuard guard, InventoryLedger ledger, WorkItemService workItems, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.ledger = ledger;
        this.workItems = workItems;
        this.audit = audit;
    }

    public record Packaging(UUID nodeId, String packagingProfileCode, UUID effectiveBomId, Integer effectiveBomVersion) {
    }

    public record ConsumptionView(UUID skuId, String skuCode, int bomVersion, int parcels, int requiredQty, int consumedQty, int shortfallQty, BigDecimal unitCost) {
    }

    // ---------------------------------------------------------------- 거점 포장 프로필

    public Packaging packaging(UUID nodeId) {
        guard.requirePermission(Perm.INVENTORY_READ);
        UUID ws = guard.actor().workspaceId();
        String code = jdbc.sql("select packaging_profile_code from fulfillment_node where workspace_id = :ws and id = :id").param("ws", ws).param("id", nodeId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("FULFILLMENT_NODE")).get("packaging_profile_code") instanceof String s ? s : null;
        Optional<Map<String, Object>> bom = code == null ? Optional.empty() : effectiveBom(ws, code, Instant.now());
        return new Packaging(nodeId, code, bom.map(b -> (UUID) b.get("id")).orElse(null), bom.map(b -> ((Number) b.get("version")).intValue()).orElse(null));
    }

    @Transactional
    public Packaging setPackaging(UUID nodeId, String profileCode) {
        guard.requirePermission(Perm.LOGISTICS_ACCOUNT_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> n = jdbc.sql("select inventory_source, packaging_profile_code from fulfillment_node where workspace_id = :ws and id = :id for update")
                .param("ws", a.workspaceId()).param("id", nodeId).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("FULFILLMENT_NODE"));
        guard.checkNode(AccessKind.WRITE, "FULFILLMENT_NODE", nodeId, nodeId);
        String code = profileCode == null || profileCode.isBlank() ? null : profileCode.trim().toUpperCase(java.util.Locale.ROOT);
        if (code != null) {
            if (!code.matches("^[A-Z0-9_]{1,40}$")) {
                throw Errors.validation("packaging_profile_code", "포장 프로필 코드는 영문 대문자·숫자·밑줄 40자 이내입니다.");
            }
            if ("EXTERNAL".equals(n.get("inventory_source"))) {
                throw Errors.unprocessable("NODE_INVENTORY_EXTERNAL", "물류사 재고 기준 거점의 부자재는 물류사가 관리합니다(포장비·부자재 청구로 반영).");
            }
            if (effectiveBom(a.workspaceId(), code, Instant.now()).isEmpty()) {
                throw Errors.unprocessable("PACKAGING_BOM_NOT_FOUND", "지금 유효한 포장 BOM(" + code + ")이 없습니다. 상품 > BOM 에서 포장 BOM 을 게시하세요.");
            }
        }
        jdbc.sql("update fulfillment_node set packaging_profile_code = cast(:c as text), version = version + 1, updated_at = now() where id = :id").param("c", code)
                .param("id", nodeId).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "NODE_PACKAGING_CHANGED", "FULFILLMENT_NODE", nodeId).actor(ActorType.USER, a.memberId(), a.displayName())
                .before(Map.of("packaging_profile_code", String.valueOf(n.get("packaging_profile_code")))).after(Map.of("packaging_profile_code", String.valueOf(code)))
                .build());
        return packaging(nodeId);
    }

    // ---------------------------------------------------------------- 출고 차감(출고 확정 트랜잭션 안)

    @Transactional(propagation = Propagation.MANDATORY)
    public void consumeForShipment(UUID ws, UUID foId, UUID ownerId, UUID nodeId, int parcels, Instant shippedAt, String foNo, UUID actor) {
        String code = jdbc.sql("select packaging_profile_code from fulfillment_node where id = :n").param("n", nodeId).query().listOfRows().get(0)
                .get("packaging_profile_code") instanceof String s ? s : null;
        if (code == null) {
            return;
        }
        Optional<Map<String, Object>> bom = effectiveBom(ws, code, shippedAt);
        if (bom.isEmpty()) {
            workItems.raise(new WorkItemService.Raise(ws, ownerId, null, null, "PACKAGING_BOM_MISSING", "MEDIUM", "포장 BOM 없음: " + code,
                    "출고 " + foNo + " 시각에 유효한 포장 BOM 이 없어 부자재를 차감하지 못했습니다.", "포장 BOM 을 게시하거나 거점 포장 프로필을 확인하세요.",
                    "packaging-bom-missing:" + nodeId + ":" + code, Map.of("node_id", nodeId.toString())));
            return;
        }
        UUID bomId = (UUID) bom.get().get("id");
        int version = ((Number) bom.get().get("version")).intValue();
        int boxes = Math.max(1, parcels);
        for (Map<String, Object> comp : jdbc.sql("select component_sku_id, quantity from bom_component where bom_id = :b order by sort_order, component_sku_id")
                .param("b", bomId).query().listOfRows()) {
            UUID sku = (UUID) comp.get("component_sku_id");
            int need = ((Number) comp.get("quantity")).intValue() * boxes;
            Position p = ledger.lockPosition(ws, ownerId, sku, nodeId);
            int free = Math.max(0, p.sellableOnHand() - p.committed());
            int left = Math.min(need, free);
            int consumed = 0;
            for (Stock s : ledger.lockSellableBucketsFefo(p)) {
                if (left == 0) {
                    break;
                }
                int take = Math.min(left, s.onHand());
                p = ledger.changeBucket(ws, p, s.bucket(), -take, "CONSUME", "FO_MATERIAL", foId.toString(), "부자재 출고 차감 " + foNo, actor);
                left -= take;
                consumed += take;
            }
            int shortfall = need - consumed;
            BigDecimal unit = jdbc.sql("select avg_unit_cost from sku_moving_cost where business_entity_id = :b and sku_id = :s").param("b", ownerId).param("s", sku)
                    .query(BigDecimal.class).optional().or(() -> jdbc.sql("""
                            select unit_cost from sku_cost where business_entity_id = :b and sku_id = :s and effective_from <= now() order by effective_from desc limit 1
                            """).param("b", ownerId).param("s", sku).query(BigDecimal.class).optional()).orElse(null);
            jdbc.sql("""
                    insert into fo_material_consumption (id, workspace_id, fo_id, business_entity_id, node_id, bom_id, bom_version, sku_id, parcels, required_qty,
                                                         consumed_qty, shortfall_qty, unit_cost)
                    values (:id, :ws, :f, :be, :n, :b, :v, :s, :p, :rq, :cq, :sq, cast(:u as numeric)) on conflict (fo_id, sku_id) do nothing
                    """).param("id", Ids.newId()).param("ws", ws).param("f", foId).param("be", ownerId).param("n", nodeId).param("b", bomId).param("v", version)
                    .param("s", sku).param("p", boxes).param("rq", need).param("cq", consumed).param("sq", shortfall).param("u", unit).update();
            if (shortfall > 0) {
                String skuCode = jdbc.sql("select sku_code from sku where id = :s").param("s", sku).query(String.class).single();
                workItems.raise(new WorkItemService.Raise(ws, ownerId, null, null, "MATERIAL_STOCK_SHORT", "MEDIUM", "부자재 재고 부족: " + skuCode,
                        "출고 " + foNo + " 에 부자재 " + skuCode + " " + need + "개가 필요했지만 재고는 " + consumed + "개만 차감했습니다(부족 " + shortfall + ").",
                        "부자재 입고 누락·실사 차이를 확인하고 재고를 맞추세요. 원가는 필요 수량 기준으로 기록했습니다.",
                        "material-short:" + nodeId + ":" + sku, Map.of("node_id", nodeId.toString(), "sku_id", sku.toString())));
            }
        }
    }

    public List<ConsumptionView> consumption(UUID foId) {
        guard.requirePermission(Perm.INVENTORY_READ);
        return jdbc.sql("""
                select m.*, k.sku_code from fo_material_consumption m join sku k on k.id = m.sku_id where m.workspace_id = :ws and m.fo_id = :f order by k.sku_code
                """).param("ws", guard.actor().workspaceId()).param("f", foId).query((rs, i) -> new ConsumptionView(Db.uuid(rs, "sku_id"), rs.getString("sku_code"),
                rs.getInt("bom_version"), rs.getInt("parcels"), rs.getInt("required_qty"), rs.getInt("consumed_qty"), rs.getInt("shortfall_qty"),
                guard.actor().has(Perm.COST_READ) ? rs.getBigDecimal("unit_cost") : null)).list();
    }

    private Optional<Map<String, Object>> effectiveBom(UUID ws, String profile, Instant at) {
        return jdbc.sql("""
                select id, version from bom where workspace_id = :ws and packaging_profile_code = :pk and bom_type = 'PACKAGING' and status <> 'DRAFT'
                   and valid_from <= :at and (valid_to is null or valid_to > :at) order by version desc limit 1
                """).param("ws", ws).param("pk", profile).param("at", Db.ts(at)).query().listOfRows().stream().findFirst();
    }
}
