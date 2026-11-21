package com.commerceos.platform.job;

import com.commerceos.shared.json.Json;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 작업 실행기. 회수(claim) → 트랜잭션 밖 처리 → 완료 기록. 회수는 SKIP LOCKED, lease 만료 작업은 다시 회수한다.
 * 완료 기록은 locked_by 가 자신일 때만 반영해 lease 를 잃은 작업자가 결과를 덮어쓰지 못하게 한다.
 */
@Component
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JdbcClient jdbc;
    private final JobService jobs;
    private final Json json;
    private final Map<String, JobHandler> handlers;
    private final TransactionTemplate tx;
    private final Duration lease;
    private final String workerId = "worker-" + UUID.randomUUID();

    public JobWorker(JdbcClient jdbc, JobService jobs, Json json, List<JobHandler> handlers, PlatformTransactionManager tm,
                     @Value("${commerce.jobs.lease:PT5M}") Duration lease) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.json = json;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::jobType, Function.identity()));
        this.tx = new TransactionTemplate(tm);
        this.lease = lease;
    }

    /** 실행 가능한 작업을 최대 max 건 처리. */
    /** 작업자는 모든 운영그룹의 작업을 처리하므로 RLS 우회 문맥에서 실행한다(F8). */
    public int runPending(int max) {
        return com.commerceos.shared.db.TenantContext.asSystem(() -> runPendingInternal(max));
    }

    private int runPendingInternal(int max) {
        int n = 0;
        while (n < max) {
            Optional<Job> claimed = tx.execute(s -> claim());
            if (claimed == null || claimed.isEmpty()) {
                break;
            }
            execute(claimed.get());
            n++;
        }
        return n;
    }

    Optional<Job> claim() {
        return jdbc.sql("""
                update async_job j
                   set status = 'RUNNING', attempts = j.attempts + 1, started_at = coalesce(j.started_at, now()),
                       lease_expires_at = now() + make_interval(secs => :leaseSecs), locked_by = :worker, updated_at = now()
                 where j.id = (
                       select id from async_job
                        where (status in ('PENDING', 'RETRY_WAIT') and available_at <= now())
                           or (status = 'RUNNING' and lease_expires_at < now())
                        order by available_at, id
                        limit 1
                        for update skip locked)
                returning j.*
                """)
                .param("leaseSecs", (double) lease.toSeconds()).param("worker", workerId)
                .query(jobs::map).optional();
    }

    void execute(Job job) {
        JobOutcome outcome;
        if (job.attempts() > job.maxAttempts()) {
            outcome = JobOutcome.failed("MAX_ATTEMPTS_EXCEEDED", "최대 재시도 횟수를 넘어 작업을 중단했습니다. 업무함에서 원인을 확인하세요.");
        } else {
            JobHandler handler = handlers.get(job.jobType());
            if (handler == null) {
                outcome = JobOutcome.reviewRequired("NO_HANDLER", "처리할 수 없는 작업 유형입니다. 관리자 확인이 필요합니다.");
            } else {
                try {
                    outcome = handler.handle(job);
                } catch (RuntimeException e) {
                    log.warn("job {} ({}) failed with unexpected error", job.id(), job.jobType(), e);
                    outcome = JobOutcome.retry("UNEXPECTED_ERROR", "작업 처리 중 오류가 발생해 다시 시도합니다.", backoffSeconds(job.attempts()));
                }
            }
        }
        if (outcome.status() == JobStatus.RETRY_WAIT && job.attempts() >= job.maxAttempts()) {
            outcome = JobOutcome.failed(outcome.errorCode(), outcome.errorMessageKo());
        }
        complete(job, outcome);
    }

    private void complete(Job job, JobOutcome o) {
        boolean terminal = o.status() != JobStatus.RETRY_WAIT;
        long delay = o.retryDelaySeconds() == null ? backoffSeconds(job.attempts()) : o.retryDelaySeconds();
        int updated = jdbc.sql("""
                update async_job
                   set status = :status, result = cast(:result as jsonb), error_code = :code, error_message_ko = :msg,
                       available_at = case when :terminal then available_at else now() + make_interval(secs => :delay) end,
                       finished_at = case when :terminal then now() else null end,
                       lease_expires_at = null, locked_by = null, updated_at = now()
                 where id = :id and status = 'RUNNING' and locked_by = :worker
                """)
                .param("status", o.status().name()).param("result", json.write(o.result()))
                .param("code", o.errorCode()).param("msg", o.errorMessageKo())
                .param("terminal", terminal).param("delay", (double) delay)
                .param("id", job.id()).param("worker", workerId)
                .update();
        if (updated == 0) {
            log.warn("job {} lease lost before completion; result discarded", job.id());
        }
    }

    static long backoffSeconds(int attempts) {
        return Math.min(600L, 5L * (1L << Math.min(attempts, 7)));
    }
}
