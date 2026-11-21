package com.commerceos.integration.mock;

/** 모의 커넥터 시나리오. 인증정보의 mock_scenario 값 또는 테스트 제어로 지정한다. */
public enum MockScenario {
    OK, AUTH_EXPIRED, PERMISSION_DENIED, RATE_LIMITED, TIMEOUT
}
