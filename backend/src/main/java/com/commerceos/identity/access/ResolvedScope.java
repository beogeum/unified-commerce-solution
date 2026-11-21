package com.commerceos.identity.access;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 한 사용자의 접근 범위를 운영그룹 내 실제 대상 ID 집합으로 풀어 둔 결과.
 * 객체 판정: 사업자 차원 통과 AND (판매계정이 있으면) 판매계정 차원 통과.
 * 사업자/판매계정 수는 운영그룹당 수십~수백 수준이므로 명시 집합으로 풀어 SQL `= any(?)` 에 그대로 쓴다.
 */
public record ResolvedScope(
        Map<AccessKind, Set<UUID>> businessEntities,
        Map<AccessKind, Set<UUID>> channelAccounts,
        Map<AccessKind, Boolean> businessEntityUnrestricted,
        Map<AccessKind, Boolean> channelAccountUnrestricted) {

    public boolean allows(AccessKind kind, UUID businessEntityId, UUID channelAccountId) {
        if (businessEntityId != null && !businessEntities.get(kind).contains(businessEntityId)) {
            return false;
        }
        return channelAccountId == null || channelAccounts.get(kind).contains(channelAccountId);
    }

    public Set<UUID> businessEntityIds(AccessKind kind) {
        return businessEntities.get(kind);
    }

    public Set<UUID> channelAccountIds(AccessKind kind) {
        return channelAccounts.get(kind);
    }

    /** 제한이 전혀 없는지(모든 사업자·판매계정 ALL 이고 DENY 없음). 화면 표시용. */
    public boolean unrestricted(AccessKind kind) {
        return businessEntityUnrestricted.get(kind) && channelAccountUnrestricted.get(kind);
    }
}
