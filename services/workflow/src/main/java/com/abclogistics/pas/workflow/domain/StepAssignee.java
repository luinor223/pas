package com.abclogistics.pas.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(name = "step_assignee", schema = "workflow")
public class StepAssignee {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_instance_id", nullable = false)
    private WorkflowStepInstance stepInstance;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "user_name", nullable = false)
    private String userName;

    protected StepAssignee() {}

    public StepAssignee(WorkflowStepInstance stepInstance, UUID userId, String userName) {
        this.stepInstance = stepInstance;
        this.userId = userId;
        this.userName = userName;
    }

    public UUID getId() { return id; }
    public WorkflowStepInstance getStepInstance() { return stepInstance; }
    public UUID getUserId() { return userId; }
    public String getUserName() { return userName; }
}
