package com.commerceos.shared.db;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * 애플리케이션 SQL·트랜잭션은 모두 {@link TenantAwareDataSource} 를 거친다(F8).
 * 자동 구성된 원본 DataSource 는 Flyway 마이그레이션(테이블 소유자 권한)만 사용한다.
 * 래퍼를 DataSource 빈으로 등록하지 않는 이유: 등록하면 원본 DataSource 자동 구성이 꺼진다.
 */
@Configuration(proxyBeanMethods = false)
public class TenantJdbcConfig {

    public record TenantConnections(TenantAwareDataSource dataSource) {
    }

    @Bean
    TenantConnections tenantConnections(DataSource dataSource,
                                        @Value("${commerce.db.app-role:commerce_app}") String appRole,
                                        @Value("${commerce.db.rls-enabled:true}") boolean enabled) {
        return new TenantConnections(new TenantAwareDataSource(dataSource, appRole, enabled));
    }

    @Bean
    JdbcTemplate jdbcTemplate(TenantConnections t) {
        return new JdbcTemplate(t.dataSource());
    }

    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(JdbcTemplate jdbcTemplate) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    @Bean
    JdbcClient jdbcClient(NamedParameterJdbcTemplate template) {
        return JdbcClient.create(template);
    }

    @Bean
    JdbcTransactionManager transactionManager(TenantConnections t) {
        return new JdbcTransactionManager(t.dataSource());
    }
}
