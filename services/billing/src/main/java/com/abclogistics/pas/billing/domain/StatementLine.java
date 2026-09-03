package com.abclogistics.pas.billing.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "statement_line", schema = "billing")
public class StatementLine extends BaseEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "statement_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_statement_line_statement"))
    private PaymentStatement statement;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "service_code", length = 50, nullable = false)
    private String serviceCode;

    @Column(name = "service_name", length = 200)
    private String serviceName;

    @Column(name = "unit", length = 50)
    private String unit;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "source", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private LineSource source = LineSource.CALCULATED;

    @Column(name = "note", length = 500)
    private String note;

    @OneToMany(mappedBy = "line", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<StatementLineVolume> volumeLinks = new ArrayList<>();

    public StatementLine() {}

    public enum LineSource {
        CALCULATED, MANUAL
    }

    public UUID getId() { return id; }
    public PaymentStatement getStatement() { return statement; }
    public void setStatement(PaymentStatement statement) { this.statement = statement; }
    public int getLineNo() { return lineNo; }
    public void setLineNo(int lineNo) { this.lineNo = lineNo; }
    public String getServiceCode() { return serviceCode; }
    public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LineSource getSource() { return source; }
    public void setSource(LineSource source) { this.source = source; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public List<StatementLineVolume> getVolumeLinks() { return volumeLinks; }
}
