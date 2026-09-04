package com.abclogistics.pas.workflow;

import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.workflow.dto.CreateDefinitionRequest;
import com.abclogistics.pas.workflow.dto.UpdateStepsRequest;
import com.abclogistics.pas.workflow.dto.WorkflowDefinitionResponse;
import com.abclogistics.pas.workflow.domain.WorkflowInstance;
import com.abclogistics.pas.workflow.repository.WorkflowDefinitionRepository;
import com.abclogistics.pas.workflow.repository.WorkflowInstanceRepository;
import com.abclogistics.pas.workflow.service.WorkflowDefinitionService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deleting a workflow version: allowed only for inactive drafts no instance
 * ever pinned (instances outlive definitions — approval history must survive).
 * The reference check is one indexed EXISTS on
 * {@code idx_workflow_instance_definition}, never a scan or entity load.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestIdentityConfig.class)
class WorkflowDefinitionDeleteIT {

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
    @Autowired WorkflowInstanceRepository instanceRepo;

    private void setAdminAuth() {
        var user = new com.abclogistics.pas.common.security.AuthenticatedUser(UUID.randomUUID(), "admin", "Admin", "IT", List.of("SYSTEM_ADMIN"));
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of(() -> "workflow:configure"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private WorkflowDefinitionResponse createDraft(String docType) {
        WorkflowDefinitionResponse def = definitionService.create(
                new CreateDefinitionRequest(docType, "Deletable " + System.nanoTime()));
        definitionService.updateSteps(def.id(), new UpdateStepsRequest(List.of(
                new UpdateStepsRequest.StepRequest("Step A", "DIRECTOR", 24))));
        return def;
    }

    @Test
    void deleteUnusedDraftRemovesDefinitionAndSteps() {
        setAdminAuth();
        try {
            WorkflowDefinitionResponse draft = createDraft("CONTRACT");
            definitionService.delete(draft.id());
            assertThat(definitionRepo.findById(draft.id())).isEmpty();
            assertThatThrownBy(() -> definitionService.get(draft.id()))
                    .isInstanceOf(NotFoundException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void deleteActiveIsRejected() {
        setAdminAuth();
        try {
            WorkflowDefinitionResponse active = definitionService.list("CONTRACT").stream()
                    .filter(WorkflowDefinitionResponse::active).findFirst().orElseThrow();
            assertThatThrownBy(() -> definitionService.delete(active.id()))
                    .isInstanceOf(FailedPreconditionException.class)
                    .hasMessageContaining("active");
            // still there
            assertThat(definitionService.get(active.id()).id()).isEqualTo(active.id());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void deleteReferencedDraftIsRejected() {
        setAdminAuth();
        try {
            WorkflowDefinitionResponse draft = createDraft("ADDENDUM");
            // pin an instance to the draft, as a submit would — history must survive
            var definition = definitionRepo.findById(draft.id()).orElseThrow();
            instanceRepo.save(WorkflowInstance.create(definition, UUID.randomUUID(),
                    "ADDENDUM", UUID.randomUUID(), "ADD-2026-0001", "Acme", "NORMAL",
                    UUID.randomUUID(), "Admin"));
            assertThatThrownBy(() -> definitionService.delete(draft.id()))
                    .isInstanceOf(FailedPreconditionException.class)
                    .hasMessageContaining("instance");
            assertThat(definitionService.get(draft.id()).id()).isEqualTo(draft.id());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void deleteUnknownIsNotFound() {        setAdminAuth();
        try {
            assertThatThrownBy(() -> definitionService.delete(UUID.randomUUID()))
                    .isInstanceOf(NotFoundException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
