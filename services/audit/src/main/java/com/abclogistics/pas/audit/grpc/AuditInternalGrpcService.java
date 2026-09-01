package com.abclogistics.pas.audit.grpc;

import com.abclogistics.pas.audit.service.AuditQueryService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
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
    private final ObjectMapper objectMapper;

    public AuditInternalGrpcService(AuditQueryService audit, ObjectMapper objectMapper) {
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Override
    public void listRecords(ListRecordsRequest request,
                            StreamObserver<ListRecordsResponse> observer) {
        try {
            Page<com.abclogistics.pas.audit.domain.AuditRecord> page =
                    audit.forEntity(entityType(request), entityId(request), pageable(request));
            ListRecordsResponse.Builder response = ListRecordsResponse.newBuilder()
                    .setTotal((int) page.getTotalElements());
            page.forEach(record -> response.addRecords(toProto(record)));
            observer.onNext(response.build());
            observer.onCompleted();
        } catch (IllegalArgumentException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (RuntimeException e) {
            observer.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private static String entityType(ListRecordsRequest request) {
        if (request.getEntityType().isBlank()) {
            // without it the query widens to "every entity with this id" — a cross-entity read
            // through the per-entity door, which is the split seq-02 draws
            throw new IllegalArgumentException("entity_type is required");
        }
        return request.getEntityType();
    }

    private static UUID entityId(ListRecordsRequest request) {
        try {
            return UUID.fromString(request.getEntityId());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("entity_id is not a uuid: " + request.getEntityId());
        }
    }

    /** proto3 has no absent int32: an unset size arrives as 0, which PageRequest rejects. */
    private static PageRequest pageable(ListRecordsRequest request) {
        if (request.getPage() < 0 || request.getSize() < 0) {
            throw new IllegalArgumentException("page and size must not be negative");
        }
        int size = request.getSize() == 0 ? DEFAULT_PAGE_SIZE : Math.min(request.getSize(), MAX_PAGE_SIZE);
        return PageRequest.of(request.getPage(), size);
    }

    /** proto3 scalars cannot be null, so every nullable column becomes "" deliberately. */
    private AuditRecord toProto(com.abclogistics.pas.audit.domain.AuditRecord r) {
        return AuditRecord.newBuilder()
                .setSourceService(text(r.getSourceService()))
                .setEntityType(text(r.getEntityType()))
                .setEntityId(text(r.getEntityId()))
                .setEntityNo(text(r.getEntityNo()))
                .setAction(text(r.getAction()))
                .setActorId(text(r.getActorId()))
                .setActorName(text(r.getActorName()))
                .setActorDepartment(text(r.getActorDepartment()))
                .setBeforeStatus(text(r.getBeforeStatus()))
                .setAfterStatus(text(r.getAfterStatus()))
                .setChanges(changes(r.getChanges()))
                .setNote(text(r.getNote()))
                .setIpAddress(text(r.getIpAddress()))
                .setOccurredAt(text(r.getOccurredAt()))
                .build();
    }

    private String changes(Map<String, Object> changes) {
        return changes == null || changes.isEmpty() ? "{}" : objectMapper.writeValueAsString(changes);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
