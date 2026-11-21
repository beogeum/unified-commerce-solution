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

    record Field(String key, String labelKo, boolean required, List<String> aliases, String exampleValue) {
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
