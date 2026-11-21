package com.commerceos.identity.member;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerceos.shared.error.ApiException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"Passw0rd-test-2026", "Gr8-day!x", "two-kinds-ok-longer", "MIXEDcase-word"})
    void accepts(String pw) {
        assertThatCode(() -> PasswordPolicy.validate(pw, "someone@example.test")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ab1!", "abcdefghij", "gr8day1x", "pass-1234-word", "Gx-9876-word", "Paaaa-word-1", "someone-X9!z"})
    void rejects(String pw) {
        assertThatThrownBy(() -> PasswordPolicy.validate(pw, "someone@example.test")).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {129, 200})
    void rejectsTooLong(int len) {
        assertThatThrownBy(() -> PasswordPolicy.validate("Aa1!".repeat(len / 4 + 1).substring(0, len), null)).isInstanceOf(ApiException.class);
    }
}
