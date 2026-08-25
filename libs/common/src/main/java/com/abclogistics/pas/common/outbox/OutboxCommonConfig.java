package com.abclogistics.pas.common.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxCommonConfig {
}
