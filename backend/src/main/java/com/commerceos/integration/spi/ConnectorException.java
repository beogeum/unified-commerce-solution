package com.commerceos.integration.spi;

/** 커넥터 오류 분류. 분류에 따라 재시도·계정 격리·업무함 생성이 달라진다(docs/04 §9). */
public class ConnectorException extends RuntimeException {
    public enum Kind {
        /** 인증 만료·폐기: 해당 연결만 AUTH_EXPIRED, 재시도하지 않음 */
        AUTH_EXPIRED,
        /** 권한 없음(개발사 미승인 등): 재시도하지 않음 */
        PERMISSION_DENIED,
        /** 호출 제한: 지연 후 재시도 */
        RATE_LIMITED,
        /** 타임아웃·일시 장애: 재시도(외부 쓰기는 사후 조회 후) */
        TRANSIENT,
        /** 커넥터가 지원하지 않는 기능 */
        UNSUPPORTED
    }

    private final Kind kind;
    private final Long retryAfterSeconds;

    public ConnectorException(Kind kind, String message, Long retryAfterSeconds) {
        super(message);
        this.kind = kind;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Kind kind() {
        return kind;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
