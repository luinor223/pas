package com.abclogistics.pas.billing.client;

import com.abclogistics.pas.common.grpc.GrpcChannels;
import com.abclogistics.pas.contract.grpc.ContractInternalGrpc;
import com.abclogistics.pas.contract.grpc.GetContractRequest;
import com.abclogistics.pas.contract.grpc.GetContractResponse;
import io.grpc.ManagedChannel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class ContractGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(ContractGrpcClient.class);

    private final ManagedChannel channel;
    private final ContractInternalGrpc.ContractInternalBlockingStub stub;

    public ContractGrpcClient(@Value("${contract.grpc.host:localhost}") String host,
                               @Value("${contract.grpc.port:50052}") int port) {
        this.channel = GrpcChannels.plaintext(host, port);
        this.stub = ContractInternalGrpc.newBlockingStub(channel);
    }

    public GetContractResponse getContract(String contractId) {
        log.debug("Calling ContractInternal.GetContract({})", contractId);
        return stub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .getContract(GetContractRequest.newBuilder()
                .setId(contractId)
                .build());
    }

    @PreDestroy
    void shutdown() {
        if (channel != null) channel.shutdown();
    }
}
