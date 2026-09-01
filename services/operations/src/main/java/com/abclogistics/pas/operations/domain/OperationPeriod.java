package com.abclogistics.pas.operations.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
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
public class OperationPeriod extends BaseEntity {

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

    protected OperationPeriod() {}

    public static OperationPeriod create(String periodCode, LocalDate startDate, LocalDate endDate, UUID createdBy) {
        OperationPeriod p = new OperationPeriod();
        p.periodCode = periodCode;
        p.startDate = startDate;
        p.endDate = endDate;
        p.status = "OPEN";
        p.setCreatedBy(createdBy);
        p.setUpdatedBy(createdBy);
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
        setUpdatedBy(userId);
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
}
