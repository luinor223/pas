package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.BillingCycle;
import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.Customer;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.ServiceGroup;
import com.abclogistics.pas.contract.domain.TriggerKind;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.repository.ContractRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Contract lifecycle (4.2, CTR-01..CTR-07).
 */
@Service
public class ContractService {

    private final ContractRepository contracts;
    private final CustomerService customers;
    private final DocumentNumberService numbers;
    private final StatusTransitionService transitions;
    private final AuditRecorder audit;

    public ContractService(ContractRepository contracts, CustomerService customers,
                           DocumentNumberService numbers, StatusTransitionService transitions,
                           AuditRecorder audit) {
        this.contracts = contracts;
        this.customers = customers;
        this.numbers = numbers;
        this.transitions = transitions;
        this.audit = audit;
    }

    /**
     * The documented filter set (openapi {@code GET /contracts}): customer, status, service group
     * and a free-text {@code q} over contract number, description and customer name. An
     * unparseable status or service group is the caller's mistake, so it is a 422 naming the
     * allowed values — not a 500 from a raw {@code valueOf}.
     */
    @Transactional(readOnly = true)
    public Page<Contract> search(UUID customerId, String status, String serviceGroup,
                                 String q, Pageable pageable) {
        return contracts.search(
                customerId,
                parseOptional("status", status, DocumentStatus::valueOf, DocumentStatus.values()),
                parseOptional("serviceGroup", serviceGroup, ServiceGroup::valueOf, ServiceGroup.values()),
                likePattern(q),
                pageable);
    }

    @Transactional(readOnly = true)
    public Contract get(UUID id) {
        return contracts.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract %s not found".formatted(id)));
    }

    @Transactional
    public Contract create(ContractRequest request) {
        // Reference values are parsed before anything is written, so an unknown service group or
        // currency never reaches Postgres as a CHECK violation.
        Reference reference = validate(request);
        Customer customer = customers.get(request.customerId());

        Contract contract = Contract.create(
                numbers.nextDocumentNo(EntityType.CONTRACT, request.validFrom().getYear()),
                customer,
                reference.serviceGroup(),
                request.validFrom(), request.validTo());
        applyFields(contract, request, reference);
        SecurityUtils.currentUser().ifPresent(user -> {
            contract.setCreatedBy(user.userId());
            contract.setCreatedByName(user.fullName());
            contract.setCreatedByDepartment(user.department());
        });
        contracts.save(contract);

        audit.record(EntityType.CONTRACT.name(), contract.getId(), contract.getContractNo(),
                "CREATE", null, contract.getStatus().name(), null,
                Map.of("customerId", customer.getId().toString(),
                        "customerCode", customer.getCode(),
                        "serviceGroup", contract.getServiceGroup().name(),
                        "validFrom", String.valueOf(contract.getValidFrom()),
                        "validTo", String.valueOf(contract.getValidTo())));
        return contract;
    }

