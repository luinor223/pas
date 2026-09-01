package com.abclogistics.pas.audit.grpc;

import com.abclogistics.pas.audit.service.AuditQueryService;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

/**
 * {@code AuditInternal.ListRecords} — the non-status half of a document's History tab (registry
 * §5). The status timeline is the owning service's local {@code status_history} and never comes
 * from here (D15/D17).
 */
@GrpcService
public class AuditInternalGrpcService extends AuditInternalGrpc.AuditInternalImplBase {

    /** A History tab shows a page, not a table; an unbounded size would let one call read it all. */
    public static final int MAX_PAGE_SIZE = 200;
    public static final int DEFAULT_PAGE_SIZE = 20;

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
