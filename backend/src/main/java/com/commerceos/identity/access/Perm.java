package com.commerceos.identity.access;

/** 권한 코드 상수. 카탈로그는 V1 마이그레이션 permission 테이블, 매트릭스는 docs/permission-matrix.md. */
public final class Perm {
    public static final String ACCESS_MANAGE = "access.manage";
    public static final String MEMBER_READ = "member.read";
    public static final String BUSINESS_ENTITY_READ = "business_entity.read";
    public static final String BUSINESS_ENTITY_MANAGE = "business_entity.manage";
    public static final String BANK_ACCOUNT_READ = "bank_account.read";
    public static final String BANK_ACCOUNT_MANAGE = "bank_account.manage";
    public static final String CHANNEL_ACCOUNT_READ = "channel_account.read";
    public static final String CHANNEL_ACCOUNT_MANAGE = "channel_account.manage";
    public static final String CHANNEL_ACCOUNT_REASSIGN = "channel_account.reassign";
    public static final String LOGISTICS_ACCOUNT_READ = "logistics_account.read";
    public static final String LOGISTICS_ACCOUNT_MANAGE = "logistics_account.manage";
    public static final String INTEGRATION_READ = "integration.read";
    public static final String INTEGRATION_MANAGE = "integration.manage";
    public static final String INTEGRATION_CREDENTIAL_MANAGE = "integration.credential.manage";
    public static final String INTEGRATION_CAPABILITY_VERIFY = "integration.capability.verify";
    public static final String INTEGRATION_SYNC = "integration.sync";
    public static final String WORK_ITEM_READ = "work_item.read";
    public static final String WORK_ITEM_UPDATE = "work_item.update";
    public static final String AUDIT_READ = "audit.read";
    public static final String EXPORT_CREATE = "export.create";
    public static final String FILE_UPLOAD = "file.upload";
    public static final String FILE_READ = "file.read";
    public static final String PII_READ = "pii.read";
    public static final String ORDER_READ = "order.read";
    public static final String ORDER_UPDATE = "order.update";
    public static final String CATALOG_READ = "catalog.read";
    public static final String CATALOG_MANAGE = "catalog.manage";
    public static final String COST_READ = "cost.read";
    public static final String COST_MANAGE = "cost.manage";
    public static final String PROFIT_READ = "profit.read";
    public static final String SETTLEMENT_READ = "settlement.read";
    public static final String PERIOD_CLOSE = "period.close";
    public static final String APPROVAL_DECIDE = "approval.decide";
    public static final String INVENTORY_READ = "inventory.read";
    public static final String INVENTORY_OPERATE = "inventory.operate";
    public static final String INVENTORY_ADJUST = "inventory.adjust";
    public static final String INVENTORY_DISPOSE = "inventory.dispose";
    public static final String CLAIM_REFUND = "claim.refund";

    private Perm() {
    }
}
