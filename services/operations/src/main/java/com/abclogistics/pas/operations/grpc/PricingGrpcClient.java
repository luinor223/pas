package com.abclogistics.pas.operations.grpc;

import com.abclogistics.pas.pricing.grpc.GetServiceItemRequest;
import com.abclogistics.pas.pricing.grpc.GetServiceItemResponse;
import com.abclogistics.pas.pricing.grpc.PricingInternalGrpc;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.error.ServiceUnavailableException;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class PricingGrpcClient implements PricingClient {

    private final ManagedChannel channel;
    private final PricingInternalGrpc.PricingInternalBlockingStub stub;

    public PricingGrpcClient(
            @Value("${pricing.grpc.host:localhost}") String host,
            @Value("${pricing.grpc.port:50053}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = PricingInternalGrpc.newBlockingStub(channel);
    }

    @Override
    public GetServiceItemResponse getServiceItem(String code) {
        GetServiceItemRequest req = GetServiceItemRequest.newBuilder().setCode(code).build();
        try {
            return stub.withDeadlineAfter(2, TimeUnit.SECONDS).getServiceItem(req);
        } catch (StatusRuntimeException e) {
            throw mapStatus(e, code);
        }
    }

    private RuntimeException mapStatus(StatusRuntimeException e, String code) {
        return switch (e.getStatus().getCode()) {
            case NOT_FOUND -> new NotFoundException("Service item not found: " + code);
            case UNAVAILABLE -> new ServiceUnavailableException("Pricing service unavailable: " + e.getStatus().getDescription());
            case FAILED_PRECONDITION -> new FailedPreconditionException("Service item lookup failed: " + e.getStatus().getDescription());
            case ABORTED -> new ConflictException("Pricing concurrent conflict: " + e.getStatus().getDescription());
            case INVALID_ARGUMENT -> new IllegalArgumentException("Pricing invalid argument: " + e.getStatus().getDescription());
            default -> new ConflictException("Pricing lookup failed (" + e.getStatus().getCode() + "): " + e.getStatus().getDescription());
        };
    }

    public void shutdown() {
        if (channel != null) channel.shutdown();
    }
}
