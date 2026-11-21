package com.commerceos.platform.job;

/**
 * 작업 유형별 처리기. 트랜잭션 밖에서 호출되므로 외부 호출 동안 DB 잠금을 잡지 않는다.
 * 같은 작업이 재실행될 수 있으므로(lease 만료 회수 등) 멱등하게 작성해야 한다.
 */
public interface JobHandler {
    String jobType();

    JobOutcome handle(Job job);
}
