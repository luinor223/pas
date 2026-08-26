package com.abclogistics.pas.workflow;

import com.abclogistics.pas.identity.grpc.UserRef;
import com.abclogistics.pas.workflow.domain.WorkflowInstance;
import com.abclogistics.pas.workflow.error.FailedPreconditionException;
import com.abclogistics.pas.workflow.repository.WorkflowInstanceRepository;
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

import static org.assertj.core.api.Assertions.*;

/**
 * Session 2 — D4 double-submit — two constraints, two jobs (db-workflow.md).
 * Permanent idempotency_key UNIQUE vs partial (document_type_code, document_id) WHERE status='IN_PROGRESS'.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyKeyPermanentVsPartialTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas").withUsername("pas").withPassword("pas");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

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
    @Autowired WorkflowInstanceRepository instanceRepo;
    @Autowired IdentityGrpcClient identityClient;

    private final UUID docId = UUID.randomUUID();
    private final String docNo = "CTR-2026-9999";
    private final String docType = "CONTRACT";

    @BeforeEach
    void stubIdentity() {
        // Seed identity returns 1 user per required role for CONTRACT (3 steps)
        UserRef salesMgr = UserRef.newBuilder().setId(UUID.randomUUID().toString()).setUsername("salesMgr").setFullName("Sales Manager").setDepartment("SALES").build();
        UserRef legal = UserRef.newBuilder().setId(UUID.randomUUID().toString()).setUsername("legal").setFullName("Legal Reviewer").setDepartment("LEGAL").build();
        UserRef director = UserRef.newBuilder().setId(UUID.randomUUID().toString()).setUsername("director").setFullName("Director").setDepartment("BOARD").build();
        identityClient.setTestOverrides(Map.of(
                "SALES_MANAGER", List.of(salesMgr),
                "LEGAL_REVIEWER", List.of(legal),
                "DIRECTOR", List.of(director)
        ));
    }

    @Test
    void permanentKeyMakesRetryIdempotentEvenAfterCancelled() {
        UUID key = UUID.randomUUID();
        WorkflowInstance first = instanceService.startInstance(docType, docId, docNo, "Customer A", "NORMAL", UUID.randomUUID(), "Tester", key);
        assertThat(first.getId()).isNotNull();
        assertThat(first.getStatus()).isEqualTo("IN_PROGRESS");

        // cancel the instance
        instanceService.cancelInstance(docType, docId, key);
        WorkflowInstance cancelled = instanceRepo.findByIdempotencyKey(key).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");

        // retry same key after cancelled -> must return same instance (permanent UNIQUE), not new IN_PROGRESS
        WorkflowInstance second = instanceService.startInstance(docType, docId, docNo, "Customer A", "NORMAL", UUID.randomUUID(), "Tester", key);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo("CANCELLED");

        // verify only one row with that key exists
        assertThat(instanceRepo.findByIdempotencyKey(key)).isPresent();
    }

    @Test
    void partialUniqueBlocksDifferentKeyWhileInProgressAndAllowsAfterTerminal() {
        UUID doc2 = UUID.randomUUID();
        UUID keyA = UUID.randomUUID();
        UUID keyB = UUID.randomUUID();

        WorkflowInstance a = instanceService.startInstance(docType, doc2, "CTR-2026-0001", "Cust", "NORMAL", UUID.randomUUID(), "Tester", keyA);
        assertThat(a.getStatus()).isEqualTo("IN_PROGRESS");

        // different key while active must fail (partial index)
        assertThatThrownBy(() -> instanceService.startInstance(docType, doc2, "CTR-2026-0001", "Cust", "NORMAL", UUID.randomUUID(), "Tester", keyB))
                .isInstanceOf(FailedPreconditionException.class)
                .hasMessageContaining("active workflow instance");

        // same key retry is idempotent, still returns A
        WorkflowInstance retryA = instanceService.startInstance(docType, doc2, "CTR-2026-0001", "Cust", "NORMAL", UUID.randomUUID(), "Tester", keyA);
        assertThat(retryA.getId()).isEqualTo(a.getId());

        // cancel A, then B should succeed with different key (partial no longer applies)
        instanceService.cancelInstance(docType, doc2, keyA);
        WorkflowInstance b = instanceService.startInstance(docType, doc2, "CTR-2026-0002", "Cust", "NORMAL", UUID.randomUUID(), "Tester", keyB);
        assertThat(b.getId()).isNotEqualTo(a.getId());
        assertThat(b.getStatus()).isEqualTo("IN_PROGRESS");
    }
}
