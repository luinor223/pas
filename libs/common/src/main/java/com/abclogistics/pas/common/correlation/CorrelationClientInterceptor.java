package com.abclogistics.pas.common.correlation;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/** Client side of correlation: copies the current MDC correlation id onto outgoing gRPC metadata. Stateless. */
public class CorrelationClientInterceptor implements ClientInterceptor {

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String id = CorrelationSupport.current();
                if (id != null) {
                    headers.put(CorrelationSupport.GRPC_KEY, id);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
