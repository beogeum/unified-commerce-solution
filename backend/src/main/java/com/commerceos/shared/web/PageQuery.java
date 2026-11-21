package com.commerceos.shared.web;

import com.commerceos.shared.error.Errors;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 커서 페이지네이션. 내부 ID(UUIDv7, 시간순) 내림차순 키셋 페이지.
 * 커서는 불투명 문자열이며 클라이언트가 해석하지 않는다.
 */
public record PageQuery(int limit, UUID after) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public static PageQuery of(Integer limit, String cursor) {
        int l = limit == null ? DEFAULT_LIMIT : limit;
        if (l < 1 || l > MAX_LIMIT) {
            throw Errors.validation("limit", "limit 은 1~200 사이여야 합니다.");
        }
        UUID after = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                after = UUID.fromString(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw Errors.validation("cursor", "cursor 값이 올바르지 않습니다.");
            }
        }
        return new PageQuery(l, after);
    }

    /** limit+1 개를 조회한 결과에서 다음 커서를 만든다. */
    public <T> ApiResponse<List<T>> toResponse(List<T> rowsPlusOne, Function<T, UUID> idOf) {
        if (rowsPlusOne.size() > limit) {
            List<T> page = rowsPlusOne.subList(0, limit);
            UUID last = idOf.apply(page.get(page.size() - 1));
            String next = Base64.getUrlEncoder().withoutPadding().encodeToString(last.toString().getBytes(StandardCharsets.UTF_8));
            return ApiResponse.page(List.copyOf(page), next);
        }
        return ApiResponse.page(rowsPlusOne, null);
    }

    public int fetchSize() {
        return limit + 1;
    }
}
