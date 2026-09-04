package com.abclogistics.pas.common.correlation;

import io.grpc.Metadata;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * One correlation id followed across the HTTP, gRPC and Kafka hops. It lives in the SLF4J MDC under
 * {@link #MDC_KEY} for the duration of each inbound unit of work, so every log line on the thread carries it.
 */
public final class CorrelationSupport {

    public static final String MDC_KEY = "correlationId";
    public static final String HTTP_HEADER = "X-Correlation-Id";
    public static final String KAFKA_HEADER = "X-Correlation-Id";
    public static final Metadata.Key<String> GRPC_KEY =
            Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    private CorrelationSupport() { }

    /** The candidate if it carries a value, otherwise a fresh id. */
    public static String orNew(String candidate) {
        return (candidate == null || candidate.isBlank()) ? UUID.randomUUID().toString() : candidate;
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static void set(String id) {
        MDC.put(MDC_KEY, id);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
