package com.abclogistics.pas.contract.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Service contract (4.2). The status column is a cache of the newest {@link StatusHistory} row. */
@Entity
@Table(name = "contract", schema = "contract")
public class Contract extends BaseEntity implements ApprovableDocument {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "contract_no", nullable = false, unique = true, updatable = false)
    private String contractNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_group", nullable = false)
    private ServiceGroup serviceGroup;

    @Column(name = "value")
    private BigDecimal value;

    @Column(nullable = false)
    private String currency;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    @Column(name = "payment_term")
    private String paymentTerm;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false)
    private BillingCycle billingCycle;

    @Column(name = "vat_rate")
    private BigDecimal vatRate;

    @Column(name = "penalty_terms")
    private String penaltyTerms;

    @Column(name = "service_clause")
    private String serviceClause;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "created_by_department")
    private String createdByDepartment;

    @Column(name = "updated_by_name")
    private String updatedByName;

    protected Contract() { } // JPA

    public static Contract create(String contractNo, Customer customer, ServiceGroup serviceGroup,
                                  LocalDate validFrom, LocalDate validTo) {
        Contract c = new Contract();
        c.contractNo = contractNo;
        c.customer = customer;
        c.serviceGroup = serviceGroup;
        c.validFrom = validFrom;
        c.validTo = validTo;
        c.currency = "VND";
        c.billingCycle = BillingCycle.MONTHLY;
        c.status = DocumentStatus.DRAFT;
        return c;
    }

    public UUID getId() { return id; }
    public String getContractNo() { return contractNo; }

    @Override
    public String getDocumentNo() { return contractNo; }

    @Override
    public EntityType entityType() { return EntityType.CONTRACT; }
    public Customer getCustomer() { return customer; }
    public String getDescription() { return description; }
    public ServiceGroup getServiceGroup() { return serviceGroup; }
    public BigDecimal getValue() { return value; }
    public String getCurrency() { return currency; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public String getPaymentTerm() { return paymentTerm; }
    public BillingCycle getBillingCycle() { return billingCycle; }
    public BigDecimal getVatRate() { return vatRate; }
    public String getPenaltyTerms() { return penaltyTerms; }
    public String getServiceClause() { return serviceClause; }
    public DocumentStatus getStatus() { return status; }
    public int getVersion() { return version; }
    public String getCreatedByName() { return createdByName; }
    public String getCreatedByDepartment() { return createdByDepartment; }
    public String getUpdatedByName() { return updatedByName; }

    public void setCustomer(Customer customer) { this.customer = customer; }
    public void setDescription(String v) { this.description = v; }
    public void setServiceGroup(ServiceGroup v) { this.serviceGroup = v; }
    public void setValue(BigDecimal v) { this.value = v; }
    public void setCurrency(String v) { this.currency = v; }
    public void setValidFrom(LocalDate v) { this.validFrom = v; }
    public void setValidTo(LocalDate v) { this.validTo = v; }
    public void setPaymentTerm(String v) { this.paymentTerm = v; }
    public void setBillingCycle(BillingCycle v) { this.billingCycle = v; }
    public void setVatRate(BigDecimal v) { this.vatRate = v; }
    public void setPenaltyTerms(String v) { this.penaltyTerms = v; }
    public void setServiceClause(String v) { this.serviceClause = v; }
    public void setStatus(DocumentStatus v) { this.status = v; }
    public void setCreatedByName(String v) { this.createdByName = v; }
    public void setCreatedByDepartment(String v) { this.createdByDepartment = v; }
    public void setUpdatedByName(String v) { this.updatedByName = v; }
}
