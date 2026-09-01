package com.abclogistics.pas.audit.grpc;

import com.abclogistics.pas.audit.service.AuditQueryService;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

/**
 * {@code AuditInternal.ListRecords} — the non-status half of a document's History tab (registry §5).
 * The status timeline is the owning service's local {@code status_history} and never comes from
 * here (D15/D17).
 *
 * <p>Read-only by construction: this is the only gRPC method audit-service exposes, and there is no
 * write path to expose. Errors reach the caller through {@code onError} with §5.1's status codes —
 * a bad {@code entity_type} or {@code entity_id} is {@code INVALID_ARGUMENT}, while an entity with
 * no rows yet is an <em>empty page</em>, not {@code NOT_FOUND}: a document whose history is empty
 * is normal, and a History tab must render it rather than report a failure.
 */
@GrpcService
public class AuditInternalGrpcService extends AuditInternalGrpc.AuditInternalImplBase {

    private final AuditQueryService audit;

    public AuditInternalGrpcService(AuditQueryService audit) {
        this.audit = audit;
    }

    @Override
    public void listRecords(ListRecordsRequest request,
                            StreamObserver<ListRecordsResponse> observer) {
        throw new UnsupportedOperationException("Phase B: page the entity's audit records");
    }
}
