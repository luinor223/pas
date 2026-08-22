package com.abclogistics.pas.common.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RedisKeyProperties.class)
public class SecurityCommonConfig {
}
