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
@Table(name = "workflow_step_instance", schema = "workflow")
public class WorkflowStepInstance {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instance_id", nullable = false)
    private WorkflowInstance instance;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(nullable = false)
    private String name;

    @Column(name = "approver_role", nullable = false)
    private String approverRole;

    @Column(name = "sla_hours", nullable = false)
    private int slaHours;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int version;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "overdue_notified_at")
    private Instant overdueNotifiedAt;

    @Column(name = "acted_by")
    private UUID actedBy;

    @Column(name = "acted_by_name")
    private String actedByName;

    protected WorkflowStepInstance() {}

    public WorkflowStepInstance(WorkflowInstance instance, int stepOrder, String name, String approverRole, int slaHours, String status) {
        this.instance = instance;
        this.stepOrder = stepOrder;
        this.name = name;
        this.approverRole = approverRole;
        this.slaHours = slaHours;
        this.status = status;
        if ("ACTIVE".equals(status)) {
            this.activatedAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public WorkflowInstance getInstance() { return instance; }
    public int getStepOrder() { return stepOrder; }
    public String getName() { return name; }
    public String getApproverRole() { return approverRole; }
    public int getSlaHours() { return slaHours; }
    public String getStatus() { return status; }
    public int getVersion() { return version; }
    public Instant getActivatedAt() { return activatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getOverdueNotifiedAt() { return overdueNotifiedAt; }
    public UUID getActedBy() { return actedBy; }
    public String getActedByName() { return actedByName; }

    public void setStatus(String status) { this.status = status; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public void setOverdueNotifiedAt(Instant overdueNotifiedAt) { this.overdueNotifiedAt = overdueNotifiedAt; }
    public void setActedBy(UUID actedBy) { this.actedBy = actedBy; }
    public void setActedByName(String actedByName) { this.actedByName = actedByName; }
    public void setVersion(int version) { this.version = version; }
}
