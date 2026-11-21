package com.commerceos.integration.spi;

import java.util.Set;

/**
 * 연동 커넥터 공통 SPI. 구현체는 실제 계정 권한·버전이 확인된 채널/물류사만 추가한다(CLAUDE.md).
 * implemented() 는 '코드가 구현한 기능'일 뿐이며, 실행 가능 여부는 연결별 지원 기능 검증 결과와 함께 판단한다.
 */
public interface Connector {
    String code();

    /** CHANNEL_ACCOUNT 또는 SHIPPER_ACCOUNT */
    String targetType();

    boolean isMock();

    Set<FeatureOp> implemented();

    /** 인증·연결 상태 점검. 실패는 ConnectorException 으로 분류해 던진다. */
    HealthResult checkAuth(ConnectorContext ctx);

    record HealthResult(String apiVersion, String message) {
    }
}
