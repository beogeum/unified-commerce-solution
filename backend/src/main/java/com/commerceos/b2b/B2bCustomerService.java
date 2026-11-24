package com.commerceos.b2b;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.order.Pii;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.crypto.SecretCipher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B2B 거래처 마스터·단가표(DLG-226·227). 사업자별 거래처, 담당자 연락처는 암호화(화면은 마스킹, 원문은 pii.read + 열람 감사).
 * 외상 한도·결제 기한 일수는 재무 권한(receivable.manage)만 바꾼다. 단가표는 적용일별 추가 전용(이력 보존).
 */
@Service
public class B2bCustomerService {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final SecretCipher cipher;
    private final Json json;
    private final AuditService audit;

    public B2bCustomerService(JdbcClient jdbc, AccessGuard guard, SecretCipher cipher, Json json, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.cipher = cipher;
        this.json = json;
        this.audit = audit;
    }

    public record CustomerInput(UUID businessEntityId, String code, String name, String businessRegistrationNo, String contactName, String contactPhone,
                                Integer paymentTermsDays, BigDecimal creditLimit, String status, String holdReason, String memo) {
    }

    public record CustomerView(UUID id, UUID businessEntityId, String businessEntityName, String code, String name, String businessRegistrationNo,
                               String contactNameMasked, String contactPhoneMasked, int paymentTermsDays, BigDecimal creditLimit, String status, String holdReason,
                               String memo, int priceCount, java.time.Instant updatedAt, int version) {
    }

    public record PriceInput(UUID skuId, BigDecimal unitPrice, LocalDate effectiveFrom) {
    }

    public record PriceView(UUID id, UUID skuId, String skuCode, String skuName, BigDecimal unitPrice, LocalDate effectiveFrom, boolean current, String createdByName) {
    }

    public record Contact(String name, String phone) {
    }

    // ---------------------------------------------------------------- 거래처

    @Transactional
    public CustomerView create(CustomerInput in) {
        guard.requirePermission(Perm.CUSTOMER_MANAGE);
        CurrentActor a = guard.actor();
        if (in == null || in.businessEntityId() == null) {
            throw Errors.validation("business_entity_id", "거래처를 둘 사업자를 선택하세요.");
        }
        guard.checkWrite("BUSINESS_ENTITY", in.businessEntityId(), in.businessEntityId(), null);
        String code = req(in.code(), 40, "code", "거래처 코드를 입력하세요.");
        if (!code.matches("^[A-Za-z0-9_-]{1,40}$")) {
            throw Errors.validation("code", "거래처 코드는 영문·숫자·_- 40자 이내입니다.");
        }
        String name = req(in.name(), 100, "name", "거래처명을 입력하세요.");
        String brn = brn(in.businessRegistrationNo());
        boolean finance = a.has(Perm.RECEIVABLE_MANAGE);
        if (!finance && ((in.creditLimit() != null && in.creditLimit().signum() != 0) || in.paymentTermsDays() != null)) {
            throw Errors.permissionDenied(Perm.RECEIVABLE_MANAGE); // 외상 한도·결제 기한은 재무 담당만
        }
        BigDecimal limit = money(in.creditLimit() == null ? BigDecimal.ZERO : in.creditLimit(), "credit_limit");
        int terms = terms(in.paymentTermsDays() == null ? 30 : in.paymentTermsDays());
        UUID id = Ids.newId();
        SecretCipher.Encrypted enc = encryptContact(in.contactName(), in.contactPhone());
        try {
            jdbc.sql("""
                    insert into b2b_customer (id, workspace_id, business_entity_id, code, name, business_registration_no, contact_cipher, contact_key_id,
                                              contact_name_masked, contact_phone_masked, payment_terms_days, credit_limit, memo, created_by)
                    values (:id, :ws, :be, :code, :name, cast(:brn as text), :cc, cast(:ck as text), cast(:cn as text), cast(:cp as text), :t, :l, cast(:m as text), :by)
                    """).param("id", id).param("ws", a.workspaceId()).param("be", in.businessEntityId()).param("code", code).param("name", name).param("brn", brn)
                    .param("cc", enc == null ? null : enc.cipherText()).param("ck", enc == null ? null : enc.keyId()).param("cn", Pii.name(in.contactName()))
                    .param("cp", Pii.phone(in.contactPhone())).param("t", terms).param("l", limit).param("m", trim(in.memo(), 500, "memo")).param("by", a.memberId())
                    .update();
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("CUSTOMER_DUPLICATE", "이 사업자에 같은 코드나 이름의 거래처가 있습니다.");
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "B2B_CUSTOMER_CREATED", "B2B_CUSTOMER", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(in.businessEntityId(), null).after(Map.of("code", code, "name", name, "credit_limit", limit.toPlainString(), "payment_terms_days", terms)).build());
        return get(id);
    }

