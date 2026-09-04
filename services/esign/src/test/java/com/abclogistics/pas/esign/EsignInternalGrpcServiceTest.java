package com.abclogistics.pas.esign;

import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.esign.domain.SigningSession;
import com.abclogistics.pas.esign.grpc.CreateSigningSessionRequest;
import com.abclogistics.pas.esign.grpc.CreateSigningSessionResponse;
import com.abclogistics.pas.esign.controller.grpc.EsignInternalGrpcService;
import com.abclogistics.pas.esign.service.SigningSessionService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The transport boundary: proto -> domain mapping and error translation. The point worth pinning is
 * that the real caller snapshots reach the service (not a fabricated requester), and that the two
 * refusals the caller relay treats as permanent map to the right gRPC status.
 */
class EsignInternalGrpcServiceTest {

    private SigningSessionService service;
    private EsignInternalGrpcService grpc;
    @SuppressWarnings("unchecked")
    private final StreamObserver<CreateSigningSessionResponse> observer = mock(StreamObserver.class);

    @BeforeEach
    void setUp() {
        service = mock(SigningSessionService.class);
        grpc = new EsignInternalGrpcService(service);
    }

    @Test
    void theRealCustomerAndRequesterSnapshotsAreForwardedToTheService() {
        UUID documentId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        UUID requestedBy = UUID.randomUUID();
        when(service.createSession(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(session());

        grpc.createSigningSession(CreateSigningSessionRequest.newBuilder()
                .setDocumentType("CONTRACT")
                .setDocumentId(documentId.toString())
                .setDocumentNo("HD-1")
                .setSignerName("Signer")
                .setSignerEmail("s@acme.vn")
                .setIdempotencyKey(idempotencyKey.toString())
                .setCustomerName("ACME Corp")
                .setRequestedBy(requestedBy.toString())
                .setRequestedByName("Sales One")
                .build(), observer);

        verify(service).createSession("CONTRACT", documentId, "HD-1", "ACME Corp", "Signer",
                "s@acme.vn", idempotencyKey, requestedBy, "Sales One");
        verify(observer).onCompleted();
    }

    @Test
    void anEmptyRequesterBecomesNullRatherThanAFabricatedUuid() {
        when(service.createSession(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(session());

        grpc.createSigningSession(baseRequest().setRequestedBy("").setCustomerName("").build(), observer);

        verify(service).createSession(eq("CONTRACT"), any(), any(), eq(null), any(), any(), any(),
                eq(null), eq(null));
    }

    @Test
    void anActiveSessionRefusalMapsToFailedPrecondition() {
        when(service.createSession(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new FailedPreconditionException("An active signing session already exists"));

        grpc.createSigningSession(baseRequest().build(), observer);

        assertThat(errorStatus()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void aMalformedIdMapsToInvalidArgument() {
        grpc.createSigningSession(baseRequest().setDocumentId("not-a-uuid").build(), observer);

        assertThat(errorStatus()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private CreateSigningSessionRequest.Builder baseRequest() {
        return CreateSigningSessionRequest.newBuilder()
                .setDocumentType("CONTRACT")
                .setDocumentId(UUID.randomUUID().toString())
                .setDocumentNo("HD-1")
                .setSignerName("Signer")
                .setSignerEmail("s@acme.vn")
                .setIdempotencyKey(UUID.randomUUID().toString())
                .setRequestedBy(UUID.randomUUID().toString());
    }

    private static SigningSession session() {
        SigningSession s = SigningSession.create("CONTRACT", UUID.randomUUID(), "HD-1", "ACME",
                "Signer", "s@acme.vn", UUID.randomUUID(), UUID.randomUUID(), "Req");
        s.setSessionNo("SIG-1");
        return s;
    }

    private Status.Code errorStatus() {
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(captor.capture());
        return ((StatusRuntimeException) captor.getValue()).getStatus().getCode();
    }
}
