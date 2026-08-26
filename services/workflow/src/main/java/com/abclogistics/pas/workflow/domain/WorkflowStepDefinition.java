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
@Table(name = "workflow_step_definition", schema = "workflow")
public class WorkflowStepDefinition {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_id", nullable = false)
    private WorkflowDefinition definition;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(nullable = false)
    private String name;

    @Column(name = "approver_role", nullable = false)
    private String approverRole;

    @Column(name = "sla_hours", nullable = false)
    private int slaHours;

    protected WorkflowStepDefinition() {}

    public WorkflowStepDefinition(WorkflowDefinition definition, int stepOrder, String name, String approverRole, int slaHours) {
        this.definition = definition;
        this.stepOrder = stepOrder;
        this.name = name;
        this.approverRole = approverRole;
        this.slaHours = slaHours;
    }

    public UUID getId() { return id; }
    public WorkflowDefinition getDefinition() { return definition; }
    public int getStepOrder() { return stepOrder; }
    public String getName() { return name; }
    public String getApproverRole() { return approverRole; }
    public int getSlaHours() { return slaHours; }

    public void setStepOrder(int stepOrder) { this.stepOrder = stepOrder; }
    public void setName(String name) { this.name = name; }
    public void setApproverRole(String approverRole) { this.approverRole = approverRole; }
    public void setSlaHours(int slaHours) { this.slaHours = slaHours; }
}
