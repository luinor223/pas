package com.abclogistics.pas.contract.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/**
 * Contact person on a customer (4.1 "thong tin lien he"). At most one primary per customer,
 * enforced by the partial unique index {@code uq_customer_contact_primary}.
 */
@Entity
@Table(name = "customer_contact", schema = "contract")
public class CustomerContact {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String title;
    private String email;
    private String phone;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    protected CustomerContact() { } // JPA

    public static CustomerContact create(Customer customer, String fullName, boolean primary) {
        CustomerContact c = new CustomerContact();
        c.customer = customer;
        c.fullName = fullName;
        c.primary = primary;
        return c;
    }

    public UUID getId() { return id; }
    public Customer getCustomer() { return customer; }
    public String getFullName() { return fullName; }
    public String getTitle() { return title; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public boolean isPrimary() { return primary; }

    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setTitle(String title) { this.title = title; }
    public void setEmail(String email) { this.email = email; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setPrimary(boolean primary) { this.primary = primary; }
}
