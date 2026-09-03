package com.abclogistics.pas.billing.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "statement_line_volume", schema = "billing")
public class StatementLineVolume extends BaseEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_line_volume_line"))
    private StatementLine line;

    @Column(name = "volume_record_id", nullable = false, length = 50)
    private String volumeRecordId;

    @Column(name = "record_no", length = 50)
    private String recordNo;

    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    public StatementLineVolume() {}

    public UUID getId() { return id; }
    public StatementLine getLine() { return line; }
    public void setLine(StatementLine line) { this.line = line; }
    public String getVolumeRecordId() { return volumeRecordId; }
    public void setVolumeRecordId(String volumeRecordId) { this.volumeRecordId = volumeRecordId; }
    public String getRecordNo() { return recordNo; }
    public void setRecordNo(String recordNo) { this.recordNo = recordNo; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
}
