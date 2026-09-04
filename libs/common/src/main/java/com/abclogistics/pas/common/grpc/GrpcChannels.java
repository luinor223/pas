package com.abclogistics.pas.common.grpc;

import com.abclogistics.pas.common.correlation.CorrelationClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

/**
 * Builds outbound gRPC channels with the shared interceptors attached, so every client gets them from
 * one place and a new client cannot ship without them. Currently: correlation-id propagation.
 */
public final class GrpcChannels {

    private GrpcChannels() { }

    public static ManagedChannel plaintext(String host, int port) {
        return ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .intercept(new CorrelationClientInterceptor())
                .build();
    }
}
