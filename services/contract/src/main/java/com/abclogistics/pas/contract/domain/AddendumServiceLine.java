package com.abclogistics.pas.contract.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * A service added by an {@code ADDED_SERVICE} addendum (requirement 4.3 "bo sung dich vu").
 *
 * <p>RECORD/DISPLAY ONLY. This is deliberately not an input to scope enforcement:
 * {@code ContractInternal.GetContract} still returns {@code Contract.serviceGroup} and
 * operations-service validates volume entries against that alone. Composing scope from addenda
 * would change that proto response and operations' validation logic (session 5) — if it is ever
 * wanted it needs its own registry decision, not a side effect of this table.
 *
 * <p>Carries no price (D8): an added service still needs its own pricing version.
 */
@Entity
@Table(name = "addendum_service", schema = "contract")
public class AddendumServiceLine {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "addendum_id", nullable = false)
    private Addendum addendum;

    /** pricing-service {@code service_item} id, when the service is already in the catalogue. */
    @Column(name = "service_item_id")
    private UUID serviceItemId;

    @Column(name = "service_code", nullable = false)
    private String serviceCode;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    private String unit;

    @Column(name = "scope_note")
    private String scopeNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AddendumServiceLine() { } // JPA

    public static AddendumServiceLine create(String serviceCode, String serviceName) {
        AddendumServiceLine s = new AddendumServiceLine();
        s.serviceCode = serviceCode;
        s.serviceName = serviceName;
        s.createdAt = Instant.now();
        return s;
    }

    public UUID getId() { return id; }
    public Addendum getAddendum() { return addendum; }
    public UUID getServiceItemId() { return serviceItemId; }
    public String getServiceCode() { return serviceCode; }
    public String getServiceName() { return serviceName; }
    public String getUnit() { return unit; }
    public String getScopeNote() { return scopeNote; }
    public Instant getCreatedAt() { return createdAt; }

    void setAddendum(Addendum addendum) { this.addendum = addendum; }
    public void setServiceItemId(UUID v) { this.serviceItemId = v; }
    public void setUnit(String v) { this.unit = v; }
    public void setScopeNote(String v) { this.scopeNote = v; }
}
