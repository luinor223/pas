package com.abclogistics.pas.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(name = "permission")
public class Permission {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    private String description;

    protected Permission() { }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
}
