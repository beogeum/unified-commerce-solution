package com.commerceos.identity.access;

import java.util.Set;
import java.util.UUID;

/** 인증된 요청 주체. 매 요청 DB 에서 다시 읽어 권한 변경이 즉시 반영된다. */
public record CurrentActor(
        UUID workspaceId,
        UUID memberId,
        UUID userId,
        String displayName,
        Set<String> permissions,
        Set<String> roleCodes,
        int accessPolicyVersion,
        UUID sessionId,
        String mfaStatus) {

    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    public boolean isSuperAdmin() {
        return roleCodes.contains(SystemRoles.SUPER_ADMIN);
    }
}
