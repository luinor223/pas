package com.abclogistics.pas.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Scans are narrowed to this service plus the shared pieces it uses. `com.abclogistics.pas` as a
 * whole would drag in {@code common.outbox} and {@code common.audit}, and this service publishes
 * nothing: its schema has no {@code outbox} table, so a broad scan fails Hibernate validation on
 * a table that should never exist here (db-audit.md).
 */
@SpringBootApplication(scanBasePackages = {
        "com.abclogistics.pas.audit",
        "com.abclogistics.pas.common.security",
        "com.abclogistics.pas.common.error"
})
@EntityScan("com.abclogistics.pas.audit.domain")
@EnableJpaRepositories("com.abclogistics.pas.audit.repository")
public class AuditServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
