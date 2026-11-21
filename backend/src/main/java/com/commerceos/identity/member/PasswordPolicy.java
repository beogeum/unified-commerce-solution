package com.commerceos.identity.member;

import com.commerceos.shared.error.Errors;
import java.util.Locale;

/**
 * 비밀번호 정책(DLG-011, KISA 비밀번호 선택·이용 안내서 기준).
 * 영문 대문자·소문자·숫자·특수문자 중 2종 이상 10자 이상, 또는 3종 이상 8자 이상. 최대 128자.
 * 같은 문자 4번 반복(aaaa), 연속 문자 4개(abcd, 4321), 이메일 아이디 포함은 허용하지 않는다.
 */
public final class PasswordPolicy {
    static final int MAX_LENGTH = 128;
    static final String MESSAGE = "비밀번호는 영문 대·소문자, 숫자, 특수문자 중 2종류 이상을 섞어 10자 이상(3종류 이상이면 8자 이상)으로 정해 주세요.";

    private PasswordPolicy() {
    }

    public static void validate(String password, String email) {
        if (password == null || password.length() > MAX_LENGTH) {
            throw Errors.validation("password", MESSAGE);
        }
        int kinds = 0;
        kinds += password.chars().anyMatch(c -> c >= 'A' && c <= 'Z') ? 1 : 0;
        kinds += password.chars().anyMatch(c -> c >= 'a' && c <= 'z') ? 1 : 0;
        kinds += password.chars().anyMatch(Character::isDigit) ? 1 : 0;
        kinds += password.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c)) ? 1 : 0;
        boolean longEnough = (kinds >= 2 && password.length() >= 10) || (kinds >= 3 && password.length() >= 8);
        if (!longEnough) {
            throw Errors.validation("password", MESSAGE);
        }
        if (hasRunOrRepeat(password.toLowerCase(Locale.ROOT))) {
            throw Errors.validation("password", "같은 문자를 4번 이상 반복하거나 연속된 문자·숫자 4개(예: aaaa, abcd, 1234)는 쓸 수 없습니다.");
        }
        if (email != null) {
            String local = email.toLowerCase(Locale.ROOT).split("@", 2)[0];
            if (local.length() >= 4 && password.toLowerCase(Locale.ROOT).contains(local)) {
                throw Errors.validation("password", "비밀번호에 이메일 아이디를 포함할 수 없습니다.");
            }
        }
    }

    static boolean hasRunOrRepeat(String s) {
        for (int i = 0; i + 3 < s.length(); i++) {
            char a = s.charAt(i), b = s.charAt(i + 1), c = s.charAt(i + 2), d = s.charAt(i + 3);
            if (a == b && b == c && c == d) {
                return true;
            }
            boolean alnum = Character.isLetterOrDigit(a) && Character.isLetterOrDigit(b) && Character.isLetterOrDigit(c) && Character.isLetterOrDigit(d);
            if (alnum && b - a == c - b && c - b == d - c && Math.abs(b - a) == 1) {
                return true;
            }
        }
        return false;
    }
}
