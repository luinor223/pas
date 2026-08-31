package com.abclogistics.pas.contract.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Contract addendum (4.3) — own row, own workflow instance, same status enum as {@link Contract}. */
@Entity
@Table(name = "addendum", schema = "contract")
public class Addendum extends BaseEntity implements ApprovableDocument, ActorStamped {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "addendum_no", nullable = false, unique = true, updatable = false)
    private String addendumNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contract_id", nullable = false)
    private Contract contract;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false)
    private ChangeType changeType;

    private String description;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "new_valid_to")
    private LocalDate newValidTo;

    @Column(name = "payment_term_override")
    private String paymentTermOverride;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status;

    @Version
    @Column(nullable = false)
    private int version;

    @OneToMany(mappedBy = "addendum", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AddendumServiceLine> services = new ArrayList<>();

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "created_by_department")
    private String createdByDepartment;

    @Column(name = "updated_by_name")
    private String updatedByName;

    protected Addendum() { } // JPA

    public static Addendum create(String addendumNo, Contract contract, ChangeType changeType,
                                  LocalDate effectiveFrom) {
        Addendum a = new Addendum();
        a.addendumNo = addendumNo;
        a.contract = contract;
        a.changeType = changeType;
        a.effectiveFrom = effectiveFrom;
        a.status = DocumentStatus.DRAFT;
        return a;
    }

    public void addService(AddendumServiceLine service) {
        services.add(service);
        service.setAddendum(this);
    }

    public UUID getId() { return id; }
    public String getAddendumNo() { return addendumNo; }

    @Override
    public String getDocumentNo() { return addendumNo; }

    @Override
    public EntityType entityType() { return EntityType.ADDENDUM; }
    public Contract getContract() { return contract; }
    public ChangeType getChangeType() { return changeType; }
    public String getDescription() { return description; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getNewValidTo() { return newValidTo; }
    public String getPaymentTermOverride() { return paymentTermOverride; }
    public DocumentStatus getStatus() { return status; }
    public int getVersion() { return version; }
    public List<AddendumServiceLine> getServices() { return services; }
    public String getCreatedByName() { return createdByName; }
    public String getCreatedByDepartment() { return createdByDepartment; }
    public String getUpdatedByName() { return updatedByName; }

    public void setDescription(String v) { this.description = v; }
    public void setChangeType(ChangeType v) { this.changeType = v; }
    public void setEffectiveFrom(LocalDate v) { this.effectiveFrom = v; }
    public void setNewValidTo(LocalDate v) { this.newValidTo = v; }
    public void setPaymentTermOverride(String v) { this.paymentTermOverride = v; }
    public void setStatus(DocumentStatus v) { this.status = v; }
    public void setCreatedByName(String v) { this.createdByName = v; }
    public void setCreatedByDepartment(String v) { this.createdByDepartment = v; }
    public void setUpdatedByName(String v) { this.updatedByName = v; }
}
