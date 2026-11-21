package com.commerceos.identity.auth;

import com.commerceos.identity.access.CurrentActor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.commerceos.shared.db.TenantContext;
import com.commerceos.shared.error.ErrorResponse;
import com.commerceos.shared.web.RequestIdFilter;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorization: Bearer &lt;token&gt; 인증. 토큰 원문은 로그에 남기지 않는다.
 * 2단계 인증을 마치지 않은 세션은 인증·로그아웃·내 정보 API 만 쓸 수 있다(DLG-014).
 */
public class BearerTokenFilter extends OncePerRequestFilter {
    private final SessionService sessions;
    private final ObjectMapper mapper;

    public BearerTokenFilter(SessionService sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    static boolean allowedBeforeMfa(HttpServletRequest req) {
        String path = req.getRequestURI();
        String m = req.getMethod();
        return path.equals("/api/v1/auth/mfa")
                || path.equals("/api/v1/auth/mfa/totp/enrollment")
                || path.equals("/api/v1/auth/mfa/totp/enrollment/confirm")
                || path.equals("/api/v1/auth/mfa/verify")
                || ("DELETE".equals(m) && path.equals("/api/v1/auth/sessions/current"))
                || ("GET".equals(m) && path.equals("/api/v1/me"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            // 토큰 확인은 운영그룹을 모르는 상태에서 하므로 시스템 문맥, 확인 뒤에는 그 운영그룹 문맥(F8)
            Optional<CurrentActor> actor = TenantContext.asSystem(() -> sessions.authenticate(token));
            actor.ifPresent(a -> TenantContext.setWorkspace(a.workspaceId()));
            if (actor.isPresent() && !MfaPolicy.satisfied(actor.get().mfaStatus()) && !allowedBeforeMfa(request)) {
                boolean enroll = MfaPolicy.ENROLLMENT_REQUIRED.equals(actor.get().mfaStatus());
                response.setStatus(403);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                mapper.writeValue(response.getOutputStream(), new ErrorResponse(new ErrorResponse.Body(
                        enroll ? "MFA_ENROLLMENT_REQUIRED" : "MFA_VERIFICATION_REQUIRED",
                        enroll ? "이 역할은 2단계 인증 등록이 필요합니다. OTP 앱을 등록한 뒤 이용하세요."
                                : "2단계 인증 코드를 입력해야 이용할 수 있습니다.", false, Map.of()), RequestIdFilter.current()));
                return;
            }
            actor.ifPresent(a -> SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(a, null, List.of())));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }
}
