package com.commerceos.support;

import com.commerceos.integration.mock.MockScenarioControl;
import com.commerceos.platform.job.JobWorker;
import com.commerceos.platform.outbox.OutboxDispatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * 통합 테스트 기반. 각 테스트는 고유한 운영그룹을 새로 만들어 서로 격리된다(데이터 정리 불필요).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainer.class, Fixtures.class})
public abstract class IntegrationTest {
    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper mapper;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected OutboxDispatcher outbox;
    @Autowired
    protected JobWorker jobWorker;
    @Autowired
    protected MockScenarioControl mockControl;
    @Autowired
    protected Fixtures fx;

    /** 비동기 처리(Outbox → 작업 → Outbox)를 더 이상 진행할 것이 없을 때까지 실행한다. */
    protected void drainAsync() {
        for (int i = 0; i < 10; i++) {
            int n = outbox.dispatchPending(1000) + jobWorker.runPending(1000);
            if (n == 0) {
                return;
            }
        }
    }
}
