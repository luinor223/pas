package com.abclogistics.pas.contract.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(name = "customer", schema = "contract")
public class Customer extends BaseEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "short_name")
    private String shortName;

    @Column(name = "tax_code")
    private String taxCode;

    private String address;

    @Column(name = "representative_name")
    private String representativeName;

    @Column(name = "representative_position")
    private String representativePosition;

    private String segment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustomerStatus status;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "created_by_department")
    private String createdByDepartment;

    @Column(name = "updated_by_name")
    private String updatedByName;

    protected Customer() { } // JPA

    public static Customer create(String code, String name) {
        Customer c = new Customer();
        c.code = code;
        c.name = name;
        c.status = CustomerStatus.ACTIVE;
        return c;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getShortName() { return shortName; }
    public String getTaxCode() { return taxCode; }
    public String getAddress() { return address; }
    public String getRepresentativeName() { return representativeName; }
    public String getRepresentativePosition() { return representativePosition; }
    public String getSegment() { return segment; }
    public CustomerStatus getStatus() { return status; }
    public String getCreatedByName() { return createdByName; }
    public String getCreatedByDepartment() { return createdByDepartment; }
    public String getUpdatedByName() { return updatedByName; }

    public void setName(String name) { this.name = name; }
    public void setShortName(String shortName) { this.shortName = shortName; }
    public void setTaxCode(String taxCode) { this.taxCode = taxCode; }
    public void setAddress(String address) { this.address = address; }
    public void setRepresentativeName(String v) { this.representativeName = v; }
    public void setRepresentativePosition(String v) { this.representativePosition = v; }
    public void setSegment(String segment) { this.segment = segment; }
    public void setStatus(CustomerStatus status) { this.status = status; }
    public void setCreatedByName(String v) { this.createdByName = v; }
    public void setCreatedByDepartment(String v) { this.createdByDepartment = v; }
    public void setUpdatedByName(String v) { this.updatedByName = v; }
}
