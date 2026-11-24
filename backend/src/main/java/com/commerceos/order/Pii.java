package com.commerceos.order;

import java.util.Set;

/** 주문 개인정보 마스킹(DLG-117). 화면·API 기본값은 마스킹, 원문은 pii.read + 열람 감사로그. */
public final class Pii {
    private static final Set<String> KEYS = Set.of("name", "phone", "email", "postal_code", "address1", "address2", "memo", "address", "tel", "mobile",
            "receiver_name", "receiver_phone", "buyer_name", "buyer_phone");

    private Pii() {
    }

    static boolean isPiiKey(String key) {
        return KEYS.contains(key.toLowerCase());
    }

    /** 이름: 첫 글자와 마지막 글자만(2자는 첫 글자만). */
    public static String name(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String s = v.trim();
        if (s.length() == 1) {
            return "*";
        }
        if (s.length() == 2) {
            return s.charAt(0) + "*";
        }
        return s.charAt(0) + "*".repeat(s.length() - 2) + s.charAt(s.length() - 1);
    }

    /** 전화: 끝 4자리만. */
    public static String phone(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String digits = v.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return "****";
        }
        return digits.substring(0, Math.min(3, digits.length() - 4)) + "-****-" + digits.substring(digits.length() - 4);
    }

    /** 주소: 앞 두 덩어리(시·도, 시·군·구)만. */
    static String address(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String[] parts = v.trim().split("\\s+");
        return parts.length <= 2 ? parts[0] + " ***" : parts[0] + " " + parts[1] + " ***";
    }

    static String maskGeneric(String v) {
        if (v.length() <= 2) {
            return "**";
        }
        return v.charAt(0) + "*".repeat(Math.min(8, v.length() - 2)) + v.charAt(v.length() - 1);
    }
}
