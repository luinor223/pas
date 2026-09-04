package com.abclogistics.pas.billing.grpc;

import com.abclogistics.pas.workflow.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class WorkflowGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(WorkflowGrpcClient.class);

    private final ManagedChannel channel;
    private final WorkflowInternalGrpc.WorkflowInternalBlockingStub stub;

    public WorkflowGrpcClient(@Value("${workflow.grpc.host:localhost}") String host,
                               @Value("${workflow.grpc.port:50056}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = WorkflowInternalGrpc.newBlockingStub(channel);
    }

    public UUID startInstance(UUID idempotencyKey, String documentType, UUID documentId,
                               String documentNo, String customerName, String priority,
                               UUID requestedById, String requestedByName) {
        log.debug("Calling WorkflowInternal.StartInstance(documentType={}, docId={})", documentType, documentId);
        StartInstanceRequest.Builder req = StartInstanceRequest.newBuilder()
            .setIdempotencyKey(idempotencyKey.toString())
            .setDocumentType(documentType)
            .setDocumentId(documentId.toString())
            .setDocumentNo(documentNo)
            .setCustomerName(customerName != null ? customerName : "")
            .setPriority(priority != null ? priority : "NORMAL");
        if (requestedById != null) req.setRequestedBy(requestedById.toString());
        if (requestedByName != null) req.setRequestedByName(requestedByName);

        StartInstanceResponse resp = stub.withDeadlineAfter(5, TimeUnit.SECONDS)
            .startInstance(req.build());
        return UUID.fromString(resp.getInstanceId());
    }

    public void cancelInstance(String documentType, String documentId, String idempotencyKey) {
        log.debug("Calling WorkflowInternal.CancelInstance(docType={}, docId={})", documentType, documentId);
        stub.withDeadlineAfter(5, TimeUnit.SECONDS)
            .cancelInstance(CancelInstanceRequest.newBuilder()
                .setDocumentType(documentType)
                .setDocumentId(documentId)
                .setIdempotencyKey(idempotencyKey)
                .build());
    }

    public void validateStartable(String documentType) {
        log.debug("Calling WorkflowInternal.ValidateStartable(docType={})", documentType);
        stub.withDeadlineAfter(5, TimeUnit.SECONDS)
            .validateStartable(ValidateStartableRequest.newBuilder()
                .setDocumentType(documentType)
                .build());
    }

    public GetInstanceByDocumentResponse getInstanceByDocument(String documentType, String documentId) {
        log.debug("Calling WorkflowInternal.GetInstanceByDocument(docType={}, docId={})", documentType, documentId);
        return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
            .getInstanceByDocument(GetInstanceByDocumentRequest.newBuilder()
                .setDocumentType(documentType)
                .setDocumentId(documentId)
                .build());
    }

    @PreDestroy
    void shutdown() {
        if (channel != null) channel.shutdown();
    }
}
