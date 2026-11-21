package com.commerceos.identity.auth;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.shared.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 본인 계정 보안(비밀번호 변경, 2단계 인증)과 관리자 2단계 인증 초기화. */
@RestController
public class AccountSecurityController {
    private final SessionService sessions;
    private final MfaService mfa;
    private final AccessGuard guard;

    public AccountSecurityController(SessionService sessions, MfaService mfa, AccessGuard guard) {
        this.sessions = sessions;
        this.mfa = mfa;
        this.guard = guard;
    }

    public record PasswordChange(@NotBlank @Size(max = 200) String currentPassword, @NotBlank @Size(max = 200) String newPassword) {
    }

    public record CodeRequest(@Size(max = 20) String code, @Size(max = 20) String recoveryCode) {
    }

    @PostMapping("/api/v1/auth/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChange req) {
        sessions.changePassword(guard.actor(), req.currentPassword(), req.newPassword());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/auth/mfa")
    public ApiResponse<MfaService.Status> status() {
        return ApiResponse.of(mfa.status(guard.actor()));
    }

    @PostMapping("/api/v1/auth/mfa/totp/enrollment")
    public ResponseEntity<ApiResponse<MfaService.Enrollment>> startEnrollment() {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(mfa.startEnrollment(guard.actor())));
    }

    @PostMapping("/api/v1/auth/mfa/totp/enrollment/confirm")
    public ApiResponse<MfaService.Confirmed> confirmEnrollment(@Valid @RequestBody CodeRequest req) {
        return ApiResponse.of(mfa.confirmEnrollment(guard.actor(), req.code()));
    }

    @PostMapping("/api/v1/auth/mfa/verify")
    public ApiResponse<Map<String, String>> verify(@Valid @RequestBody CodeRequest req) {
        return ApiResponse.of(Map.of("mfa_status", mfa.verify(guard.actor(), req.code(), req.recoveryCode())));
    }

    @PostMapping("/api/v1/auth/mfa/recovery-codes")
    public ApiResponse<Map<String, List<String>>> regenerate() {
        return ApiResponse.of(Map.of("recovery_codes", mfa.regenerateRecoveryCodes(guard.actor())));
    }

    @PostMapping("/api/v1/users/{userId}/mfa-reset")
    public ResponseEntity<Void> reset(@PathVariable UUID userId) {
        mfa.reset(guard.actor(), userId);
        return ResponseEntity.noContent().build();
    }
}
