package com.abclogistics.pas.operations.grpc;

import com.abclogistics.pas.contract.grpc.ContractInternalGrpc;
import com.abclogistics.pas.contract.grpc.GetContractRequest;
import com.abclogistics.pas.contract.grpc.GetContractResponse;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.error.ServiceUnavailableException;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class ContractGrpcClient implements ContractClient {

    private final ManagedChannel channel;
    private final ContractInternalGrpc.ContractInternalBlockingStub stub;

    public ContractGrpcClient(
            @Value("${contract.grpc.host:localhost}") String host,
            @Value("${contract.grpc.port:50052}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = ContractInternalGrpc.newBlockingStub(channel);
    }

    @Override
    public GetContractResponse getContract(UUID contractId) {
        GetContractRequest req = GetContractRequest.newBuilder().setId(contractId.toString()).build();
        try {
            // per-call deadline — not one-shot at construction (P0-2)
            return stub.withDeadlineAfter(2, TimeUnit.SECONDS).getContract(req);
        } catch (StatusRuntimeException e) {
            throw mapStatus(e);
        }
    }

    private RuntimeException mapStatus(StatusRuntimeException e) {
        return switch (e.getStatus().getCode()) {
            case NOT_FOUND -> new NotFoundException("Contract not found: " + e.getStatus().getDescription());
            case UNAVAILABLE -> new ServiceUnavailableException("Contract service unavailable: " + e.getStatus().getDescription());
            case FAILED_PRECONDITION -> new FailedPreconditionException("Contract lookup failed: " + e.getStatus().getDescription());
            case ABORTED -> new ConflictException("Contract concurrent conflict: " + e.getStatus().getDescription());
            case INVALID_ARGUMENT -> new IllegalArgumentException("Contract invalid argument: " + e.getStatus().getDescription());
            default -> new ConflictException("Contract lookup failed (" + e.getStatus().getCode() + "): " + e.getStatus().getDescription());
        };
    }

    public void shutdown() {
        if (channel != null) channel.shutdown();
    }
}
