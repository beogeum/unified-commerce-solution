package com.commerceos.platform.outbox;

/**
 * 도메인 이벤트 소비자. 전달은 반복될 수 있으므로(at-least-once) 디스패처가 소비자별 처리기록으로 1회 적용을 보장한다.
 * handle 은 디스패처 트랜잭션 안에서 실행된다.
 */
public interface OutboxSubscriber {
    String consumerName();

    boolean supports(String eventType);

    void handle(OutboxEvent event);
}
