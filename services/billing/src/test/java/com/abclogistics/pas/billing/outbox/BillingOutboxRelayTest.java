package com.abclogistics.pas.billing.outbox;

import com.abclogistics.pas.billing.grpc.EsignGrpcClient;
import com.abclogistics.pas.billing.grpc.WorkflowGrpcClient;
import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.esign.grpc.CreateSigningSessionResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Relay routing (§M2): audit → Kafka, intents → gRPC; permanent refusals park instead of
 * retrying forever. No Spring, no Docker.
 */
class BillingOutboxRelayTest {

    private OutboxRepository outbox;
    private KafkaTemplate<String, String> kafka;
    private WorkflowGrpcClient workflow;
    private EsignGrpcClient esign;
    private BillingOutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outbox = mock(OutboxRepository.class);
        kafka = mock(KafkaTemplate.class);
        workflow = mock(WorkflowGrpcClient.class);
        esign = mock(EsignGrpcClient.class);
        relay = new BillingOutboxRelay(outbox, new OutboxRelayProperties(),
                mock(TransactionTemplate.class), kafka, workflow, esign,
                new ObjectMapper(), mock(AuditRecorder.class));
        when(workflow.startInstance(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());
        when(esign.createSigningSession(any(), any(), any(), any(), any(), any()))
                .thenReturn(CreateSigningSessionResponse.newBuilder()
                        .setSessionId(UUID.randomUUID().toString()).setStatus("SIGNING").build());
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void auditGoesToKafkaNotGrpc() throws Exception {
        relay.dispatch(OutboxEvent.audit("PAYMENT_STATEMENT", UUID.randomUUID(), "{}"));

        verify(kafka).send(any(ProducerRecord.class));
        verify(workflow, never()).startInstance(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void workflowStartRequestedCallsStartInstance() throws Exception {
        UUID docId = UUID.randomUUID();
        relay.dispatch(OutboxEvent.event("workflow.start_requested", "PAYMENT_STATEMENT", docId,
                "{\"idempotency_key\":\"" + UUID.randomUUID() + "\",\"document_type\":\"PAYMENT_STATEMENT\","
                        + "\"document_id\":\"" + docId + "\",\"document_no\":\"PMT-2026-0001\"}"));

        verify(workflow).startInstance(any(), any(), any(), any(), any(), any(), any(), any());
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void esignRequestedCallsCreateSigningSession() throws Exception {
        UUID docId = UUID.randomUUID();
        relay.dispatch(OutboxEvent.event("esign.session_requested", "PAYMENT_STATEMENT", docId,
                "{\"idempotency_key\":\"" + UUID.randomUUID() + "\",\"document_type\":\"PAYMENT_STATEMENT\","
                        + "\"document_id\":\"" + docId + "\",\"document_no\":\"PMT-2026-0001\"}"));

        verify(esign).createSigningSession(any(), any(), any(), any(), any(), any());
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void malformedPayloadSurfacesUnwrappedForParking() {
        OutboxEvent poison = OutboxEvent.event("workflow.start_requested",
                "PAYMENT_STATEMENT", UUID.randomUUID(), "{not json");

        // must reach isPermanentFailure as a JacksonException, not a RuntimeException wrapper
        assertThatThrownBy(() -> relay.dispatch(poison))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
    }

    @Test
    void missingPayloadFieldParksLoudlyInsteadOfNpeLoop() {
        // parseable JSON but no idempotency_key: must park, never NPE-retry forever
        OutboxEvent row = OutboxEvent.event("workflow.start_requested",
                "PAYMENT_STATEMENT", UUID.randomUUID(),
                "{\"document_type\":\"PAYMENT_STATEMENT\",\"document_id\":\"" + UUID.randomUUID()
                        + "\",\"document_no\":\"PMT-2026-0001\"}");

        assertThatThrownBy(() -> relay.dispatch(row))
                .isInstanceOf(BillingOutboxRelay.UnroutableEventException.class)
                .hasMessageContaining("idempotency_key");
    }

    @Test
    void unroutableTypeParksLoudly() {
        assertThatThrownBy(() -> relay.dispatch(
                OutboxEvent.event("billing.something_new", "PAYMENT_STATEMENT", UUID.randomUUID(), "{}")))
                .isInstanceOf(BillingOutboxRelay.UnroutableEventException.class);
    }

    @Test
    void refusalStatusesArePermanentButNotFoundAndUnavailableRetry() {
        for (Status.Code code : new Status.Code[]{
                Status.Code.FAILED_PRECONDITION, Status.Code.INVALID_ARGUMENT,
                Status.Code.OUT_OF_RANGE, Status.Code.ALREADY_EXISTS}) {
            assertThat(relay.isPermanentFailure(new StatusRuntimeException(Status.fromCode(code))))
                    .as("permanent: %s", code).isTrue();
        }
        for (Status.Code code : new Status.Code[]{
                Status.Code.NOT_FOUND, Status.Code.UNAVAILABLE,
                Status.Code.PERMISSION_DENIED, Status.Code.UNAUTHENTICATED, Status.Code.UNIMPLEMENTED}) {
            assertThat(relay.isPermanentFailure(new StatusRuntimeException(Status.fromCode(code))))
                    .as("retryable: %s", code).isFalse();
        }
    }
}
