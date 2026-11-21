package com.commerceos.identity.member;

import com.commerceos.shared.error.Errors;

/** 임시 비밀번호 정책(OQ-023 확정 전): 10자 이상, 영문·숫자 포함. */
public final class PasswordPolicy {
    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < 10 || password.length() > 200
                || !password.chars().anyMatch(Character::isLetter) || !password.chars().anyMatch(Character::isDigit)) {
            throw Errors.validation("password", "비밀번호는 10자 이상이며 영문과 숫자를 모두 포함해야 합니다.");
        }
    }
}
