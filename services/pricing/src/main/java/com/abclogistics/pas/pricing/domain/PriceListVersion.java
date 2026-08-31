package com.abclogistics.pas.pricing.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One version of a price list. Editable only in DRAFT (PRC-05). scope_key is copied from the parent
 * list at creation so the DB EXCLUDE constraint (PRC-03) can act on this row. addendum_id (D8) is
 * provenance only — no automation, no event.
 */
@Entity
@Table(name = "price_list_version", schema = "pricing")
public class PriceListVersion extends BaseEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "price_list_id", nullable = false)
    private UUID priceListId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PriceListVersionStatus status = PriceListVersionStatus.DRAFT;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    @Column(name = "scope_key", nullable = false)
    private String scopeKey;

    @Column(name = "addendum_id")
    private UUID addendumId;

    @Version
    @Column(nullable = false)
    private int version;

    protected PriceListVersion() {}

    public PriceListVersion(UUID priceListId, int versionNo, String scopeKey,
                            LocalDate validFrom, LocalDate validTo, UUID addendumId) {
        this.priceListId = priceListId;
        this.versionNo = versionNo;
        this.scopeKey = scopeKey;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.addendumId = addendumId;
    }

    public UUID getId() { return id; }
    public UUID getPriceListId() { return priceListId; }
    public int getVersionNo() { return versionNo; }
    public PriceListVersionStatus getStatus() { return status; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public String getScopeKey() { return scopeKey; }
    public UUID getAddendumId() { return addendumId; }
    public int getVersion() { return version; }

    public void setStatus(PriceListVersionStatus status) { this.status = status; }
    public void setValidTo(LocalDate validTo) { this.validTo = validTo; }
}
