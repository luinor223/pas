package com.abclogistics.pas.operations.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "volume_record", schema = "operations")
public class VolumeRecord extends BaseEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "record_no", nullable = false, unique = true)
    private String recordNo; // VOL-YYYY-seq

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "period_id", nullable = false)
    private OperationPeriod period;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_name", nullable = false)
    private String customerName; // snapshot

    @Column(name = "service_code", nullable = false)
    private String serviceCode;

    @Column(name = "service_name", nullable = false)
    private String serviceName; // snapshot

    @Column(name = "unit", nullable = false)
    private String unit; // snapshot

    @Column(name = "quantity", nullable = false, precision = 18, scale = 3)
    private BigDecimal quantity;

    @Column(name = "note")
    private String note;

    protected VolumeRecord() {}

    public static VolumeRecord create(OperationPeriod period, String recordNo,
                                       UUID contractId, UUID customerId, String customerName,
                                       String serviceCode, String serviceName, String unit,
                                       BigDecimal quantity, String note,
                                       UUID createdBy) {
        VolumeRecord v = new VolumeRecord();
        v.recordNo = recordNo;
        v.period = period;
        v.contractId = contractId;
        v.customerId = customerId;
        v.customerName = customerName;
        v.serviceCode = serviceCode;
        v.serviceName = serviceName;
        v.unit = unit;
        v.quantity = quantity;
        v.note = note;
        v.setCreatedBy(createdBy);
        v.setUpdatedBy(createdBy);
        return v;
    }

    public void updateQuantity(BigDecimal newQuantity, String newNote, UUID updatedBy) {
        this.quantity = newQuantity;
        if (newNote != null) this.note = newNote;
        setUpdatedBy(updatedBy);
    }

    // getters/setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getRecordNo() { return recordNo; }
    public OperationPeriod getPeriod() { return period; }
    public UUID getContractId() { return contractId; }
    public UUID getCustomerId() { return customerId; }
    public String getCustomerName() { return customerName; }
    public String getServiceCode() { return serviceCode; }
    public String getServiceName() { return serviceName; }
    public String getUnit() { return unit; }
    public BigDecimal getQuantity() { return quantity; }
    public String getNote() { return note; }
}
