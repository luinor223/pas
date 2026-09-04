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
import com.abclogistics.pas.contract.dto.ContractResponse;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.domain.CustomerContact;
import com.abclogistics.pas.contract.event.EsignSessionRequested;
import com.abclogistics.pas.contract.event.WorkflowStartRequested;
import com.abclogistics.pas.contract.repository.AttachmentRepository;
import com.abclogistics.pas.contract.repository.ContractRepository;
import com.abclogistics.pas.workflow.grpc.GetInstanceByDocumentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Contract lifecycle (4.2, CTR-01..CTR-07). */
@Service
public class ContractService {

    static final String DOCUMENT_TYPE = "CONTRACT";

    public static final String INITIALIZATION_PENDING = "INITIALIZATION_PENDING";

    static final String WORKFLOW_IN_PROGRESS = "IN_PROGRESS";

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
    private final DocumentCancellationService cancellation;
    private final TransactionTemplate tx;

    public ContractService(ContractRepository contracts, CustomerService customers,
                           DocumentNumberService numbers, StatusTransitionService transitions,
                           AttachmentRepository attachments, WorkflowGrpcClient workflow,
                           OutboxRepository outbox, ObjectMapper objectMapper,
                           AuditRecorder audit, DocumentCancellationService cancellation,
                           TransactionTemplate tx) {
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
        this.tx = tx;
    }

    @Transactional(readOnly = true)
    public Page<Contract> search(UUID customerId, String status, String serviceGroup,
                                 String q,
                                 String validFromFrom, String validFromTo,
                                 String validToFrom, String validToTo,
                                 Pageable pageable) {
        return contracts.search(
                customerId,
                RequestValues.parseOptional("status", status, DocumentStatus::valueOf, DocumentStatus.values()),
                RequestValues.parseOptional("serviceGroup", serviceGroup, ServiceGroup::valueOf, ServiceGroup.values()),
                RequestValues.likePattern(q),
                RequestValues.parseOptionalDate("validFromFrom", validFromFrom),
                RequestValues.parseOptionalDate("validFromTo", validFromTo),
                RequestValues.parseOptionalDate("validToFrom", validToFrom),
                RequestValues.parseOptionalDate("validToTo", validToTo),
                pageable);
    }

    @Transactional(readOnly = true)
    public Page<Contract> search(UUID customerId, String status, String serviceGroup,
                                 String q, Pageable pageable) {
        return search(customerId, status, serviceGroup, q, null, null, null, null, pageable);
    }

    @Transactional(readOnly = true)
    public Contract get(UUID id) {
        return contracts.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract %s not found".formatted(id)));
    }

    @Transactional(readOnly = true)
    public List<ContractResponse> lookupResponses(List<UUID> ids) {
        return contracts.findAllById(ids).stream().map(ContractResponse::of).toList();
    }

