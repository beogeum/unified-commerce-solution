package com.commerceos.shared.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** 표준 오류 팩토리. 코드 목록은 docs/api/foundation-api.md §오류 코드. */
public final class Errors {
    private Errors() {
    }

    public static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요합니다.", false, null);
    }

    public static ApiException loginFailed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "이메일 또는 비밀번호가 올바르지 않습니다.", false, null);
    }

    public static ApiException accountLocked() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_LOCKED",
                "로그인 실패가 반복되어 계정이 잠시 잠겼습니다. 잠시 후 다시 시도하거나 관리자에게 문의하세요.", false, null);
    }

    public static ApiException permissionDenied(String permission) {
        return new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "이 작업을 할 권한이 없습니다.", false,
                Map.of("required_permission", permission));
    }

    public static ApiException scopeDenied(String dimension, Object targetId) {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCESS_SCOPE_DENIED", "접근이 허용되지 않은 사업자 또는 판매계정입니다.", false,
                Map.of("dimension", dimension, "target_id", String.valueOf(targetId)));
    }

    public static ApiException writeScopeDenied() {
        return new ApiException(HttpStatus.FORBIDDEN, "WRITE_SCOPE_DENIED", "이 사업자 또는 판매계정의 정보를 수정할 권한이 없습니다.", false, null);
    }

    public static ApiException notFound(String resource) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다.", false, Map.of("resource", resource));
    }

    public static ApiException validation(String field, String messageKo) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", messageKo, false, Map.of("field", field));
    }

    public static ApiException conflict(String code, String messageKo) {
        return new ApiException(HttpStatus.CONFLICT, code, messageKo, false, null);
    }

    public static ApiException conflict(String code, String messageKo, Map<String, Object> details) {
        return new ApiException(HttpStatus.CONFLICT, code, messageKo, false, details);
    }

    public static ApiException unprocessable(String code, String messageKo) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, messageKo, false, null);
    }

    public static ApiException unprocessable(String code, String messageKo, Map<String, Object> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, messageKo, false, details);
    }

    public static ApiException versionConflict(int expected, int actual) {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                "다른 사용자가 먼저 변경했습니다. 최신 내용을 다시 불러온 뒤 수정하세요.", false,
                Map.of("expected_version", expected, "current_version", actual));
    }

    public static ApiException preconditionRequired() {
        return new ApiException(HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED",
                "수정하려면 현재 버전(If-Match)이 필요합니다.", false, null);
    }

    public static ApiException idempotencyKeyRequired() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REQUIRED",
                "중복 실행 방지 키(Idempotency-Key)가 필요합니다.", false, null);
    }
}
