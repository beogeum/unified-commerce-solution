package com.commerceos.identity.access;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 시스템 역할 템플릿(docs/08 §3 역할 11종). 권한 배정은 초안이며 OQ-026 에서 확정한다(docs/permission-matrix.md).
 */
public final class SystemRoles {
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    private static final List<String> BASE_READ = List.of(
            "business_entity.read", "channel_account.read", "logistics_account.read", "order.read",
            "work_item.read", "integration.read", "catalog.read", "inventory.read");

    public record Template(String code, String nameKo, Set<String> permissions) {
    }

    private SystemRoles() {
    }

    public static List<Template> templates(Set<String> allPermissions) {
        Map<String, Template> t = new LinkedHashMap<>();
        t.put(SUPER_ADMIN, new Template(SUPER_ADMIN, "최고관리자", new TreeSet<>(allPermissions)));
        t.put("OPS_ADMIN", tpl("OPS_ADMIN", "운영관리자", "member.read", "business_entity.manage", "channel_account.manage",
                "logistics_account.manage", "integration.manage", "integration.sync", "work_item.update", "audit.read",
                "export.create", "file.upload", "file.read", "order.update", "catalog.manage", "approval.decide", "inventory.operate", "purchase.manage", "customer.manage"));
        t.put("MD", tpl("MD", "상품/MD", "export.create", "file.upload", "file.read", "cost.read", "catalog.manage", "purchase.manage"));
        t.put("ORDER_MANAGER", tpl("ORDER_MANAGER", "주문담당", "order.update", "work_item.update", "file.upload", "file.read",
                "export.create", "pii.read", "customer.manage"));
        t.put("CS_AGENT", tpl("CS_AGENT", "CS담당", "order.update", "work_item.update", "pii.read"));
        t.put("LOGISTICS_MANAGER", tpl("LOGISTICS_MANAGER", "물류관리자", "inventory.adjust", "inventory.operate", "logistics_account.manage",
                "work_item.update", "file.upload", "file.read", "export.create", "integration.sync"));
        t.put("WAREHOUSE_WORKER", new Template("WAREHOUSE_WORKER", "창고작업자",
                new TreeSet<>(Set.of("business_entity.read", "logistics_account.read", "order.read", "work_item.read", "catalog.read", "inventory.read", "inventory.operate"))));
        t.put("FINANCE", tpl("FINANCE", "정산/재무", "bank_account.read", "bank_account.manage", "settlement.read", "cost.read",
                "profit.read", "period.close", "export.create", "file.upload", "file.read", "audit.read", "cost.manage", "settlement.manage", "vat.manage", "receivable.manage"));
        t.put("ANALYST", tpl("ANALYST", "분석읽기전용", "cost.read", "profit.read", "settlement.read", "export.create"));
        t.put("VIEWER", tpl("VIEWER", "일반읽기전용"));
        t.put("INTEGRATION_ADMIN", tpl("INTEGRATION_ADMIN", "연동관리자", "integration.manage", "integration.credential.manage",
                "integration.capability.verify", "integration.sync", "channel_account.manage", "logistics_account.manage",
                "work_item.update", "file.upload", "file.read", "audit.read"));
        return List.copyOf(t.values());
    }

    private static Template tpl(String code, String nameKo, String... extra) {
        Set<String> p = new TreeSet<>(BASE_READ);
        p.addAll(List.of(extra));
        return new Template(code, nameKo, p);
    }
}