    /**
     * CTR-01: editable only in DRAFT or REVISION_REQUESTED, and {@code version} must match or the
     * optimistic lock rejects the write.
     *
     * <p>Editing a REVISION_REQUESTED contract also flips it back to DRAFT (registry §9's
     * {@code REVISION_REQUESTED → DRAFT} edge). There is no separate action for that — the flip,
     * its history row and the field update all land in this one transaction.
     *
     * <p>CTR-07 needs no check here: APPROVED and ACTIVE are not editable at all, so a terms change
     * on a live contract is already refused by the CTR-01 guard and must go through an addendum.
     */
    @Transactional
    public Contract update(UUID id, ContractRequest request) {
        Contract contract = get(id);
        DocumentStatus before = contract.getStatus();

        if (!before.isEditable()) {
            throw new ConflictException(
                    "Contract %s is %s and cannot be edited (CTR-01). A change to an approved or "
                            + "active contract must be made through an addendum (CTR-07)."
                            .formatted(contract.getContractNo(), before));
        }
        if (request.version() == null) {
            throw new UnprocessableEntityException("version is required on update (CTR-01 optimistic lock)");
        }
        if (request.version() != contract.getVersion()) {
            throw new ObjectOptimisticLockingFailureException(Contract.class, id);
        }
        Reference reference = validate(request);

        if (!Objects.equals(contract.getCustomer().getId(), request.customerId())) {
            // A DRAFT may be re-pointed at another customer; anything past DRAFT is unreachable
            // here because the CTR-01 guard above already refused it.
            Customer replacement = customers.get(request.customerId());
            audit.record(EntityType.CONTRACT.name(), contract.getId(), contract.getContractNo(),
                    "REASSIGN_CUSTOMER", null, null, null,
                    Map.of("from", contract.getCustomer().getCode(), "to", replacement.getCode()));
            contract.setCustomer(replacement);
        }
        Map<String, Object> was = snapshot(contract);
        applyFields(contract, request, reference);
        SecurityUtils.currentUser().ifPresent(user -> {
            contract.setUpdatedBy(user.userId());
            contract.setUpdatedByName(user.fullName());
        });

        // D15: the audit row carries which fields changed and what they changed from. "UPDATE" on
        // its own tells a reviewer nothing, and the row is the only record — status_history covers
        // transitions, and nothing else keeps a prior value.
        audit.record(EntityType.CONTRACT.name(), contract.getId(), contract.getContractNo(),
                "UPDATE", null, null, null, diff(was, snapshot(contract)));

        if (before == DocumentStatus.REVISION_REQUESTED) {
            transitions.transition(EntityType.CONTRACT, contract.getId(), contract.getContractNo(),
                    before, DocumentStatus.DRAFT, TriggerKind.U, null,
                    "Returned to DRAFT by being edited after a revision request");
            contract.setStatus(DocumentStatus.DRAFT);
        }
        return contract;
    }

    private void applyFields(Contract contract, ContractRequest request, Reference reference) {
        contract.setDescription(request.description());
        contract.setServiceGroup(reference.serviceGroup());
        contract.setValue(request.value());
        // currency and billingCycle have non-null DDL defaults, so an omitted value keeps what is
        // already there rather than being nulled out.
        if (reference.currency() != null) {
            contract.setCurrency(reference.currency());
        }
        contract.setValidFrom(request.validFrom());
        contract.setValidTo(request.validTo());
        contract.setPaymentTerm(request.paymentTerm());
        if (reference.billingCycle() != null) {
            contract.setBillingCycle(reference.billingCycle());
        }
        // vatRate is copied as-is, null included: null means "not stated" and must never become 0.
        contract.setVatRate(request.vatRate());
        contract.setPenaltyTerms(request.penaltyTerms());
        contract.setServiceClause(request.serviceClause());
    }

    /** The reference values a request carries as free text, resolved once and reused. */
    private record Reference(ServiceGroup serviceGroup, String currency, BillingCycle billingCycle) { }

    /**
     * Every check that can be made without touching the database, made before anything is written.
     * A bad reference value is the caller's mistake (422), not a database constraint violation.
     */
    private Reference validate(ContractRequest request) {
        if (request.validFrom().isAfter(request.validTo())) {
            throw new UnprocessableEntityException(
                    "validFrom must not be after validTo (CTR-02)");
        }
        return new Reference(
                parseRequired("serviceGroup", request.serviceGroup(),
                        ServiceGroup::valueOf, ServiceGroup.values()),
                parseCurrency(request.currency()),
                parseOptional("billingCycle", request.billingCycle(),
                        BillingCycle::valueOf, BillingCycle.values()));
    }

