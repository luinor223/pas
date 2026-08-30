package com.abclogistics.pas.operations.grpc;

import com.abclogistics.pas.pricing.grpc.GetServiceItemRequest;
import com.abclogistics.pas.pricing.grpc.GetServiceItemResponse;
import com.abclogistics.pas.pricing.grpc.PricingInternalGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class PricingGrpcClient {

    private final ManagedChannel channel;
    private final PricingInternalGrpc.PricingInternalBlockingStub stub;

    public PricingGrpcClient(
            @Value("${pricing.grpc.host:localhost}") String host,
            @Value("${pricing.grpc.port:50053}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = PricingInternalGrpc.newBlockingStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS);
    }

    protected PricingGrpcClient() {
        this.channel = null;
        this.stub = null;
    }

    public GetServiceItemResponse getServiceItem(String code) {
        GetServiceItemRequest req = GetServiceItemRequest.newBuilder().setCode(code).build();
        try {
            return stub.getServiceItem(req);
        } catch (StatusRuntimeException e) {
            throw mapStatus(e, code);
        }
    }

    private RuntimeException mapStatus(StatusRuntimeException e, String code) {
        return switch (e.getStatus().getCode()) {
            case NOT_FOUND -> new com.abclogistics.pas.common.error.NotFoundException("Service item not found: " + code);
            case UNAVAILABLE -> new com.abclogistics.pas.common.error.ConflictException("Pricing service unavailable: " + e.getStatus().getDescription());
            default -> new com.abclogistics.pas.operations.error.FailedPreconditionException("Service item lookup failed: " + e.getStatus().getDescription());
        };
    }

    public void shutdown() {
        if (channel != null) channel.shutdown();
    }
}
