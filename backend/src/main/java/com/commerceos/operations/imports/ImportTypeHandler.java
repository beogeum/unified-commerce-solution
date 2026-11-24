package com.commerceos.operations.imports;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 가져오기 유형별 처리기(도메인 모듈이 구현해 빈으로 등록). 미리보기·실행 모두 요청한 사용자 권한으로 호출된다.
 * 실행({@link #apply})은 기존 도메인 서비스를 불러 화면 등록과 같은 규칙·권한·감사를 거치게 한다.
 */
public interface ImportTypeHandler {
    String importType();

    String templateVersion();

    String labelKo();

    String requiredPermission();

    List<Field> fields();

    /** 행 간 규칙(파일 안 중복 등)까지 보고 행마다 결과를 돌려준다. 입력·출력 순서는 같다. */
    List<RowCheck> validate(List<Map<String, String>> rows);

    /** 행 하나 적용. 반환값은 적용 대상 참조(예: SKU 코드). 실패는 ApiException(한국어 메시지)으로. */
    String apply(Map<String, String> normalized, BatchContext context);

    /** sensitive = 개인정보(수령인 이름·연락처·주소 등): 가져오기 행에 평문으로 두지 않고 미리보기는 마스킹, 실행 값은 암호화(DLG-235). */
    record Field(String key, String labelKo, boolean required, List<String> aliases, String exampleValue, boolean sensitive) {
        public Field(String key, String labelKo, boolean required, List<String> aliases, String exampleValue) {
            this(key, labelKo, required, aliases, exampleValue, false);
        }
    }

    /**
     * 사업자·판매계정 범위가 있는 데이터(예: 주문)를 다루는 유형은 배치를 만든 사용자만 보고 실행·취소한다(DLG-250).
     * 같은 권한이라도 범위가 다른 사용자에게 다른 사업자의 행(마스킹된 수령인·판매계정·금액)이 보이지 않게 한다.
     */
    default boolean creatorOnly() {
        return hasSensitiveFields();
    }

    default boolean hasSensitiveFields() {
        return fields().stream().anyMatch(Field::sensitive);
    }

    record Message(String level, String field, String text) {
    }

    record RowCheck(String status, List<Message> messages, Map<String, String> normalized) {
        public static RowCheck of(List<Message> messages, Map<String, String> normalized) {
            boolean error = messages.stream().anyMatch(m -> "ERROR".equals(m.level()));
            boolean warn = messages.stream().anyMatch(m -> "WARNING".equals(m.level()));
            return new RowCheck(error ? "ERROR" : warn ? "WARNING" : "OK", messages, error ? null : normalized);
        }
    }

    /** 같은 배치의 실행 대상 행 전체(정규화 값). 예: 새 상품의 옵션 축을 모든 행에서 모은다. */
    record BatchContext(UUID batchId, List<Map<String, String>> executableRows) {
    }
}
