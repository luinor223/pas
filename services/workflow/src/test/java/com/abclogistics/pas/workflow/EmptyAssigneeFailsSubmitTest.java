package com.abclogistics.pas.workflow;

import com.abclogistics.pas.identity.grpc.UserRef;
import com.abclogistics.pas.workflow.error.FailedPreconditionException;
import com.abclogistics.pas.workflow.service.IdentityGrpcClient;
import com.abclogistics.pas.workflow.service.WorkflowInstanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmptyAssigneeFailsSubmitTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas").withUsername("pas").withPassword("pas");
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("outbox.relay.enabled", () -> "false");
        r.add("identity.grpc.host", () -> "localhost");
        r.add("identity.grpc.port", () -> "50051");
    }

    @Autowired WorkflowInstanceService instanceService;
    @Autowired IdentityGrpcClient identityClient;

    @BeforeEach
    void stub() {
        // Make LEGAL_REVIEWER return empty -> step 2 of CONTRACT fails
        UserRef salesMgr = UserRef.newBuilder().setId(UUID.randomUUID().toString()).setUsername("sm").setFullName("SM").setDepartment("SALES").build();
        UserRef director = UserRef.newBuilder().setId(UUID.randomUUID().toString()).setUsername("dir").setFullName("DIR").setDepartment("BOARD").build();
        identityClient.setTestOverrides(Map.of(
                "SALES_MANAGER", List.of(salesMgr),
                "LEGAL_REVIEWER", List.of(), // empty -> should fail
                "DIRECTOR", List.of(director)
        ));
    }

    @Test
    void emptyAssigneeFailsSubmitImmediately() {
        UUID docId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> instanceService.startInstance("CONTRACT", docId, "CTR-2026-0100", "Cust", "NORMAL", UUID.randomUUID(), "Tester", key))
                .isInstanceOf(FailedPreconditionException.class)
                .hasMessageContaining("No assignee for role: LEGAL_REVIEWER");
    }

    @Test
    void validateStartableAlsoFailsOnEmptyAssignee() {
        // PRICE_LIST has steps SALES_MANAGER + DIRECTOR -> make DIRECTOR empty as well
        UserRef sm = UserRef.newBuilder().setId(UUID.randomUUID().toString()).setUsername("sm2").setFullName("SM2").setDepartment("SALES").build();
        identityClient.setTestOverrides(Map.of(
                "SALES_MANAGER", List.of(sm),
                "DIRECTOR", List.of()
        ));
        assertThatThrownBy(() -> instanceService.validateStartable("PRICE_LIST"))
                .isInstanceOf(FailedPreconditionException.class)
                .hasMessageContaining("DIRECTOR");
    }
}
