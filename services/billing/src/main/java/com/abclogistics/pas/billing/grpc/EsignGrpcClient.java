package com.abclogistics.pas.billing.grpc;

import com.abclogistics.pas.esign.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class EsignGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(EsignGrpcClient.class);

    private final ManagedChannel channel;
    private final EsignInternalGrpc.EsignInternalBlockingStub stub;

    public EsignGrpcClient(@Value("${esign.grpc.host:localhost}") String host,
                            @Value("${esign.grpc.port:50057}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = EsignInternalGrpc.newBlockingStub(channel);
    }

    public CreateSigningSessionResponse createSigningSession(String documentType, String documentId,
                                                              String documentNo, String signerName,
                                                              String signerEmail, String idempotencyKey,
                                                              String customerName, String requestedBy,
                                                              String requestedByName) {
        log.debug("Calling EsignInternal.CreateSigningSession(docType={}, docId={})", documentType, documentId);
        return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
            .createSigningSession(CreateSigningSessionRequest.newBuilder()
                .setDocumentType(documentType)
                .setDocumentId(documentId)
                .setDocumentNo(documentNo)
                .setSignerName(signerName != null ? signerName : "")
                .setSignerEmail(signerEmail != null ? signerEmail : "")
                .setIdempotencyKey(idempotencyKey)
                .setCustomerName(customerName != null ? customerName : "")
                .setRequestedBy(requestedBy != null ? requestedBy : "")
                .setRequestedByName(requestedByName != null ? requestedByName : "")
                .build());
    }

    @PreDestroy
    void shutdown() {
        if (channel != null) channel.shutdown();
    }
}
