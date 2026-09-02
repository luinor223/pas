package com.abclogistics.pas.operations;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestConfiguration
public class TestGrpcConfig {

    @Bean
    @Primary
    public StubContractGrpcClient stubContractGrpcClient() {
        return new StubContractGrpcClient();
    }

    @Bean
    @Primary
    public StubPricingGrpcClient stubPricingGrpcClient() {
        return new StubPricingGrpcClient();
    }

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public KafkaTemplate<String, String> kafkaTemplate() {
        KafkaTemplate<String, String> mock = mock(KafkaTemplate.class);
        try {
            when(mock.send(any(ProducerRecord.class))).thenAnswer(inv -> CompletableFuture.completedFuture(mock(org.springframework.kafka.support.SendResult.class)));
            when(mock.send(any(String.class), any(String.class))).thenReturn(CompletableFuture.completedFuture(null));
            when(mock.send(any(String.class), any(String.class), any(String.class))).thenReturn(CompletableFuture.completedFuture(null));
        } catch (Exception ignored) {}
        return mock;
    }
}