    /**
     * ISO 4217, checked against the JDK's own currency table rather than a hand-kept list —
     * the value ends up on an invoice, and billing has no way to interpret "VDN".
     */
    private String parseCurrency(String raw) {
        String code = blankToNull(raw);
        if (code == null) {
            return null;
        }
        code = code.trim().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new UnprocessableEntityException(
                    "currency must be an ISO 4217 code (got \"%s\")".formatted(raw));
        }
        return code;
    }

    private static <E extends Enum<E>> E parseRequired(String field, String raw,
                                                       Function<String, E> parser, E[] allowed) {
        E parsed = parseOptional(field, raw, parser, allowed);
        if (parsed == null) {
            throw new UnprocessableEntityException("%s is required".formatted(field));
        }
        return parsed;
    }

    private static <E extends Enum<E>> E parseOptional(String field, String raw,
                                                       Function<String, E> parser, E[] allowed) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return parser.apply(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnprocessableEntityException("%s must be one of %s (got \"%s\")"
                    .formatted(field, Arrays.toString(allowed), raw));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Lower-cased and wrapped here rather than in SQL — see {@link ContractRepository#search}. */
    private static String likePattern(String q) {
        String term = blankToNull(q);
        return term == null ? null : "%" + term.trim().toLowerCase(Locale.ROOT) + "%";
    }

    /** The audited fields, in the order they read on the contract itself. */
    private static Map<String, Object> snapshot(Contract contract) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("description", contract.getDescription());
        fields.put("serviceGroup", contract.getServiceGroup());
        fields.put("value", contract.getValue());
        fields.put("currency", contract.getCurrency());
        fields.put("validFrom", contract.getValidFrom());
        fields.put("validTo", contract.getValidTo());
        fields.put("paymentTerm", contract.getPaymentTerm());
        fields.put("billingCycle", contract.getBillingCycle());
        fields.put("vatRate", contract.getVatRate());
        fields.put("penaltyTerms", contract.getPenaltyTerms());
        fields.put("serviceClause", contract.getServiceClause());
        return fields;
    }

    private static Map<String, Object> diff(Map<String, Object> was, Map<String, Object> now) {
        Map<String, Object> changes = new LinkedHashMap<>();
        was.forEach((field, before) -> {
            Object after = now.get(field);
            if (!sameValue(before, after)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", text(before));
                change.put("to", text(after));
                changes.put(field, change);
            }
        });
        return changes;
    }

    /** 10 and 10.00 are the same VAT rate; {@code equals} on BigDecimal disagrees. */
    private static boolean sameValue(Object before, Object after) {
        if (before instanceof BigDecimal x && after instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(before, after);
    }

    /** Null stays null in the payload — "not stated" and the string "null" are different facts. */
    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * D4 commit-then-dispatch. In ONE transaction: CTR-02 checks, {@code DRAFT → SUBMITTED},
     * the {@code status_history} row, and a {@code workflow.start_requested} outbox row
     * carrying a freshly generated {@code idempotency_key}. A background relay then retries
     * {@code WorkflowInternal.StartInstance}.
     *
     * <p>Never the reverse order: a remote success followed by a failed local commit would orphan
     * a live, assignee-notified workflow instance on a document still DRAFT, and no retry can
     * undo that.
     *
     * <p>{@code ValidateStartable} is called as a read-only pre-check BEFORE the commit, so an
     * unconfigured document type fails fast instead of parking in the outbox forever.
     */
    @Transactional
    public void submit(UUID id) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /**
     * The M2 cancel-vs-dispatch handoff. A timestamp lease has no fencing token, so only a row
     * with {@code claimed_at IS NULL} may be cancelled directly; a stale claim is re-claimed and
     * its dispatch forced to completion before {@code CancelInstance} is called.
     *
     * <p>The document is never flipped to CANCELLED on an inconclusive read — it stays
     * SUBMITTED/UNDER_REVIEW until one branch definitively resolves, so there is no window where
     * a workflow instance starts after the document was already cancelled.
     */
    @Transactional
    public void cancel(UUID id, String reason) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /** CTR-04: a REJECTED contract is not silently editable; this is the audited opt-in back to DRAFT. */
    @Transactional
    public void revise(UUID id) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /**
     * Sends an APPROVED contract for e-signature (4.8, D10). Writes an
     * {@code esign.session_requested} outbox row with NO status change at all (D14e) — §5.5
     * forbids mixing approval state and signing state. The contract stays APPROVED, and
     * {@code APPROVED → ACTIVE} still fires purely on schedule regardless of signing progress.
     */
    @Transactional
    public void sendForSigning(UUID id) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }
}
