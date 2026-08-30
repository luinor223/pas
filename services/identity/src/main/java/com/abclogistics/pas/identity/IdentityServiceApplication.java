package com.abclogistics.pas.identity;

import com.abclogistics.pas.common.outbox.OutboxCommonConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.abclogistics.pas")
@Import(OutboxCommonConfig.class)
@EntityScan("com.abclogistics.pas")
@EnableJpaRepositories("com.abclogistics.pas")
@EnableScheduling
public class IdentityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
