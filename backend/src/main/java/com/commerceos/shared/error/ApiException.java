package com.commerceos.shared.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 사용자에게 보여 줄 한국어 메시지와 기계 판독용 코드를 가진 업무 오류.
 * 기술 세부는 로그/연동 이력에만 남기고 message 에 넣지 않는다(CLAUDE.md).
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final boolean retryable;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String messageKo, boolean retryable, Map<String, Object> details) {
        super(messageKo);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> details() {
        return details;
    }
}
