package com.abclogistics.pas.billing.client;

import com.abclogistics.pas.common.correlation.CorrelationClientInterceptor;
import com.abclogistics.pas.operations.grpc.ListVolumesRequest;
import com.abclogistics.pas.operations.grpc.ListVolumesResponse;
import com.abclogistics.pas.operations.grpc.OperationsInternalGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class OperationsGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(OperationsGrpcClient.class);

    private final ManagedChannel channel;
    private final OperationsInternalGrpc.OperationsInternalBlockingStub stub;

    public OperationsGrpcClient(@Value("${operations.grpc.host:localhost}") String host,
                                 @Value("${operations.grpc.port:50054}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().intercept(new CorrelationClientInterceptor()).build();
        this.stub = OperationsInternalGrpc.newBlockingStub(channel);
    }

    public ListVolumesResponse listVolumes(String contractId, String periodCode) {
        log.debug("Calling OperationsInternal.ListVolumes(contract={}, period={})", contractId, periodCode);
        return stub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .listVolumes(ListVolumesRequest.newBuilder()
                .setContractId(contractId)
                .setPeriodCode(periodCode)
                .build());
    }

    @PreDestroy
    void shutdown() {
        if (channel != null) channel.shutdown();
    }
}
