package com.abclogistics.pas.pricing.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/** A catalog service, keyed by its business `code` (referenced cross-service as service_code). */
@Entity
@Table(name = "service_item", schema = "pricing")
public class ServiceItem extends BaseEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String unit;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected ServiceItem() {}

    public ServiceItem(String code, String name, String unit) {
        this.code = code;
        this.name = name;
        this.unit = unit;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getUnit() { return unit; }
    public boolean isActive() { return active; }
}
