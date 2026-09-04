package com.abclogistics.pas.contract;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.contract.event.EsignSessionRequested;
import com.abclogistics.pas.contract.event.WorkflowStartRequested;
import com.abclogistics.pas.contract.outbox.ContractOutboxRelay;
import com.abclogistics.pas.contract.client.EsignGrpcClient;
import com.abclogistics.pas.contract.client.WorkflowGrpcClient;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Item 6 — the relay's routing table. No Spring context and no Docker: the point under test is
 * which destination an event type reaches, not the M2 claim SQL, which the shared
 * {@code OutboxRelay} owns and its own service tests cover.
 *
 * <p>The failure that matters here is silent: a gRPC intent published to Kafka is accepted by the
 * broker, marked published, and read by nobody. So every test asserts the destination it did NOT
 * go to as well as the one it did.
 */
class ContractOutboxRelayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OutboxRepository outbox;
    private KafkaTemplate<String, String> kafka;
    private WorkflowGrpcClient workflow;
    private EsignGrpcClient esign;
    private AuditRecorder audit;
    private ContractOutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outbox = mock(OutboxRepository.class);
        kafka = mock(KafkaTemplate.class);
        workflow = mock(WorkflowGrpcClient.class);
        esign = mock(EsignGrpcClient.class);
        audit = mock(AuditRecorder.class);
        // A template that just runs the callback: these tests are about routing, and the real
        // transaction boundaries are pinned by libs:common's OutboxRelayDatabaseTest.
        relay = new ContractOutboxRelay(outbox, new OutboxRelayProperties(), kafka, workflow,
                esign, MAPPER, audit, directTransactions());
        when(esign.createSigningSession(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    // --- audit.recorded → Kafka -------------------------------------------------------------

    @Test
    void anAuditRowIsPublishedToTheAuditTopic() {
        OutboxEvent event = queued(OutboxEvent.audit("CONTRACT", UUID.randomUUID(), "{\"action\":\"CREATE\"}"));

        relay.pollAndDispatch();

        ProducerRecord<String, String> record = captureSend();
        assertThat(record.topic()).isEqualTo("pas.audit");
        assertThat(record.key()).isEqualTo(event.getAggregateId().toString());
        assertThat(record.value()).isEqualTo("{\"action\":\"CREATE\"}");
        verifyNoInteractions(workflow);
    }

    @Test
    void theAuditRecordCarriesRoutingHeaders() {
        OutboxEvent event = queued(OutboxEvent.audit("CUSTOMER", UUID.randomUUID(), "{}"));

        relay.pollAndDispatch();

        ProducerRecord<String, String> record = captureSend();
        assertThat(header(record, "event_type")).isEqualTo("audit.recorded");
        assertThat(header(record, "document_type")).isEqualTo("CUSTOMER");
        // The consumer's dedup key. Without it on the wire a redelivery is indistinguishable
        // from a new event, so processed_event cannot do its job.
        assertThat(header(record, "event_id")).isEqualTo(event.getId().toString());
    }

    @Test
    void aPublishedAuditRowIsStampedOnlyAfterTheAck() {
        OutboxEvent event = queued(OutboxEvent.audit("CONTRACT", UUID.randomUUID(), "{}"));

        relay.pollAndDispatch();

        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getRetryCount()).isZero();
    }

    @Test
    void aBrokerFailureLeavesTheRowPendingForRetry() {
        OutboxEvent event = queued(OutboxEvent.audit("CONTRACT", UUID.randomUUID(), "{}"));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        relay.pollAndDispatch();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getClaimedAt()).isNull();
        assertThat(event.getRetryCount()).isEqualTo(1);
    }

    // --- workflow.start_requested → gRPC ----------------------------------------------------

    @Test
    void aStartRequestIsDispatchedOverGrpcNotKafka() {
        UUID key = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID requestedBy = UUID.randomUUID();
        queued(startRequested(new WorkflowStartRequested(key, "CONTRACT", documentId, "HD-2026-0001",
                "ACME Co", "NORMAL", requestedBy, "Sales One")));

        relay.pollAndDispatch();

        verify(workflow).startInstance(key, "CONTRACT", documentId, "HD-2026-0001",
                "ACME Co", "NORMAL", requestedBy, "Sales One");
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void everyRetrySendsTheIdempotencyKeyGeneratedAtSubmit() {
        UUID key = UUID.randomUUID();
        OutboxEvent event = queued(startRequested(new WorkflowStartRequested(key, "CONTRACT",
                UUID.randomUUID(), "HD-2026-0002", "ACME Co", "NORMAL", null, "system")));
        when(workflow.startInstance(any(), anyString(), any(), anyString(), any(), any(), any(), any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
                .thenReturn(UUID.randomUUID());

        relay.pollAndDispatch();
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getPublishedAt()).isNull();

        relay.pollAndDispatch();

        // Same key both times: a lost ack must resolve to the instance that already exists.
        verify(workflow, times(2)).startInstance(eq(key), anyString(), any(), anyString(),
                any(), any(), any(), any());
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void anUnreachableWorkflowServiceNeverFallsBackToKafka() {
        queued(startRequested(new WorkflowStartRequested(UUID.randomUUID(), "CONTRACT",
                UUID.randomUUID(), "HD-2026-0003", "ACME Co", "NORMAL", null, "system")));
        when(workflow.startInstance(any(), anyString(), any(), anyString(), any(), any(), any(), any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        relay.pollAndDispatch();

        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void anAbsentRequesterSurvivesTheRoundTripAsNull() {
        // Submitted by the scheduler rather than a user — the payload's null must not become the
        // string "null" on the way through the outbox.
        UUID key = UUID.randomUUID();
        queued(startRequested(new WorkflowStartRequested(key, "CONTRACT", UUID.randomUUID(),
                "HD-2026-0004", null, "NORMAL", null, "system")));

        relay.pollAndDispatch();

        verify(workflow).startInstance(eq(key), eq("CONTRACT"), any(), eq("HD-2026-0004"),
                eq(null), eq("NORMAL"), eq(null), eq("system"));
    }

    // --- esign.session_requested → gRPC ------------------------------------------------------

    @Test
    void aSendForSigningIsDispatchedOverGrpcNotKafka() {
        UUID key = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        queued(sessionRequested(new EsignSessionRequested(key, "CONTRACT", documentId,
                "HD-2026-0001", "Tran Thi B", "b@acme.vn", "ACME Corp", null, null)));

        relay.pollAndDispatch();

        verify(esign).createSigningSession(key, "CONTRACT", documentId, "HD-2026-0001",
                "Tran Thi B", "b@acme.vn", "ACME Corp", null, null);
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void everySendRetryReusesTheKeyGeneratedWhenTheUserPressedSend() {
        // The reason the key is stored on the row: a retried send must resolve to the session that
        // already exists, not open a second one against the provider (APR-07).
        UUID key = UUID.randomUUID();
        OutboxEvent event = queued(sessionRequested(new EsignSessionRequested(key, "CONTRACT",
                UUID.randomUUID(), "HD-2026-0002", "Tran Thi B", "b@acme.vn", "ACME Corp", null, null)));
        when(esign.createSigningSession(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
                .thenReturn(UUID.randomUUID());

        relay.pollAndDispatch();
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getPublishedAt()).isNull();

        relay.pollAndDispatch();

        verify(esign, times(2)).createSigningSession(eq(key), anyString(), any(), anyString(),
                any(), any(), any(), any(), any());
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void anUnreachableEsignServiceNeverFallsBackToKafka() {
        queued(sessionRequested(new EsignSessionRequested(UUID.randomUUID(), "CONTRACT",
                UUID.randomUUID(), "HD-2026-0003", "Tran Thi B", "b@acme.vn", "ACME Corp", null, null)));
        when(esign.createSigningSession(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        relay.pollAndDispatch();

        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    // --- refusals vs outages ------------------------------------------------------------------

    @Test
    void aPermanentlyRefusedSendIsParkedRatherThanRetriedForEver() {
        // A double-send is refused with FAILED_PRECONDITION, and it will be refused identically on
        // every poll. At a five-second interval, retrying is a row re-claimed for the life of the
        // deployment and a log line every five seconds saying the same thing.
        OutboxEvent event = queued(sessionRequested(new EsignSessionRequested(UUID.randomUUID(),
                "CONTRACT", UUID.randomUUID(), "CTR-2026-0006", "Tran Thi B", "b@acme.vn", "ACME Corp", null, null)));
        when(esign.createSigningSession(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new StatusRuntimeException(
                        Status.FAILED_PRECONDITION.withDescription("session already exists")));

        relay.pollAndDispatch();

        // parked, not published: nothing reached esign, and the row says so
        assertThat(event.getCancelledAt()).isNotNull();
        assertThat(event.getPublishedAt()).isNull();
        // and the payload and count survive, so the parked send is still readable after the fact
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getPayload()).contains("b@acme.vn");
        // the half that reaches a person: the send is in History as failed, not just as sent
        Map<String, Object> detail = captureAudit("SEND_FOR_SIGNING_FAILED", "CTR-2026-0006");
        assertThat(detail).containsEntry("grpcStatus", "FAILED_PRECONDITION")
                .containsEntry("grpcDescription", "session already exists");
    }

    @Test
    void anOutageIsStillRetried() {
        // The distinction the classification exists for: UNAVAILABLE is what the outbox is FOR.
        OutboxEvent event = queued(sessionRequested(new EsignSessionRequested(UUID.randomUUID(),
                "CONTRACT", UUID.randomUUID(), "CTR-2026-0007", "Tran Thi B", "b@acme.vn", "ACME Corp", null, null)));
        when(esign.createSigningSession(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        relay.pollAndDispatch();

        assertThat(event.getCancelledAt()).isNull();
        assertThat(event.getClaimedAt()).isNull();
        assertThat(event.getRetryCount()).isEqualTo(1);
    }

    @Test
    void aSystemicRefusalIsRetriedNotParked() {
        // PERMISSION_DENIED / UNAUTHENTICATED / UNIMPLEMENTED are about the deployment, not the
        // row. Parking them would abandon every pending dispatch over one bad credential, when a
        // config fix or a redeploy recovers all of them at once.
        OutboxEvent event = queued(sessionRequested(new EsignSessionRequested(UUID.randomUUID(),
                "CONTRACT", UUID.randomUUID(), "CTR-2026-0010", "Tran Thi B", "b@acme.vn", "ACME Corp", null, null)));
        when(esign.createSigningSession(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new StatusRuntimeException(
                        Status.PERMISSION_DENIED.withDescription("caller not allowed")));

        relay.pollAndDispatch();

        assertThat(event.getCancelledAt()).isNull();
        assertThat(event.getClaimedAt()).isNull();
        assertThat(event.getRetryCount()).isEqualTo(1);
        // and nothing is audited: the send has not been abandoned, only delayed
        verifyNoInteractions(audit);
    }

    @Test
    void aWorkflowStartRefusedOutrightIsParkedToo() {
        // Not esign-specific: a StartInstance the workflow service refuses is equally undeliverable.
        OutboxEvent event = queued(startRequested(new WorkflowStartRequested(UUID.randomUUID(),
                "CONTRACT", UUID.randomUUID(), "CTR-2026-0008", "ACME Co", "NORMAL", null, "system")));
        when(workflow.startInstance(any(), anyString(), any(), anyString(), any(), any(), any(), any()))
                .thenThrow(new StatusRuntimeException(
                        Status.INVALID_ARGUMENT.withDescription("unknown document type")));

        relay.pollAndDispatch();

        assertThat(event.getCancelledAt()).isNotNull();
        assertThat(event.getPublishedAt()).isNull();
        // "Submitted — workflow initialization pending" is a lie once the dispatch is abandoned,
        // and the status column cannot say so: SUBMITTED is still the document's real status.
        Map<String, Object> detail = captureAudit("WORKFLOW_INITIALIZATION_FAILED", "CTR-2026-0008");
        assertThat(detail).containsEntry("grpcStatus", "INVALID_ARGUMENT");
    }

    @Test
    void anOutageIsNotAudited() {
        // Only abandonment is news. Auditing every retry would bury the one entry that matters.
        queued(sessionRequested(new EsignSessionRequested(UUID.randomUUID(), "CONTRACT",
                UUID.randomUUID(), "CTR-2026-0009", "Tran Thi B", "b@acme.vn", "ACME Corp", null, null)));
        when(esign.createSigningSession(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        relay.pollAndDispatch();

        verifyNoInteractions(audit);
    }

    @Test
    void aBrokerRefusalIsNotTreatedAsPermanent() {
        // Kafka failures arrive as plain exceptions, not gRPC statuses, and a broker that rejected
        // a record is an outage from here. Parking an audit row would lose it silently.
        OutboxEvent event = queued(OutboxEvent.audit("CONTRACT", UUID.randomUUID(), "{}"));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        relay.pollAndDispatch();

        assertThat(event.getCancelledAt()).isNull();
        assertThat(event.getRetryCount()).isEqualTo(1);
    }

    // --- events with no route ---------------------------------------------------------------

    @Test
    void anUnknownEventTypeIsNotSilentlyPublished() {
        OutboxEvent event = queued(OutboxEvent.event("contract.something_new",
                "CONTRACT", UUID.randomUUID(), "{}"));

        relay.pollAndDispatch();

        verify(kafka, never()).send(any(ProducerRecord.class));
        verifyNoInteractions(workflow);
        assertThat(event.getPublishedAt()).isNull();
        // parked, not retried: a missing dispatch branch is a deployment bug, and polling it every
        // five seconds neither fixes it nor makes it more visible than the audit row does
        assertThat(event.getCancelledAt()).isNotNull();
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(captureAudit("DISPATCH_FAILED", null))
                .containsEntry("eventType", "contract.something_new");
    }

    @Test
    void anAuditRowThatCannotBePublishedIsNeverParked() {
        // It would take its own failure record with it. Audit is Kafka-bound, so its failures are
        // outages by construction — but the guard is asserted rather than assumed.
        OutboxEvent event = queued(OutboxEvent.audit("CONTRACT", UUID.randomUUID(), "{}"));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        relay.pollAndDispatch();

        assertThat(event.getCancelledAt()).isNull();
        verifyNoInteractions(audit);
    }

    // --- mixed batch ------------------------------------------------------------------------

    @Test
    void aMixedBatchSplitsBetweenBothDestinations() {
        UUID key = UUID.randomUUID();
        OutboxEvent audit = OutboxEvent.audit("CONTRACT", UUID.randomUUID(), "{}");
        OutboxEvent start = startRequested(new WorkflowStartRequested(key, "CONTRACT",
                UUID.randomUUID(), "HD-2026-0005", "ACME Co", "NORMAL", null, "system"));
        queued(audit, start);

        relay.pollAndDispatch();

        verify(kafka, times(1)).send(any(ProducerRecord.class));
        verify(workflow, times(1)).startInstance(eq(key), anyString(), any(), anyString(),
                any(), any(), any(), any());
        assertThat(audit.getPublishedAt()).isNotNull();
        assertThat(start.getPublishedAt()).isNotNull();
    }

    @Test
    void aRowLostToAConcurrentClaimIsSkippedEntirely() {
        OutboxEvent event = OutboxEvent.audit("CONTRACT", UUID.randomUUID(), "{}");
        when(outbox.findUnpublishedForRelay(any(Instant.class), any(Limit.class)))
                .thenReturn(List.of(event));
        when(outbox.claim(any(UUID.class), any(Instant.class), any(Instant.class))).thenReturn(0);

        relay.pollAndDispatch();

        verify(kafka, never()).send(any(ProducerRecord.class));
        verifyNoInteractions(workflow);
        assertThat(event.getPublishedAt()).isNull();
    }

    // --- helpers ----------------------------------------------------------------------------

    private OutboxEvent sessionRequested(EsignSessionRequested payload) {
        return OutboxEvent.event(EsignSessionRequested.EVENT_TYPE, "CONTRACT",
                payload.documentId(), MAPPER.writeValueAsString(payload));
    }

    private OutboxEvent startRequested(WorkflowStartRequested payload) {
        return OutboxEvent.event(WorkflowStartRequested.EVENT_TYPE, "CONTRACT",
                payload.documentId(), MAPPER.writeValueAsString(payload));
    }

    /** Wires the mocks so {@code pollAndDispatch} sees these rows and wins every claim. The two
     *  bulk UPDATEs are stubbed to apply what their JPQL does, or every outcome assertion below
     *  passes vacuously. */
    private OutboxEvent queued(OutboxEvent... events) {
        when(outbox.findUnpublishedForRelay(any(Instant.class), any(Limit.class)))
                .thenReturn(List.of(events));
        when(outbox.claim(any(UUID.class), any(Instant.class), any(Instant.class))).thenReturn(1);
        for (OutboxEvent event : events) {
            when(outbox.findById(event.getId())).thenReturn(Optional.of(event));
            when(outbox.markPublished(eq(event.getId()), any(Instant.class))).thenAnswer(call -> {
                event.markPublished();
                if (event.getClaimedAt() == null) {
                    event.markClaimed();   // the query coalesces claimed_at
                }
                return 1;
            });
            when(outbox.releaseClaim(event.getId())).thenAnswer(call -> {
                event.releaseClaim();
                return 1;
            });
        }
        return events[0];
    }

    private static TransactionTemplate directTransactions() {
        return new TransactionTemplate(new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        });
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> captureSend() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        return captor.getValue();
    }

    /** The audit row the relay wrote when it gave up, checked down to the detail map. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> captureAudit(String action, String documentNo) {
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("CONTRACT"), any(UUID.class), eq(documentNo), eq(action),
                eq(null), eq(null), anyString(), detail.capture());
        return detail.getValue();
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
