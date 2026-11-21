package com.commerceos.platform.job;

import java.util.Map;

/** 작업 처리 결과. RETRY 는 최대 시도횟수 안에서만 재시도되고 이후 FAILED 로 바뀐다. */
public record JobOutcome(JobStatus status, Map<String, Object> result, String errorCode, String errorMessageKo, Long retryDelaySeconds) {

    public static JobOutcome succeeded(Map<String, Object> result) {
        return new JobOutcome(JobStatus.SUCCEEDED, result, null, null, null);
    }

    public static JobOutcome retry(String code, String messageKo, long delaySeconds) {
        return new JobOutcome(JobStatus.RETRY_WAIT, null, code, messageKo, delaySeconds);
    }

    public static JobOutcome failed(String code, String messageKo) {
        return new JobOutcome(JobStatus.FAILED, null, code, messageKo, null);
    }

    public static JobOutcome reviewRequired(String code, String messageKo) {
        return new JobOutcome(JobStatus.REVIEW_REQUIRED, null, code, messageKo, null);
    }
}
