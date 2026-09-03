package com.abclogistics.pas.esign.grpc;

import com.abclogistics.pas.contract.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class ContractGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(ContractGrpcClient.class);

    private final ManagedChannel channel;
    private final ContractInternalGrpc.ContractInternalBlockingStub stub;

    public ContractGrpcClient(@Value("${contract.grpc.host:localhost}") String host,
                               @Value("${contract.grpc.port:50052}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = ContractInternalGrpc.newBlockingStub(channel);
    }

    public GetSigningPayloadResponse getSigningPayload(String documentType, UUID documentId) {
        log.debug("Calling ContractInternal.GetSigningPayload(docType={}, docId={})", documentType, documentId);
        try {
            return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                .getSigningPayload(GetSigningPayloadRequest.newBuilder()
                    .setDocumentType(documentType)
                    .setId(documentId.toString())
                    .build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw e;
            }
            throw e;
        }
    }

    @PreDestroy
    void shutdown() {
        if (channel != null) {
            channel.shutdown();
            try {
                channel.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
