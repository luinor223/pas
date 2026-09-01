package com.abclogistics.pas.notification.service;

import com.abclogistics.pas.identity.grpc.IdentityInternalGrpc;
import com.abclogistics.pas.identity.grpc.ListUsersByRoleRequest;
import com.abclogistics.pas.identity.grpc.UserRef;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** {@code IdentityInternal.ListUsersByRole} — ACTIVE users only (registry §5). */
@Component
public class IdentityGrpcClient {

    private final ManagedChannel channel;
    private final IdentityInternalGrpc.IdentityInternalBlockingStub stub;

    @Autowired
    public IdentityGrpcClient(@Value("${identity.grpc.host:localhost}") String host,
                              @Value("${identity.grpc.port:50051}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = IdentityInternalGrpc.newBlockingStub(channel);
    }

    /** For test doubles that don't need a real channel. */
    protected IdentityGrpcClient(ManagedChannel channel,
                                 IdentityInternalGrpc.IdentityInternalBlockingStub stub) {
        this.channel = channel;
        this.stub = stub;
    }

    /** 2s deadline (read, §5.1). {@code UNAVAILABLE} is the only retryable status. */
    public List<UUID> listUsersByRole(String roleCode) {
        ListUsersByRoleRequest request = ListUsersByRoleRequest.newBuilder()
                .setRoleCode(roleCode).build();
        return stub.withDeadlineAfter(2, TimeUnit.SECONDS).listUsersByRole(request)
                .getUsersList().stream()
                .map(UserRef::getId)
                .map(UUID::fromString)
                .toList();
    }

    public void shutdown() {
        if (channel != null) {
            channel.shutdown();
        }
    }
}
