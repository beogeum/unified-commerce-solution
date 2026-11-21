package com.commerceos.identity.workspace;

import com.commerceos.identity.access.SystemRoles;
import com.commerceos.identity.member.PasswordPolicy;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영그룹 개설(시스템 작업). 테넌트 API 로는 노출하지 않는다 — 운영그룹 관리자라도 다른 그룹을 만들거나 볼 수 없다(docs/08 §1).
 * 개발 환경에서는 BootstrapRunner 가, 운영에서는 별도 운영 절차가 호출한다.
 */
@Service
public class WorkspaceProvisioningService {
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public WorkspaceProvisioningService(JdbcClient jdbc, PasswordEncoder encoder, AuditService audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
    }

    public record Provisioned(UUID workspaceId, UUID ownerUserId, UUID ownerMemberId) {
    }

    @Transactional
    public Provisioned provision(String code, String name, String ownerEmail, String ownerName, String ownerPassword) {
        if (jdbc.sql("select count(*) from workspace where code = :c").param("c", code).query(Integer.class).single() > 0) {
            throw Errors.conflict("WORKSPACE_CODE_TAKEN", "이미 사용 중인 운영그룹 코드입니다.");
        }
        UUID wsId = Ids.newId();
        jdbc.sql("insert into workspace (id, code, name) values (:id, :c, :n)").param("id", wsId).param("c", code).param("n", name).update();

        Set<String> all = new HashSet<>(jdbc.sql("select code from permission").query(String.class).list());
        UUID superAdminRole = null;
        for (SystemRoles.Template t : SystemRoles.templates(all)) {
            UUID roleId = Ids.newId();
            jdbc.sql("insert into role (id, workspace_id, code, name_ko, is_system) values (:id, :ws, :c, :n, true)")
                    .param("id", roleId).param("ws", wsId).param("c", t.code()).param("n", t.nameKo()).update();
            for (String p : t.permissions()) {
                jdbc.sql("insert into role_permission (workspace_id, role_id, permission_code) values (:ws, :r, :p)")
                        .param("ws", wsId).param("r", roleId).param("p", p).update();
            }
            if (SystemRoles.SUPER_ADMIN.equals(t.code())) {
                superAdminRole = roleId;
            }
        }

        Optional<UUID> existing = jdbc.sql("select id from user_account where email = :e").param("e", ownerEmail).query(UUID.class).optional();
        UUID userId = existing.orElseGet(() -> {
            PasswordPolicy.validate(ownerPassword, ownerEmail);
            UUID id = Ids.newId();
            jdbc.sql("insert into user_account (id, email, display_name, password_hash) values (:id, :e, :n, :h)")
                    .param("id", id).param("e", ownerEmail).param("n", ownerName).param("h", encoder.encode(ownerPassword)).update();
            return id;
        });
        UUID memberId = Ids.newId();
        jdbc.sql("insert into workspace_member (id, workspace_id, user_id) values (:id, :ws, :u)")
                .param("id", memberId).param("ws", wsId).param("u", userId).update();
        jdbc.sql("insert into member_role (workspace_id, member_id, role_id) values (:ws, :m, :r)")
                .param("ws", wsId).param("m", memberId).param("r", superAdminRole).update();
        audit.record(AuditEntry.builder(wsId, "WORKSPACE_PROVISIONED", "WORKSPACE", wsId)
                .actor(ActorType.SYSTEM, null, "provisioning").after(Map.of("code", code, "owner_member_id", memberId.toString())).build());
        return new Provisioned(wsId, userId, memberId);
    }
}
