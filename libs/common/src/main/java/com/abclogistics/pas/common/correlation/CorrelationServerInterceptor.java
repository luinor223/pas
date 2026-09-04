package com.abclogistics.pas.common.correlation;

import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/**
 * Server side of correlation: reads {@code x-correlation-id} from inbound gRPC metadata (or mints one)
 * and keeps it in the MDC around each call callback. Registered globally, so it wraps every gRPC service.
 */
@Component
@GlobalServerInterceptor
public class CorrelationServerInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String id = CorrelationSupport.orNew(headers.get(CorrelationSupport.GRPC_KEY));
        ServerCall.Listener<ReqT> delegate;
        CorrelationSupport.set(id);
        try {
            delegate = next.startCall(call, headers);
        } finally {
            CorrelationSupport.clear();
        }
        return new SimpleForwardingServerCallListener<ReqT>(delegate) {
            @Override public void onMessage(ReqT message) { run(() -> super.onMessage(message)); }
            @Override public void onHalfClose() { run(() -> super.onHalfClose()); }
            @Override public void onCancel() { run(() -> super.onCancel()); }
            @Override public void onComplete() { run(() -> super.onComplete()); }
            @Override public void onReady() { run(() -> super.onReady()); }

            private void run(Runnable action) {
                CorrelationSupport.set(id);
                try {
                    action.run();
                } finally {
                    CorrelationSupport.clear();
                }
            }
        };
    }
}
