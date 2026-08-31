package com.abclogistics.pas.pricing.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/**
 * A scoped price list (PRC-01). At least one of customer/contract/service_group is set; scope_key
 * is the derived normalization used for overlap (PRC-03) and the lookup precedence. Scope is frozen
 * once a version exists, so scope_key stays in sync with what versions copied.
 */
@Entity
@Table(name = "price_list", schema = "pricing")
public class PriceList extends BaseEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "price_list_no", nullable = false, unique = true)
    private String priceListNo;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "contract_id")
    private UUID contractId;

    @Column(name = "service_group")
    private String serviceGroup;

    @Column(name = "scope_key", nullable = false)
    private String scopeKey;

    @Column(name = "note")
    private String note;

    protected PriceList() {}

    public PriceList(String priceListNo, UUID customerId, UUID contractId, String serviceGroup, String note) {
        this.priceListNo = priceListNo;
        this.customerId = customerId;
        this.contractId = contractId;
        this.serviceGroup = serviceGroup;
        this.note = note;
        this.scopeKey = deriveScopeKey(customerId, contractId, serviceGroup);
    }

    /** The scope keys a lookup should try, most specific first: CONTRACT &gt; CUSTOMER+GROUP &gt;
     *  CUSTOMER &gt; GROUP. A stored list's own key is the first entry. Single source of the wire format
     *  shared with the effective-price lookup. */
    public static java.util.List<String> scopeKeyCandidates(UUID customerId, UUID contractId, String serviceGroup) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        if (contractId != null) keys.add("CONTRACT:" + contractId);
        if (customerId != null && serviceGroup != null) keys.add("CUSTOMER:" + customerId + ":GROUP:" + serviceGroup);
        if (customerId != null) keys.add("CUSTOMER:" + customerId);
        if (serviceGroup != null) keys.add("GROUP:" + serviceGroup);
        return keys;
    }

    public static String deriveScopeKey(UUID customerId, UUID contractId, String serviceGroup) {
        return scopeKeyCandidates(customerId, contractId, serviceGroup).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "A price list needs a customer, contract or service group (PRC-01)"));
    }

    public UUID getId() { return id; }
    public String getPriceListNo() { return priceListNo; }
    public UUID getCustomerId() { return customerId; }
    public UUID getContractId() { return contractId; }
    public String getServiceGroup() { return serviceGroup; }
    public String getScopeKey() { return scopeKey; }
    public String getNote() { return note; }
}
