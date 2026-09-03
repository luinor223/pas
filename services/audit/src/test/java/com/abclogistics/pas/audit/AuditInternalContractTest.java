package com.abclogistics.pas.audit;

import com.abclogistics.pas.audit.grpc.AuditInternalGrpc;
import com.abclogistics.pas.audit.grpc.AuditRecord;
import com.abclogistics.pas.audit.grpc.ListRecordsRequest;
import com.abclogistics.pas.audit.grpc.ListRecordsResponse;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §7.2 contract test: the wire shape other services compile against. A breaking proto change
 * fails the build here rather than at whichever caller notices first.
 *
 * <p>audit-service is the callee, so it owns `audit_internal.proto` (§5.1). The file already
 * exists from session 0 and this spec proposes no change to it — this test pins what session 8
 * relies on.
 */
class AuditInternalContractTest {

    @Test
    void theServiceIsNamedPerTheRegistryConvention() {
        // pas.<service>.v1, one internal service per callee, named <Service>Internal (§5.1)
        assertThat(AuditInternalGrpc.SERVICE_NAME).isEqualTo("pas.audit.v1.AuditInternal");
    }

    @Test
    void listRecordsIsKeyedOnOneEntityWithPaging() {
        // registry §5: ListRecords(entity_type, entity_id, page) — per-entity, never cross-entity;
        // the cross-entity axes are the REST search's job and must not leak in here
        Descriptor request = ListRecordsRequest.getDescriptor();
        assertThat(fieldNames(request))
                .containsExactlyInAnyOrder("entity_type", "entity_id", "page", "size");
    }

    @Test
    void theResponseCarriesTheWholeAuditRow() {
        // 4.10's five demands — ai / khi nào / hành động / trước-sau / ghi chú — plus the
        // provenance snapshots the History tab renders without calling identity
        assertThat(fieldNames(AuditRecord.getDescriptor())).contains(
                "source_service", "entity_type", "entity_id", "entity_no", "action",
                "actor_id", "actor_name", "actor_department",
                "before_status", "after_status", "changes", "note", "occurred_at");
    }

    @Test
    void theResponseIsPagedNotUnbounded() {
        assertThat(fieldNames(ListRecordsResponse.getDescriptor()))
                .containsExactlyInAnyOrder("records", "total");
    }

    @Test
    void noStatusHistoryLeaksIntoTheAuditContract() {
        // D17: the status timeline is the owning service's local table. A trigger/transition field
        // here would invite a caller to read status from the eventually-consistent source.
        assertThat(fieldNames(AuditRecord.getDescriptor()))
                .doesNotContain("trigger", "trigger_kind", "trigger_ref", "from_status", "to_status");
    }

    private static List<String> fieldNames(Descriptor descriptor) {
        return descriptor.getFields().stream().map(FieldDescriptor::getName).toList();
    }
}
