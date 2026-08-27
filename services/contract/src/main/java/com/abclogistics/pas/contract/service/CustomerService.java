package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.Customer;
import com.abclogistics.pas.contract.domain.CustomerContact;
import com.abclogistics.pas.contract.domain.CustomerStatus;
import com.abclogistics.pas.contract.dto.CustomerContactRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.repository.CustomerContactRepository;
import com.abclogistics.pas.contract.repository.CustomerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer master data (4.1). Customers have no approval workflow — they are activated and
 * suspended directly, so nothing here writes {@code status_history} (D17 covers documents with a
 * state machine, and this is not one).
 */
@Service
public class CustomerService {

    private static final String ENTITY = "CUSTOMER";

    private final CustomerRepository customers;
    private final CustomerContactRepository contacts;
    private final DocumentNumberService numbers;
    private final AuditRecorder audit;

    public CustomerService(CustomerRepository customers, CustomerContactRepository contacts,
                           DocumentNumberService numbers, AuditRecorder audit) {
        this.customers = customers;
        this.contacts = contacts;
        this.numbers = numbers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<Customer> search(String query, String status, Pageable pageable) {
        CustomerStatus parsed = status == null || status.isBlank()
                ? null : CustomerStatus.valueOf(status);
        String q = query == null || query.isBlank() ? null : query;
        return customers.search(q, parsed, pageable);
    }

    @Transactional(readOnly = true)
    public Customer get(UUID id) {
        return customers.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer %s not found".formatted(id)));
    }

    @Transactional(readOnly = true)
    public List<CustomerContact> contactsOf(UUID customerId) {
        return contacts.findByCustomerId(customerId);
    }

    /** {@code code} is allocated here, never taken from the request. */
    @Transactional
    public Customer create(CustomerRequest request) {
        Customer customer = Customer.create(numbers.nextCustomerCode(), request.name());
        applyFields(customer, request);
        stampCreator(customer);
        customers.save(customer);
        replaceContacts(customer, request.contacts());

        audit.record(ENTITY, customer.getId(), customer.getCode(), "CREATE",
                null, customer.getStatus().name(), null,
                Map.of("name", customer.getName()));
        return customer;
    }

    @Transactional
    public Customer update(UUID id, CustomerRequest request) {
        Customer customer = get(id);
        String previousName = customer.getName();
        applyFields(customer, request);
        stampEditor(customer);
        replaceContacts(customer, request.contacts());

        audit.record(ENTITY, customer.getId(), customer.getCode(), "UPDATE",
                null, null, null,
                Map.of("name", Map.of("from", previousName, "to", customer.getName())));
        return customer;
    }

    /**
     * Suspending does not touch existing contracts — live obligations survive it. It blocks new
     * submits, which CTR-02 enforces at submit time by re-reading the customer's status.
     */
    @Transactional
    public void suspend(UUID id, String reason) {
        Customer customer = get(id);
        if (customer.getStatus() == CustomerStatus.SUSPENDED) {
            throw new ConflictException("Customer %s is already suspended".formatted(customer.getCode()));
        }
        customer.setStatus(CustomerStatus.SUSPENDED);
        stampEditor(customer);
        audit.record(ENTITY, customer.getId(), customer.getCode(), "SUSPEND",
                CustomerStatus.ACTIVE.name(), CustomerStatus.SUSPENDED.name(), reason, Map.of());
    }

    @Transactional
    public void activate(UUID id) {
        Customer customer = get(id);
        if (customer.getStatus() == CustomerStatus.ACTIVE) {
            throw new ConflictException("Customer %s is already active".formatted(customer.getCode()));
        }
        customer.setStatus(CustomerStatus.ACTIVE);
        stampEditor(customer);
        audit.record(ENTITY, customer.getId(), customer.getCode(), "ACTIVATE",
                CustomerStatus.SUSPENDED.name(), CustomerStatus.ACTIVE.name(), null, Map.of());
    }

    private void applyFields(Customer customer, CustomerRequest request) {
        customer.setName(request.name());
        customer.setShortName(request.shortName());
        customer.setTaxCode(request.taxCode());
        customer.setAddress(request.address());
        customer.setRepresentativeName(request.representativeName());
        customer.setRepresentativePosition(request.representativePosition());
        customer.setSegment(request.segment());
    }

    /**
     * Replaces the contact set wholesale. Null means "not supplied" and leaves contacts alone;
     * an empty list means "remove them all" — the two are not the same request.
     */
    private void replaceContacts(Customer customer, List<CustomerContactRequest> requested) {
        if (requested == null) {
            return;
        }
        long primaries = requested.stream().filter(CustomerContactRequest::primary).count();
        if (primaries > 1) {
            // The partial unique index would reject this anyway; failing here names the problem.
            throw new ConflictException("A customer may have at most one primary contact");
        }
        contacts.deleteAll(contacts.findByCustomerId(customer.getId()));
        contacts.flush(); // the partial unique index is checked per statement, not at commit
        for (CustomerContactRequest r : requested) {
            CustomerContact contact = CustomerContact.create(customer, r.fullName(), r.primary());
            contact.setTitle(r.title());
            contact.setEmail(r.email());
            contact.setPhone(r.phone());
            contacts.save(contact);
        }
    }

    private void stampCreator(Customer customer) {
        SecurityUtils.currentUser().ifPresent(user -> {
            customer.setCreatedBy(user.userId());
            customer.setCreatedByName(user.fullName());
            customer.setCreatedByDepartment(user.department());
        });
    }

    private void stampEditor(Customer customer) {
        SecurityUtils.currentUser().ifPresent((AuthenticatedUser user) -> {
            customer.setUpdatedBy(user.userId());
            customer.setUpdatedByName(user.fullName());
        });
    }
}
