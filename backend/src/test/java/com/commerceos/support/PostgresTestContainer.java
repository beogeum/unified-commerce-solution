package com.commerceos.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
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
}
