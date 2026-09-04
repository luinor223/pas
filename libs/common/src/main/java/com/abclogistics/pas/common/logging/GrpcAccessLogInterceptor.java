package com.abclogistics.pas.common.logging;

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/**
 * One line per gRPC call: {@code full.Method -> STATUS (Nms)}, at INFO (WARN for a non-OK status). The
 * call closes synchronously inside the correlation interceptor's scope, so the line carries the
 * correlation id. Silence with {@code logging.level.access.grpc=WARN}.
 */
@Component
@GlobalServerInterceptor
public class GrpcAccessLogInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger("access.grpc");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        long start = System.nanoTime();
        String method = call.getMethodDescriptor().getFullMethodName();
        ServerCall<ReqT, RespT> logged = new SimpleForwardingServerCall<ReqT, RespT>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                if (status.isOk()) {
                    log.info("{} -> {} ({}ms)", method, status.getCode(), ms);
                } else {
                    log.warn("{} -> {} ({}ms)", method, status.getCode(), ms);
                }
                super.close(status, trailers);
            }
        };
        return next.startCall(logged, headers);
    }
}
