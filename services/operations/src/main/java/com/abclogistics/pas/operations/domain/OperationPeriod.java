package com.abclogistics.pas.operations.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "operation_period", schema = "operations", uniqueConstraints = {
    @UniqueConstraint(name = "uk_operation_period_code", columnNames = "period_code")
})
public class OperationPeriod extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_code", length = 50, nullable = false, updatable = false)
    private String periodCode;

    @Column(name = "period_name", length = 100, nullable = false)
    private String periodName;

    @Column(name = "month", nullable = false)
    private int month;

    @Column(name = "year", nullable = false)
    private int year;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private PeriodStatus status = PeriodStatus.DRAFT;

    @OneToMany(mappedBy = "operationPeriod", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<VolumeRecord> volumeRecords = new ArrayList<>();

    public OperationPeriod() {}

    public enum PeriodStatus {
        LOCKED, DRAFT
    }

    public boolean isLocked() {
        return PeriodStatus.LOCKED == status;
    }

    public Long getId() { return id; }
    public String getPeriodCode() { return periodCode; }
    public void setPeriodCode(String periodCode) { this.periodCode = periodCode; }
    public String getPeriodName() { return periodName; }
    public void setPeriodName(String periodName) { this.periodName = periodName; }
    public int getMonth() { return month; }
    public void setMonth(int month) { this.month = month; }
    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public PeriodStatus getStatus() { return status; }
    public void setStatus(PeriodStatus status) { this.status = status; }
}
