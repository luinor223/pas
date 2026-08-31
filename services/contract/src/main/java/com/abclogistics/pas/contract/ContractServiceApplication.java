package com.abclogistics.pas.contract;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

// libs:common ships no AutoConfiguration.imports — its beans (PermissionCache,
// SecurityCommonConfig, OutboxCommonConfig, AuditRecorder, GlobalExceptionHandler)
// and its OutboxEvent entity / OutboxRepository are picked up only by these scans.
@SpringBootApplication(scanBasePackages = "com.abclogistics.pas")
@EntityScan("com.abclogistics.pas")
@EnableJpaRepositories("com.abclogistics.pas")
@EnableScheduling
public class ContractServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContractServiceApplication.class, args);
    }
}
