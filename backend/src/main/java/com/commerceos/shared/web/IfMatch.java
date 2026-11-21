package com.commerceos.shared.web;

import com.commerceos.shared.error.Errors;

/** If-Match: "3" 또는 W/"3" 형식에서 버전 정수를 꺼낸다(DEC-F11). */
public final class IfMatch {
    private IfMatch() {
    }

    public static int parse(String header) {
        if (header == null || header.isBlank()) {
            throw Errors.preconditionRequired();
        }
        String v = header.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw Errors.validation("If-Match", "If-Match 값이 올바르지 않습니다.");
        }
    }

    public static String etag(int version) {
        return "\"" + version + "\"";
    }
}
