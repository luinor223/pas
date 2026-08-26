package com.abclogistics.pas.workflow;

import com.abclogistics.pas.workflow.service.IdentityGrpcClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestIdentityConfig {

    @Bean
    @Primary
    public IdentityGrpcClient identityGrpcClient() {
        return new StubIdentityGrpcClient();
    }
}
