package com.abclogistics.pas.common.outbox;

import com.abclogistics.pas.common.audit.AuditRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/** Registers {@link AuditRecorder} only where an {@link OutboxRepository} exists, so outbox-less
 *  services (audit, notification) can scan broadly without pulling in a writer they can't satisfy. */
@AutoConfiguration
@ConditionalOnBean(OutboxRepository.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AuditRecorder auditRecorder(OutboxRepository outbox, ObjectMapper objectMapper,
                                @Value("${spring.application.name:}") String sourceService) {
        return new AuditRecorder(outbox, objectMapper, sourceService);
    }
}
