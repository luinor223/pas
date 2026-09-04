package com.abclogistics.pas.billing;

import com.abclogistics.pas.billing.config.KafkaConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Registry §4's consumer failure policy has to exist, not merely be configured.
 *
 * <p>The handler is what bounds retries and moves a poisoned record to {@code <topic>.DLT} so the
 * offset advances. Without it consumers fall back to Spring's default and neither happens — a
 * degradation with no error attached to it, which is exactly what a condition on this bean could
 * cause. So the presence of the bean is asserted, and so is the failure when it cannot be built.
 */
class KafkaConsumerConfigWiringTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(KafkaConsumerConfig.class);

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }

    @Test
    void theErrorHandlerIsBuiltWhenKafkaIsPresent() {
        context.withBean(KafkaTemplate.class, KafkaConsumerConfigWiringTest::kafkaTemplate)
                .run(ctx -> assertThat(ctx).hasSingleBean(DefaultErrorHandler.class));
    }

    @Test
    void aMissingKafkaTemplateFailsStartupRatherThanDroppingThePolicy() {
        context.run(ctx -> assertThat(ctx)
                .hasFailed()
                .getFailure()
                .hasMessageContaining("KafkaTemplate"));
    }
}
