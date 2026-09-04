package com.abclogistics.pas.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_instance", schema = "workflow")
public class WorkflowInstance {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_id", nullable = false)
    private WorkflowDefinition definition;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(name = "document_type_code", nullable = false)
    private String documentTypeCode;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "document_no", nullable = false)
    private String documentNo;

    @Column(name = "customer_name")
    private String customerName;

    @Column(nullable = false)
    private String priority;

    @Column(nullable = false)
    private String status;

    @Column(name = "current_step_order")
    private Integer currentStepOrder;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "requested_by_name")
    private String requestedByName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected WorkflowInstance() {}

    public static WorkflowInstance create(WorkflowDefinition definition, UUID idempotencyKey,
                                          String documentTypeCode, UUID documentId, String documentNo,
                                          String customerName, String priority, UUID requestedBy, String requestedByName) {
        WorkflowInstance wi = new WorkflowInstance();
        wi.definition = definition;
        wi.idempotencyKey = idempotencyKey;
        wi.documentTypeCode = documentTypeCode;
        wi.documentId = documentId;
        wi.documentNo = documentNo;
        wi.customerName = customerName;
        wi.priority = priority != null ? priority : "NORMAL";
        wi.status = "IN_PROGRESS";
        wi.currentStepOrder = 1;
        wi.requestedBy = requestedBy;
        wi.requestedByName = requestedByName;
        wi.createdAt = Instant.now();
        return wi;
    }

    public UUID getId() { return id; }
    public WorkflowDefinition getDefinition() { return definition; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public String getDocumentTypeCode() { return documentTypeCode; }
    public UUID getDocumentId() { return documentId; }
    public String getDocumentNo() { return documentNo; }
    public String getCustomerName() { return customerName; }
    public String getPriority() { return priority; }
    public String getStatus() { return status; }
    public Integer getCurrentStepOrder() { return currentStepOrder; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getRequestedByName() { return requestedByName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void setStatus(String status) { this.status = status; }
    public void setCurrentStepOrder(Integer currentStepOrder) { this.currentStepOrder = currentStepOrder; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
