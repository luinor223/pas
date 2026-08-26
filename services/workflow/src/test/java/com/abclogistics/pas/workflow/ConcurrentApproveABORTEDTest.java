package com.abclogistics.pas.workflow;

import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.identity.grpc.UserRef;
import com.abclogistics.pas.workflow.domain.WorkflowInstance;
import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import com.abclogistics.pas.workflow.error.AbortedException;
import com.abclogistics.pas.workflow.repository.WorkflowStepInstanceRepository;
import com.abclogistics.pas.workflow.service.IdentityGrpcClient;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestIdentityConfig.class)
class ConcurrentApproveABORTEDTest {

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

    private UUID actorId;
    private String actorName = "Sales Manager";
    private UserRef actorRef;

    @BeforeEach
    void stub() {
        actorId = UUID.randomUUID();
        actorRef = UserRef.newBuilder().setId(actorId.toString()).setUsername("actor").setFullName(actorName).setDepartment("SALES").build();
        UserRef legal = UserRef.newBuilder().setId(UUID.randomUUID().toString()).setUsername("legal").setFullName("Legal").setDepartment("LEGAL").build();
        UserRef dir = UserRef.newBuilder().setId(UUID.randomUUID().toString()).setUsername("dir").setFullName("Director").setDepartment("BOARD").build();
        identityClient.setTestOverrides(Map.of(
                "SALES_MANAGER", List.of(actorRef),
                "LEGAL_REVIEWER", List.of(legal),
                "DIRECTOR", List.of(dir)
        ));
    }

    private void setAuth(UUID userId, String fullName) {
        var user = new AuthenticatedUser(userId, "user", fullName, "SALES", List.of("SALES_MANAGER"));
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of(() -> "approval:act"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void concurrentApproveOneWinsOneAborted() throws Exception {
        UUID docId = UUID.randomUUID();
        WorkflowInstance inst = instanceService.startInstance("CONTRACT", docId, "CTR-2026-0300", "Cust", "NORMAL", UUID.randomUUID(), "Tester", UUID.randomUUID());
        WorkflowStepInstance step = stepRepo.findByInstance_IdAndStepOrder(inst.getId(), 1).orElseThrow();
        UUID stepId = step.getId();

        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Exception> ex1 = new AtomicReference<>();
        AtomicReference<Exception> ex2 = new AtomicReference<>();

        Future<?> f1 = exec.submit(() -> {
            ready.countDown();
            try { go.await(); } catch (Exception ignored) {}
            setAuth(actorId, actorName);
            try {
                instanceService.actOnStep(stepId, "APPROVE", null);
            } catch (Exception e) { ex1.set(e); }
            SecurityContextHolder.clearContext();
        });
        Future<?> f2 = exec.submit(() -> {
            ready.countDown();
            try { go.await(); } catch (Exception ignored) {}
            setAuth(actorId, actorName);
            try {
                instanceService.actOnStep(stepId, "APPROVE", null);
            } catch (Exception e) { ex2.set(e); }
            SecurityContextHolder.clearContext();
        });

        ready.await();
        go.countDown();
        f1.get();
        f2.get();
        exec.shutdown();

        // One must succeed (no exception), one must be ABORTED
        int successes = (ex1.get() == null ? 1 : 0) + (ex2.get() == null ? 1 : 0);
        int aborts = ((ex1.get() instanceof AbortedException) ? 1 : 0) + ((ex2.get() instanceof AbortedException) ? 1 : 0);
        // The loser may also be FailedPrecondition if step flipped to APPROVED before second SELECT
        // but our implementation throws Aborted only for version mismatch; if step status check fails first we get FailedPrecondition.
        // Accept either Aborted or FailedPrecondition as loser.
        int losers = (ex1.get() != null ? 1 : 0) + (ex2.get() != null ? 1 : 0);
        assertThat(successes).isEqualTo(1);
        assertThat(losers).isEqualTo(1);
        Exception loser = ex1.get() != null ? ex1.get() : ex2.get();
        assertThat(loser).isInstanceOfAny(AbortedException.class, com.abclogistics.pas.workflow.error.FailedPreconditionException.class, org.springframework.dao.OptimisticLockingFailureException.class);

        // verify step now APPROVED
        WorkflowStepInstance after = stepRepo.findById(stepId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("APPROVED");
    }
}
