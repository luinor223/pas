package com.abclogistics.pas.audit;

import com.abclogistics.pas.audit.grpc.AuditInternalGrpcService;
import com.abclogistics.pas.audit.grpc.ListRecordsRequest;
import com.abclogistics.pas.audit.grpc.ListRecordsResponse;
import com.abclogistics.pas.audit.service.AuditQueryService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The behaviour behind the contract. {@link AuditInternalContractTest} pins the proto's shape —
 * which fields exist — but a matching shape says nothing about whether the values are right, and
 * this is the method every owning service's History tab calls.
 *
 * <p>The mapping matters more than it looks: proto3 scalars have no null, so every nullable column
 * — {@code actor_id} on a scheduler action, {@code before_status} on a field edit — has to become
 * an empty string deliberately rather than by NPE.
 */
class AuditInternalGrpcServiceTest {

    private AuditQueryService audit;
    private AuditInternalGrpcService service;
    private CapturingObserver observer;

    @BeforeEach
    void setUp() {
        audit = mock(AuditQueryService.class);
        service = new AuditInternalGrpcService(audit);
        observer = new CapturingObserver();
        when(audit.forEntity(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
    }

    @Test
    void itQueriesTheEntityItWasAsked() {
        UUID entityId = UUID.randomUUID();

        service.listRecords(request("CONTRACT", entityId, 0, 20), observer);

        verify(audit).forEntity(eq("CONTRACT"), eq(entityId), eq(PageRequest.of(0, 20)));
    }

    @Test
    void anEmptyEntityTypeIsRejectedRatherThanScanningEverything() {
        // without the guard the query widens to "every entity with this id" — a cross-entity read
        // through the per-entity door, which is exactly the split seq-02 draws
        service.listRecords(request("", UUID.randomUUID(), 0, 20), observer);

        assertThat(observer.codeOfError()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        verify(audit, never()).forEntity(any(), any(), any());
    }

    @Test
    void anEntityIdThatIsNotAUuidIsInvalidArgumentNotAnInternalError() {
        // §5.1's status mapping: a caller's bad input is INVALID_ARGUMENT, and INTERNAL would tell
        // them to retry something that can never succeed
        ListRecordsRequest request = ListRecordsRequest.newBuilder()
                .setEntityType("CONTRACT").setEntityId("not-a-uuid").setPage(0).setSize(20).build();

        service.listRecords(request, observer);

        assertThat(observer.codeOfError()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void anUnknownEntityIsAnEmptyPageNotNotFound() {
        // a document with no audit rows yet is normal — its History tab is empty, not broken. This
        // is the one place the audit read differs from a domain read, where NOT_FOUND is right.
        service.listRecords(request("CONTRACT", UUID.randomUUID(), 0, 20), observer);

        assertThat(observer.response.getRecordsList()).isEmpty();
        assertThat(observer.response.getTotal()).isZero();
    }

    @Test
    void aMissingSizeFallsBackToADefaultRatherThanRequestingZeroRows() {
        // proto3 has no "absent" for an int32: an unset size arrives as 0, and passing that
        // straight to PageRequest throws
        service.listRecords(ListRecordsRequest.newBuilder()
                .setEntityType("CONTRACT").setEntityId(UUID.randomUUID().toString()).build(), observer);

        verify(audit).forEntity(any(), any(), (Pageable) org.mockito.ArgumentMatchers
                .argThat(p -> ((Pageable) p).getPageSize() > 0));
    }

    @Test
    void aRowMapsOntoTheProtoFieldForField() {
        UUID entityId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant at = Instant.parse("2026-02-15T09:00:00Z");
        when(audit.forEntity(any(), any(), any())).thenReturn(new PageImpl<>(List.of(
                com.abclogistics.pas.audit.domain.AuditRecord.of(
                        UUID.randomUUID(), "contract-service", "CONTRACT", entityId, "HD-2026-0001",
                        "UPDATE", actorId, "Nguyen Thi Lan", "SALES", "DRAFT", "SUBMITTED",
                        Map.of("paymentTerm", "NET45"), "ghi chú", "10.0.0.1", at))));

        service.listRecords(request("CONTRACT", entityId, 0, 20), observer);

        var record = observer.response.getRecords(0);
        assertThat(record.getSourceService()).isEqualTo("contract-service");
        assertThat(record.getEntityNo()).isEqualTo("HD-2026-0001");
        assertThat(record.getAction()).isEqualTo("UPDATE");
        assertThat(record.getActorId()).isEqualTo(actorId.toString());
        assertThat(record.getActorName()).isEqualTo("Nguyen Thi Lan");
        assertThat(record.getActorDepartment()).isEqualTo("SALES");
        assertThat(record.getBeforeStatus()).isEqualTo("DRAFT");
        assertThat(record.getAfterStatus()).isEqualTo("SUBMITTED");
        assertThat(record.getNote()).isEqualTo("ghi chú");
        assertThat(record.getOccurredAt()).isEqualTo(at.toString());
        // changes is JSON on the wire: the caller renders it, this service never reads into it
        assertThat(record.getChanges()).contains("paymentTerm");
    }

    @Test
    void nullColumnsBecomeEmptyStringsRatherThanFailing() {
        // a scheduler action has no actor and a field edit moves no status; proto3 cannot carry
        // null, so the mapping has to choose — and choosing by NPE would fail the History tab for
        // exactly the rows D14d's schedulers write
        when(audit.forEntity(any(), any(), any())).thenReturn(new PageImpl<>(List.of(
                com.abclogistics.pas.audit.domain.AuditRecord.of(
                        UUID.randomUUID(), "contract-service", "CONTRACT", UUID.randomUUID(), null,
                        "ACTIVATE", null, "system", null, null, null,
                        null, null, null, Instant.now()))));

        service.listRecords(request("CONTRACT", UUID.randomUUID(), 0, 20), observer);

        var record = observer.response.getRecords(0);
        assertThat(record.getActorId()).isEmpty();
        assertThat(record.getActorDepartment()).isEmpty();
        assertThat(record.getBeforeStatus()).isEmpty();
        assertThat(record.getEntityNo()).isEmpty();
        assertThat(record.getNote()).isEmpty();
        assertThat(record.getActorName()).isEqualTo("system");
    }

    @Test
    void theTotalIsTheQuerysNotThePages() {
        when(audit.forEntity(any(), any(), any())).thenReturn(new PageImpl<>(
                List.of(row(), row()), PageRequest.of(0, 2), 17));

        service.listRecords(request("CONTRACT", UUID.randomUUID(), 0, 2), observer);

        assertThat(observer.response.getRecordsCount()).isEqualTo(2);
        assertThat(observer.response.getTotal()).isEqualTo(17);
    }

    @Test
    void theResponseIsCompletedExactlyOnce() {
        service.listRecords(request("CONTRACT", UUID.randomUUID(), 0, 20), observer);

        assertThat(observer.completed).isEqualTo(1);
    }

    private static com.abclogistics.pas.audit.domain.AuditRecord row() {
        return com.abclogistics.pas.audit.domain.AuditRecord.of(
                UUID.randomUUID(), "contract-service", "CONTRACT", UUID.randomUUID(), "HD-2026-0001",
                "UPDATE", UUID.randomUUID(), "Nguyen Thi Lan", "SALES", null, null,
                Map.of(), null, null, Instant.now());
    }

    private static ListRecordsRequest request(String entityType, UUID entityId, int page, int size) {
        return ListRecordsRequest.newBuilder()
                .setEntityType(entityType).setEntityId(entityId.toString())
                .setPage(page).setSize(size).build();
    }

    /** Errors reach a caller through onError, as every other internal service here does (§5.1). */
    private static final class CapturingObserver implements StreamObserver<ListRecordsResponse> {
        private ListRecordsResponse response;
        private Throwable error;
        private int completed;

        @Override public void onNext(ListRecordsResponse value) { this.response = value; }
        @Override public void onError(Throwable t) { this.error = t; }
        @Override public void onCompleted() { completed++; }

        Status.Code codeOfError() {
            assertThat(error).as("expected the call to fail").isNotNull();
            return Status.fromThrowable(error).getCode();
        }
    }
}
