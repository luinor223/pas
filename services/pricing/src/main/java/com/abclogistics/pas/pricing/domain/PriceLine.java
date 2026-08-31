package com.abclogistics.pas.pricing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

/** A priced item within a version. One row per (version, service item). */
@Entity
@Table(name = "price_line", schema = "pricing")
public class PriceLine {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "service_item_id", nullable = false)
    private UUID serviceItemId;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    protected PriceLine() {}

    public PriceLine(UUID versionId, UUID serviceItemId, BigDecimal unitPrice) {
        this.versionId = versionId;
        this.serviceItemId = serviceItemId;
        this.unitPrice = unitPrice;
    }

    public UUID getId() { return id; }
    public UUID getVersionId() { return versionId; }
    public UUID getServiceItemId() { return serviceItemId; }
    public BigDecimal getUnitPrice() { return unitPrice; }
}
