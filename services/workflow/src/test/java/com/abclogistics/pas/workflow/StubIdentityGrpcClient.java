package com.abclogistics.pas.workflow;

import com.abclogistics.pas.identity.grpc.UserRef;
import com.abclogistics.pas.workflow.service.IdentityGrpcClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for IdentityGrpcClient — replaces the gRPC call with an in-memory map.
 * Used via @TestConfiguration @Primary so production code stays clean (review P0#2).
 */
public class StubIdentityGrpcClient extends IdentityGrpcClient {

    private final Map<String, List<UserRef>> overrides = new ConcurrentHashMap<>();

    public StubIdentityGrpcClient() {
        super("localhost", 50051);
    }

    @Override
    public List<UserRef> listUsersByRole(String roleCode) {
        return overrides.getOrDefault(roleCode, List.of());
    }

    public void setTestOverride(String roleCode, List<UserRef> users) {
        overrides.put(roleCode, users);
    }

    public void setTestOverrides(Map<String, List<UserRef>> map) {
        overrides.clear();
        overrides.putAll(map);
    }

    public void clearTestOverrides() {
        overrides.clear();
    }
}
