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
import com.abclogistics.pas.contract.domain.StatusHistory;
import com.abclogistics.pas.contract.domain.TriggerKind;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.domain.CustomerStatus;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.event.WorkflowStartRequested;
import com.abclogistics.pas.contract.repository.AttachmentRepository;
import com.abclogistics.pas.contract.repository.ContractRepository;
import com.abclogistics.pas.workflow.grpc.GetInstanceByDocumentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Contract lifecycle (4.2, CTR-01..CTR-07).
 */
@Service
public class ContractService {

    /** The workflow document type code this service submits under (workflow V2 seed). */
    static final String DOCUMENT_TYPE = "CONTRACT";

    /** Rendered while D4's dispatch window is open; never persisted as a document status (D14e). */
    public static final String INITIALIZATION_PENDING = "INITIALIZATION_PENDING";

    /** workflow_instance.status — the one non-terminal value (workflow V1 CHECK). */
    private static final String WORKFLOW_IN_PROGRESS = "IN_PROGRESS";

    private static final BigDecimal MIN_VAT = BigDecimal.ZERO;
    private static final BigDecimal MAX_VAT = new BigDecimal("100");

    private final ContractRepository contracts;
    private final CustomerService customers;
    private final DocumentNumberService numbers;
    private final StatusTransitionService transitions;
    private final AttachmentRepository attachments;
    private final WorkflowGrpcClient workflow;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final AuditRecorder audit;
    private final ContractCancellationService cancellation;

