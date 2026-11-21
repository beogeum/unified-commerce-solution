package com.commerceos.catalog;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.operations.approval.ApprovalHandler;
import com.commerceos.operations.approval.ApprovalService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.outbox.OutboxPublisher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.money.Money;
import com.commerceos.shared.money.TaxType;
import com.commerceos.shared.money.TaxedAmount;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사업자별 SKU 정책·원가(M2 C2, CONF-004, T04). 같은 SKU 라도 사업자마다 판매 여부·가격·원가·안전재고가 다르다.
 * <ul>
 *   <li>조회는 사업자 READ 범위, 변경은 WRITE 범위. 원가는 `cost.read` 가 없으면 보이지 않는다.</li>
 *   <li>원가는 적용 시작 시각이 있는 이력으로만 쌓는다. 과거 시점으로 소급 등록하지 않는다(DLG-035).</li>
 *   <li>최저판매가 변경은 항상 승인 대상(DLG-043). 승인 전까지 기존 값이 유지된다.</li>
 * </ul>
 */
@Service
public class BusinessSkuPolicyService implements ApprovalHandler {
    static final String MIN_PRICE_CHANGE = "SKU_MIN_PRICE_CHANGE";
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");
    /** 시계 차이를 감안해 1분 전까지는 '지금'으로 본다. */
    static final Duration BACKDATE_TOLERANCE = Duration.ofMinutes(1);

    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final ApprovalService approvals;
    private final Clock clock;

