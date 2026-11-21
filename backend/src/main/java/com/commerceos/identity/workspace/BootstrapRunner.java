package com.commerceos.identity.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 개발 환경 전용: 설정값으로 첫 운영그룹과 최고관리자를 만든다. 비밀번호는 환경변수로만 주입한다. */
@Component
@ConditionalOnProperty(name = "commerce.bootstrap.enabled", havingValue = "true")
public class BootstrapRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);
    private final WorkspaceProvisioningService provisioning;
    private final JdbcClient jdbc;
    private final String code;
    private final String name;
    private final String email;
    private final String password;

    public BootstrapRunner(WorkspaceProvisioningService provisioning, JdbcClient jdbc,
                           @Value("${commerce.bootstrap.workspace-code}") String code,
                           @Value("${commerce.bootstrap.workspace-name}") String name,
                           @Value("${commerce.bootstrap.owner-email}") String email,
                           @Value("${commerce.bootstrap.owner-password}") String password) {
        this.provisioning = provisioning;
        this.jdbc = jdbc;
        this.code = code;
        this.name = name;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (jdbc.sql("select count(*) from workspace where code = :c").param("c", code).query(Integer.class).single() > 0) {
            return;
        }
        provisioning.provision(code, name, email, "최고관리자", password);
        log.info("bootstrap workspace '{}' provisioned", code);
    }
}
