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
@Table(name = "workflow_action", schema = "workflow")
public class WorkflowAction {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_instance_id", nullable = false)
    private WorkflowStepInstance stepInstance;

    @Column(nullable = false)
    private String action;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_name")
    private String actorName;

    @Column
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WorkflowAction() {}

    public WorkflowAction(WorkflowStepInstance stepInstance, String action, UUID actorId, String actorName, String comment) {
        if (!"APPROVE".equals(action) && (comment == null || comment.isBlank())) {
            throw new IllegalArgumentException("Comment required for " + action);
        }
        this.stepInstance = stepInstance;
        this.action = action;
        this.actorId = actorId;
        this.actorName = actorName;
        this.comment = comment;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public WorkflowStepInstance getStepInstance() { return stepInstance; }
    public String getAction() { return action; }
    public UUID getActorId() { return actorId; }
    public String getActorName() { return actorName; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }
}