    public BusinessSkuPolicyService(JdbcClient jdbc, AccessGuard guard, AuditService audit, OutboxPublisher outbox,
                                    @Lazy ApprovalService approvals, Clock clock) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.outbox = outbox;
        this.approvals = approvals;
        this.clock = clock;
    }

    public record PendingMinPrice(UUID approvalId, BigDecimal requestedMinPrice, String requestedByName, Instant requestedAt) {
    }

    public record PolicyView(UUID businessEntityId, String businessEntityName, UUID skuId, String skuCode, String productName,
                             String skuType, boolean configured, boolean sellEnabled, boolean purchaseEnabled, String currency,
                             BigDecimal basePrice, BigDecimal basePriceSupply, BigDecimal basePriceVat, BigDecimal minPrice,
                             int safetyStock, String note, boolean costVisible, BigDecimal currentCost, Instant currentCostEffectiveFrom,
                             PendingMinPrice pendingMinPrice, int version, Instant updatedAt) {
    }

    public record Put(Boolean sellEnabled, Boolean purchaseEnabled, BigDecimal basePrice, BigDecimal minPrice, Integer safetyStock,
                      String note, String minPriceReason) {
    }

    public record CostView(UUID id, BigDecimal unitCost, String currency, boolean vatIncluded, Instant effectiveFrom, String source,
                           String sourceRef, String reason, String createdByName, Instant createdAt, boolean current) {
    }

    public record NewCost(BigDecimal unitCost, Instant effectiveFrom, String reason) {
    }

    // ---------------- 조회 ----------------

    public List<PolicyView> list(UUID businessEntityId, String q) {
        guard.requirePermission(Perm.CATALOG_READ);
        requireReadScope(businessEntityId);
        String term = q == null || q.isBlank() ? null : q.trim();
        return jdbc.sql(SELECT + """
                 where s.workspace_id = :ws and bp.business_entity_id = :be
                   and (cast(:q as text) is null or lower(s.sku_code) like '%' || lower(:q) || '%' or p.name ilike '%' || :q || '%')
                 order by s.sku_code limit 500
                """).param("ws", ws()).param("be", businessEntityId).param("q", term).query(this::map).list()
                .stream().map(this::finish).toList();
    }

    public PolicyView get(UUID businessEntityId, UUID skuId) {
        guard.requirePermission(Perm.CATALOG_READ);
        requireReadScope(businessEntityId);
        requireSku(skuId);
        return finish(find(businessEntityId, skuId).orElseGet(() -> unconfigured(businessEntityId, skuId)));
    }

    /** 한 SKU 의 사업자별 정책(T04 비교 보기). 조회 범위 안의 활성 사업자만. */
    public List<PolicyView> bySku(UUID skuId) {
        guard.requirePermission(Perm.CATALOG_READ);
        requireSku(skuId);
        List<UUID> bes = jdbc.sql("select id from business_entity where workspace_id = :ws and status = 'ACTIVE' and id = any(:ids) order by display_name")
                .param("ws", ws()).param("ids", Db.uuids(guard.businessEntityFilter(AccessKind.READ, null))).query(UUID.class).list();
        return bes.stream().map(be -> finish(find(be, skuId).orElseGet(() -> unconfigured(be, skuId)))).toList();
    }

    // ---------------- 변경 ----------------

    @Transactional
    public PolicyView put(UUID businessEntityId, UUID skuId, Integer expectedVersion, Put p) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        requireBusinessEntity(businessEntityId);
        guard.checkWrite("BUSINESS_SKU_POLICY", skuId, businessEntityId, null);
        Map<String, Object> sku = requireSku(skuId);
        if ("ARCHIVED".equals(sku.get("status"))) {
            throw Errors.conflict("SKU_ARCHIVED", "보관된 SKU 의 정책은 바꿀 수 없습니다.");
        }
        BigDecimal base = amount("base_price", p.basePrice());
        BigDecimal requestedMin = amount("min_price", p.minPrice());
        if (p.safetyStock() != null && p.safetyStock() < 0) {
            throw Errors.validation("safety_stock", "안전재고는 0 이상이어야 합니다.");
        }
        if (Boolean.TRUE.equals(p.sellEnabled()) && !Boolean.TRUE.equals(sku.get("sellable"))) {
            throw Errors.unprocessable("SKU_NOT_SELLABLE", "판매 불가 SKU(부자재·증정품 등)는 사업자 판매를 켤 수 없습니다.");
        }
        Optional<Map<String, Object>> existing = jdbc.sql("""
                select * from business_sku_policy where workspace_id = :ws and business_entity_id = :be and sku_id = :s for update
                """).param("ws", a.workspaceId()).param("be", businessEntityId).param("s", skuId).query().listOfRows().stream().findFirst();
        if (existing.isPresent()) {
            if (expectedVersion == null) {
                throw Errors.preconditionRequired();
            }
            CatalogRules.checkVersion(expectedVersion, (Integer) existing.get().get("version"));
        } else if (expectedVersion != null && expectedVersion != 0) {
            throw Errors.versionConflict(expectedVersion, 0);
        }
        BigDecimal currentMin = existing.map(r -> (BigDecimal) r.get("min_price")).orElse(null);
        BigDecimal nextBase = base != null ? base : existing.map(r -> (BigDecimal) r.get("base_price")).orElse(null);
        // 최저판매가 보호: 현재 최저판매가와 요청 중인 새 최저판매가 모두 기준
        if (nextBase != null && currentMin != null && nextBase.compareTo(currentMin) < 0) {
            throw Errors.unprocessable("PRICE_BELOW_MIN_PRICE", "기준 판매가가 최저판매가 " + won(currentMin) + "원보다 낮습니다.",
                    Map.of("min_price", currentMin.toPlainString()));
        }
        boolean defaultSell = Boolean.TRUE.equals(sku.get("sellable"));
        boolean defaultBuy = Boolean.TRUE.equals(sku.get("purchasable"));
        Map<String, Object> before = existing.map(BusinessSkuPolicyService::snapshot).orElse(Map.of());
        if (existing.isEmpty()) {
            jdbc.sql("""
                    insert into business_sku_policy (workspace_id, business_entity_id, sku_id, sell_enabled, purchase_enabled, base_price,
                                                     safety_stock, note)
                    values (:ws, :be, :s, :sell, :buy, :base, :safety, :note)
                    """).param("ws", a.workspaceId()).param("be", businessEntityId).param("s", skuId)
                    .param("sell", p.sellEnabled() == null ? defaultSell : p.sellEnabled())
                    .param("buy", p.purchaseEnabled() == null ? defaultBuy : p.purchaseEnabled()).param("base", base)
                    .param("safety", p.safetyStock() == null ? 0 : p.safetyStock())
                    .param("note", CatalogRules.optionalText("note", p.note(), 500)).update();
        } else {
            jdbc.sql("""
                    update business_sku_policy set sell_enabled = coalesce(:sell, sell_enabled), purchase_enabled = coalesce(:buy, purchase_enabled),
                           base_price = coalesce(:base, base_price), safety_stock = coalesce(:safety, safety_stock), note = coalesce(:note, note),
                           version = version + 1, updated_at = now()
                     where workspace_id = :ws and business_entity_id = :be and sku_id = :s
                    """).param("sell", p.sellEnabled()).param("buy", p.purchaseEnabled()).param("base", base).param("safety", p.safetyStock())
                    .param("note", CatalogRules.optionalText("note", p.note(), 500))
                    .param("ws", a.workspaceId()).param("be", businessEntityId).param("s", skuId).update();
        }
        // 최저판매가는 승인 후에만 바뀐다
        if (requestedMin != null && (currentMin == null || requestedMin.compareTo(currentMin) != 0)) {
            if (nextBase != null && nextBase.compareTo(requestedMin) < 0) {
                throw Errors.unprocessable("PRICE_BELOW_MIN_PRICE", "새 최저판매가가 기준 판매가 " + won(nextBase) + "원보다 높습니다.",
                        Map.of("base_price", nextBase.toPlainString()));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("business_entity_id", businessEntityId.toString());
            payload.put("sku_id", skuId.toString());
            payload.put("from", currentMin == null ? null : currentMin.toPlainString());
            payload.put("to", requestedMin.toPlainString());
            Map<String, Object> impact = new LinkedHashMap<>();
            impact.put("sku_code", sku.get("sku_code"));
            impact.put("base_price", nextBase == null ? null : nextBase.toPlainString());
            impact.put("min_price_from", currentMin == null ? null : currentMin.toPlainString());
            impact.put("min_price_to", requestedMin.toPlainString());
            String reason = p.minPriceReason() == null || p.minPriceReason().isBlank() ? "최저판매가 변경" : p.minPriceReason();
            approvals.create(new ApprovalService.NewRequest(MIN_PRICE_CHANGE, "BUSINESS_SKU_POLICY", targetKey(businessEntityId, skuId),
                    businessEntityId, payload, impact, reason));
        }
        PolicyView after = finish(find(businessEntityId, skuId).orElseThrow());
        audit.record(AuditEntry.builder(a.workspaceId(), existing.isEmpty() ? "SKU_POLICY_CREATED" : "SKU_POLICY_UPDATED", "SKU", skuId)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(businessEntityId, null).before(before)
                .after(snapshot(after)).build());
        outbox.publish(a.workspaceId(), businessEntityId, "SKU", skuId, "BusinessSkuPolicyChanged",
                Map.of("business_entity_id", businessEntityId.toString(), "sku_id", skuId.toString()));
        return after;
    }

    // ---------------- 승인 처리기(최저판매가) ----------------

    @Override
    public String requestType() {
        return MIN_PRICE_CHANGE;
    }

    @Override
    public boolean isCurrent(ApprovalService.Request r) {
        UUID be = UUID.fromString(r.payload().get("business_entity_id").asString());
        UUID sku = UUID.fromString(r.payload().get("sku_id").asString());
        Map<String, Object> row = jdbc.sql("select min_price, base_price from business_sku_policy where workspace_id = :ws and business_entity_id = :be and sku_id = :s for update")
                .param("ws", ws()).param("be", be).param("s", sku).query().listOfRows().stream().findFirst().orElse(null);
        if (row == null) {
            return false;
        }
        String from = r.payload().get("from").isNull() ? null : r.payload().get("from").asString();
        BigDecimal currentMin = (BigDecimal) row.get("min_price");
        BigDecimal to = new BigDecimal(r.payload().get("to").asString());
        BigDecimal base = (BigDecimal) row.get("base_price");
        boolean sameFrom = from == null ? currentMin == null : currentMin != null && currentMin.compareTo(new BigDecimal(from)) == 0;
        return sameFrom && (base == null || base.compareTo(to) >= 0);
    }

    @Override
    public void apply(ApprovalService.Request r) {
        CurrentActor a = guard.actor();
        UUID be = UUID.fromString(r.payload().get("business_entity_id").asString());
        UUID sku = UUID.fromString(r.payload().get("sku_id").asString());
        BigDecimal to = new BigDecimal(r.payload().get("to").asString());
        jdbc.sql("update business_sku_policy set min_price = :m, version = version + 1, updated_at = now() where workspace_id = :ws and business_entity_id = :be and sku_id = :s")
                .param("m", to).param("ws", ws()).param("be", be).param("s", sku).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "SKU_MIN_PRICE_CHANGED", "SKU", sku)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(be, null)
                .before(Map.of("min_price", String.valueOf(r.payload().get("from").isNull() ? null : r.payload().get("from").asString())))
                .after(Map.of("min_price", to.toPlainString())).metadata(Map.of("approval_id", r.id().toString())).build());
        outbox.publish(a.workspaceId(), be, "SKU", sku, "BusinessSkuPolicyChanged", Map.of("business_entity_id", be.toString(), "sku_id", sku.toString()));
    }

    // ---------------- 원가 ----------------

    public List<CostView> costs(UUID businessEntityId, UUID skuId) {
        guard.requirePermission(Perm.COST_READ);
        requireReadScope(businessEntityId);
        requireSku(skuId);
        Instant now = clock.instant();
        List<CostView> rows = jdbc.sql("""
                select c.*, u.display_name as created_by_name from sku_cost c
                  left join workspace_member m on m.workspace_id = c.workspace_id and m.id = c.created_by
                  left join user_account u on u.id = m.user_id
                 where c.workspace_id = :ws and c.business_entity_id = :be and c.sku_id = :s order by c.effective_from desc limit 200
                """).param("ws", ws()).param("be", businessEntityId).param("s", skuId)
                .query((rs, i) -> new CostView(Db.uuid(rs, "id"), rs.getBigDecimal("unit_cost"), rs.getString("currency"), rs.getBoolean("vat_included"),
                        Db.instant(rs, "effective_from"), rs.getString("source"), rs.getString("source_ref"), rs.getString("reason"),
                        rs.getString("created_by_name"), Db.instant(rs, "created_at"), false)).list();
        Optional<UUID> current = rows.stream().filter(c -> !c.effectiveFrom().isAfter(now)).map(CostView::id).findFirst();
        return rows.stream().map(c -> new CostView(c.id(), c.unitCost(), c.currency(), c.vatIncluded(), c.effectiveFrom(), c.source(), c.sourceRef(),
                c.reason(), c.createdByName(), c.createdAt(), current.map(c.id()::equals).orElse(false))).toList();
    }

    @Transactional
    public CostView addCost(UUID businessEntityId, UUID skuId, NewCost c) {
        guard.requirePermission(Perm.COST_MANAGE);
        CurrentActor a = guard.actor();
        requireBusinessEntity(businessEntityId);
        guard.checkWrite("SKU_COST", skuId, businessEntityId, null);
        Map<String, Object> sku = requireSku(skuId);
        if (NO_COST_TYPES.contains(String.valueOf(sku.get("sku_type")))) {
            throw Errors.unprocessable("SKU_COST_NOT_APPLICABLE", "가상묶음 원가는 구성품 원가로 계산하고, 서비스·디지털 SKU 는 재고 원가가 없습니다.");
        }
        BigDecimal cost = amount("unit_cost", c.unitCost());
        if (cost == null) {
            throw Errors.validation("unit_cost", "원가를 입력하세요.");
        }
        Instant now = clock.instant();
        Instant from = c.effectiveFrom() == null ? now : c.effectiveFrom();
        if (from.isBefore(now.minus(BACKDATE_TOLERANCE))) {
            throw Errors.unprocessable("COST_BACKDATE_FORBIDDEN", "이미 지난 시점부터 적용하는 원가는 등록할 수 없습니다. 지난 원가 정정은 원가 조정(M5)으로 처리합니다.");
        }
        String reason = CatalogRules.requireText("reason", c.reason(), 500);
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    insert into sku_cost (id, workspace_id, business_entity_id, sku_id, unit_cost, effective_from, source, reason, created_by)
                    values (:id, :ws, :be, :s, :c, :f, 'MANUAL', :r, :by)
                    """).param("id", id).param("ws", a.workspaceId()).param("be", businessEntityId).param("s", skuId).param("c", cost)
                    .param("f", Db.ts(from)).param("r", reason).param("by", a.memberId()).update();
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("COST_EFFECTIVE_FROM_DUPLICATE", "같은 적용 시작 시각의 원가가 이미 있습니다.");
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "SKU_COST_REGISTERED", "SKU", skuId)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(businessEntityId, null)
                .after(Map.of("unit_cost", cost.toPlainString(), "effective_from", from.toString(), "source", "MANUAL")).build());
        outbox.publish(a.workspaceId(), businessEntityId, "SKU", skuId, "SkuCostRegistered",
                Map.of("business_entity_id", businessEntityId.toString(), "sku_id", skuId.toString(), "effective_from", from.toString()));
        return costs(businessEntityId, skuId).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    // ---------------- 내부 ----------------

    private static final String SELECT = """
            select bp.*, be.display_name as be_name, s.sku_code, s.sku_type, p.name as product_name, p.tax_type
              from business_sku_policy bp
              join business_entity be on be.workspace_id = bp.workspace_id and be.id = bp.business_entity_id
              join sku s on s.workspace_id = bp.workspace_id and s.id = bp.sku_id
              join product p on p.workspace_id = s.workspace_id and p.id = s.product_id
            """;

    private Optional<PolicyView> find(UUID be, UUID sku) {
        return jdbc.sql(SELECT + " where bp.workspace_id = :ws and bp.business_entity_id = :be and bp.sku_id = :s")
                .param("ws", ws()).param("be", be).param("s", sku).query(this::map).optional();
    }

    private PolicyView unconfigured(UUID be, UUID skuId) {
        Map<String, Object> s = jdbc.sql("""
                select s.sku_code, s.sku_type, s.sellable, s.purchasable, p.name as product_name, be.display_name as be_name
                  from sku s join product p on p.workspace_id = s.workspace_id and p.id = s.product_id
                  join business_entity be on be.workspace_id = s.workspace_id and be.id = :be
                 where s.workspace_id = :ws and s.id = :s
                """).param("ws", ws()).param("s", skuId).param("be", be).query().singleRow();
        return new PolicyView(be, (String) s.get("be_name"), skuId, (String) s.get("sku_code"), (String) s.get("product_name"),
                (String) s.get("sku_type"), false, Boolean.TRUE.equals(s.get("sellable")), Boolean.TRUE.equals(s.get("purchasable")), "KRW",
                null, null, null, null, 0, null, false, null, null, null, 0, null);
    }

    /** 원가 표시 여부와 현재 원가·대기 중 최저판매가 요청을 붙인다. */
    private PolicyView finish(PolicyView v) {
        CurrentActor a = guard.actor();
        boolean costVisible = a.has(Perm.COST_READ);
        BigDecimal cost = null;
        Instant costFrom = null;
        if (costVisible) {
            record Current(BigDecimal cost, Instant from) {
            }
            Current c = jdbc.sql("""
                    select unit_cost, effective_from from sku_cost where workspace_id = :ws and business_entity_id = :be and sku_id = :s
                       and effective_from <= :now order by effective_from desc limit 1
                    """).param("ws", ws()).param("be", v.businessEntityId()).param("s", v.skuId()).param("now", Db.ts(clock.instant()))
                    .query((rs, i) -> new Current(rs.getBigDecimal("unit_cost"), Db.instant(rs, "effective_from"))).optional().orElse(null);
            if (c != null) {
                cost = c.cost();
                costFrom = c.from();
            }
        }
        PendingMinPrice pending = approvals.pending(MIN_PRICE_CHANGE, targetKey(v.businessEntityId(), v.skuId()))
                .map(r -> new PendingMinPrice(r.id(), new BigDecimal(r.payload().get("to").asString()), r.requestedByName(), r.requestedAt()))
                .orElse(null);
        return new PolicyView(v.businessEntityId(), v.businessEntityName(), v.skuId(), v.skuCode(), v.productName(), v.skuType(), v.configured(),
                v.sellEnabled(), v.purchaseEnabled(), v.currency(), v.basePrice(), v.basePriceSupply(), v.basePriceVat(), v.minPrice(),
                v.safetyStock(), v.note(), costVisible, cost, costFrom, pending, v.version(), v.updatedAt());
    }

    private PolicyView map(ResultSet rs, int i) throws SQLException {
        BigDecimal base = rs.getBigDecimal("base_price");
        String tax = rs.getString("tax_type");
        BigDecimal supply = null;
        BigDecimal vat = null;
        if (base != null && tax != null) {
            TaxedAmount t = TaxedAmount.fromGrossKrwVatFloor(Money.of(base.toPlainString(), "KRW"), "TAXABLE".equals(tax) ? TaxType.TAXABLE
                    : "ZERO_RATED".equals(tax) ? TaxType.ZERO_RATED : TaxType.EXEMPT);
            supply = t.supply().amount();
            vat = t.vat().amount();
        }
        return new PolicyView(Db.uuid(rs, "business_entity_id"), rs.getString("be_name"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"),
                rs.getString("product_name"), rs.getString("sku_type"), true, rs.getBoolean("sell_enabled"), rs.getBoolean("purchase_enabled"),
                rs.getString("currency"), base, supply, vat, rs.getBigDecimal("min_price"), rs.getInt("safety_stock"), rs.getString("note"),
                false, null, null, null, rs.getInt("version"), Db.instant(rs, "updated_at"));
    }

    private void requireReadScope(UUID businessEntityId) {
        requireBusinessEntity(businessEntityId);
        guard.checkRead("BUSINESS_ENTITY", businessEntityId, businessEntityId, null);
    }

    private void requireBusinessEntity(UUID id) {
        if (jdbc.sql("select count(*) from business_entity where workspace_id = :ws and id = :id").param("ws", ws()).param("id", id)
                .query(Integer.class).single() == 0) {
            throw Errors.notFound("BUSINESS_ENTITY");
        }
    }

    private Map<String, Object> requireSku(UUID skuId) {
        return jdbc.sql("select id, sku_code, sku_type, sellable, purchasable, status from sku where workspace_id = :ws and id = :id")
                .param("ws", ws()).param("id", skuId).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SKU"));
    }

    /** 원 단위 정수, 0 이상(DLG-030·031). 부동소수 입력도 소수점이 있으면 거부한다. */
    static BigDecimal amount(String field, BigDecimal v) {
        if (v == null) {
            return null;
        }
        if (v.signum() < 0 || v.compareTo(MAX_AMOUNT) >= 0) {
            throw Errors.validation(field, "금액은 0원 이상 1조원 미만이어야 합니다.");
        }
        if (v.stripTrailingZeros().scale() > 0) {
            throw Errors.validation(field, "금액은 원 단위 정수로 입력하세요.");
        }
        return v.setScale(0);
    }

    private static String targetKey(UUID be, UUID sku) {
        return be + ":" + sku;
    }

    private static String won(BigDecimal v) {
        return String.format("%,d", v.longValueExact());
    }

    private static Map<String, Object> snapshot(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("sell_enabled", "purchase_enabled", "base_price", "min_price", "safety_stock", "version")) {
            m.put(k, String.valueOf(row.get(k)));
        }
        return m;
    }

    private static Map<String, Object> snapshot(PolicyView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sell_enabled", String.valueOf(v.sellEnabled()));
        m.put("purchase_enabled", String.valueOf(v.purchaseEnabled()));
        m.put("base_price", String.valueOf(v.basePrice()));
        m.put("min_price", String.valueOf(v.minPrice()));
        m.put("safety_stock", String.valueOf(v.safetyStock()));
        m.put("version", String.valueOf(v.version()));
        return m;
    }

    private UUID ws() {
        return guard.actor().workspaceId();
    }

    static final Set<String> NO_COST_TYPES = Set.of("BUNDLE", "SERVICE", "DIGITAL");
}
