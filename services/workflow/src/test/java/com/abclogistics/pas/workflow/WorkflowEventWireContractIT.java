package com.abclogistics.pas.workflow;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.identity.grpc.UserRef;
import com.abclogistics.pas.workflow.domain.WorkflowInstance;
import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import com.abclogistics.pas.workflow.repository.WorkflowStepInstanceRepository;
import com.abclogistics.pas.workflow.service.WorkflowInstanceService;
import com.abclogistics.pas.workflow.service.InboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What this service actually writes to its outbox, asserted here rather than trusted by its
 * consumers.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
@Import(TestIdentityConfig.class)
class WorkflowEventWireContractIT {

    private static final String REQUESTER_NAME = "Nguyen Van A";

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
        // the rows must stay in the table for this test to read them
        r.add("outbox.relay.enabled", () -> "false");
        r.add("identity.grpc.host", () -> "localhost");
        r.add("identity.grpc.port", () -> "50051");
    }

    @Autowired WorkflowInstanceService instances;
    @Autowired InboxService inbox;
    @Autowired WorkflowStepInstanceRepository steps;
    @Autowired OutboxRepository outbox;
    @Autowired StubIdentityGrpcClient identity;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();
    private UUID requester;

    @BeforeEach
    void stubRolesAndActor() {
        outbox.deleteAll();
        requester = UUID.randomUUID();
        UserRef approver = UserRef.newBuilder().setId(requester.toString()).setUsername("approver")
                .setFullName(REQUESTER_NAME).setDepartment("LEGAL").build();
        identity.setTestOverrides(Map.of(
                "SALES_MANAGER", List.of(approver),
                "LEGAL_REVIEWER", List.of(approver),
                "DIRECTOR", List.of(approver)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(requester, "approver", REQUESTER_NAME, "LEGAL",
                        List.of("SALES_MANAGER", "LEGAL_REVIEWER", "DIRECTOR")),
                null, List.of(() -> "approval:act")));
    }

    @Test
    void completedCarriesTheRequesterAsAUuidAndTheNameSeparately() {
        // the defect this test exists for: requested_by held "Nguyen Van A"
        approveEveryStep(startInstance());

        JsonNode payload = payloadOf("workflow.completed");
        assertThat(payload.get("requested_by").asString()).isEqualTo(requester.toString());
        assertThat(UUID.fromString(payload.get("requested_by").asString())).isEqualTo(requester);
        assertThat(payload.get("requested_by_name").asString()).isEqualTo(REQUESTER_NAME);
    }

    @Test
    void stepActionedCarriesTheRequesterAsAUuidToo() {
        // the same field on the other event that carries it
        approveEveryStep(startInstance());

        JsonNode payload = payloadOf("workflow.step_actioned");
        assertThat(UUID.fromString(payload.get("requested_by").asString())).isEqualTo(requester);
        assertThat(payload.get("requested_by_name").asString()).isEqualTo(REQUESTER_NAME);
    }

    @Test
    void stepAssignedCarriesAssigneeIdsAsUuids() {
        // the fan-out's main path: every id here becomes one notification row
        startInstance();

        JsonNode assignees = payloadOf("workflow.step_assigned").get("assignee_ids");
        assertThat(assignees.isArray()).isTrue();
        assertThat(assignees).allSatisfy(id -> UUID.fromString(id.asString()));
    }

    @Test
    void instanceStartedStillCarriesNoRecipient() {
        // the reason it is no longer a notification consumer
        startInstance();

        JsonNode payload = payloadOf("workflow.instance_started");
        assertThat(payload.has("assignee_ids")).isFalse();
        assertThat(payload.has("requested_by")).isFalse();
        assertThat(payload.has("owner_user_id")).isFalse();
        assertThat(payload.has("recipient_role")).isFalse();
    }

    @Test
    void everyOutboxRowIsKeyedOnTheDocumentNotTheInstance() {
        // registry §4: one document's events share a partition
        UUID documentId = startInstance().getDocumentId();

        assertThat(published()).allSatisfy(
                event -> assertThat(event.getAggregateId()).isEqualTo(documentId));
    }

    @Test
    void theValueIsThePayloadAloneWithNoEnvelopeWrapper() {
        // what the relay publishes verbatim (registry §4)
        startInstance();

        assertThat(published()).allSatisfy(event -> {
            assertThat(event.getPayload()).doesNotContain("\"payload\"");
            assertThat(event.getPayload()).doesNotContain("\"event_id\"");
            assertThat(event.getPayload()).doesNotContain("\"event_type\"");
        });
    }

    @Test
    void theseEventsAreOutboxedRatherThanPublishedDirectly() {
        // unlike step_overdue, these are written in the business transaction (D6)
        startInstance();

        assertThat(published()).isNotEmpty();
        assertThat(published()).extracting(OutboxEvent::topic).containsOnly("pas.events");
    }

    @Test
    void submittedInboxIsFilteredAndPagedWithItsCurrentStep() {
        WorkflowInstance instance = startInstance();

        var result = inbox.submittedByMe(
                requester, 0, 15, "HD-2026", "CONTRACT", "NORMAL");

        assertThat(result.totalItems()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.instanceId()).isEqualTo(instance.getId());
            assertThat(item.currentStepName()).isNotBlank();
            assertThat(item.stepInstanceId()).isNotNull();
        });
    }

    @Test
    void inboxQueriesExecuteAgainstPostgresWithFiltersCountsAndTerminalRows() {
        WorkflowInstance matching = startInstance();
        instances.startInstance("PRICE_LIST", UUID.randomUUID(), "PRC-OTHER", "Other customer",
                "HIGH", requester, REQUESTER_NAME, UUID.randomUUID());

        var assigned = inbox.assignedToMe(requester, 0, 1, "ACME", "contract", "normal");
        assertThat(assigned.totalItems()).isEqualTo(1);
        assertThat(assigned.totalPages()).isEqualTo(1);
        assertThat(assigned.items()).singleElement().satisfies(item -> {
            assertThat(item.instanceId()).isEqualTo(matching.getId());
            assertThat(item.stepInstanceId()).isNotNull();
        });
        assertThat(inbox.assignedToMe(requester, 0, 15, "Sales review", null, null).totalItems()).isGreaterThanOrEqualTo(1);
        assertThat(inbox.submittedByMe(requester, 0, 15, REQUESTER_NAME, null, null).totalItems()).isGreaterThanOrEqualTo(2);

        WorkflowStepInstance first = steps.findByInstance_IdAndStepOrder(matching.getId(), 1).orElseThrow();
        instances.actOnStep(first.getId(), "APPROVE", null);
        var completed = inbox.completed(requester, 0, 15, "HD-2026", "CONTRACT", "NORMAL");
        assertThat(completed.totalItems()).isEqualTo(1);
        assertThat(completed.items()).singleElement().extracting(item -> item.instanceId()).isEqualTo(matching.getId());

        approveEveryStep(matching);
        var submitted = inbox.submittedByMe(requester, 0, 15, "HD-2026", "CONTRACT", "NORMAL");
        assertThat(submitted.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("APPROVED");
            assertThat(item.stepInstanceId()).isNull();
        });
    }

    @Test
    void finalApprovalEmitsOneCompletionAndNoDuplicateFinalStepNotificationEvent() {
        approveEveryStep(startInstance());

        assertThat(published()).filteredOn(event -> "workflow.completed".equals(event.getEventType())).hasSize(1);
        assertThat(published()).filteredOn(event -> "workflow.step_actioned".equals(event.getEventType())).hasSize(2);
    }

    @Test
    void workflowAuditRowsCarryTheBusinessDocumentNumber() {
        WorkflowInstance instance = startInstance();
        WorkflowStepInstance first = steps.findByInstance_IdAndStepOrder(instance.getId(), 1).orElseThrow();
        instances.actOnStep(first.getId(), "APPROVE", "ok");

        assertThat(auditPayloads())
                .filteredOn(payload -> payload.get("action").asString().equals("workflow.instance_started")
                        || payload.get("action").asString().equals("workflow.step_approved"))
                .isNotEmpty()
                .allSatisfy(payload -> assertThat(payload.get("entity_no").asString())
                        .isEqualTo("HD-2026-0001"));
    }

    @Test
    void tiedInboxTimestampsRemainStableAcrossPages() {
        for (int index = 0; index < 16; index++) {
            instances.startInstance("CONTRACT", UUID.randomUUID(), "TIE-%02d".formatted(index), "Tie customer",
                    "NORMAL", requester, REQUESTER_NAME, UUID.randomUUID());
        }
        jdbc.update("update workflow.workflow_instance set created_at = timestamp with time zone '2026-01-01 00:00:00Z' where requested_by = ?", requester);

        var first = inbox.submittedByMe(requester, 0, 10, "TIE-", null, null);
        var second = inbox.submittedByMe(requester, 1, 10, "TIE-", null, null);
        var ids = java.util.stream.Stream.concat(first.items().stream(), second.items().stream())
                .map(item -> item.instanceId()).collect(java.util.stream.Collectors.toSet());

        assertThat(first.totalItems()).isEqualTo(16);
        assertThat(ids).hasSize(16);
    }

    private WorkflowInstance startInstance() {
        return instances.startInstance("CONTRACT", UUID.randomUUID(), "HD-2026-0001", "ACME Co",
                "NORMAL", requester, REQUESTER_NAME, UUID.randomUUID());
    }

    /** Re-query after each approval: approving one step activates the next, so a snapshot is stale. */
    private void approveEveryStep(WorkflowInstance instance) {
        for (int guard = 0; guard < 10; guard++) {
            var active = steps.findByInstance_IdOrderByStepOrderAsc(instance.getId()).stream()
                    .filter(s -> "ACTIVE".equals(s.getStatus()))
                    .findFirst();
            if (active.isEmpty()) {
                return;
            }
            instances.actOnStep(active.get().getId(), "APPROVE", "ok");
        }
        throw new AssertionError("chain did not terminate");
    }

    /** pas.events only — audit.recorded rows share the outbox but are a different contract. */
    private List<OutboxEvent> published() {
        return outbox.findAll().stream().filter(e -> "pas.events".equals(e.topic())).toList();
    }

    private List<JsonNode> auditPayloads() {
        return outbox.findAll().stream()
                .filter(e -> "audit.recorded".equals(e.getEventType()))
                .map(e -> mapper.readTree(e.getPayload()))
                .toList();
    }

    private JsonNode payloadOf(String eventType) {
        return published().stream()
                .filter(e -> e.getEventType().equals(eventType))
                .findFirst()
                .map(e -> mapper.readTree(e.getPayload()))
                .orElseThrow(() -> new AssertionError("no " + eventType + " event was published"));
    }
}
