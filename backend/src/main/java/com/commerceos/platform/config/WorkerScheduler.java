package com.commerceos.platform.config;

import com.commerceos.platform.job.JobWorker;
import com.commerceos.platform.outbox.OutboxDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 백그라운드 작업자. 테스트에서는 끄고 직접 drain 한다(commerce.workers.enabled=false). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "commerce.workers.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerScheduler {
    private final OutboxDispatcher outbox;
    private final JobWorker jobs;

    public WorkerScheduler(OutboxDispatcher outbox, JobWorker jobs) {
        this.outbox = outbox;
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${commerce.workers.outbox-interval:PT0.5S}")
    public void dispatchOutbox() {
        outbox.dispatchPending(100);
    }

    @Scheduled(fixedDelayString = "${commerce.workers.job-interval:PT1S}")
    public void runJobs() {
        jobs.runPending(20);
    }
}
