package com.commerceos.identity.access;

import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.error.Errors;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 서비스 계층 인가 진입점. 컨트롤러·서비스·백그라운드 작업·내보내기·검색이 모두 이 규칙을 쓴다(docs/08 §4).
 * <ul>
 *   <li>다른 운영그룹 객체: 쿼리가 항상 workspace_id 로 한정되므로 존재하지 않는 것으로 처리(404)</li>
 *   <li>같은 운영그룹이지만 READ 범위 밖 객체: 404 + 감사 DENIED (DEC-F01)</li>
 *   <li>READ 는 되지만 WRITE 범위 밖: 403 WRITE_SCOPE_DENIED</li>
 *   <li>범위 밖 사업자를 목록 필터로 명시: 403 ACCESS_SCOPE_DENIED</li>
 * </ul>
 */
@Component
public class AccessGuard {
    private static final String SCOPE_ATTR = AccessGuard.class.getName() + ".scope";

    private final AccessScopeService scopes;
    private final AuditService audit;

    public AccessGuard(AccessScopeService scopes, AuditService audit) {
        this.scopes = scopes;
        this.audit = audit;
    }

    public CurrentActor actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentActor actor) {
            return actor;
        }
        throw Errors.unauthenticated();
    }

    public void requirePermission(String permission) {
        CurrentActor a = actor();
        if (!a.has(permission)) {
            audit.recordIsolated(AuditEntry.builder(a.workspaceId(), "PERMISSION_DENIED", "PERMISSION", null)
                    .actor(ActorType.USER, a.memberId(), a.displayName()).outcome("DENIED")
                    .metadata(Map.of("permission", permission)).build());
            throw Errors.permissionDenied(permission);
        }
    }

    /** 현재 요청 사용자의 범위. 한 요청 안에서는 한 번만 계산한다. */
    public ResolvedScope scope() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object cached = attrs.getAttribute(SCOPE_ATTR, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof ResolvedScope s) {
                return s;
            }
        }
        CurrentActor a = actor();
        ResolvedScope s = scopes.resolve(a.workspaceId(), a.memberId());
        if (attrs != null) {
            attrs.setAttribute(SCOPE_ATTR, s, RequestAttributes.SCOPE_REQUEST);
        }
        return s;
    }

    /** 요청 중 범위가 바뀐 경우(접근정책 변경 직후 등) 캐시를 비운다. */
    public void invalidateScope() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.removeAttribute(SCOPE_ATTR, RequestAttributes.SCOPE_REQUEST);
        }
    }

    public void checkRead(String targetType, UUID targetId, UUID businessEntityId, UUID channelAccountId) {
        if (!scope().allows(AccessKind.READ, businessEntityId, channelAccountId)) {
            denied("SCOPE_READ_DENIED", targetType, targetId, businessEntityId, channelAccountId);
            throw Errors.notFound(targetType);
        }
    }

    public void checkWrite(String targetType, UUID targetId, UUID businessEntityId, UUID channelAccountId) {
        checkRead(targetType, targetId, businessEntityId, channelAccountId);
        if (!scope().allows(AccessKind.WRITE, businessEntityId, channelAccountId)) {
            denied("SCOPE_WRITE_DENIED", targetType, targetId, businessEntityId, channelAccountId);
            throw Errors.writeScopeDenied();
        }
    }

    /**
     * 목록 필터의 사업자 지정값 검증. null/빈 값/"all" 은 허용 범위 전체를 뜻한다(docs/07 §4).
     * 범위 밖 사업자를 명시하면 403.
     */
    public Set<UUID> businessEntityFilter(AccessKind kind, List<String> requested) {
        Set<UUID> allowed = scope().businessEntityIds(kind);
        if (requested == null || requested.isEmpty() || (requested.size() == 1 && "all".equalsIgnoreCase(requested.get(0)))) {
            return allowed;
        }
        Set<UUID> result = new LinkedHashSet<>();
        for (String r : requested) {
            for (String part : r.split(",")) {
                UUID id = parseUuid("business_entity_id", part.trim());
                if (!allowed.contains(id)) {
                    denied("SCOPE_FILTER_DENIED", "BUSINESS_ENTITY", id, id, null);
                    throw Errors.scopeDenied(ScopeDimension.BUSINESS_ENTITY.name(), id);
                }
                result.add(id);
            }
        }
        return result;
    }

    public UUID channelAccountFilter(AccessKind kind, UUID requested) {
        if (requested != null && !scope().channelAccountIds(kind).contains(requested)) {
            denied("SCOPE_FILTER_DENIED", "CHANNEL_ACCOUNT", requested, null, requested);
            throw Errors.scopeDenied(ScopeDimension.CHANNEL_ACCOUNT.name(), requested);
        }
        return requested;
    }

    private void denied(String action, String targetType, UUID targetId, UUID be, UUID ca) {
        CurrentActor a = actor();
        audit.recordIsolated(AuditEntry.builder(a.workspaceId(), action, targetType, targetId)
                .actor(ActorType.USER, a.memberId(), a.displayName()).outcome("DENIED").scope(be, ca).build());
    }

    public static UUID parseUuid(String field, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw Errors.validation(field, field + " 값이 올바르지 않습니다.");
        }
    }
}
