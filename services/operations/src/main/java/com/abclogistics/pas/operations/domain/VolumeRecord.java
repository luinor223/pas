package com.abclogistics.pas.operations.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "volume_record", schema = "operations")
public class VolumeRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_code", nullable = false, updatable = false, length = 50)
    private String periodCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "period_code", referencedColumnName = "period_code",
        insertable = false, updatable = false,
        foreignKey = @ForeignKey(name = "fk_volume_record_period"))
    private OperationPeriod operationPeriod;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "contract_code", length = 50, nullable = false)
    private String contractCode;

    @Column(name = "contract_name", length = 200)
    private String contractName;

    @Column(name = "partner_id")
    private Long partnerId;

    @Column(name = "partner_name", length = 200)
    private String partnerName;

    @Column(name = "service_item_id")
    private Long serviceItemId;

    @Column(name = "service_code", length = 50, nullable = false)
    private String serviceCode;

    @Column(name = "service_name", length = 200)
    private String serviceName;

    @Column(name = "quantity", nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit", length = 50)
    private String unit;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "volume_cost_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal volumeCostAmount;

    @Column(name = "note", length = 500)
    private String note;

    public VolumeRecord() {}

    public Long getId() { return id; }
    public String getPeriodCode() { return periodCode; }
    public void setPeriodCode(String periodCode) { this.periodCode = periodCode; }
    public Long getContractId() { return contractId; }
    public void setContractId(Long contractId) { this.contractId = contractId; }
    public String getContractCode() { return contractCode; }
    public void setContractCode(String contractCode) { this.contractCode = contractCode; }
    public String getContractName() { return contractName; }
    public void setContractName(String contractName) { this.contractName = contractName; }
    public Long getPartnerId() { return partnerId; }
    public void setPartnerId(Long partnerId) { this.partnerId = partnerId; }
    public String getPartnerName() { return partnerName; }
    public void setPartnerName(String partnerName) { this.partnerName = partnerName; }
    public Long getServiceItemId() { return serviceItemId; }
    public void setServiceItemId(Long serviceItemId) { this.serviceItemId = serviceItemId; }
    public String getServiceCode() { return serviceCode; }
    public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getVolumeCostAmount() { return volumeCostAmount; }
    public void setVolumeCostAmount(BigDecimal volumeCostAmount) { this.volumeCostAmount = volumeCostAmount; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
