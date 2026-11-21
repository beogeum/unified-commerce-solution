package com.commerceos.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerceos.platform.outbox.OutboxEvent;
import com.commerceos.platform.outbox.OutboxPublisher;
import com.commerceos.platform.outbox.OutboxSubscriber;
import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 멱등성·동시 수정·Outbox·감사로그·DB 격리 제약 (docs/02 §3, docs/03 §7-1/10, docs/08 §7). */
@Import(PlatformReliabilityTest.CountingSubscriberConfig.class)
class PlatformReliabilityTest extends IntegrationTest {
    @Autowired
    OutboxPublisher publisher;
    @Autowired
    PlatformTransactionManager tm;
    @Autowired
    CountingSubscriber counting;

    @Test
    void idempotencyKey_replaysSameResponse_rejectsDifferentPayload() {
        Fixtures.Ws ws = fx.workspace();
        Map<String, Object> body = Map.of("display_name", "A", "legal_name", "A 주식회사", "legal_type", "CORPORATION",
                "business_registration_no", Fixtures.validBrn(), "tax_type", "GENERAL");
        Api.Res first = ws.owner().postWithKey("/api/v1/business-entities", body, "idem-key-0001");
        Api.Res second = ws.owner().postWithKey("/api/v1/business-entities", body, "idem-key-0001");
        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.data().get("id").asString()).isEqualTo(first.data().get("id").asString());
        assertThat(jdbc.sql("select count(*) from business_entity where workspace_id = :ws").param("ws", ws.id()).query(Integer.class).single())
                .isEqualTo(1);
        Map<String, Object> other = new java.util.HashMap<>(body);
        other.put("display_name", "B");
        assertThat(ws.owner().postWithKey("/api/v1/business-entities", other, "idem-key-0001").errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(ws.owner().postWithKey("/api/v1/business-entities", body, null).errorCode()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        // 실패한 요청은 기록되지 않아 같은 키로 재시도 가능
        Map<String, Object> invalid = new java.util.HashMap<>(body);
        invalid.put("business_registration_no", "0000000001");
        assertThat(ws.owner().postWithKey("/api/v1/business-entities", invalid, "idem-key-0002").status()).isEqualTo(422);
        invalid.put("business_registration_no", Fixtures.validBrn());
        // 다른 본문이므로 새 키로 판단되지 않고, 앞선 실패는 기록이 남지 않았으므로 정상 처리
        assertThat(ws.owner().postWithKey("/api/v1/business-entities", invalid, "idem-key-0002").status()).isEqualTo(201);
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKey_createExactlyOne() throws Exception {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "A");
        Map<String, Object> body = Map.of("business_entity_id", a.toString(), "sales_channel_code", "TOSS_SHOPPING",
                "display_name", "토스", "external_account_key", "TOSS-1");
        List<Api.Res> results = parallel(8, () -> ws.owner().postWithKey("/api/v1/channel-accounts", body, "concurrent-key-1"));
        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(results.stream().map(r -> r.data().get("id").asString()).distinct()).hasSize(1);
        assertThat(jdbc.sql("select count(*) from channel_account where workspace_id = :ws").param("ws", ws.id()).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void concurrentPatchesWithSameVersion_onlyOneWins() throws Exception {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "A");
        AtomicInteger n = new AtomicInteger();
        List<Api.Res> results = parallel(6, () -> ws.owner().patch("/api/v1/business-entities/" + a,
                Map.of("display_name", "변경-" + n.incrementAndGet()), 1));
        assertThat(results.stream().filter(r -> r.status() == 200)).hasSize(1);
        assertThat(results.stream().filter(r -> "VERSION_CONFLICT".equals(r.errorCode()))).hasSize(5);
        assertThat(ws.owner().patch("/api/v1/business-entities/" + a, Map.of("display_name", "x"), null).status()).isEqualTo(428);
        assertThat(ws.owner().get("/api/v1/business-entities/" + a).header("ETag")).isEqualTo("\"2\"");
    }

    @Test
    void outbox_writtenOnlyOnCommit_andRedeliveryAppliedOnce() {
        Fixtures.Ws ws = fx.workspace();
        UUID aggregate = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(tm);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            publisher.publish(ws.id(), null, "TEST", aggregate, "TestCounted", Map.of("n", 1));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id = :a").param("a", aggregate).query(Integer.class).single()).isZero();
        assertThatThrownBy(() -> publisher.publish(ws.id(), null, "TEST", aggregate, "TestCounted", Map.of())).isNotNull();

        UUID eventId = tx.execute(s -> publisher.publish(ws.id(), null, "TEST", aggregate, "TestCounted", Map.of("n", 2)));
        counting.count.set(0);
        drainAsync();
        assertThat(counting.count.get()).isEqualTo(1);
        // 재전달(상태를 PENDING 으로 되돌려 중복 전달을 흉내) → 소비 기록으로 1회만 적용
        jdbc.sql("update outbox_event set status = 'PENDING' where id = :id").param("id", eventId).update();
        drainAsync();
        assertThat(counting.count.get()).isEqualTo(1);
    }

    @Test
    void failingSubscriber_rollsBackAndRetriesWithBackoff() {
        Fixtures.Ws ws = fx.workspace();
        UUID aggregate = UUID.randomUUID();
        UUID eventId = new TransactionTemplate(tm).execute(s -> publisher.publish(ws.id(), null, "TEST", aggregate, "TestFailing", Map.of()));
        drainAsync();
        Map<String, Object> row = jdbc.sql("select status, attempts, last_error, available_at > now() as later from outbox_event where id = :id")
                .param("id", eventId).query().singleRow();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("later")).isEqualTo(true);
        assertThat(jdbc.sql("select count(*) from event_consumption where event_id = :id").param("id", eventId).query(Integer.class).single()).isZero();
    }

    @Test
    void auditLog_isAppendOnly() {
        Fixtures.Ws ws = fx.workspace();
        fx.businessEntity(ws.owner(), "A");
        assertThatThrownBy(() -> jdbc.sql("update audit_log set action = 'TAMPERED' where workspace_id = :ws").param("ws", ws.id()).update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from audit_log where workspace_id = :ws").param("ws", ws.id()).update())
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    void database_rejectsCrossWorkspaceReferences() {
        Fixtures.Ws ws1 = fx.workspace();
        Fixtures.Ws ws2 = fx.workspace();
        UUID beOfWs2 = fx.businessEntity(ws2.owner(), "B");
        assertThatThrownBy(() -> jdbc.sql("""
                insert into channel_account (id, workspace_id, business_entity_id, sales_channel_code, display_name)
                values (gen_random_uuid(), :ws1, :be2, 'GMARKET', '교차참조')
                """).param("ws1", ws1.id()).param("be2", beOfWs2).update()).hasMessageContaining("foreign key");
    }

    @Test
    void errorResponses_areKoreanWithRequestId() {
        Fixtures.Ws ws = fx.workspace();
        Api.Res r = ws.owner().get("/api/v1/business-entities/" + UUID.randomUUID());
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body().get("error").get("message").asString()).isEqualTo("대상을 찾을 수 없습니다.");
        assertThat(r.body().get("request_id").asString()).isNotBlank();
        Api.Res v = ws.owner().post("/api/v1/business-entities", Map.of("display_name", ""));
        assertThat(v.status()).isEqualTo(422);
        assertThat(v.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(ws.owner().get("/api/v1/business-entities?limit=999").status()).isEqualTo(422);
    }

    @Test
    void cursorPagination_returnsAllRowsWithoutDuplicates() {
        Fixtures.Ws ws = fx.workspace();
        for (int i = 0; i < 5; i++) {
            fx.businessEntity(ws.owner(), "사업자" + i);
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            Api.Res page = ws.owner().get("/api/v1/business-entities?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            page.data().forEach(n -> seen.add(n.get("id").asString()));
            cursor = page.body().get("page").get("next_cursor").isNull() ? null : page.body().get("page").get("next_cursor").asString();
        } while (cursor != null);
        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    void jobLeaseExpiry_jobIsReclaimed() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "A");
        UUID acc = fx.channelAccount(ws.owner(), a, "SSG", "SSG");
        UUID conn = IntegrationIsolationTest.connection(ws.owner(), acc, "SSG 모의", "OK");
        String job = ws.owner().post("/api/v1/integrations/" + conn + "/sync-jobs", Map.of()).data().get("job_id").asString();
        // 다른 작업자가 회수한 뒤 중단된 상황
        jdbc.sql("update async_job set status = 'RUNNING', attempts = 1, locked_by = 'dead-worker', lease_expires_at = now() - interval '1 minute' where id = cast(:id as uuid)")
                .param("id", job).update();
        drainAsync();
        Map<String, Object> row = jdbc.sql("select status, attempts from async_job where id = cast(:id as uuid)").param("id", job).query().singleRow();
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("attempts")).isEqualTo(2);
    }

    private <T> List<T> parallel(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }

    static class CountingSubscriber implements OutboxSubscriber {
        final AtomicInteger count = new AtomicInteger();

        @Override
        public String consumerName() {
            return "test.counting";
        }

        @Override
        public boolean supports(String eventType) {
            return eventType.startsWith("Test");
        }

        @Override
        public void handle(OutboxEvent event) {
            if ("TestFailing".equals(event.eventType())) {
                throw new IllegalStateException("subscriber failure");
            }
            count.incrementAndGet();
        }
    }

    @TestConfiguration
    static class CountingSubscriberConfig {
        @Bean
        CountingSubscriber countingSubscriber() {
            return new CountingSubscriber();
        }
    }
}
