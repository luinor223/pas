package com.abclogistics.pas.contract;

import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.contract.outbox.ContractOutboxRelay;
import com.abclogistics.pas.contract.service.EsignGrpcClient;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Item 6 — when the relay bean exists, and what happens when its dependencies do not.
 *
 * <p>{@link ContractOutboxRelayTest} builds the relay by hand, so it proves the routing table but
 * nothing about the conditions that decide whether Spring creates the bean at all. That gap is
 * where the interesting failure lived: a relay that never registers publishes nothing and says
 * nothing, and every document submitted sits in the outbox looking merely pending.
 */
class ContractOutboxRelayWiringTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ContractOutboxRelay.class)
            .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
            .withBean(OutboxRelayProperties.class, OutboxRelayProperties::new)
            .withBean(WorkflowGrpcClient.class, () -> mock(WorkflowGrpcClient.class))
            .withBean(EsignGrpcClient.class, () -> mock(EsignGrpcClient.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class));

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }

    @Test
    void theRelayRunsByDefault() {
        // matchIfMissing: a service that says nothing about the relay still gets one.
        context.withBean(KafkaTemplate.class, ContractOutboxRelayWiringTest::kafkaTemplate)
                .run(ctx -> assertThat(ctx).hasSingleBean(ContractOutboxRelay.class));
    }

    @Test
    void theRelayIsOptOutByProperty() {
        // The tests that drive the outbox by hand rely on this, and so does any deployment that
        // runs the relay in one replica only.
        context.withBean(KafkaTemplate.class, ContractOutboxRelayWiringTest::kafkaTemplate)
                .withPropertyValues("outbox.relay.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ContractOutboxRelay.class));
    }

    @Test
    void anEnabledRelayWithNoKafkaFailsStartupInsteadOfDisappearing() {
        // The condition that used to guard this was @ConditionalOnBean(KafkaTemplate), which
        // silently dropped the relay -- taking the gRPC dispatch of workflow.start_requested down
        // with the Kafka one, though that half needs no broker at all. Missing Kafka is a
        // misconfiguration and has to be loud.
        context.run(ctx -> assertThat(ctx)
                .hasFailed()
                .getFailure()
                .hasMessageContaining("KafkaTemplate"));
    }
}
