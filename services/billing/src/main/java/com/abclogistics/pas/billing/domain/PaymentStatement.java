package com.abclogistics.pas.billing.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "payment_statement", schema = "billing")
public class PaymentStatement extends BaseEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "statement_no", length = 50, nullable = false, unique = true)
    private String statementNo;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "contract_no", length = 50, nullable = false)
    private String contractNo;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "period_code", length = 20, nullable = false)
    private String periodCode;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "price_list_version_id")
    private UUID priceListVersionId;

    @Column(name = "price_list_no", length = 50)
    private String priceListNo;

    @Column(name = "price_list_version_no")
    private Integer priceListVersionNo;

    @Column(name = "payment_term", length = 50)
    private String paymentTerm;

    @Column(name = "vat_rate", precision = 5, scale = 2)
    private BigDecimal vatRate;

    @Column(name = "subtotal", nullable = false, precision = 18, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private StatementStatus status = StatementStatus.DRAFT;

    @Column(name = "adjusts_statement_id")
    private UUID adjustsStatementId;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "reconciled_by")
    private UUID reconciledBy;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Version
    @Column(nullable = false)
    private int version;

    @OneToMany(mappedBy = "statement", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<StatementLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "statement", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<StatusHistory> statusHistory = new ArrayList<>();

    public PaymentStatement() {}

    public enum StatementStatus {
        DRAFT, CALCULATED, RECONCILED, SUBMITTED, APPROVED, SIGNING, SIGNED, ISSUED, REJECTED, REVISION, CANCELLED;

        private record Edge(StatementStatus from, StatementStatus to, Set<StatusHistory.TriggerKind> triggers) { }

        private static final List<Edge> TABLE = List.of(
                new Edge(DRAFT, CALCULATED, EnumSet.of(StatusHistory.TriggerKind.U)),
                new Edge(CALCULATED, DRAFT, EnumSet.of(StatusHistory.TriggerKind.U)),
                new Edge(CALCULATED, RECONCILED, EnumSet.of(StatusHistory.TriggerKind.U)),
                new Edge(RECONCILED, SUBMITTED, EnumSet.of(StatusHistory.TriggerKind.U)),
                new Edge(REJECTED, DRAFT, EnumSet.of(StatusHistory.TriggerKind.U)),
                new Edge(REVISION, DRAFT, EnumSet.of(StatusHistory.TriggerKind.U)),
                new Edge(APPROVED, SIGNING, EnumSet.of(StatusHistory.TriggerKind.U)),
                new Edge(SIGNED, ISSUED, EnumSet.of(StatusHistory.TriggerKind.U)),
                new Edge(APPROVED, CANCELLED, EnumSet.of(StatusHistory.TriggerKind.U)),
                new Edge(SIGNED, CANCELLED, EnumSet.of(StatusHistory.TriggerKind.U)),
                new Edge(SUBMITTED, APPROVED, EnumSet.of(StatusHistory.TriggerKind.W)),
                new Edge(SUBMITTED, REJECTED, EnumSet.of(StatusHistory.TriggerKind.W)),
                new Edge(SUBMITTED, REVISION, EnumSet.of(StatusHistory.TriggerKind.W)),
                new Edge(SIGNING, SIGNED, EnumSet.of(StatusHistory.TriggerKind.E)),
                new Edge(SIGNING, REVISION, EnumSet.of(StatusHistory.TriggerKind.E)));

        public boolean canTransitionTo(StatementStatus to, StatusHistory.TriggerKind trigger) {
            return TABLE.stream().anyMatch(
                    e -> e.from() == this && e.to() == to && e.triggers().contains(trigger));
        }
    }

    public UUID getId() { return id; }
    public String getStatementNo() { return statementNo; }
    public void setStatementNo(String statementNo) { this.statementNo = statementNo; }
    public UUID getContractId() { return contractId; }
    public void setContractId(UUID contractId) { this.contractId = contractId; }
    public String getContractNo() { return contractNo; }
    public void setContractNo(String contractNo) { this.contractNo = contractNo; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getPeriodCode() { return periodCode; }
    public void setPeriodCode(String periodCode) { this.periodCode = periodCode; }
    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }
    public UUID getPriceListVersionId() { return priceListVersionId; }
    public void setPriceListVersionId(UUID priceListVersionId) { this.priceListVersionId = priceListVersionId; }
    public String getPriceListNo() { return priceListNo; }
    public void setPriceListNo(String priceListNo) { this.priceListNo = priceListNo; }
    public Integer getPriceListVersionNo() { return priceListVersionNo; }
    public void setPriceListVersionNo(Integer priceListVersionNo) { this.priceListVersionNo = priceListVersionNo; }
    public String getPaymentTerm() { return paymentTerm; }
    public void setPaymentTerm(String paymentTerm) { this.paymentTerm = paymentTerm; }
    public BigDecimal getVatRate() { return vatRate; }
    public void setVatRate(BigDecimal vatRate) { this.vatRate = vatRate; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public StatementStatus getStatus() { return status; }
    public void setStatus(StatementStatus status) { this.status = status; }
    public UUID getAdjustsStatementId() { return adjustsStatementId; }
    public void setAdjustsStatementId(UUID adjustsStatementId) { this.adjustsStatementId = adjustsStatementId; }
    public Instant getReconciledAt() { return reconciledAt; }
    public void setReconciledAt(Instant reconciledAt) { this.reconciledAt = reconciledAt; }
    public UUID getReconciledBy() { return reconciledBy; }
    public void setReconciledBy(UUID reconciledBy) { this.reconciledBy = reconciledBy; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public int getVersion() { return version; }
    public List<StatementLine> getLines() { return lines; }
    public List<StatusHistory> getStatusHistory() { return statusHistory; }
}