    @Transactional
    public Contract create(ContractRequest request) {
        // parsed before any write, so a bad value never reaches Postgres as a CHECK violation
        Reference reference = validate(request);
        Customer customer = customers.get(request.customerId());

        Contract contract = Contract.create(
                numbers.nextDocumentNo(EntityType.CONTRACT, request.validFrom().getYear()),
                customer,
                reference.serviceGroup(),
                request.validFrom(), request.validTo());
        applyFields(contract, request, reference);
        RecordStamp.creator(contract);
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

    @Transactional
    public Contract update(UUID id, ContractRequest request) {
        Contract contract = get(id);
        DocumentStatus before = contract.getStatus();

        if (!before.isEditable()) {
            // parenthesised: .formatted binds to the last operand of a concatenation
            throw new ConflictException(
                    ("Contract %s is %s and cannot be edited (CTR-01). A change to an approved or "
                            + "active contract must be made through an addendum (CTR-07).")
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
            // a DRAFT may be re-pointed at another customer; CTR-01 refused the rest
            Customer replacement = customers.get(request.customerId());
            audit.record(EntityType.CONTRACT.name(), contract.getId(), contract.getContractNo(),
                    "REASSIGN_CUSTOMER", null, null, null,
                    Map.of("from", contract.getCustomer().getCode(), "to", replacement.getCode()));
            contract.setCustomer(replacement);
        }
        Map<String, Object> was = snapshot(contract);
        applyFields(contract, request, reference);
        RecordStamp.editor(contract);

        // D15: the row carries which fields changed and from what; nothing else keeps a prior value
        audit.record(EntityType.CONTRACT.name(), contract.getId(), contract.getContractNo(),
                "UPDATE", null, null, null, FieldDiff.between(was, snapshot(contract)));

        if (before == DocumentStatus.REVISION_REQUESTED) {
            transitions.transition(contract, DocumentStatus.DRAFT, TriggerKind.U, null,
                    "Returned to DRAFT by being edited after a revision request");
        }
        return contract;
    }

    private void applyFields(Contract contract, ContractRequest request, Reference reference) {
        contract.setDescription(request.description());
        contract.setServiceGroup(reference.serviceGroup());
        contract.setValue(request.value());
        // non-null DDL defaults: an omitted value keeps what is there
        if (reference.currency() != null) {
            contract.setCurrency(reference.currency());
        }
        contract.setValidFrom(request.validFrom());
        contract.setValidTo(request.validTo());
        contract.setPaymentTerm(request.paymentTerm());
        if (reference.billingCycle() != null) {
            contract.setBillingCycle(reference.billingCycle());
        }
        // vatRate copied as-is: null means "not stated" and must never become 0
        contract.setVatRate(request.vatRate());
        contract.setPenaltyTerms(request.penaltyTerms());
        contract.setServiceClause(request.serviceClause());
    }

    private record Reference(ServiceGroup serviceGroup, String currency, BillingCycle billingCycle) { }

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

    /** D4 submit: pre-check between two transactions, never inside one. Not {@code @Transactional}. */
    public void submit(UUID id) {
        requireNoTransaction();
        // advisory only: refuses a hopeless document before it costs a round trip
        tx.executeWithoutResult(s -> requireSubmittable(requireDraft(id)));

        // before the commit, or a SUBMITTED contract parks against config that never appears;
        // outside a transaction, or a 2s gRPC deadline holds a pooled connection for its duration
        workflow.validateStartable(DOCUMENT_TYPE);

        // the template, not a self-invoked @Transactional method the proxy would not intercept
        tx.executeWithoutResult(s -> commitSubmission(id));
    }

    // asserted, not just documented: a caller wrapping submit puts the remote call back inside a
    // transaction, and the two templates below would silently join it
    static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "submit must run outside a transaction: ValidateStartable is a remote call, and "
                    + "the local commit opens a transaction of its own (D4)");
        }
    }

    private Contract requireDraft(UUID id) {
        Contract contract = get(id);
        if (contract.getStatus() != DocumentStatus.DRAFT) {
            throw new ConflictException(
                    "Contract %s is %s; only a DRAFT can be submitted (registry §9)"
                            .formatted(contract.getContractNo(), contract.getStatus()));
        }
        return contract;
    }

    // D4: re-read and re-checked, since nothing held a lock across the remote call. Status,
    // history and dispatch intent commit together; StartInstance first would orphan a live
    // instance on a still-DRAFT document.
    private void commitSubmission(UUID id) {
        Contract contract = requireDraft(id);
        requireSubmittable(contract);

        transitions.transition(contract, DocumentStatus.SUBMITTED, TriggerKind.U, null,
                "Submitted for approval");

        // submit is an authenticated endpoint, and the instance records who asked for it
        AuthenticatedUser actor = SecurityUtils.currentUser().orElseThrow(
                () -> new IllegalStateException("submit requires an authenticated user"));
        WorkflowStartRequested payload = new WorkflowStartRequested(
                UUID.randomUUID(), DOCUMENT_TYPE, contract.getId(), contract.getContractNo(),
                contract.getCustomer().getName(), "NORMAL",
                actor.userId(), actor.fullName());
        outbox.save(OutboxEvent.event(WorkflowStartRequested.EVENT_TYPE,
                EntityType.CONTRACT.name(), contract.getId(), objectMapper.writeValueAsString(payload)));
    }

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
        // null means "not stated"; 0% is a different answer, so never read null as zero
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

    @Transactional(readOnly = true)
    public List<StatusHistory> history(UUID id) {
        get(id);   // an unknown contract is a 404, not an empty timeline
        return transitions.history(EntityType.CONTRACT, id);
    }

    public record ApprovalProgress(DocumentStatus documentStatus, String state,
                                   GetInstanceByDocumentResponse instance) { }

    public DocumentCancellationService.Outcome cancel(UUID id, String reason) {
        return cancellation.cancel(EntityType.CONTRACT, id, reason);
    }

    @Transactional
    public Contract revise(UUID id) {
        Contract contract = get(id);
        DocumentStatus before = contract.getStatus();
        if (before != DocumentStatus.REJECTED) {
            throw new ConflictException(
                    "Contract %s is %s; only a REJECTED contract is revised (CTR-04)"
                            .formatted(contract.getContractNo(), before));
        }
        transitions.transition(contract, DocumentStatus.DRAFT, TriggerKind.U, null,
                "Reopened for revision after rejection (CTR-04)");
        return contract;
    }

    /**
     * D10 send-for-signing — registry §6's third outbox use, and NOT a transition. Requirement 5.5
     * keeps approval state and signing state apart, so nothing about the contract's status moves
     * here and no status_history row is written; the frontend composes signing state from
     * esign-service for display.
     *
     * <p>Outboxed rather than called synchronously for the same reason submit is (D4): APR-07 wants
     * the user's send to survive esign-service being down, and a remote call committed afterwards
     * would leave a session sending to the provider for a document this service never recorded.
     */
    @Transactional
    public void sendForSigning(UUID id) {
        Contract contract = get(id);
        // GetSigningPayload's own guard (registry §5): esign will re-check it when it fetches, and
        // refusing here means the user learns now rather than through a failed session
        if (contract.getStatus() != DocumentStatus.APPROVED) {
            throw new ConflictException(
                    "Contract %s is %s; only an APPROVED contract can be sent for signature (D10)"
                            .formatted(contract.getContractNo(), contract.getStatus()));
        }
        CustomerContact signer = signerFor(contract.getCustomer());

        EsignSessionRequested payload = new EsignSessionRequested(
                // generated once, here: every retry of this row reuses it, so a re-dispatch after
                // a timeout cannot create a second signing session (§M2)
                UUID.randomUUID(), DOCUMENT_TYPE, contract.getId(), contract.getContractNo(),
                signer.getFullName(), signer.getEmail());
        outbox.save(OutboxEvent.event(EsignSessionRequested.EVENT_TYPE,
                EntityType.CONTRACT.name(), contract.getId(),
                objectMapper.writeValueAsString(payload)));

        // audited even though no status moved: "who sent this for signature, and when" is exactly
        // the kind of action the History tab exists to answer (D15)
        audit.record(EntityType.CONTRACT.name(), contract.getId(), contract.getContractNo(),
                "SEND_FOR_SIGNING", null, null,
                "Sent for e-signature to %s".formatted(signer.getEmail()),
                Map.of("signerName", signer.getFullName(), "signerEmail", signer.getEmail()));
    }

    /** The signer esign addresses (db-esign.md: "the mock addresses a named customer signer"). */
    private CustomerContact signerFor(Customer customer) {
        CustomerContact signer = customers.primaryContactOf(customer.getId())
                .orElseThrow(() -> new UnprocessableEntityException(
                        "Customer %s has no primary contact; there is nobody to address the "
                                + "signature request to (D10)".formatted(customer.getCode())));
        if (RequestValues.blankToNull(signer.getEmail()) == null) {
            throw new UnprocessableEntityException(
                    "Primary contact %s of customer %s has no email address; the signature request "
                            + "has nowhere to go (D10)".formatted(signer.getFullName(), customer.getCode()));
        }
        return signer;
    }

    // --- D14d date-driven transitions (driven by ContractStatusScheduler) ------------------------

    /** APPROVED contracts whose valid_from has arrived, oldest first (CTR-05). */
    @Transactional(readOnly = true)
    public List<UUID> dueForActivation(LocalDate onOrBefore) {
        return contracts.dueForActivation(DocumentStatus.APPROVED, onOrBefore)
                .stream().map(Contract::getId).toList();
    }

    /** ACTIVE contracts whose valid_to has passed, oldest first. */
    @Transactional(readOnly = true)
    public List<UUID> dueForExpiry(LocalDate today) {
        return contracts.dueForExpiry(DocumentStatus.ACTIVE, today)
                .stream().map(Contract::getId).toList();
    }

    /**
     * CTR-05: APPROVED to ACTIVE on the effective date. Fires whether or not a signing session
     * exists (D14e, registry §9 footnote 3) — this service has no dependency on esign at all.
     */
    @Transactional
    public void activate(UUID id) {
        Contract contract = get(id);
        DocumentStatus before = contract.getStatus();
        // the candidate list was read outside this transaction: an overlapping run may already
        // have activated it, and a second history row would claim it happened twice
        if (before != DocumentStatus.APPROVED) {
            return;
        }
        transitions.transition(contract, DocumentStatus.ACTIVE, TriggerKind.S, null,
                "Effective date reached (CTR-05, D14d)");
    }

    /**
     * ACTIVE to EXPIRED once valid_to has passed — the stored one, so an extension is honoured.
     * {@code today} comes from the caller so a whole sweep judges every document against one date.
     */
    @Transactional
    public void expire(UUID id, LocalDate today) {
        Contract contract = get(id);
        DocumentStatus before = contract.getStatus();
        if (before != DocumentStatus.ACTIVE) {
            return;
        }
        // re-checked against the row as it is now: an addendum activated earlier in this same
        // sweep may have moved valid_to past today, and expiring it anyway would undo the renewal
        if (!contract.getValidTo().isBefore(today)) {
            return;
        }
        transitions.transition(contract, DocumentStatus.EXPIRED, TriggerKind.S, null,
                "End date passed (D14d)");
    }

    /**
     * D9 warning candidates: ACTIVE, expiring inside the horizon, not yet warned for the valid_to
     * they currently carry. Snapshotted into a record because the warning is published outside any
     * transaction — a Kafka ack must not be waited on while holding a connection.
     */
    @Transactional(readOnly = true)
    public List<ExpiryWarning> dueForExpiryWarning(LocalDate today, LocalDate horizon) {
        return contracts.dueForExpiryWarning(DocumentStatus.ACTIVE, today, horizon).stream()
                .map(c -> new ExpiryWarning(c.getId(), c.getContractNo(), c.getValidTo(),
                        ChronoUnit.DAYS.between(today, c.getValidTo()), c.getCreatedBy()))
                .toList();
    }

    /** registry §4 document.expiring payload, snapshotted for a publish outside the transaction. */
    public record ExpiryWarning(UUID contractId, String documentNo, LocalDate expiresOn,
                                long daysLeft, UUID ownerUserId) { }

    /**
     * Stamped only after Kafka acked (D9): an unstamped warning re-fires next run, which is the
     * whole reason this event needs no outbox row.
     */
    @Transactional
    public void markExpiryWarned(UUID id, LocalDate warnedFor) {
        Contract contract = get(id);
        // guarded: an extension applied between the send and this write must not be recorded as
        // warned-for, or the new term would go silent
        if (warnedFor.equals(contract.getValidTo())) {
            contract.setLastExpiryWarningFor(warnedFor);
        }
    }
}
