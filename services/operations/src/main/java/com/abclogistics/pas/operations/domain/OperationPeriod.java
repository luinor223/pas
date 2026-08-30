package com.abclogistics.pas.operations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "operation_period", schema = "operations")
public class OperationPeriod {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "period_code", nullable = false, unique = true)
    private String periodCode; // YYYY-MM

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private String status; // OPEN, LOCKED

    @Column(name = "locked_by")
    private UUID lockedBy;

    @Column(name = "locked_by_name")
    private String lockedByName;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected OperationPeriod() {}

    public static OperationPeriod create(String periodCode, LocalDate startDate, LocalDate endDate, UUID createdBy) {
        OperationPeriod p = new OperationPeriod();
        p.periodCode = periodCode;
        p.startDate = startDate;
        p.endDate = endDate;
        p.status = "OPEN";
        p.createdAt = Instant.now();
        p.updatedAt = Instant.now();
        p.createdBy = createdBy;
        p.updatedBy = createdBy;
        return p;
    }

    public void lock(UUID userId, String userName) {
        if ("LOCKED".equals(this.status)) {
            return; // idempotent
        }
        this.status = "LOCKED";
        this.lockedBy = userId;
        this.lockedByName = userName;
        this.lockedAt = Instant.now();
        this.updatedAt = Instant.now();
        this.updatedBy = userId;
    }

    public boolean isLocked() {
        return "LOCKED".equals(status);
    }

    // getters/setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getPeriodCode() { return periodCode; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getLockedBy() { return lockedBy; }
    public String getLockedByName() { return lockedByName; }
    public Instant getLockedAt() { return lockedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
