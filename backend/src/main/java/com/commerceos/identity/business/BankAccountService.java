package com.commerceos.identity.business;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.crypto.SecretCipher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사업자 정산계좌(FR-ORG-01). 계좌번호는 암호화 저장, bank_account.read 가 없으면 마스킹(DEC-F13).
 * 원문 열람은 감사로그에 남긴다(값은 남기지 않음).
 */
@Service
public class BankAccountService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final SecretCipher cipher;
    private final BusinessEntityService entities;

    public BankAccountService(JdbcClient jdbc, AccessGuard guard, AuditService audit, SecretCipher cipher, BusinessEntityService entities) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.cipher = cipher;
        this.entities = entities;
    }

    public record BankAccountView(UUID id, UUID businessEntityId, String bankCode, String accountHolder, String accountNumber,
                                  boolean accountNumberMasked, boolean isDefault, String status, int version, Instant createdAt) {
    }

    public record Create(String bankCode, String accountHolder, String accountNumber, boolean isDefault) {
    }

    public record Patch(Boolean isDefault, String status, String accountHolder) {
    }

    record Row(UUID id, UUID be, String bankCode, String holder, byte[] cipher, String keyId, String last4, boolean isDefault,
               String status, int version, Instant createdAt) {
    }

    public List<BankAccountView> list(UUID businessEntityId) {
        entities.get(businessEntityId); // business_entity.read + READ 범위
        CurrentActor a = guard.actor();
        boolean reveal = a.has(Perm.BANK_ACCOUNT_READ);
        List<BankAccountView> views = rows(a.workspaceId(), businessEntityId, null).stream().map(r -> view(r, reveal)).toList();
        if (reveal && !views.isEmpty()) {
            audit.record(AuditEntry.builder(a.workspaceId(), "SENSITIVE_FIELD_VIEWED", "BANK_ACCOUNT", null)
                    .actor(ActorType.USER, a.memberId(), a.displayName()).scope(businessEntityId, null)
                    .metadata(Map.of("field", "account_number", "count", views.size())).build());
        }
        return views;
    }

    @Transactional
    public BankAccountView create(UUID businessEntityId, Create c) {
        guard.requirePermission(Perm.BANK_ACCOUNT_MANAGE);
        entities.get(businessEntityId);
        guard.checkWrite("BUSINESS_ENTITY", businessEntityId, businessEntityId, null);
        CurrentActor a = guard.actor();
        if (c.bankCode() == null || !c.bankCode().matches("^[0-9]{3}$")) {
            throw Errors.validation("bank_code", "은행 코드는 숫자 3자리여야 합니다.");
        }
        String digits = c.accountNumber() == null ? "" : c.accountNumber().replace("-", "").trim();
        if (!digits.matches("^[0-9]{6,20}$")) {
            throw Errors.validation("account_number", "계좌번호는 숫자 6~20자리여야 합니다.");
        }
        if (c.accountHolder() == null || c.accountHolder().isBlank()) {
            throw Errors.validation("account_holder", "예금주를 입력하세요.");
        }
        byte[] hmac = cipher.hmac(c.bankCode() + ":" + digits);
        if (jdbc.sql("select count(*) from bank_account where business_entity_id = :be and account_number_hmac = :h")
                .param("be", businessEntityId).param("h", hmac).query(Integer.class).single() > 0) {
            throw Errors.conflict("BANK_ACCOUNT_DUPLICATE", "이미 등록된 계좌입니다.");
        }
        jdbc.sql("select id from business_entity where id = :be for update").param("be", businessEntityId).query(UUID.class).single();
        boolean hasDefault = jdbc.sql("select count(*) from bank_account where business_entity_id = :be and is_default and status = 'ACTIVE'")
                .param("be", businessEntityId).query(Integer.class).single() > 0;
        boolean makeDefault = c.isDefault() || !hasDefault;
        if (makeDefault && hasDefault) {
            jdbc.sql("update bank_account set is_default = false, version = version + 1, updated_at = now() where business_entity_id = :be and is_default")
                    .param("be", businessEntityId).update();
        }
        SecretCipher.Encrypted enc = cipher.encrypt(digits);
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into bank_account (id, workspace_id, business_entity_id, bank_code, account_holder, account_number_cipher,
                    account_number_key_id, account_number_last4, account_number_hmac, is_default)
                values (:id, :ws, :be, :bank, :holder, :cipher, :kid, :last4, :hmac, :def)
                """).param("id", id).param("ws", a.workspaceId()).param("be", businessEntityId).param("bank", c.bankCode())
                .param("holder", c.accountHolder().trim()).param("cipher", enc.cipherText()).param("kid", enc.keyId())
                .param("last4", digits.substring(digits.length() - 4)).param("hmac", hmac).param("def", makeDefault).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "BANK_ACCOUNT_CREATED", "BANK_ACCOUNT", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(businessEntityId, null)
                .after(Map.of("bank_code", c.bankCode(), "account_number", mask(digits.substring(digits.length() - 4)), "is_default", makeDefault))
                .build());
        // 생성 응답은 멱등 재생용으로 저장되므로 원문을 담지 않는다(항상 마스킹).
        return view(rows(a.workspaceId(), businessEntityId, id).get(0), false);
    }

    @Transactional
    public BankAccountView patch(UUID businessEntityId, UUID accountId, int expectedVersion, Patch p) {
        guard.requirePermission(Perm.BANK_ACCOUNT_MANAGE);
        entities.get(businessEntityId);
        guard.checkWrite("BUSINESS_ENTITY", businessEntityId, businessEntityId, null);
        CurrentActor a = guard.actor();
        jdbc.sql("select id from business_entity where id = :be for update").param("be", businessEntityId).query(UUID.class).single();
        Row before = rows(a.workspaceId(), businessEntityId, accountId).stream().findFirst()
                .orElseThrow(() -> Errors.notFound("BANK_ACCOUNT"));
        if (before.version() != expectedVersion) {
            throw Errors.versionConflict(expectedVersion, before.version());
        }
        if (p.status() != null) {
            BusinessEntityService.validateEnum("status", p.status(), java.util.Set.of("ACTIVE", "INACTIVE"));
            if ("INACTIVE".equals(p.status()) && before.isDefault()) {
                throw Errors.conflict("DEFAULT_BANK_ACCOUNT", "기본 정산계좌는 비활성화할 수 없습니다. 다른 계좌를 기본으로 지정한 뒤 진행하세요.");
            }
        }
        if (Boolean.TRUE.equals(p.isDefault()) && !before.isDefault()) {
            if ("INACTIVE".equals(before.status()) && !"ACTIVE".equals(p.status())) {
                throw Errors.conflict("INACTIVE_BANK_ACCOUNT", "비활성 계좌는 기본 정산계좌로 지정할 수 없습니다.");
            }
            jdbc.sql("update bank_account set is_default = false, version = version + 1, updated_at = now() where business_entity_id = :be and is_default")
                    .param("be", businessEntityId).update();
        }
        if (Boolean.FALSE.equals(p.isDefault()) && before.isDefault()) {
            throw Errors.conflict("DEFAULT_BANK_ACCOUNT", "기본 정산계좌는 다른 계좌를 기본으로 지정하는 방식으로만 바꿀 수 있습니다.");
        }
        jdbc.sql("""
                update bank_account set is_default = coalesce(:def, is_default), status = coalesce(:st, status),
                       account_holder = coalesce(:holder, account_holder), version = version + 1, updated_at = now()
                 where id = :id
                """).param("def", p.isDefault()).param("st", p.status()).param("holder", p.accountHolder() == null ? null : p.accountHolder().trim())
                .param("id", accountId).update();
        Row after = rows(a.workspaceId(), businessEntityId, accountId).get(0);
        audit.record(AuditEntry.builder(a.workspaceId(), "BANK_ACCOUNT_UPDATED", "BANK_ACCOUNT", accountId)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(businessEntityId, null)
                .before(Map.of("is_default", before.isDefault(), "status", before.status()))
                .after(Map.of("is_default", after.isDefault(), "status", after.status())).build());
        return view(after, false);
    }

    private List<Row> rows(UUID ws, UUID be, UUID id) {
        return jdbc.sql("""
                select * from bank_account where workspace_id = :ws and business_entity_id = :be and (cast(:id as uuid) is null or id = :id)
                 order by is_default desc, created_at
                """).param("ws", ws).param("be", be).param("id", id)
                .query((rs, i) -> new Row(Db.uuid(rs, "id"), Db.uuid(rs, "business_entity_id"), rs.getString("bank_code"),
                        rs.getString("account_holder"), rs.getBytes("account_number_cipher"), rs.getString("account_number_key_id"),
                        rs.getString("account_number_last4"), rs.getBoolean("is_default"), rs.getString("status"), rs.getInt("version"),
                        Db.instant(rs, "created_at"))).list();
    }

    private BankAccountView view(Row r, boolean reveal) {
        String number = reveal ? cipher.decrypt(r.cipher(), r.keyId()) : mask(r.last4());
        return new BankAccountView(r.id(), r.be(), r.bankCode(), r.holder(), number, !reveal, r.isDefault(), r.status(), r.version(), r.createdAt());
    }

    static String mask(String last4) {
        return "****" + last4;
    }
}
