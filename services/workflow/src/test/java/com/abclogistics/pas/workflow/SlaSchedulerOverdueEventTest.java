package com.abclogistics.pas.workflow;

import com.abclogistics.pas.workflow.domain.StepAssignee;
import com.abclogistics.pas.workflow.domain.WorkflowInstance;
import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import com.abclogistics.pas.workflow.repository.StepAssigneeRepository;
import com.abclogistics.pas.workflow.scheduler.SlaScheduler;
import com.abclogistics.pas.workflow.scheduler.SlaSchedulerHelper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The other half of the producer contract notification-service depends on, and the one that was
 * broken: {@code workflow.step_overdue} publishes without an outbox row (D9), and until
 * recently without an {@code event_id} at all.
 */
class SlaSchedulerOverdueEventTest {

    private static final String DOCUMENT_TYPE = "CONTRACT";

    private StepAssigneeRepository assignees;
    private SlaSchedulerHelper helper;
    private KafkaTemplate<String, String> kafka;
    private SlaScheduler scheduler;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        assignees = mock(StepAssigneeRepository.class);
        helper = mock(SlaSchedulerHelper.class);
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        ObjectProvider<KafkaTemplate<String, String>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(kafka);
        scheduler = new SlaScheduler(assignees, provider, mapper, helper);
    }

    @Test
    void theOverdueWarningCarriesADerivedEventId() {
        // without this header the record has no dedup key at all
        sweepOver(overdueStep());

        assertThat(header(published(), "event_id")).isNotBlank();
        assertThat(UUID.fromString(header(published(), "event_id"))).isNotNull();
    }

    @Test
    void twoSweepsOverTheSameOverdueStepDeriveTheSameId() {
        // the crash-between-ack-and-stamp window: the second send must be the same logical event
        WorkflowStepInstance step = overdueStep();

        sweepOver(step);
        sweepOver(step);

        List<ProducerRecord<String, String>> sent = allPublished();
        assertThat(header(sent.get(1), "event_id")).isEqualTo(header(sent.get(0), "event_id"));
    }

    @Test
    void aStepOverdueAgainstALaterDeadlineEarnsANewId() {
        // the deadline is in the key for the same reason valid_to is in document.expiring's
        Instant activatedAt = Instant.now().minus(Duration.ofHours(30));
        UUID stepId = UUID.randomUUID();
        sweepOver(step(activatedAt, 24, stepId));
        String firstId = header(published(), "event_id");

        sweepOver(step(activatedAt, 26, stepId));

        assertThat(header(allPublished().get(1), "event_id")).isNotEqualTo(firstId);
    }

    @Test
    void theIdIsDerivedFromTheStepAndItsDeadlineIndependentlyOfTheSweep() {
        // restated from the outside: whoever re-derives it must get the same answer
        WorkflowStepInstance step = overdueStep();
        Instant deadline = step.getActivatedAt().plusSeconds((long) step.getSlaHours() * 3600);

        sweepOver(step);

        assertThat(header(published(), "event_id"))
                .isEqualTo(SlaScheduler.eventId(step.getId(), deadline).toString());
    }

    @Test
    void allThreeMandatoryHeadersAreSet() {
        sweepOver(overdueStep());

        assertThat(header(published(), "event_type")).isEqualTo("workflow.step_overdue");
        assertThat(header(published(), "document_type")).isEqualTo(DOCUMENT_TYPE);
        assertThat(header(published(), "event_id")).isNotBlank();
    }

    @Test
    void theValueIsTheBarePayloadNotAnEnvelope() {
        // the same shape OutboxRelay publishes, so a direct publish is not the one event a cons
        sweepOver(overdueStep());

        JsonNode payload = mapper.readTree(published().value());
        assertThat(payload.has("event_id")).isFalse();
        assertThat(payload.has("payload")).isFalse();
        assertThat(payload.get("step_name").asString()).isEqualTo("Legal review");
        assertThat(payload.get("document_no").asString()).isEqualTo("HD-2026-0001");
        assertThat(payload.get("assignee_ids").isArray()).isTrue();
    }

    @Test
    void theRecordIsKeyedOnTheDocument() {
        // a direct publish has no outbox row to take an aggregate_id from
        WorkflowStepInstance step = overdueStep();

        sweepOver(step);

        assertThat(published().key()).isEqualTo(step.getInstance().getDocumentId().toString());
        assertThat(published().topic()).isEqualTo("pas.events");
    }

    @Test
    void theStepIsStampedOnlyAfterTheAckSoAFailedSendSelfHeals() {
        sweepOver(overdueStep());

        verify(helper, times(1)).markOverdueNotified(any(), any());
    }

    @Test
    void aStepStillInsideItsSlaIsNotWarnedAbout() {
        WorkflowStepInstance fresh = step(Instant.now().minus(Duration.ofHours(1)), 24);
        when(helper.fetchOverdueCandidates()).thenReturn(List.of(fresh));

        scheduler.checkOverdue();

        verify(kafka, org.mockito.Mockito.never()).send(any(ProducerRecord.class));
    }

    private void sweepOver(WorkflowStepInstance step) {
        when(helper.fetchOverdueCandidates()).thenReturn(List.of(step));
        when(assignees.findByStepInstance_Id(step.getId()))
                .thenReturn(List.of(new StepAssignee(step, UUID.randomUUID(), "Tran Thi B")));
        scheduler.checkOverdue();
    }

    private static WorkflowStepInstance overdueStep() {
        return step(Instant.now().minus(Duration.ofHours(30)), 24);
    }

    private static WorkflowStepInstance step(Instant activatedAt, int slaHours) {
        return step(activatedAt, slaHours, UUID.randomUUID());
    }

    private static WorkflowStepInstance step(Instant activatedAt, int slaHours, UUID stepId) {
        WorkflowInstance instance = WorkflowInstance.create(null, UUID.randomUUID(), DOCUMENT_TYPE,
                UUID.randomUUID(), "HD-2026-0001", "ACME Co", "NORMAL",
                UUID.randomUUID(), "Nguyen Van A");
        WorkflowStepInstance step =
                new WorkflowStepInstance(instance, 1, "Legal review", "LEGAL_REVIEWER", slaHours, "ACTIVE");
        step.setActivatedAt(activatedAt);
        // @GeneratedValue, so both ids are null until a flush; the derived event id needs them
        setId(instance, UUID.randomUUID());
        setId(step, stepId);
        return step;
    }

    private static void setId(Object entity, UUID id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private ProducerRecord<String, String> published() {
        return allPublished().getFirst();
    }

    @SuppressWarnings("unchecked")
    private List<ProducerRecord<String, String>> allPublished() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
        return captor.getAllValues();
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
