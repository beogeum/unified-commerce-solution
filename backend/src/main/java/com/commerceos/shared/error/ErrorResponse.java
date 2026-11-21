package com.commerceos.shared.error;

import java.util.Map;

/** docs/07 §2 오류 응답 형식. */
public record ErrorResponse(Body error, String requestId) {
    public record Body(String code, String message, boolean retryable, Map<String, Object> details) {
    }
}
