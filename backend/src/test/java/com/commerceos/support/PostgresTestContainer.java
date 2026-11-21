package com.commerceos.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 실제 PostgreSQL 로 동시성·트랜잭션·제약을 검증한다(docs/09 테스트 실행 필수 조건). 컨테이너는 JVM 당 1개 재사용. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainer {
    static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:16-alpine");

    static {
        CONTAINER.start();
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return CONTAINER;
    }

    /**
     * 테스트 검증용 원본 연결(테이블 소유자, RLS 미적용). 애플리케이션 코드는 이 빈을 쓰지 않는다 —
     * 기본 후보가 아니므로 {@code @Qualifier("rawJdbc")} 로만 주입된다.
     */
    @Bean(defaultCandidate = false)
    JdbcClient rawJdbc(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }
}
