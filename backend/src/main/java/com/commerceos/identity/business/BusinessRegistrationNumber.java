package com.commerceos.identity.business;

import com.commerceos.shared.error.Errors;

/** 사업자등록번호 10자리 형식·검증번호 확인(국세청 공개 산식). 하이픈은 제거해 저장한다. */
public final class BusinessRegistrationNumber {
    private static final int[] WEIGHTS = {1, 3, 7, 1, 3, 7, 1, 3, 5};

    private BusinessRegistrationNumber() {
    }

    public static String normalize(String raw) {
        String digits = raw == null ? "" : raw.replace("-", "").trim();
        if (!digits.matches("^[0-9]{10}$")) {
            throw Errors.validation("business_registration_no", "사업자등록번호는 숫자 10자리여야 합니다.");
        }
        if (!checksumValid(digits)) {
            throw Errors.validation("business_registration_no", "사업자등록번호 검증번호가 맞지 않습니다. 번호를 다시 확인하세요.");
        }
        return digits;
    }

    static boolean checksumValid(String d) {
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (d.charAt(i) - '0') * WEIGHTS[i];
        }
        sum += ((d.charAt(8) - '0') * 5) / 10;
        int check = (10 - (sum % 10)) % 10;
        return check == d.charAt(9) - '0';
    }
}
