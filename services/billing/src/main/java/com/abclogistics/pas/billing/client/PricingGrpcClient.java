package com.abclogistics.pas.billing.client;

import com.abclogistics.pas.common.correlation.CorrelationClientInterceptor;
import com.abclogistics.pas.pricing.grpc.GetEffectivePriceListRequest;
import com.abclogistics.pas.pricing.grpc.GetEffectivePriceListResponse;
import com.abclogistics.pas.pricing.grpc.PricingInternalGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class PricingGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(PricingGrpcClient.class);

    private final ManagedChannel channel;
    private final PricingInternalGrpc.PricingInternalBlockingStub stub;

    public PricingGrpcClient(@Value("${pricing.grpc.host:localhost}") String host,
                              @Value("${pricing.grpc.port:50053}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().intercept(new CorrelationClientInterceptor()).build();
        this.stub = PricingInternalGrpc.newBlockingStub(channel);
    }

    public GetEffectivePriceListResponse getEffectivePriceList(String contractId, String customerId,
                                                                String serviceGroup, String date) {
        log.debug("Calling PricingInternal.GetEffectivePriceList(contract={}, customer={}, group={}, date={})",
            contractId, customerId, serviceGroup, date);
        return stub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .getEffectivePriceList(GetEffectivePriceListRequest.newBuilder()
                .setContractId(contractId)
                .setCustomerId(customerId)
                .setServiceGroup(serviceGroup)
                .setDate(date)
                .build());
    }

    @PreDestroy
    void shutdown() {
        if (channel != null) channel.shutdown();
    }
}
