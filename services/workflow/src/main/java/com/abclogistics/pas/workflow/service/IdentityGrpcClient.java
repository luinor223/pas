package com.abclogistics.pas.workflow.service;

import com.abclogistics.pas.identity.grpc.IdentityInternalGrpc;
import com.abclogistics.pas.identity.grpc.ListUsersByRoleRequest;
import com.abclogistics.pas.identity.grpc.UserRef;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the identity gRPC to resolve role -> active users.
 * In tests this bean is replaced by a mock or controlled via test overrides.
 */
@Component
public class IdentityGrpcClient {

    private final ManagedChannel channel;
    private final IdentityInternalGrpc.IdentityInternalBlockingStub stub;
    private final java.util.Map<String, List<UserRef>> testOverrides = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean useTestOverrides = false;

    public IdentityGrpcClient(@Value("${identity.grpc.host:localhost}") String host,
                              @Value("${identity.grpc.port:50051}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.stub = IdentityInternalGrpc.newBlockingStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS);
    }

    public List<UserRef> listUsersByRole(String roleCode) {
        if (useTestOverrides) {
            return testOverrides.getOrDefault(roleCode, List.of());
        }
        ListUsersByRoleRequest req = ListUsersByRoleRequest.newBuilder().setRoleCode(roleCode).build();
        return stub.listUsersByRole(req).getUsersList();
    }

    public void shutdown() {
        if (channel != null) channel.shutdown();
    }

    // test helpers
    public void setTestOverride(String roleCode, List<UserRef> users) {
        testOverrides.put(roleCode, users);
        useTestOverrides = true;
    }
    public void setTestOverrides(java.util.Map<String, List<UserRef>> map) {
        testOverrides.clear();
        testOverrides.putAll(map);
        useTestOverrides = true;
    }
    public void clearTestOverrides() {
        testOverrides.clear();
        useTestOverrides = false;
    }
    public void enableTestOverrides() { useTestOverrides = true; }
}