    public ContractService(ContractRepository contracts, CustomerService customers,
                           DocumentNumberService numbers, StatusTransitionService transitions,
                           AttachmentRepository attachments, WorkflowGrpcClient workflow,
                           OutboxRepository outbox, ObjectMapper objectMapper,
                           AuditRecorder audit, ContractCancellationService cancellation) {
        this.contracts = contracts;
        this.customers = customers;
        this.numbers = numbers;
        this.transitions = transitions;
        this.attachments = attachments;
        this.workflow = workflow;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.cancellation = cancellation;
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
                RequestValues.parseOptional("status", status, DocumentStatus::valueOf, DocumentStatus.values()),
                RequestValues.parseOptional("serviceGroup", serviceGroup, ServiceGroup::valueOf, ServiceGroup.values()),
                RequestValues.likePattern(q),
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
                "UPDATE", null, null, null, FieldDiff.between(was, snapshot(contract)));

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
                RequestValues.parseRequired("serviceGroup", request.serviceGroup(),
                        ServiceGroup::valueOf, ServiceGroup.values()),
                parseCurrency(request.currency()),
                RequestValues.parseOptional("billingCycle", request.billingCycle(),
                        BillingCycle::valueOf, BillingCycle.values()));
    }

    /**
     * ISO 4217, checked against the JDK's own currency table rather than a hand-kept list —
     * the value ends up on an invoice, and billing has no way to interpret "VDN".
     */
    private String parseCurrency(String raw) {
        String code = RequestValues.blankToNull(raw);
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
        Contract contract = get(id);
        DocumentStatus before = contract.getStatus();
        if (before != DocumentStatus.DRAFT) {
            throw new ConflictException(
                    "Contract %s is %s; only a DRAFT can be submitted (registry §9)"
                            .formatted(contract.getContractNo(), before));
        }
        requireSubmittable(contract);

        // BEFORE the commit, and read-only: a document type with no active definition must fail
        // fast as a 412. Committing first would leave a SUBMITTED contract whose outbox row the
        // relay retries forever against a configuration that is never going to appear.
        workflow.validateStartable(DOCUMENT_TYPE);

        transitions.transition(EntityType.CONTRACT, contract.getId(), contract.getContractNo(),
                before, DocumentStatus.SUBMITTED, TriggerKind.U, null, "Submitted for approval");
        contract.setStatus(DocumentStatus.SUBMITTED);

        // D4: the status change, its history row and the dispatch intent commit together. The
        // remote call is the relay's job. The reverse order — StartInstance first — orphans a
        // live, assignee-notified instance on a still-DRAFT document if the commit then fails,
        // and no retry can undo that.
        AuthenticatedUser actor = SecurityUtils.currentUser().orElse(null);
        WorkflowStartRequested payload = new WorkflowStartRequested(
                UUID.randomUUID(), DOCUMENT_TYPE, contract.getId(), contract.getContractNo(),
                contract.getCustomer().getName(), "NORMAL",
                actor == null ? null : actor.userId(),
                actor == null ? "system" : actor.fullName());
        outbox.save(OutboxEvent.event(WorkflowStartRequested.EVENT_TYPE,
                EntityType.CONTRACT.name(), contract.getId(), objectMapper.writeValueAsString(payload)));
    }

    /**
     * CTR-02. Every prerequisite is checked here rather than trusted from create/update, because
     * the world moves between the two: a customer can be suspended, and the fields that billing
     * snapshots (PAY-03) are deliberately optional while the contract is still a DRAFT.
     */
    private void requireSubmittable(Contract contract) {
        Customer customer = contract.getCustomer();
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw new UnprocessableEntityException(
                    "Customer %s is %s; only an ACTIVE customer may have a contract submitted (CTR-02)"
                            .formatted(customer.getCode(), customer.getStatus()));
        }
        if (contract.getValidFrom().isAfter(contract.getValidTo())) {
            throw new UnprocessableEntityException("validFrom must not be after validTo (CTR-02)");
        }
        if (!attachments.existsByOwnerTypeAndOwnerId(EntityType.CONTRACT, contract.getId())) {
            throw new UnprocessableEntityException(
                    "Contract %s has no attachment; at least one is required to submit (CTR-02)"
                            .formatted(contract.getContractNo()));
        }
        // Null here means "not stated", and billing would have to guess. 0% is a different and
        // perfectly valid answer, which is exactly why null must never be read as zero.
        if (contract.getVatRate() == null) {
            throw new UnprocessableEntityException(
                    "vatRate is required to submit (CTR-02); it is never assumed to be 0");
        }
        if (contract.getVatRate().compareTo(MIN_VAT) < 0 || contract.getVatRate().compareTo(MAX_VAT) > 0) {
            throw new UnprocessableEntityException(
                    "vatRate must be between 0 and 100 (got %s)".formatted(contract.getVatRate()));
        }
        if (RequestValues.blankToNull(contract.getPaymentTerm()) == null) {
            throw new UnprocessableEntityException("paymentTerm is required to submit (CTR-02)");
        }
    }

    /**
     * Approval progress for the document (4.7), composed owner-side rather than retried.
     *
     * <p>registry §5 states the rule as a predicate, not a case list: when the local status is
     * SUBMITTED and the response is <em>not</em> an IN_PROGRESS instance — whether that is an
     * absent instance or a terminal one from a previous submission — the answer is
     * INITIALIZATION_PENDING and the returned instance is DISCARDED.
     *
     * <p>The discard is the whole point. CTR-04's revise and REVISION_REQUESTED → DRAFT → submit
     * each mint a new idempotency key and therefore a new instance, so a document accumulates
     * terminal instances. Without it, a resubmitted contract shows its previous REJECTED chain as
     * current progress for the entire dispatch window.
     */
    @Transactional(readOnly = true)
    public ApprovalProgress progress(UUID id) {
        Contract contract = get(id);
        GetInstanceByDocumentResponse instance =
                workflow.getInstanceByDocument(DOCUMENT_TYPE, id).orElse(null);
        boolean live = instance != null && WORKFLOW_IN_PROGRESS.equals(instance.getStatus());

        if (contract.getStatus() == DocumentStatus.SUBMITTED && !live) {
            return new ApprovalProgress(contract.getStatus(), INITIALIZATION_PENDING, null);
        }
        return instance == null
                ? new ApprovalProgress(contract.getStatus(), contract.getStatus().name(), null)
                : new ApprovalProgress(contract.getStatus(), instance.getStatus(), instance);
    }

    /** The local D17 timeline, oldest first. Synchronous and authoritative, unlike audit-service. */
    @Transactional(readOnly = true)
    public List<StatusHistory> history(UUID id) {
        get(id);   // an unknown contract is a 404, not an empty timeline
        return transitions.history(EntityType.CONTRACT, id);
    }

    /**
     * {@code instance} is null while the dispatch is still pending, and null too when a stale
     * terminal instance was discarded. Item 10 maps this to a response DTO for REST; the proto
     * message does not leave the service layer before then.
     */
    public record ApprovalProgress(DocumentStatus documentStatus, String state,
                                   GetInstanceByDocumentResponse instance) { }

    /**
     * The M2 cancel-vs-dispatch handoff. A timestamp lease has no fencing token, so only a row
     * with {@code claimed_at IS NULL} may be cancelled directly; a stale claim is re-claimed and
     * its dispatch forced to completion before {@code CancelInstance} is called.
     *
     * <p>The document is never flipped to CANCELLED on an inconclusive read — it stays
     * SUBMITTED/UNDER_REVIEW until one branch definitively resolves, so there is no window where
     * a workflow instance starts after the document was already cancelled.
     */
    public ContractCancellationService.Outcome cancel(UUID id, String reason) {
        return cancellation.cancel(id, reason);
    }

    /**
     * CTR-04: a REJECTED contract is not silently editable; this is the audited opt-in back to
     * DRAFT.
     *
     * <p>It changes only the status, never the content — the edit that follows goes through the
     * ordinary {@link #update} path and its CTR-01 guard. Folding the two together would make the
     * opt-in invisible, which is the whole thing CTR-04 is there to prevent.
     */
    @Transactional
    public Contract revise(UUID id) {
        Contract contract = get(id);
        DocumentStatus before = contract.getStatus();
        if (before != DocumentStatus.REJECTED) {
            throw new ConflictException(
                    "Contract %s is %s; only a REJECTED contract is revised (CTR-04)"
                            .formatted(contract.getContractNo(), before));
        }
        transitions.transition(EntityType.CONTRACT, contract.getId(), contract.getContractNo(),
                before, DocumentStatus.DRAFT, TriggerKind.U, null,
                "Reopened for revision after rejection (CTR-04)");
        contract.setStatus(DocumentStatus.DRAFT);
        return contract;
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
