package com.abclogistics.pas.audit.grpc;

import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

/**
 * {@code AuditInternal.ListRecords} — the non-status half of a document's History tab (registry §5).
 * The status timeline is the owning service's local {@code status_history} and never comes from
 * here (D15/D17).
 */
@GrpcService
public class AuditInternalGrpcService extends AuditInternalGrpc.AuditInternalImplBase {

    @Override
    public void listRecords(ListRecordsRequest request,
                            StreamObserver<ListRecordsResponse> observer) {
        throw new UnsupportedOperationException("Phase B: page the entity's audit records");
    }
}
