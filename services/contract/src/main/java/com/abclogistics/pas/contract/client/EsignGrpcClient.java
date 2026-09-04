package com.abclogistics.pas.contract.client;

import com.abclogistics.pas.esign.grpc.CreateSigningSessionRequest;
import com.abclogistics.pas.esign.grpc.EsignInternalGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper over {@code EsignInternal} (D16). Called only from the relay, never from a request
 * thread: the send action commits first and is dispatched afterwards (registry §6 third use).
 */
@Component
public class EsignGrpcClient {

    private final ManagedChannel channel;
    private final EsignInternalGrpc.EsignInternalBlockingStub stub;

    // Select this constructor instead of the protected test constructor.
    @Autowired
    public EsignGrpcClient(@Value("${esign.grpc.host:localhost}") String host,
                           @Value("${esign.grpc.port:50057}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = EsignInternalGrpc.newBlockingStub(channel);
    }

    protected EsignGrpcClient() {
        this.channel = null;
        this.stub = null;
    }

    /**
     * @return the session id esign-service created or already had for this idempotency key
     */
    public UUID createSigningSession(UUID idempotencyKey, String documentType, UUID documentId,
                                     String documentNo, String signerName, String signerEmail,
                                     String customerName, UUID requestedBy, String requestedByName) {
        CreateSigningSessionRequest request = CreateSigningSessionRequest.newBuilder()
                .setIdempotencyKey(idempotencyKey.toString())
                .setDocumentType(documentType)
                .setDocumentId(documentId.toString())
                .setDocumentNo(documentNo)
                .setSignerName(signerName == null ? "" : signerName)
                .setSignerEmail(signerEmail == null ? "" : signerEmail)
                .setCustomerName(customerName == null ? "" : customerName)
                .setRequestedBy(requestedBy == null ? "" : requestedBy.toString())
                .setRequestedByName(requestedByName == null ? "" : requestedByName)
                .build();
        return UUID.fromString(deadlined().createSigningSession(request).getSessionId());
    }

    private EsignInternalGrpc.EsignInternalBlockingStub deadlined() {
        return stub.withDeadlineAfter(2, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (channel == null) {
            return;
        }
        channel.shutdown();
        try {
            channel.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
