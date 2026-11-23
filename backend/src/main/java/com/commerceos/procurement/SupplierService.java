package com.commerceos.procurement;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공급처·공급처 SKU(FR-FIN-01, docs/01 §발주·매입, DLG-190). 공급처는 사업자별(거래·세금계산서 주체가 사업자).
 * 단가는 원가 정보라 원가 조회 권한이 없으면 숨긴다.
 */
@Service
public class SupplierService {
    static final Set<String> TAX = Set.of("TAXABLE", "TAX_FREE", "ZERO_RATED");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;

    public SupplierService(JdbcClient jdbc, AccessGuard guard, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    public record SupplierInput(UUID businessEntityId, String name, String businessRegistrationNo, Integer leadTimeDays, String paymentTerms, String memo,
                                String status) {
    }

    public record SupplierView(UUID id, UUID businessEntityId, String businessEntityName, String name, String businessRegistrationNo, int leadTimeDays,
                               String paymentTerms, String memo, String status, int skuCount, int version) {
    }

    public record SupplierSkuInput(UUID skuId, String supplierSkuCode, BigDecimal unitCost, String taxType, Integer moq, Integer orderMultiple, Integer leadTimeDays,
                                   String status) {
    }

    public record SupplierSkuView(UUID id, UUID supplierId, UUID skuId, String skuCode, String skuName, String supplierSkuCode, BigDecimal unitCost, String taxType,
                                  int moq, int orderMultiple, Integer leadTimeDays, int effectiveLeadTimeDays, String status, int version) {
    }

    // ---------------------------------------------------------------- 공급처

    @Transactional
    public SupplierView create(SupplierInput in) {
        guard.requirePermission(Perm.PURCHASE_MANAGE);
        CurrentActor a = guard.actor();
        if (in == null || in.businessEntityId() == null) {
            throw Errors.validation("business_entity_id", "공급처를 둘 사업자를 선택하세요.");
        }
        guard.checkWrite("BUSINESS_ENTITY", in.businessEntityId(), in.businessEntityId(), null);
        String name = trim(in.name(), 100, "name");
        if (name == null) {
            throw Errors.validation("name", "공급처 이름을 입력하세요.");
        }
        if (jdbc.sql("select count(*) from supplier where business_entity_id = :be and name = :n").param("be", in.businessEntityId()).param("n", name)
                .query(Integer.class).single() > 0) {
            throw Errors.conflict("SUPPLIER_DUPLICATE", "이 사업자에 같은 이름의 공급처가 있습니다.");
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into supplier (id, workspace_id, business_entity_id, name, business_registration_no, lead_time_days, payment_terms, memo)
                values (:id, :ws, :be, :n, cast(:brn as text), :lt, cast(:pt as text), cast(:m as text))
                """).param("id", id).param("ws", a.workspaceId()).param("be", in.businessEntityId()).param("n", name).param("brn", brn(in.businessRegistrationNo()))
                .param("lt", leadTime(in.leadTimeDays(), 7)).param("pt", trim(in.paymentTerms(), 200, "payment_terms")).param("m", trim(in.memo(), 500, "memo"))
                .update();
        audit.record(AuditEntry.builder(a.workspaceId(), "SUPPLIER_CREATED", "SUPPLIER", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(in.businessEntityId(), null).after(Map.of("name", name)).build());
        return get(id);
    }

    @Transactional
    public SupplierView update(UUID id, SupplierInput in, Integer version) {
        guard.requirePermission(Perm.PURCHASE_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> cur = row(a.workspaceId(), id);
        UUID be = (UUID) cur.get("business_entity_id");
        guard.checkWrite("SUPPLIER", id, be, null);
        if (version != null && version != ((Number) cur.get("version")).intValue()) {
            throw Errors.conflict("VERSION_CONFLICT", "다른 사용자가 먼저 바꿨습니다. 새로 고친 뒤 다시 시도하세요.");
        }
        String name = in.name() == null ? (String) cur.get("name") : trim(in.name(), 100, "name");
        String status = in.status() == null ? (String) cur.get("status") : in.status();
        if (!Set.of("ACTIVE", "INACTIVE").contains(status)) {
            throw Errors.validation("status", "상태는 ACTIVE·INACTIVE 입니다.");
        }
        jdbc.sql("""
                update supplier set name = :n, business_registration_no = cast(:brn as text), lead_time_days = :lt, payment_terms = cast(:pt as text),
                       memo = cast(:m as text), status = :st, updated_at = now(), version = version + 1 where id = :id
                """).param("n", name).param("brn", in.businessRegistrationNo() == null ? cur.get("business_registration_no") : brn(in.businessRegistrationNo()))
                .param("lt", in.leadTimeDays() == null ? cur.get("lead_time_days") : leadTime(in.leadTimeDays(), 7))
                .param("pt", in.paymentTerms() == null ? cur.get("payment_terms") : trim(in.paymentTerms(), 200, "payment_terms"))
                .param("m", in.memo() == null ? cur.get("memo") : trim(in.memo(), 500, "memo")).param("st", status).param("id", id).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "SUPPLIER_UPDATED", "SUPPLIER", id).actor(ActorType.USER, a.memberId(), a.displayName()).scope(be, null)
                .before(Map.of("name", cur.get("name"), "status", cur.get("status"))).after(Map.of("name", name, "status", status)).build());
        return get(id);
    }

    public List<SupplierView> list(List<String> businessEntityIds, String q) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "bes", bes));
        String where = " where s.workspace_id = :ws and s.business_entity_id in (:bes)";
        if (q != null && !q.isBlank()) {
            where += " and s.name ilike :q";
            p.put("q", "%" + q.trim() + "%");
        }
        return jdbc.sql(SELECT + where + " order by be.display_name, s.name limit 300").params(p).query(this::map).list();
    }

    public SupplierView get(UUID id) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Map<String, Object> r = row(guard.actor().workspaceId(), id);
        guard.checkRead("SUPPLIER", id, (UUID) r.get("business_entity_id"), null);
        return jdbc.sql(SELECT + " where s.id = :id").param("id", id).query(this::map).single();
    }

    // ---------------------------------------------------------------- 공급처 SKU

    @Transactional
    public SupplierSkuView upsertSku(UUID supplierId, SupplierSkuInput in) {
        guard.requirePermission(Perm.PURCHASE_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> s = row(a.workspaceId(), supplierId);
        UUID be = (UUID) s.get("business_entity_id");
        guard.checkWrite("SUPPLIER", supplierId, be, null);
        if (in == null || in.skuId() == null) {
            throw Errors.validation("sku_id", "SKU 를 선택하세요.");
        }
        if (jdbc.sql("select count(*) from sku where workspace_id = :ws and id = :id").param("ws", a.workspaceId()).param("id", in.skuId()).query(Integer.class)
                .single() == 0) {
            throw Errors.notFound("SKU");
        }
        if (in.unitCost() == null || in.unitCost().signum() < 0 || in.unitCost().stripTrailingZeros().scale() > 0) {
            throw Errors.validation("unit_cost", "매입 단가(공급가, 원 단위)를 입력하세요.");
        }
        String tax = in.taxType() == null ? "TAXABLE" : in.taxType();
        if (!TAX.contains(tax)) {
            throw Errors.validation("tax_type", "과세 유형을 확인하세요.");
        }
        int moq = in.moq() == null ? 1 : in.moq();
        int mult = in.orderMultiple() == null ? 1 : in.orderMultiple();
        if (moq < 1 || mult < 1) {
            throw Errors.validation("moq", "최소 발주수량·발주단위는 1 이상입니다.");
        }
        String status = in.status() == null ? "ACTIVE" : in.status();
        if (!Set.of("ACTIVE", "INACTIVE").contains(status)) {
            throw Errors.validation("status", "상태는 ACTIVE·INACTIVE 입니다.");
        }
        Map<String, Object> before = jdbc.sql("select unit_cost, moq, order_multiple from supplier_sku where supplier_id = :s and sku_id = :k").param("s", supplierId)
                .param("k", in.skuId()).query().listOfRows().stream().findFirst().orElse(null);
        jdbc.sql("""
                insert into supplier_sku (id, workspace_id, supplier_id, sku_id, supplier_sku_code, unit_cost, tax_type, moq, order_multiple, lead_time_days, status)
                values (:id, :ws, :s, :k, cast(:c as text), :u, :t, :m, :mu, cast(:lt as integer), :st)
                on conflict (supplier_id, sku_id) do update set supplier_sku_code = excluded.supplier_sku_code, unit_cost = excluded.unit_cost, tax_type = excluded.tax_type,
                    moq = excluded.moq, order_multiple = excluded.order_multiple, lead_time_days = excluded.lead_time_days, status = excluded.status, updated_at = now(),
                    version = supplier_sku.version + 1
                """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("s", supplierId).param("k", in.skuId())
                .param("c", trim(in.supplierSkuCode(), 100, "supplier_sku_code")).param("u", in.unitCost()).param("t", tax).param("m", moq).param("mu", mult)
                .param("lt", in.leadTimeDays() == null ? null : leadTime(in.leadTimeDays(), 7)).param("st", status).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "SUPPLIER_SKU_SAVED", "SUPPLIER", supplierId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).before(before == null ? Map.of() : Map.of("unit_cost", before.get("unit_cost").toString(), "moq", before.get("moq")))
                .after(Map.of("sku_id", in.skuId().toString(), "unit_cost", in.unitCost().toPlainString(), "moq", moq, "order_multiple", mult)).build());
        return skus(supplierId).stream().filter(x -> x.skuId().equals(in.skuId())).findFirst().orElseThrow();
    }

    public List<SupplierSkuView> skus(UUID supplierId) {
        guard.requirePermission(Perm.INVENTORY_READ);
        Map<String, Object> s = row(guard.actor().workspaceId(), supplierId);
        guard.checkRead("SUPPLIER", supplierId, (UUID) s.get("business_entity_id"), null);
        boolean cost = guard.actor().has(Perm.COST_READ) || guard.actor().has(Perm.PURCHASE_MANAGE);
        int supplierLead = ((Number) s.get("lead_time_days")).intValue();
        return jdbc.sql("""
                select ss.*, k.sku_code, k.name as sku_name from supplier_sku ss join sku k on k.id = ss.sku_id where ss.supplier_id = :s order by k.sku_code
                """).param("s", supplierId).query((rs, i) -> new SupplierSkuView(Db.uuid(rs, "id"), supplierId, Db.uuid(rs, "sku_id"), rs.getString("sku_code"),
                rs.getString("sku_name"), rs.getString("supplier_sku_code"), cost ? rs.getBigDecimal("unit_cost") : null, rs.getString("tax_type"), rs.getInt("moq"),
                rs.getInt("order_multiple"), (Integer) rs.getObject("lead_time_days"), rs.getObject("lead_time_days") == null ? supplierLead : rs.getInt("lead_time_days"),
                rs.getString("status"), rs.getInt("version"))).list();
    }

    // ---------------------------------------------------------------- 내부

    Map<String, Object> row(UUID ws, UUID id) {
        return jdbc.sql("select * from supplier where workspace_id = :ws and id = :id").param("ws", ws).param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> Errors.notFound("SUPPLIER"));
    }

    static final String SELECT = """
            select s.*, be.display_name as be_name, (select count(*) from supplier_sku x where x.supplier_id = s.id and x.status = 'ACTIVE') as sku_count
              from supplier s join business_entity be on be.id = s.business_entity_id
            """;

    private SupplierView map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new SupplierView(Db.uuid(rs, "id"), Db.uuid(rs, "business_entity_id"), rs.getString("be_name"), rs.getString("name"),
                rs.getString("business_registration_no"), rs.getInt("lead_time_days"), rs.getString("payment_terms"), rs.getString("memo"), rs.getString("status"),
                rs.getInt("sku_count"), rs.getInt("version"));
    }

    static String trim(String s, int max, String field) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        if (t.length() > max) {
            throw Errors.validation(field, max + "자 이내로 입력하세요.");
        }
        return t;
    }

    private static String brn(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String d = s.replaceAll("[^0-9]", "");
        if (d.length() != 10) {
            throw Errors.validation("business_registration_no", "사업자등록번호 10자리를 입력하세요.");
        }
        return d;
    }

    private static int leadTime(Integer v, int def) {
        int x = v == null ? def : v;
        if (x < 0 || x > 365) {
            throw Errors.validation("lead_time_days", "리드타임은 0~365일입니다.");
        }
        return x;
    }
}
