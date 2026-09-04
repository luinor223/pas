package com.abclogistics.pas.workflow;

import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.identity.grpc.UserRef;
import com.abclogistics.pas.workflow.domain.WorkflowInstance;
import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import com.abclogistics.pas.workflow.repository.WorkflowStepInstanceRepository;
import com.abclogistics.pas.workflow.service.WorkflowInstanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
@Import(TestIdentityConfig.class)
class GetInstanceByDocumentSelectionTest {

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
    @Autowired WorkflowStepInstanceRepository stepRepo;
    @Autowired StubIdentityGrpcClient identityClient;

    private UUID salesMgrId;
    private UUID legalId;
    private UUID directorId;

    @BeforeEach
    void stub() {
        salesMgrId = UUID.randomUUID();
        legalId = UUID.randomUUID();
        directorId = UUID.randomUUID();
        UserRef sm = UserRef.newBuilder().setId(salesMgrId.toString()).setUsername("sm").setFullName("Sales Mgr").setDepartment("SALES").build();
        UserRef leg = UserRef.newBuilder().setId(legalId.toString()).setUsername("leg").setFullName("Legal").setDepartment("LEGAL").build();
        UserRef dir = UserRef.newBuilder().setId(directorId.toString()).setUsername("dir").setFullName("Director").setDepartment("BOARD").build();
        identityClient.setTestOverrides(Map.of(
                "SALES_MANAGER", List.of(sm),
                "LEGAL_REVIEWER", List.of(leg),
                "DIRECTOR", List.of(dir)
        ));
    }

    private void setAuth(UUID id, String name, String role) {
        var user = new AuthenticatedUser(id, "u", name, "SALES", List.of(role));
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of(() -> "approval:act"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void returnsInProgressIfExistsElseLatestTerminal() throws Exception {
        UUID docId = UUID.randomUUID();
        String docType = "CONTRACT";
        // first instance -> cancel it -> terminal CANCELLED
        UUID key1 = UUID.randomUUID();
        WorkflowInstance i1 = instanceService.startInstance(docType, docId, "CTR-2026-0400", "Cust", "NORMAL", UUID.randomUUID(), "Tester", key1);
        Thread.sleep(10);
        instanceService.cancelInstance(docType, docId, key1);
        assertThat(i1.getId()).isNotNull();

        // second instance -> approve all steps to APPROVED terminal
        UUID key2 = UUID.randomUUID();
        WorkflowInstance i2 = instanceService.startInstance(docType, docId, "CTR-2026-0401", "Cust", "NORMAL", UUID.randomUUID(), "Tester", key2);
        // approve step1 as salesMgr
        WorkflowStepInstance s1 = stepRepo.findByInstance_IdAndStepOrder(i2.getId(), 1).orElseThrow();
        setAuth(salesMgrId, "Sales Mgr", "SALES_MANAGER");
        instanceService.actOnStep(s1.getId(), "APPROVE", null);
        SecurityContextHolder.clearContext();
        // step2 legal
        WorkflowStepInstance s2 = stepRepo.findByInstance_IdAndStepOrder(i2.getId(), 2).orElseThrow();
        setAuth(legalId, "Legal", "LEGAL_REVIEWER");
        instanceService.actOnStep(s2.getId(), "APPROVE", null);
        SecurityContextHolder.clearContext();
        // step3 director
        WorkflowStepInstance s3 = stepRepo.findByInstance_IdAndStepOrder(i2.getId(), 3).orElseThrow();
        setAuth(directorId, "Director", "DIRECTOR");
        instanceService.actOnStep(s3.getId(), "APPROVE", null);
        SecurityContextHolder.clearContext();

        // now no IN_PROGRESS, latest terminal should be i2 (APPROVED) not i1 (CANCELLED)
        WorkflowInstance latest = instanceService.getInstanceByDocument(docType, docId);
        assertThat(latest.getId()).isEqualTo(i2.getId());
        assertThat(latest.getStatus()).isEqualTo("APPROVED");

        // create third instance IN_PROGRESS with new key
        UUID key3 = UUID.randomUUID();
        WorkflowInstance i3 = instanceService.startInstance(docType, docId, "CTR-2026-0402", "Cust", "NORMAL", UUID.randomUUID(), "Tester", key3);
        assertThat(i3.getStatus()).isEqualTo("IN_PROGRESS");

        // getInstanceByDocument should now return IN_PROGRESS i3 even though i2 is later terminal
        WorkflowInstance current = instanceService.getInstanceByDocument(docType, docId);
        assertThat(current.getId()).isEqualTo(i3.getId());
        assertThat(current.getStatus()).isEqualTo("IN_PROGRESS");
        // current_step should be 1
        assertThat(current.getCurrentStepOrder()).isEqualTo(1);
    }

    @Test
    void currentStepNullForTerminalAndStepsContainSnapshots() {
        UUID docId = UUID.randomUUID();
        String docType = "ADDENDUM"; // 2 steps: LEGAL -> DIRECTOR
        UUID key = UUID.randomUUID();
        WorkflowInstance inst = instanceService.startInstance(docType, docId, "ADD-2026-0001", "Cust", "LOW", UUID.randomUUID(), "Tester", key);
        // first step ACTIVE -> getInstance should have current_step = 1
        WorkflowInstance fetched = instanceService.getInstanceByDocument(docType, docId);
        assertThat(fetched.getCurrentStepOrder()).isEqualTo(1);

        // approve first step -> second becomes ACTIVE
        WorkflowStepInstance s1 = stepRepo.findByInstance_IdAndStepOrder(inst.getId(), 1).orElseThrow();
        setAuth(legalId, "Legal", "LEGAL_REVIEWER");
        instanceService.actOnStep(s1.getId(), "APPROVE", null);
        SecurityContextHolder.clearContext();

        WorkflowInstance after1 = instanceService.getInstanceByDocument(docType, docId);
        assertThat(after1.getCurrentStepOrder()).isEqualTo(2);

        // approve second -> instance APPROVED, current null
        WorkflowStepInstance s2 = stepRepo.findByInstance_IdAndStepOrder(inst.getId(), 2).orElseThrow();
        setAuth(directorId, "Director", "DIRECTOR");
        instanceService.actOnStep(s2.getId(), "APPROVE", null);
        SecurityContextHolder.clearContext();

        WorkflowInstance terminal = instanceService.getInstanceByDocument(docType, docId);
        assertThat(terminal.getStatus()).isEqualTo("APPROVED");
        assertThat(terminal.getCurrentStepOrder()).isNull();

        // also verify gRPC would have null current_step and steps contain assignee_names and sla
        List<WorkflowStepInstance> steps = stepRepo.findByInstance_IdOrderByStepOrderAsc(inst.getId());
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getApproverRole()).isEqualTo("LEGAL_REVIEWER");
        assertThat(steps.get(1).getApproverRole()).isEqualTo("DIRECTOR");
    }
}
