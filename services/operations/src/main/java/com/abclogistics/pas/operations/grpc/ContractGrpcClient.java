package com.abclogistics.pas.operations.grpc;

import com.abclogistics.pas.contract.grpc.ContractInternalGrpc;
import com.abclogistics.pas.contract.grpc.GetContractRequest;
import com.abclogistics.pas.contract.grpc.GetContractResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class ContractGrpcClient {

    private final ManagedChannel channel;
    private final ContractInternalGrpc.ContractInternalBlockingStub stub;

    public ContractGrpcClient(
            @Value("${contract.grpc.host:localhost}") String host,
            @Value("${contract.grpc.port:50052}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = ContractInternalGrpc.newBlockingStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS);
    }

    /** For test mocks. */
    protected ContractGrpcClient() {
        this.channel = null;
        this.stub = null;
    }

    public GetContractResponse getContract(UUID contractId) {
        GetContractRequest req = GetContractRequest.newBuilder().setId(contractId.toString()).build();
        try {
            return stub.getContract(req);
        } catch (StatusRuntimeException e) {
            throw mapStatus(e);
        }
    }

    private RuntimeException mapStatus(StatusRuntimeException e) {
        return switch (e.getStatus().getCode()) {
            case NOT_FOUND -> new com.abclogistics.pas.common.error.NotFoundException("Contract not found: " + e.getStatus().getDescription());
            case UNAVAILABLE -> new com.abclogistics.pas.common.error.ConflictException("Contract service unavailable: " + e.getStatus().getDescription());
            default -> new com.abclogistics.pas.operations.error.FailedPreconditionException("Contract lookup failed: " + e.getStatus().getDescription());
        };
    }

    public void shutdown() {
        if (channel != null) channel.shutdown();
    }
}
