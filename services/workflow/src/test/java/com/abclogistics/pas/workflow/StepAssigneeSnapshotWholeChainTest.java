package com.abclogistics.pas.workflow;

import com.abclogistics.pas.identity.grpc.UserRef;
import com.abclogistics.pas.workflow.domain.WorkflowInstance;
import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import com.abclogistics.pas.workflow.repository.StepAssigneeRepository;
import com.abclogistics.pas.workflow.repository.WorkflowStepInstanceRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StepAssigneeSnapshotWholeChainTest {

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
    @Autowired WorkflowStepInstanceRepository stepRepo;
    @Autowired StepAssigneeRepository assigneeRepo;

    @BeforeEach
    void stub() {
        UserRef sm1 = UserRef.newBuilder().setId(UUID.randomUUID().toString()).setUsername("sm1").setFullName("Sales Mgr 1").setDepartment("SALES").build();
        UserRef sm2 = UserRef.newBuilder().setId(UUID.randomUUID().toString()).setUsername("sm2").setFullName("Sales Mgr 2").setDepartment("SALES").build();
        UserRef legal = UserRef.newBuilder().setId(UUID.randomUUID().toString()).setUsername("legal").setFullName("Legal").setDepartment("LEGAL").build();
        UserRef dir = UserRef.newBuilder().setId(UUID.randomUUID().toString()).setUsername("dir").setFullName("Director").setDepartment("BOARD").build();
        identityClient.setTestOverrides(Map.of(
                "SALES_MANAGER", List.of(sm1, sm2),
                "LEGAL_REVIEWER", List.of(legal),
                "DIRECTOR", List.of(dir)
        ));
    }

    @Test
    void wholeChainSnapshotAtCreation() {
        UUID docId = UUID.randomUUID();
        WorkflowInstance inst = instanceService.startInstance("CONTRACT", docId, "CTR-2026-0200", "Cust", "HIGH", UUID.randomUUID(), "Tester", UUID.randomUUID());
        List<WorkflowStepInstance> steps = stepRepo.findByInstance_IdOrderByStepOrderAsc(inst.getId());
        assertThat(steps).hasSize(3);
        // step 1 ACTIVE, steps 2,3 PENDING but all should have assignees snapshotted
        for (WorkflowStepInstance s : steps) {
            var assignees = assigneeRepo.findByStepInstance_Id(s.getId());
            assertThat(assignees).isNotEmpty();
            if ("SALES_MANAGER".equals(s.getApproverRole())) {
                assertThat(assignees).hasSize(2); // we stubbed 2 SM
            } else {
                assertThat(assignees).hasSize(1);
            }
        }
        // verify second step (PENDING) already has assignee before its activation
        WorkflowStepInstance second = steps.get(1);
        assertThat(second.getStatus()).isEqualTo("PENDING");
        assertThat(assigneeRepo.findByStepInstance_Id(second.getId())).isNotEmpty();
    }
}
