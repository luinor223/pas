package com.abclogistics.pas.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(name = "document_type_config", schema = "workflow")
public class DocumentTypeConfig {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "number_prefix", nullable = false)
    private String numberPrefix;

    @Column(name = "esign_enabled", nullable = false)
    private boolean esignEnabled;

    @Column(name = "esign_provider")
    private String esignProvider;

    protected DocumentTypeConfig() {}

    public DocumentTypeConfig(String code, String name, String numberPrefix, boolean esignEnabled, String esignProvider) {
        this.code = code;
        this.name = name;
        this.numberPrefix = numberPrefix;
        this.esignEnabled = esignEnabled;
        this.esignProvider = esignProvider;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getNumberPrefix() { return numberPrefix; }
    public boolean isEsignEnabled() { return esignEnabled; }
    public String getEsignProvider() { return esignProvider; }

    public void setId(UUID id) { this.id = id; }
    public void setCode(String code) { this.code = code; }
    public void setName(String name) { this.name = name; }
    public void setNumberPrefix(String numberPrefix) { this.numberPrefix = numberPrefix; }
    public void setEsignEnabled(boolean esignEnabled) { this.esignEnabled = esignEnabled; }
    public void setEsignProvider(String esignProvider) { this.esignProvider = esignProvider; }
}
