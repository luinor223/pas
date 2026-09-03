package com.abclogistics.pas.esign.grpc;

import com.abclogistics.pas.billing.grpc.*;
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
public class BillingGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(BillingGrpcClient.class);

    private final ManagedChannel channel;
    private final BillingInternalGrpc.BillingInternalBlockingStub stub;

    public BillingGrpcClient(@Value("${billing.grpc.host:localhost}") String host,
                              @Value("${billing.grpc.port:50055}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = BillingInternalGrpc.newBlockingStub(channel);
    }

    public GetSigningPayloadResponse getSigningPayload(UUID statementId) {
        log.debug("Calling BillingInternal.GetSigningPayload(id={})", statementId);
        return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
            .getSigningPayload(GetSigningPayloadRequest.newBuilder()
                .setId(statementId.toString())
                .build());
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
