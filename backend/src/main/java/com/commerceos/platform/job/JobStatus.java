package com.commerceos.platform.job;

/** docs/02 §3 작업 상태. */
public enum JobStatus {
    PENDING, RUNNING, SUCCEEDED, RETRY_WAIT, REVIEW_REQUIRED, FAILED
}
