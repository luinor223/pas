package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.Customer;
import com.abclogistics.pas.contract.domain.CustomerContact;
import com.abclogistics.pas.contract.domain.CustomerStatus;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.dto.CustomerContactRequest;
import com.abclogistics.pas.contract.dto.CustomerMetricsResponse;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.dto.CustomerResponse;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.repository.ContractRepository;
import com.abclogistics.pas.contract.repository.CustomerContactRepository;
import com.abclogistics.pas.contract.repository.CustomerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Customer master data (4.1). No approval workflow, so nothing here writes status_history. */
@Service
public class CustomerService {

    private static final String ENTITY = "CUSTOMER";

    private final CustomerRepository customers;
    private final CustomerContactRepository contacts;
    private final ContractRepository contracts;
    private final DocumentNumberService numbers;
    private final AuditRecorder audit;

    public CustomerService(CustomerRepository customers, CustomerContactRepository contacts,
                           ContractRepository contracts, DocumentNumberService numbers,
                           AuditRecorder audit) {
        this.customers = customers;
        this.contacts = contacts;
        this.contracts = contracts;
        this.numbers = numbers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<Customer> search(String query, String status, Instant snapshot, Pageable pageable) {
        return customers.search(
                RequestValues.likePattern(query),
                RequestValues.parseOptional("status", status,
                        CustomerStatus::valueOf, CustomerStatus.values()),
                snapshot,
                pageable);
    }

    @Transactional(readOnly = true)
    public Page<Customer> search(String query, String status, Pageable pageable) {
        return search(query, status, Instant.now(), pageable);
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> searchResponses(String query, String status, Instant snapshot, Pageable pageable) {
        Page<Customer> page = search(query, status, snapshot, pageable);
        Map<UUID, CustomerResponse> responses = listResponses(page.getContent()).stream()
                .collect(Collectors.toMap(CustomerResponse::id, Function.identity()));
        return page.map(customer -> responses.get(customer.getId()));
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> searchResponses(String query, String status, Pageable pageable) {
        return searchResponses(query, status, Instant.now(), pageable);
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> lookupResponses(List<UUID> ids) {
        return listResponses(customers.findAllById(ids));
    }

    private List<CustomerResponse> listResponses(List<Customer> values) {
        List<UUID> ids = values.stream().map(Customer::getId).toList();
        Map<UUID, CustomerContact> primaries = ids.isEmpty() ? Map.of()
                : contacts.findByCustomerIdInAndPrimaryTrue(ids).stream()
                        .collect(Collectors.toMap(c -> c.getCustomer().getId(), Function.identity()));
        boolean canReadContracts = SecurityUtils.hasPermission("contract:read");
        Map<UUID, Long> counts = ids.isEmpty() || !canReadContracts ? Map.of()
                : contracts.countByCustomerIds(ids).stream()
                        .collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));
        return values.stream().map(c -> CustomerResponse.ofList(c, primaries.get(c.getId()),
                canReadContracts ? counts.getOrDefault(c.getId(), 0L) : null)).toList();
    }

    @Transactional(readOnly = true)
    public Customer get(UUID id) {
        return customers.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer %s not found".formatted(id)));
    }

    /** Single-record view (detail, create/update responses): full contacts + exact count. */
    @Transactional(readOnly = true)
    public CustomerResponse toResponse(Customer customer) {
        return CustomerResponse.of(customer, contactsOf(customer.getId()),
                SecurityUtils.hasPermission("contract:read")
                        ? contracts.countByCustomerId(customer.getId()) : null);
    }

    @Transactional(readOnly = true)
    public CustomerMetricsResponse metrics(UUID customerId) {
        get(customerId); // preserve the customer API's 404 semantics
        long activeContracts = contracts.countByCustomerIdAndStatus(
                customerId, DocumentStatus.ACTIVE);
        List<CustomerMetricsResponse.CurrencyValue> values = contracts
                .sumValuesByCustomerAndStatusesGroupedByCurrency(customerId,
                        List.of(DocumentStatus.APPROVED, DocumentStatus.ACTIVE))
                .stream()
                .map(row -> new CustomerMetricsResponse.CurrencyValue(
                        (String) row[0], ((java.math.BigDecimal) row[1]).toPlainString()))
                .toList();
        return new CustomerMetricsResponse(activeContracts, values);
    }

    @Transactional(readOnly = true)
    public List<CustomerContact> contactsOf(UUID customerId) {
        return contacts.findByCustomerId(customerId);
    }

    /** The contact a document addressed to this customer goes to — at most one exists (D10). */
    @Transactional(readOnly = true)
    public Optional<CustomerContact> primaryContactOf(UUID customerId) {
        return contacts.findByCustomerIdAndPrimaryTrue(customerId);
    }

    @Transactional
    public Customer create(CustomerRequest request) {
        Customer customer = Customer.create(numbers.nextCustomerCode(), request.name());
        applyFields(customer, request);
        RecordStamp.creator(customer);
        customers.save(customer);
        replaceContacts(customer, request.contacts());

        audit.record(ENTITY, customer.getId(), customer.getCode(), "CREATE",
                null, customer.getStatus().name(), null,
                Map.of("name", customer.getName(),
                        "taxCode", String.valueOf(customer.getTaxCode()),
                        "segment", String.valueOf(customer.getSegment())));
        return customer;
    }

    @Transactional
    public Customer update(UUID id, CustomerRequest request) {
        if (request.contacts() == null) {
            throw new UnprocessableEntityException(
                    "CUSTOMER_CONTACTS_REQUIRED",
                    "Include customer contacts when saving. To remove all contacts, submit an empty contact list.",
                    "Customer update omitted the contacts collection");
        }
        Customer customer = get(id);
        // taken before anything is applied: the audit row is the only record of the prior value
        Map<String, Object> was = snapshot(customer, contactsOf(id));
        applyFields(customer, request);
        RecordStamp.editor(customer);
        List<CustomerContact> contactsAfter = replaceContacts(customer, request.contacts());

        audit.record(ENTITY, customer.getId(), customer.getCode(), "UPDATE",
                null, null, null,
                FieldDiff.between(was, snapshot(customer, contactsAfter)));
        return customer;
    }

    @Transactional
    public void suspend(UUID id, String reason) {
        Customer customer = get(id);
        if (customer.getStatus() == CustomerStatus.SUSPENDED) {
            throw new ConflictException("Customer %s is already suspended".formatted(customer.getCode()));
        }
        customer.setStatus(CustomerStatus.SUSPENDED);
        RecordStamp.editor(customer);
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
        RecordStamp.editor(customer);
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

    private List<CustomerContact> replaceContacts(Customer customer,
                                                  List<CustomerContactRequest> requested) {
        if (requested == null) {
            return contactsOf(customer.getId());
        }
        long primaries = requested.stream().filter(CustomerContactRequest::primary).count();
        if (primaries > 1) {
            // the partial unique index would reject this anyway; failing here names it
            throw new ConflictException("A customer may have at most one primary contact");
        }
        contacts.deleteAll(contacts.findByCustomerId(customer.getId()));
        contacts.flush(); // the partial unique index is checked per statement, not at commit
        List<CustomerContact> saved = new ArrayList<>();
        for (CustomerContactRequest r : requested) {
            CustomerContact contact = CustomerContact.create(customer, r.fullName(), r.primary());
            contact.setTitle(r.title());
            contact.setEmail(r.email());
            contact.setPhone(r.phone());
            contacts.save(contact);
            saved.add(contact);
        }
        return saved;
    }

    private static Map<String, Object> snapshot(Customer customer, List<CustomerContact> contacts) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", customer.getName());
        fields.put("shortName", customer.getShortName());
        fields.put("taxCode", customer.getTaxCode());
        fields.put("address", customer.getAddress());
        fields.put("representativeName", customer.getRepresentativeName());
        fields.put("representativePosition", customer.getRepresentativePosition());
        fields.put("segment", customer.getSegment());
        fields.put("contacts", render(contacts));
        return fields;
    }

    private static List<String> render(List<CustomerContact> contacts) {
        return contacts.stream()
                .map(c -> "%s|%s|%s|%s|%s".formatted(c.getFullName(), c.getTitle(),
                        c.getEmail(), c.getPhone(), c.isPrimary() ? "primary" : "secondary"))
                .sorted()
                .toList();
    }

}
