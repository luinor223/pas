package com.abclogistics.pas.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Entity and repository scans stay narrowed: this service has no {@code outbox} table (db-audit.md),
 *  so a broad {@code @EntityScan} would register {@code OutboxEvent} and fail Hibernate validation. */
@SpringBootApplication(scanBasePackages = "com.abclogistics.pas")
@EntityScan("com.abclogistics.pas.audit.domain")
@EnableJpaRepositories("com.abclogistics.pas.audit.repository")
public class AuditServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
