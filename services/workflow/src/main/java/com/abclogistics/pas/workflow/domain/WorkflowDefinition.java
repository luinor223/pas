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
@Table(name = "workflow_definition", schema = "workflow")
public class WorkflowDefinition {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_type_id", nullable = false)
    private DocumentTypeConfig documentType;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    protected WorkflowDefinition() {}

    public static WorkflowDefinition create(DocumentTypeConfig documentType, int versionNo, String name, UUID createdBy) {
        WorkflowDefinition d = new WorkflowDefinition();
        d.documentType = documentType;
        d.versionNo = versionNo;
        d.name = name;
        d.active = false;
        d.createdAt = Instant.now();
        d.createdBy = createdBy;
        return d;
    }

    public UUID getId() { return id; }
    public DocumentTypeConfig getDocumentType() { return documentType; }
    public int getVersionNo() { return versionNo; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getCreatedBy() { return createdBy; }

    public void setId(UUID id) { this.id = id; }
    public void setActive(boolean active) { this.active = active; }
    public void setName(String name) { this.name = name; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }
}
