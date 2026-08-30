package com.abclogistics.pas.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OutboxCommonConfig {

    @Bean
    @ConfigurationProperties(prefix = "outbox.relay")
    public OutboxRelayProperties outboxRelayProperties() {
        return new OutboxRelayProperties();
    }
}
