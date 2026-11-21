package com.commerceos.identity.auth;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 2단계 인증 필요 여부 판단(DLG-014).
 * 등록한 사용자는 항상 확인이 필요하고, 필수 역할(기본: 최고관리자·운영관리자) 보유자는 등록이 필요하다.
 * 판단은 요청마다 다시 하므로 역할이 바뀌면 즉시 반영된다.
 */
@Component
public class MfaPolicy {
    public static final String NOT_REQUIRED = "NOT_REQUIRED";
    public static final String VERIFIED = "VERIFIED";
    public static final String VERIFICATION_REQUIRED = "VERIFICATION_REQUIRED";
    public static final String ENROLLMENT_REQUIRED = "ENROLLMENT_REQUIRED";

    private final Set<String> requiredRoles;
    private final Duration pendingTtl;

    public MfaPolicy(@Value("${commerce.auth.mfa-required-roles:SUPER_ADMIN,OPS_ADMIN}") String requiredRoles,
                     @Value("${commerce.auth.mfa-pending-ttl:PT10M}") Duration pendingTtl) {
        this.requiredRoles = Collections.unmodifiableSet(Arrays.stream(requiredRoles.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet()));
        this.pendingTtl = pendingTtl;
    }

    public String status(boolean sessionVerified, boolean enrolled, Set<String> roleCodes) {
        if (sessionVerified) {
            return VERIFIED;
        }
        if (enrolled) {
            return VERIFICATION_REQUIRED;
        }
        return roleCodes.stream().anyMatch(requiredRoles::contains) ? ENROLLMENT_REQUIRED : NOT_REQUIRED;
    }

    public static boolean satisfied(String status) {
        return NOT_REQUIRED.equals(status) || VERIFIED.equals(status);
    }

    public boolean requiredFor(Set<String> roleCodes) {
        return roleCodes.stream().anyMatch(requiredRoles::contains);
    }

    /** 2단계 인증을 마치지 않은 세션이 살아 있을 수 있는 시간. */
    public Duration pendingTtl() {
        return pendingTtl;
    }
}
