package com.commerceos.shared.error;

import com.commerceos.shared.web.RequestIdFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handle(ApiException e) {
        return build(e.status(), e.code(), e.getMessage(), e.retryable(), e.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handle(MethodArgumentNotValidException e) {
        List<Map<String, String>> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "reason", String.valueOf(f.getDefaultMessage())))
                .toList();
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "입력값을 확인하세요.", false, Map.of("fields", fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        log.debug("bad request", e);
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "요청 형식이 올바르지 않습니다.", false, Map.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handle(MissingRequestHeaderException e) {
        if ("If-Match".equalsIgnoreCase(e.getHeaderName())) {
            return handle(Errors.preconditionRequired());
        }
        if ("Idempotency-Key".equalsIgnoreCase(e.getHeaderName())) {
            return handle(Errors.idempotencyKeyRequired());
        }
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "필수 요청 헤더가 없습니다.", false,
                Map.of("header", e.getHeaderName()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handle(MaxUploadSizeExceededException e) {
        return build(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "파일이 허용 크기를 초과했습니다.", false, Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handle(AccessDeniedException e) {
        return build(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "이 작업을 할 권한이 없습니다.", false, Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handle(HttpRequestMethodNotSupportedException e) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다.", false, Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handle(NoResourceFoundException e) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다.", false, Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handle(DataIntegrityViolationException e) {
        // 서비스 계층에서 선검증하지만 경쟁 상태에서는 DB 제약이 최종 방어선이 된다.
        log.warn("data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "DATA_CONFLICT", "이미 존재하거나 다른 데이터와 충돌합니다. 입력값을 확인하세요.", false, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "일시적인 오류가 발생했습니다. 잠시 후 다시 시도하세요. 문제가 계속되면 요청번호와 함께 문의하세요.", true, Map.of());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message, boolean retryable,
                                                Map<String, Object> details) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return ResponseEntity.status(status)
                .body(new ErrorResponse(new ErrorResponse.Body(code, message, retryable, new LinkedHashMap<>(details)), requestId));
    }
}
