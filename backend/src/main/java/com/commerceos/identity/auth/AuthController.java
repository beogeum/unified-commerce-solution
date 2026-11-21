package com.commerceos.identity.auth;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/sessions")
public class AuthController {
    private final SessionService sessions;
    private final AccessGuard guard;

    public AuthController(SessionService sessions, AccessGuard guard) {
        this.sessions = sessions;
        this.guard = guard;
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String workspaceCode,
                               @NotBlank @Size(max = 254) String email,
                               @NotBlank @Size(max = 200) String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, UUID workspaceId, String workspaceCode,
                                UUID memberId, UUID userId, String mfaStatus) {
    }

    @PostMapping
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        SessionService.LoginResult r = sessions.login(req.workspaceCode(), req.email().trim(), req.password(),
                http.getRemoteAddr(), http.getHeader("User-Agent"));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(new LoginResponse(r.accessToken(), "Bearer",
                r.expiresAt(), r.workspaceId(), r.workspaceCode(), r.memberId(), r.userId(), r.mfaStatus())));
    }

    @DeleteMapping("/current")
    public ResponseEntity<Void> logout() {
        sessions.logout(guard.actor());
        return ResponseEntity.noContent().build();
    }
}
