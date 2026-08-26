package com.abclogistics.pas.workflow;

import com.abclogistics.pas.workflow.dto.CreateDefinitionRequest;
import com.abclogistics.pas.workflow.dto.UpdateStepsRequest;
import com.abclogistics.pas.workflow.dto.WorkflowDefinitionResponse;
import com.abclogistics.pas.workflow.error.FailedPreconditionException;
import com.abclogistics.pas.workflow.repository.WorkflowDefinitionRepository;
import com.abclogistics.pas.workflow.service.WorkflowDefinitionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActivationDeactivateThenActivateTxTest {

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

    @Autowired WorkflowDefinitionService definitionService;
    @Autowired WorkflowDefinitionRepository definitionRepo;

    private void setAdminAuth() {
        var user = new com.abclogistics.pas.common.security.AuthenticatedUser(UUID.randomUUID(), "admin", "Admin", "IT", List.of("SYSTEM_ADMIN"));
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of(() -> "workflow:configure"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void editActiveFailsAndActivateSwapsCorrectly() {
        setAdminAuth();
        // create new inactive definition for CONTRACT
        WorkflowDefinitionResponse newDef = definitionService.create(new CreateDefinitionRequest("CONTRACT", "Contract vTest " + System.nanoTime()));
        assertThat(newDef.active()).isFalse();

        // set steps
        definitionService.updateSteps(newDef.id(), new UpdateStepsRequest(List.of(
                new UpdateStepsRequest.StepRequest("Step A", "SALES_MANAGER", 48),
                new UpdateStepsRequest.StepRequest("Step B", "DIRECTOR", 48)
        )));

        // find current active
        WorkflowDefinitionResponse beforeActive = definitionService.list("CONTRACT").stream().filter(WorkflowDefinitionResponse::active).findFirst().orElseThrow();
        assertThat(beforeActive.id()).isNotEqualTo(newDef.id());

        // activate new -> should deactivate old and activate new in same tx
        WorkflowDefinitionResponse activated = definitionService.activate(newDef.id());
        assertThat(activated.active()).isTrue();

        // verify only one active remains
        long activeCount = definitionService.list("CONTRACT").stream().filter(WorkflowDefinitionResponse::active).count();
        assertThat(activeCount).isEqualTo(1);
        WorkflowDefinitionResponse nowActive = definitionService.list("CONTRACT").stream().filter(WorkflowDefinitionResponse::active).findFirst().orElseThrow();
        assertThat(nowActive.id()).isEqualTo(newDef.id());

        // editing active should fail
        assertThatThrownBy(() -> definitionService.updateSteps(nowActive.id(), new UpdateStepsRequest(List.of(
                new UpdateStepsRequest.StepRequest("X", "DIRECTOR", 24)
        )))).isInstanceOf(FailedPreconditionException.class);

        // activating already active is idempotent (no error, still one active)
        WorkflowDefinitionResponse again = definitionService.activate(nowActive.id());
        assertThat(again.active()).isTrue();
        assertThat(definitionService.list("CONTRACT").stream().filter(WorkflowDefinitionResponse::active).count()).isEqualTo(1);
        SecurityContextHolder.clearContext();
    }

    @Test
    void concurrentActivateSerializes() throws Exception {
        setAdminAuth();
        WorkflowDefinitionResponse defA = definitionService.create(new CreateDefinitionRequest("PRICE_LIST", "PL A " + System.nanoTime()));
        definitionService.updateSteps(defA.id(), new UpdateStepsRequest(List.of(
                new UpdateStepsRequest.StepRequest("S1", "SALES_MANAGER", 24),
                new UpdateStepsRequest.StepRequest("S2", "DIRECTOR", 24)
        )));
        WorkflowDefinitionResponse defB = definitionService.create(new CreateDefinitionRequest("PRICE_LIST", "PL B " + System.nanoTime()));
        definitionService.updateSteps(defB.id(), new UpdateStepsRequest(List.of(
                new UpdateStepsRequest.StepRequest("S1", "SALES_MANAGER", 24),
                new UpdateStepsRequest.StepRequest("S2", "DIRECTOR", 24)
        )));

        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<WorkflowDefinitionResponse> fa = exec.submit(() -> {
            setAdminAuth();
            go.await();
            try { return definitionService.activate(defA.id()); } finally { SecurityContextHolder.clearContext(); }
        });
        Future<WorkflowDefinitionResponse> fb = exec.submit(() -> {
            setAdminAuth();
            go.await();
            try { return definitionService.activate(defB.id()); } finally { SecurityContextHolder.clearContext(); }
        });
        go.countDown();
        fa.get(); fb.get();
        exec.shutdown();
        // exactly one active after concurrent activates
        long actives = definitionService.list("PRICE_LIST").stream().filter(WorkflowDefinitionResponse::active).count();
        assertThat(actives).isEqualTo(1);
        SecurityContextHolder.clearContext();
    }
}
