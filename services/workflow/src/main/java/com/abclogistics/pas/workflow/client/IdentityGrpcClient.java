package com.abclogistics.pas.workflow.client;

import com.abclogistics.pas.common.grpc.GrpcChannels;
import com.abclogistics.pas.identity.grpc.IdentityInternalGrpc;
import com.abclogistics.pas.identity.grpc.ListUsersByRoleRequest;
import com.abclogistics.pas.identity.grpc.UserRef;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the identity gRPC to resolve role -> active users.
 */
@Component
public class IdentityGrpcClient {

    private final ManagedChannel channel;
    private final IdentityInternalGrpc.IdentityInternalBlockingStub stub;

    @Autowired
    public IdentityGrpcClient(@Value("${identity.grpc.host:localhost}") String host,
                              @Value("${identity.grpc.port:50051}") int port) {
        this.channel = GrpcChannels.plaintext(host, port);
        this.stub = IdentityInternalGrpc.newBlockingStub(channel);
    }

    /** For test doubles that don't need a real channel. */
    protected IdentityGrpcClient() {
        this.channel = null;
        this.stub = null;
    }

    public List<UserRef> listUsersByRole(String roleCode) {
        ListUsersByRoleRequest req = ListUsersByRoleRequest.newBuilder().setRoleCode(roleCode).build();
        return stub.withDeadlineAfter(2, TimeUnit.SECONDS).listUsersByRole(req).getUsersList();
    }

    public void shutdown() {
        if (channel != null) channel.shutdown();
    }
}
