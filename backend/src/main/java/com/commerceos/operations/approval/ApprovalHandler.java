package com.commerceos.operations.approval;

/**
 * 승인 요청 종류별 처리기. 도메인 모듈이 구현해 빈으로 등록한다.
 * {@link #isCurrent} 는 요청 이후 대상이 바뀌었는지 본다(바뀌었으면 승인하지 않고 요청을 취소).
 * {@link #apply} 는 승인과 같은 트랜잭션에서 실행된다.
 */
public interface ApprovalHandler {
    String requestType();

    boolean isCurrent(ApprovalService.Request request);

    void apply(ApprovalService.Request request);
}
