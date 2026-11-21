package com.commerceos.shared.web;

import java.time.Instant;

/** docs/07 §2 성공 응답 형식: data / page / meta. */
public record ApiResponse<T>(T data, Page page, Meta meta) {
    public record Page(String nextCursor, Long totalEstimate) {
    }

    public record Meta(Instant asOf, String sourceStatus) {
    }

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, null, new Meta(Instant.now(), "CURRENT"));
    }

    public static <T> ApiResponse<T> page(T data, String nextCursor) {
        return new ApiResponse<>(data, new Page(nextCursor, null), new Meta(Instant.now(), "CURRENT"));
    }
}
