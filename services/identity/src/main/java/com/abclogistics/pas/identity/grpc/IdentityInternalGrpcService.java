package com.abclogistics.pas.identity.grpc;

import com.abclogistics.pas.identity.domain.AppUser;
import com.abclogistics.pas.identity.domain.UserStatus;
import com.abclogistics.pas.identity.repository.AppUserRepository;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;

/** Resolves a role to its ACTIVE users. Disabled users are never returned. */
@GrpcService
public class IdentityInternalGrpcService extends IdentityInternalGrpc.IdentityInternalImplBase {

    private final AppUserRepository users;

    public IdentityInternalGrpcService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public void listUsersByRole(ListUsersByRoleRequest request,
                                StreamObserver<ListUsersByRoleResponse> responseObserver) {
        ListUsersByRoleResponse.Builder response = ListUsersByRoleResponse.newBuilder();
        for (AppUser user : users.findByRoles_CodeAndStatus(request.getRoleCode(), UserStatus.ACTIVE)) {
            response.addUsers(UserRef.newBuilder()
                    .setId(user.getId().toString())
                    .setUsername(user.getUsername())
                    .setFullName(user.getFullName())
                    .setDepartment(user.getDepartment().getCode())
                    .build());
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }
}
