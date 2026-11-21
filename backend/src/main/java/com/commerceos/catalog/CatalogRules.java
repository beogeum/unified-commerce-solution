package com.commerceos.catalog;

import com.commerceos.shared.error.Errors;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** 상품·SKU 입력 규칙(docs/13 §1). DB CHECK 와 같은 규칙을 먼저 검사해 한국어 안내를 준다. */
final class CatalogRules {
    static final Set<String> SKU_TYPES = Set.of("NORMAL", "BUNDLE", "KIT", "MATERIAL", "GIFT", "SAMPLE", "SERVICE", "DIGITAL");
    static final Set<String> TAX_TYPES = Set.of("TAXABLE", "TAX_FREE", "ZERO_RATED");
    static final String CODE_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";

    /** 유형별 기본값: {판매가능, 구매가능, 재고관리}. 명시값이 있으면 그것을 쓰되 유형 제약은 항상 검사한다. */
    static final Map<String, boolean[]> TYPE_DEFAULTS = Map.of(
            "NORMAL", new boolean[] {true, true, true},
            "BUNDLE", new boolean[] {true, false, false},
            "KIT", new boolean[] {true, true, true},
            "MATERIAL", new boolean[] {false, true, true},
            "GIFT", new boolean[] {false, true, true},
            "SAMPLE", new boolean[] {false, true, true},
            "SERVICE", new boolean[] {true, false, false},
            "DIGITAL", new boolean[] {true, false, false});

    private CatalogRules() {
    }

    static String requireText(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            throw Errors.validation(field, field + " 을(를) 입력하세요.");
        }
        String v = value.trim();
        if (v.length() > max) {
            throw Errors.validation(field, field + " 은(는) " + max + "자 이하로 입력하세요.");
        }
        return v;
    }

    static String optionalText(String field, String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() > max) {
            throw Errors.validation(field, field + " 은(는) " + max + "자 이하로 입력하세요.");
        }
        return v.isEmpty() ? null : v;
    }

    static String requireCode(String field, String value) {
        if (value == null || !value.trim().matches(CODE_PATTERN)) {
            throw Errors.validation(field, "코드는 영문·숫자로 시작하고 영문·숫자·점·하이픈·밑줄만 쓸 수 있습니다(최대 64자).");
        }
        return value.trim();
    }

    static void requireEnum(String field, String value, Set<String> allowed) {
        if (value == null || !allowed.contains(value)) {
            throw Errors.validation(field, field + " 값이 올바르지 않습니다. 허용값: " + String.join(", ", allowed.stream().sorted().toList()));
        }
    }

    static void requireNonNegative(String field, BigDecimal v) {
        if (v != null && v.signum() < 0) {
            throw Errors.validation(field, "중량·규격은 0 이상이어야 합니다.");
        }
    }

    static void checkVersion(int expected, int actual) {
        if (expected != actual) {
            throw Errors.versionConflict(expected, actual);
        }
    }

    /** 유형과 재고·판매 속성의 조합 검사(DB CHECK 와 동일). */
    static void checkTypeFlags(String type, boolean sellable, boolean inventoryManaged, boolean lotManaged, boolean expiryManaged,
                               Integer minShelfDays) {
        if (Set.of("BUNDLE", "SERVICE", "DIGITAL").contains(type) && inventoryManaged) {
            throw Errors.unprocessable("SKU_TYPE_FLAGS_INVALID", "가상묶음·서비스·디지털 SKU 는 자체 재고를 관리하지 않습니다(가상묶음은 구성품 재고로 계산).");
        }
        if (Set.of("KIT", "MATERIAL").contains(type) && !inventoryManaged) {
            throw Errors.unprocessable("SKU_TYPE_FLAGS_INVALID", "실물 세트·부자재 SKU 는 재고관리 대상이어야 합니다.");
        }
        if ("MATERIAL".equals(type) && sellable) {
            throw Errors.unprocessable("SKU_TYPE_FLAGS_INVALID", "부자재는 고객에게 판매하는 SKU 로 지정할 수 없습니다.");
        }
        if (lotManaged && !inventoryManaged) {
            throw Errors.unprocessable("SKU_TYPE_FLAGS_INVALID", "LOT 관리는 재고관리 SKU 에만 쓸 수 있습니다.");
        }
        if (expiryManaged && !lotManaged) {
            throw Errors.unprocessable("SKU_TYPE_FLAGS_INVALID", "유통기한 관리는 LOT 관리와 함께 켜야 합니다.");
        }
        if (minShelfDays != null && (!expiryManaged || minShelfDays < 0)) {
            throw Errors.unprocessable("SKU_TYPE_FLAGS_INVALID", "남은 유통기한 최소일은 유통기한 관리 SKU 에 0 이상으로만 정할 수 있습니다.");
        }
    }
}
