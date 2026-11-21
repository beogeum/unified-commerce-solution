package com.commerceos.platform.outbox;

import com.commerceos.shared.db.Db;
import com.commerceos.shared.json.Json;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox 디스패처. 이벤트 1건 = 트랜잭션 1개(행 잠금 + SKIP LOCKED 로 다중 작업자 안전).
 * 소비자 실패 시 롤백 후 시도횟수·백오프 기록, 최대 횟수 초과 시 FAILED.
 */
@Component
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    static final int MAX_ATTEMPTS = 10;

    private final JdbcClient jdbc;
    private final Json json;
    private final List<OutboxSubscriber> subscribers;
    private final TransactionTemplate tx;

    public OutboxDispatcher(JdbcClient jdbc, Json json, List<OutboxSubscriber> subscribers, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.json = json;
        this.subscribers = subscribers;
        this.tx = new TransactionTemplate(tm);
    }

    /** 대기 이벤트를 최대 max 건 전달하고 전달한 건수를 돌려준다. */
    /** 작업자는 모든 운영그룹의 이벤트를 처리하므로 RLS 우회 문맥에서 실행한다(F8). */
    public int dispatchPending(int max) {
        return com.commerceos.shared.db.TenantContext.asSystem(() -> dispatchPendingInternal(max));
    }

    private int dispatchPendingInternal(int max) {
        int n = 0;
        while (n < max) {
            Optional<OutboxEvent> claimed;
            try {
                claimed = tx.execute(s -> dispatchOne());
            } catch (DeliveryFailed e) {
                // 소비자 트랜잭션이 롤백된 뒤(잠금 해제 후) 실패 기록을 별도 트랜잭션으로 남긴다.
                tx.executeWithoutResult(s -> recordFailure(e.event, e.getCause()));
                n++;
                continue;
            }
            if (claimed == null || claimed.isEmpty()) {
                break;
            }
            n++;
        }
        return n;
    }

    private Optional<OutboxEvent> dispatchOne() {
        Optional<OutboxEvent> next = jdbc.sql("""
                select * from outbox_event
                 where status = 'PENDING' and available_at <= now()
                 order by occurred_at, id
                 limit 1
                 for update skip locked
                """).query((rs, i) -> new OutboxEvent(
                Db.uuid(rs, "id"), Db.uuid(rs, "workspace_id"), Db.uuid(rs, "business_entity_id"),
                rs.getString("aggregate_type"), Db.uuid(rs, "aggregate_id"), rs.getString("event_type"),
                rs.getInt("schema_version"), json.read(rs.getString("payload")), Db.instant(rs, "occurred_at"),
                rs.getString("correlation_id"), Db.uuid(rs, "source_event_id"), rs.getInt("attempts"))).optional();
        if (next.isEmpty()) {
            return next;
        }
        OutboxEvent event = next.get();
        try {
            deliver(event);
            jdbc.sql("update outbox_event set status = 'DISPATCHED', dispatched_at = now(), attempts = attempts + 1 where id = :id")
                    .param("id", event.eventId()).update();
        } catch (RuntimeException e) {
            // 소비자 효과를 되돌리기 위해 이 트랜잭션은 롤백한다.
            throw new DeliveryFailed(event, e);
        }
        return next;
    }

    void deliver(OutboxEvent event) {
        for (OutboxSubscriber s : subscribers) {
            if (!s.supports(event.eventType())) {
                continue;
            }
            int inserted = jdbc.sql("insert into event_consumption (consumer, event_id) values (:c, :e) on conflict do nothing")
                    .param("c", s.consumerName()).param("e", event.eventId()).update();
            if (inserted == 0) {
                continue; // 이미 처리됨: 중복 전달
            }
            s.handle(event);
        }
    }

    private void recordFailure(OutboxEvent event, Throwable cause) {
        jdbc.sql("""
                update outbox_event
                   set attempts = attempts + 1,
                       last_error = :err,
                       available_at = now() + make_interval(secs => least(300, power(2, attempts + 1))),
                       status = case when attempts + 1 >= :max then 'FAILED' else 'PENDING' end
                 where id = :id
                """).param("err", String.valueOf(cause.getMessage())).param("max", MAX_ATTEMPTS)
                .param("id", event.eventId()).update();
        log.warn("outbox event {} ({}) delivery failed: {}", event.eventId(), event.eventType(), cause.toString());
    }

    static final class DeliveryFailed extends RuntimeException {
        final transient OutboxEvent event;

        DeliveryFailed(OutboxEvent event, RuntimeException cause) {
            super(cause);
            this.event = event;
        }
    }
}