    @Transactional
    public CustomerView update(UUID id, Integer version, CustomerInput in) {
        guard.requirePermission(Perm.CUSTOMER_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> c = lock(a.workspaceId(), id);
        UUID be = (UUID) c.get("business_entity_id");
        guard.checkWrite("B2B_CUSTOMER", id, be, null);
        if (version != null && version != ((Number) c.get("version")).intValue()) {
            throw Errors.conflict("VERSION_CONFLICT", "다른 사용자가 먼저 바꿨습니다. 새로 고친 뒤 다시 시도하세요.");
        }
        if (in == null) {
            throw Errors.validation("body", "바꿀 내용을 입력하세요.");
        }
        BigDecimal limit = in.creditLimit() == null ? (BigDecimal) c.get("credit_limit") : money(in.creditLimit(), "credit_limit");
        int terms = in.paymentTermsDays() == null ? ((Number) c.get("payment_terms_days")).intValue() : terms(in.paymentTermsDays());
        boolean creditChanged = limit.compareTo((BigDecimal) c.get("credit_limit")) != 0 || terms != ((Number) c.get("payment_terms_days")).intValue();
        if (creditChanged && !a.has(Perm.RECEIVABLE_MANAGE)) {
            throw Errors.permissionDenied(Perm.RECEIVABLE_MANAGE);
        }
        String status = in.status() == null ? (String) c.get("status") : in.status();
        if (!Set.of("ACTIVE", "ON_HOLD", "INACTIVE").contains(status)) {
            throw Errors.validation("status", "상태는 ACTIVE(거래)·ON_HOLD(거래 보류)·INACTIVE(중지)입니다.");
        }
        String hold = "ON_HOLD".equals(status) ? trim(in.holdReason() == null ? (String) c.get("hold_reason") : in.holdReason(), 300, "hold_reason") : null;
        if ("ON_HOLD".equals(status) && hold == null) {
            throw Errors.validation("hold_reason", "거래 보류 사유를 입력하세요.");
        }
        String name = in.name() == null ? (String) c.get("name") : req(in.name(), 100, "name", "거래처명을 입력하세요.");
        String brn = in.businessRegistrationNo() == null ? (String) c.get("business_registration_no") : brn(in.businessRegistrationNo());
        boolean contact = in.contactName() != null || in.contactPhone() != null;
        Contact old = contact ? contact(c) : null;
        String cn = contact ? (in.contactName() != null ? in.contactName() : old == null ? null : old.name()) : null;
        String cp = contact ? (in.contactPhone() != null ? in.contactPhone() : old == null ? null : old.phone()) : null;
        SecretCipher.Encrypted enc = contact ? encryptContact(cn, cp) : null;
        try {
            jdbc.sql("""
                    update b2b_customer set name = :name, business_registration_no = cast(:brn as text), payment_terms_days = :t, credit_limit = :l, status = :st,
                           hold_reason = cast(:h as text), memo = coalesce(cast(:m as text), memo), updated_at = now(), version = version + 1
                     where id = :id
                    """).param("name", name).param("brn", brn).param("t", terms).param("l", limit).param("st", status).param("h", hold)
                    .param("m", trim(in.memo(), 500, "memo")).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("CUSTOMER_DUPLICATE", "이 사업자에 같은 이름의 거래처가 있습니다.");
        }
        if (contact) {
            jdbc.sql("""
                    update b2b_customer set contact_cipher = :cc, contact_key_id = cast(:ck as text), contact_name_masked = cast(:cn as text),
                           contact_phone_masked = cast(:cp as text) where id = :id
                    """).param("cc", enc == null ? null : enc.cipherText()).param("ck", enc == null ? null : enc.keyId()).param("cn", Pii.name(cn))
                    .param("cp", Pii.phone(cp)).param("id", id).update();
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", c.get("status"));
        before.put("credit_limit", ((BigDecimal) c.get("credit_limit")).toPlainString());
        before.put("payment_terms_days", c.get("payment_terms_days"));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", status);
        after.put("credit_limit", limit.toPlainString());
        after.put("payment_terms_days", terms);
        after.put("contact_changed", contact);
        audit.record(AuditEntry.builder(a.workspaceId(), creditChanged ? "B2B_CUSTOMER_CREDIT_CHANGED" : "B2B_CUSTOMER_UPDATED", "B2B_CUSTOMER", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(be, null).reason(hold).before(before).after(after).build());
        return get(id);
    }

    public List<CustomerView> list(List<String> businessEntityIds, String q, String status) {
        guard.requirePermission(Perm.ORDER_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", guard.actor().workspaceId(), "bes", bes));
        String sql = "select id from b2b_customer where workspace_id = :ws and business_entity_id in (:bes)";
        if (q != null && !q.isBlank()) {
            sql += " and (name ilike :q or code ilike :q)";
            p.put("q", "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (status != null && !status.isBlank()) {
            sql += " and status = :st";
            p.put("st", status);
        }
        return jdbc.sql(sql + " order by name limit 500").params(p).query(UUID.class).list().stream().map(this::view).toList();
    }

    public CustomerView get(UUID id) {
        guard.requirePermission(Perm.ORDER_READ);
        UUID be = beOf(id);
        guard.checkRead("B2B_CUSTOMER", id, be, null);
        return view(id);
    }

    /** 담당자 연락처 원문(pii.read + 열람 감사). */
    public Contact revealContact(UUID id) {
        guard.requirePermission(Perm.PII_READ);
        CurrentActor a = guard.actor();
        UUID be = beOf(id);
        guard.checkRead("B2B_CUSTOMER", id, be, null);
        Map<String, Object> c = jdbc.sql("select * from b2b_customer where id = :id").param("id", id).query().listOfRows().get(0);
        audit.record(AuditEntry.builder(a.workspaceId(), "PII_VIEWED", "B2B_CUSTOMER", id).actor(ActorType.USER, a.memberId(), a.displayName()).scope(be, null)
                .after(Map.of("fields", "contact")).build());
        Contact contact = contact(c);
        return contact == null ? new Contact(null, null) : contact;
    }

    // ---------------------------------------------------------------- 단가표

    @Transactional
    public List<PriceView> addPrices(UUID customerId, List<PriceInput> prices) {
        guard.requirePermission(Perm.CUSTOMER_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> c = lock(a.workspaceId(), customerId);
        UUID be = (UUID) c.get("business_entity_id");
        guard.checkWrite("B2B_CUSTOMER", customerId, be, null);
        if (prices == null || prices.isEmpty() || prices.size() > 500) {
            throw Errors.validation("prices", "단가를 1~500건 입력하세요.");
        }
        LocalDate today = LocalDate.now(KST);
        for (PriceInput p : prices) {
            if (p == null || p.skuId() == null) {
                throw Errors.validation("sku_id", "SKU 를 지정하세요.");
            }
            int skuOk = jdbc.sql("select count(*) from sku where workspace_id = :ws and id = :id").param("ws", a.workspaceId()).param("id", p.skuId()).query(Integer.class)
                    .single();
            if (skuOk == 0) {
                throw Errors.notFound("SKU");
            }
            LocalDate from = p.effectiveFrom() == null ? today : p.effectiveFrom();
            if (from.isBefore(today)) {
                throw Errors.validation("effective_from", "지난 날짜로 단가를 바꿀 수 없습니다(이미 만든 주문은 그대로입니다).");
            }
            try {
                jdbc.sql("""
                        insert into b2b_price (id, workspace_id, customer_id, sku_id, unit_price, effective_from, created_by) values (:id, :ws, :c, :s, :u, :f, :by)
                        """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("c", customerId).param("s", p.skuId())
                        .param("u", money(p.unitPrice(), "unit_price")).param("f", from).param("by", a.memberId()).update();
            } catch (DuplicateKeyException e) {
                throw Errors.conflict("PRICE_DUPLICATE", "같은 SKU·적용일 단가가 이미 있습니다. 다른 적용일로 등록하세요.");
            }
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "B2B_PRICE_ADDED", "B2B_CUSTOMER", customerId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).after(Map.of("count", prices.size())).build());
        return prices(customerId);
    }

    public List<PriceView> prices(UUID customerId) {
        guard.requirePermission(Perm.ORDER_READ);
        guard.checkRead("B2B_CUSTOMER", customerId, beOf(customerId), null);
        LocalDate today = LocalDate.now(KST);
        return jdbc.sql("""
                select p.*, k.sku_code, coalesce(k.name, pr.name) as sku_name,
                       (select u.display_name from workspace_member m join user_account u on u.id = m.user_id where m.id = p.created_by) as by_name,
                       p.effective_from = (select max(x.effective_from) from b2b_price x where x.customer_id = p.customer_id and x.sku_id = p.sku_id and x.effective_from <= :d)
                           as is_current
                  from b2b_price p join sku k on k.id = p.sku_id join product pr on pr.id = k.product_id where p.customer_id = :c order by k.sku_code, p.effective_from desc
                """).param("c", customerId).param("d", today).query((rs, i) -> new PriceView(Db.uuid(rs, "id"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"),
                rs.getString("sku_name"), rs.getBigDecimal("unit_price"), rs.getObject("effective_from", LocalDate.class), rs.getBoolean("is_current"),
                rs.getString("by_name"))).list();
    }

    /** 주문일 기준 적용 단가(없으면 비어 있음). */
    public Optional<BigDecimal> priceOn(UUID customerId, UUID skuId, LocalDate day) {
        return jdbc.sql("""
                select unit_price from b2b_price where customer_id = :c and sku_id = :s and effective_from <= :d order by effective_from desc limit 1
                """).param("c", customerId).param("s", skuId).param("d", day).query(BigDecimal.class).optional();
    }

    // ---------------------------------------------------------------- 내부

    Map<String, Object> lock(UUID ws, UUID id) {
        return jdbc.sql("select * from b2b_customer where workspace_id = :ws and id = :id for update").param("ws", ws).param("id", id).query().listOfRows().stream()
                .findFirst().orElseThrow(() -> Errors.notFound("B2B_CUSTOMER"));
    }

    UUID beOf(UUID id) {
        return jdbc.sql("select business_entity_id from b2b_customer where workspace_id = :ws and id = :id").param("ws", guard.actor().workspaceId()).param("id", id)
                .query(UUID.class).optional().orElseThrow(() -> Errors.notFound("B2B_CUSTOMER"));
    }

    private CustomerView view(UUID id) {
        return jdbc.sql("""
                select c.*, be.display_name as be_name, (select count(distinct p.sku_id) from b2b_price p where p.customer_id = c.id) as price_count
                  from b2b_customer c join business_entity be on be.id = c.business_entity_id where c.id = :id
                """).param("id", id).query((rs, i) -> new CustomerView(Db.uuid(rs, "id"), Db.uuid(rs, "business_entity_id"), rs.getString("be_name"),
                rs.getString("code"), rs.getString("name"), rs.getString("business_registration_no"), rs.getString("contact_name_masked"),
                rs.getString("contact_phone_masked"), rs.getInt("payment_terms_days"), rs.getBigDecimal("credit_limit"), rs.getString("status"),
                rs.getString("hold_reason"), rs.getString("memo"), rs.getInt("price_count"), rs.getTimestamp("updated_at").toInstant(), rs.getInt("version"))).single();
    }

    private SecretCipher.Encrypted encryptContact(String name, String phone) {
        if ((name == null || name.isBlank()) && (phone == null || phone.isBlank())) {
            return null;
        }
        Map<String, Object> pii = new LinkedHashMap<>();
        pii.put("name", name == null ? null : trim(name, 50, "contact_name"));
        pii.put("phone", phone == null ? null : trim(phone, 30, "contact_phone"));
        return cipher.encrypt(json.write(pii));
    }

    private Contact contact(Map<String, Object> c) {
        if (c.get("contact_cipher") == null) {
            return null;
        }
        var n = json.read(cipher.decrypt((byte[]) c.get("contact_cipher"), (String) c.get("contact_key_id")));
        return new Contact(n.hasNonNull("name") ? n.get("name").asString() : null, n.hasNonNull("phone") ? n.get("phone").asString() : null);
    }

    private static String brn(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String d = v.replaceAll("[^0-9]", "");
        if (d.length() != 10) {
            throw Errors.validation("business_registration_no", "사업자등록번호는 숫자 10자리입니다.");
        }
        return d;
    }

    private static int terms(int days) {
        if (days < 0 || days > 365) {
            throw Errors.validation("payment_terms_days", "결제 기한은 0~365일입니다.");
        }
        return days;
    }

    static BigDecimal money(BigDecimal v, String field) {
        if (v == null || v.signum() < 0 || v.stripTrailingZeros().scale() > 0) {
            throw Errors.validation(field, "금액을 0 이상 원 단위로 입력하세요.");
        }
        return v;
    }

    static String req(String v, int max, String field, String message) {
        String t = trim(v, max, field);
        if (t == null) {
            throw Errors.validation(field, message);
        }
        return t;
    }

    static String trim(String v, int max, String field) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String t = v.trim();
        if (t.length() > max) {
            throw Errors.validation(field, max + "자 이내로 입력하세요.");
        }
        return t;
    }
}
