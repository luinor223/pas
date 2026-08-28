package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.Addendum;
import com.abclogistics.pas.contract.domain.AddendumServiceLine;
import com.abclogistics.pas.contract.domain.ChangeType;
import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.TriggerKind;
import com.abclogistics.pas.contract.dto.AddendumRequest;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.event.WorkflowStartRequested;
import com.abclogistics.pas.contract.repository.AddendumRepository;
import com.abclogistics.pas.contract.repository.AttachmentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Addendum lifecycle (4.3). Same status machine and same D4 submit as a contract; the approval
 * chain may differ by configuration.
 */
@Service
public class AddendumService {

    /** The workflow document type code addenda submit under. */
    static final String DOCUMENT_TYPE = "ADDENDUM";

    private final AddendumRepository addenda;
    private final ContractService contracts;
    private final DocumentNumberService numbers;
    private final StatusTransitionService transitions;
    private final AttachmentRepository attachments;
    private final WorkflowGrpcClient workflow;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final AuditRecorder audit;
    private final DocumentCancellationService cancellation;

    public AddendumService(AddendumRepository addenda, ContractService contracts,
                           DocumentNumberService numbers, StatusTransitionService transitions,
                           AttachmentRepository attachments, WorkflowGrpcClient workflow,
                           OutboxRepository outbox, ObjectMapper objectMapper, AuditRecorder audit,
                           DocumentCancellationService cancellation) {
        this.addenda = addenda;
        this.contracts = contracts;
        this.numbers = numbers;
        this.transitions = transitions;
        this.attachments = attachments;
        this.workflow = workflow;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.cancellation = cancellation;
    }

    @Transactional(readOnly = true)
    public Page<Addendum> search(UUID contractId, String status, Pageable pageable) {
        return addenda.search(contractId,
                RequestValues.parseOptional("status", status,
                        DocumentStatus::valueOf, DocumentStatus.values()),
                pageable);
    }

    @Transactional(readOnly = true)
    public Addendum get(UUID id) {
        return addenda.findById(id)
                .orElseThrow(() -> new NotFoundException("Addendum %s not found".formatted(id)));
    }

    /**
     * An addendum amends a contract that is already in force, so the parent must be APPROVED or
     * ACTIVE — that is the whole reason CTR-07 sends terms changes here rather than to
     * {@code PUT /contracts/{id}}, and creating one against a DRAFT would be a way of editing a
     * document that is editable anyway.
     */
    @Transactional
    public Addendum create(AddendumRequest request) {
        Contract parent = contracts.get(request.contractId());
        requireAmendable(parent);
        ChangeType changeType = RequestValues.parseRequired("changeType", request.changeType(),
                ChangeType::valueOf, ChangeType.values());
        validate(changeType, request, parent);

        Addendum addendum = Addendum.create(
                numbers.nextDocumentNo(EntityType.ADDENDUM, request.effectiveFrom().getYear()),
                parent, changeType, request.effectiveFrom());
        applyFields(addendum, request, changeType);
        SecurityUtils.currentUser().ifPresent(user -> {
            addendum.setCreatedBy(user.userId());
            addendum.setCreatedByName(user.fullName());
            addendum.setCreatedByDepartment(user.department());
        });
        Addendum saved = addenda.save(addendum);

        audit.record(EntityType.ADDENDUM.name(), saved.getId(), saved.getAddendumNo(),
                "CREATE", null, DocumentStatus.DRAFT.name(), null,
                Map.of("contractNo", parent.getContractNo(), "changeType", changeType.name()));
        return saved;
    }

    /** CTR-01 applies equally: DRAFT and REVISION_REQUESTED only, under the optimistic lock. */
    @Transactional
    public Addendum update(UUID id, AddendumRequest request) {
        Addendum addendum = get(id);
        DocumentStatus before = addendum.getStatus();
        if (!before.isEditable()) {
            throw new ConflictException(
                    "Addendum %s is %s and cannot be edited (CTR-01)"
                            .formatted(addendum.getAddendumNo(), before));
        }
        if (request.version() == null) {
            throw new UnprocessableEntityException("version is required on update (CTR-01 optimistic lock)");
        }
        if (request.version() != addendum.getVersion()) {
            throw new ObjectOptimisticLockingFailureException(Addendum.class, id);
        }
        ChangeType changeType = RequestValues.parseRequired("changeType", request.changeType(),
                ChangeType::valueOf, ChangeType.values());
        validate(changeType, request, addendum.getContract());

        Map<String, Object> was = snapshot(addendum);
        applyFields(addendum, request, changeType);
        SecurityUtils.currentUser().ifPresent(user -> {
            addendum.setUpdatedBy(user.userId());
            addendum.setUpdatedByName(user.fullName());
        });
        audit.record(EntityType.ADDENDUM.name(), addendum.getId(), addendum.getAddendumNo(),
                "UPDATE", null, null, null, FieldDiff.between(was, snapshot(addendum)));

        if (before == DocumentStatus.REVISION_REQUESTED) {
            transitions.transition(EntityType.ADDENDUM, addendum.getId(), addendum.getAddendumNo(),
                    before, DocumentStatus.DRAFT, TriggerKind.U, null,
                    "Returned to DRAFT by being edited after a revision request");
            addendum.setStatus(DocumentStatus.DRAFT);
        }
        return addendum;
    }

    /** D4, identical in shape to {@link ContractService#submit} but under its own document type. */
    @Transactional
    public void submit(UUID id) {
        Addendum addendum = get(id);
        DocumentStatus before = addendum.getStatus();
        if (before != DocumentStatus.DRAFT) {
            throw new ConflictException(
                    "Addendum %s is %s; only a DRAFT can be submitted (registry §9)"
                            .formatted(addendum.getAddendumNo(), before));
        }
        requireSubmittable(addendum);
        workflow.validateStartable(DOCUMENT_TYPE);

        transitions.transition(EntityType.ADDENDUM, addendum.getId(), addendum.getAddendumNo(),
                before, DocumentStatus.SUBMITTED, TriggerKind.U, null, "Submitted for approval");
        addendum.setStatus(DocumentStatus.SUBMITTED);

        AuthenticatedUser actor = SecurityUtils.currentUser().orElse(null);
        WorkflowStartRequested payload = new WorkflowStartRequested(
                UUID.randomUUID(), DOCUMENT_TYPE, addendum.getId(), addendum.getAddendumNo(),
                addendum.getContract().getCustomer().getName(), "NORMAL",
                actor == null ? null : actor.userId(),
                actor == null ? "system" : actor.fullName());
        outbox.save(OutboxEvent.event(WorkflowStartRequested.EVENT_TYPE,
                EntityType.ADDENDUM.name(), addendum.getId(),
                objectMapper.writeValueAsString(payload)));
    }

    /**
     * The same M2 handoff a contract gets — same lease semantics, same restricted UNDER_REVIEW
     * edge, same 202-while-pending — because it is literally the same code (D4/M2 apply to every
     * document type that starts a workflow).
     */
    public DocumentCancellationService.Outcome cancel(UUID id, String reason) {
        return cancellation.cancel(EntityType.ADDENDUM, id, reason);
    }

    /** CTR-04, as for a contract: a REJECTED addendum is not silently editable. */
    @Transactional
    public Addendum revise(UUID id) {
        Addendum addendum = get(id);
        DocumentStatus before = addendum.getStatus();
        if (before != DocumentStatus.REJECTED) {
            throw new ConflictException(
                    "Addendum %s is %s; only a REJECTED addendum is revised (CTR-04)"
                            .formatted(addendum.getAddendumNo(), before));
        }
        transitions.transition(EntityType.ADDENDUM, addendum.getId(), addendum.getAddendumNo(),
                before, DocumentStatus.DRAFT, TriggerKind.U, null,
                "Reopened for revision after rejection (CTR-04)");
        addendum.setStatus(DocumentStatus.DRAFT);
        return addendum;
    }

    /**
     * Approval progress for the addendum's own instance. The registry §5 owner-side composition is
     * the contract's, unchanged: SUBMITTED plus anything that is not an IN_PROGRESS instance reads
     * as INITIALIZATION_PENDING, and the returned instance is discarded.
     */
    @Transactional(readOnly = true)
    public ContractService.ApprovalProgress progress(UUID id) {
        Addendum addendum = get(id);
        var instance = workflow.getInstanceByDocument(DOCUMENT_TYPE, id).orElse(null);
        boolean live = instance != null
                && ContractService.WORKFLOW_IN_PROGRESS.equals(instance.getStatus());

        if (addendum.getStatus() == DocumentStatus.SUBMITTED && !live) {
            return new ContractService.ApprovalProgress(
                    addendum.getStatus(), ContractService.INITIALIZATION_PENDING, null);
        }
        return instance == null
                ? new ContractService.ApprovalProgress(
                        addendum.getStatus(), addendum.getStatus().name(), null)
                : new ContractService.ApprovalProgress(
                        addendum.getStatus(), instance.getStatus(), instance);
    }

    /**
     * Applies an approved addendum's effects to its parent contract, in the SAME transaction as
     * the addendum's own {@code APPROVED → ACTIVE} flip (registry §9 footnote ²):
     * {@code TERM_EXTENSION} sets {@code contract.validTo = newValidTo};
     * {@code PAYMENT_TERMS} sets {@code contract.paymentTerm = paymentTermOverride}.
     *
     * <p>This is a system action, audit-logged, and NOT a CTR-07 violation — it applies an already
     * approved addendum rather than editing terms directly.
     *
     * <p>{@code UNIT_PRICE_CHANGE} and {@code ADDED_SERVICE} apply nothing here: the former
     * carries no price data (D8) and the latter is record-only.
     *
     * <p>MANDATORY propagation is the enforcement, not a hint: in its own transaction the parent
     * could keep its old terms while the addendum claims to be in force, and
     * {@code GetContract} — which billing snapshots — would return values that no longer match
     * the document.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyEffectsToParent(Addendum addendum) {
        // Asserted, not merely annotated: MANDATORY is enforced by the proxy, and the proxy does
        // not see a call from inside this class. The check makes the guarantee hold on every
        // route into the method rather than only the ones that happen to arrive from outside.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "applyEffectsToParent must run in the addendum's own transaction (registry §9²)");
        }
        Contract parent = addendum.getContract();
        Map<String, Object> was = Map.of(
                "validTo", parent.getValidTo(),
                "paymentTerm", String.valueOf(parent.getPaymentTerm()));

        switch (addendum.getChangeType()) {
            case TERM_EXTENSION -> parent.setValidTo(addendum.getNewValidTo());
            case PAYMENT_TERMS -> parent.setPaymentTerm(addendum.getPaymentTermOverride());
            // D8: the figures live in a pricing version Sales creates from the approved addendum.
            // Nothing on the contract row changes.
            case UNIT_PRICE_CHANGE -> { }
            // Record/display only. contract.serviceGroup stays the single enforced scope value
            // operations-service validates against; widening it here would silently change that.
            case ADDED_SERVICE -> { }
        }

        Map<String, Object> now = Map.of(
                "validTo", parent.getValidTo(),
                "paymentTerm", String.valueOf(parent.getPaymentTerm()));
        Map<String, Object> changes = FieldDiff.between(was, now);
        if (changes.isEmpty()) {
            return;   // nothing to say: an audit row claiming a change would be a false record
        }
        audit.record(EntityType.CONTRACT.name(), parent.getId(), parent.getContractNo(),
                "ADDENDUM_APPLIED", null, null,
                "Applied addendum %s (%s)".formatted(addendum.getAddendumNo(), addendum.getChangeType()),
                changes);
    }

    // --- validation ---------------------------------------------------------------------------

    private void requireAmendable(Contract parent) {
        DocumentStatus status = parent.getStatus();
        if (status != DocumentStatus.APPROVED && status != DocumentStatus.ACTIVE) {
            throw new UnprocessableEntityException(
                    ("Contract %s is %s; an addendum amends a contract already in force, so the "
                            + "parent must be APPROVED or ACTIVE (4.3)")
                            .formatted(parent.getContractNo(), status));
        }
    }

    /**
     * The change type decides which field is mandatory, which is why neither can be a bean
     * validation annotation on the request: a TERM_EXTENSION without {@code newValidTo} would
     * otherwise reach {@link #applyEffectsToParent} and quietly null out the parent's expiry.
     */
    private void validate(ChangeType changeType, AddendumRequest request, Contract parent) {
        switch (changeType) {
            case TERM_EXTENSION -> {
                if (request.newValidTo() == null) {
                    throw new UnprocessableEntityException(
                            "newValidTo is required for a TERM_EXTENSION addendum (D14b renewal)");
                }
                if (!request.newValidTo().isAfter(parent.getValidTo())) {
                    throw new UnprocessableEntityException(
                            ("newValidTo (%s) must be after the contract's current validTo (%s); "
                                    + "a TERM_EXTENSION extends the term, it does not shorten it")
                                    .formatted(request.newValidTo(), parent.getValidTo()));
                }
            }
            case PAYMENT_TERMS -> {
                if (RequestValues.blankToNull(request.paymentTermOverride()) == null) {
                    throw new UnprocessableEntityException(
                            "paymentTermOverride is required for a PAYMENT_TERMS addendum");
                }
            }
            case ADDED_SERVICE -> {
                if (request.services() == null || request.services().isEmpty()) {
                    throw new UnprocessableEntityException(
                            "An ADDED_SERVICE addendum must list at least one service");
                }
            }
            case UNIT_PRICE_CHANGE -> { }   // records only that prices change, and from when (D8)
        }
    }

    /** CTR-02's analogue: an addendum is a document, so it needs its evidence before submission. */
    private void requireSubmittable(Addendum addendum) {
        if (!attachments.existsByOwnerTypeAndOwnerId(EntityType.ADDENDUM, addendum.getId())) {
            throw new UnprocessableEntityException(
                    "Addendum %s has no attachment; at least one is required to submit (CTR-02)"
                            .formatted(addendum.getAddendumNo()));
        }
        if (addendum.getEffectiveFrom().isBefore(addendum.getContract().getValidFrom())) {
            throw new UnprocessableEntityException(
                    "effectiveFrom must not precede the contract's validFrom");
        }
    }

    // --- mapping ------------------------------------------------------------------------------

    private void applyFields(Addendum addendum, AddendumRequest request, ChangeType changeType) {
        addendum.setChangeType(changeType);
        addendum.setDescription(request.description());
        addendum.setEffectiveFrom(request.effectiveFrom());
        // Cleared rather than carried over: a type change from TERM_EXTENSION to PAYMENT_TERMS
        // would otherwise leave a newValidTo that applyEffectsToParent no longer looks at, and
        // a later change back would silently reinstate it.
        addendum.setNewValidTo(changeType == ChangeType.TERM_EXTENSION ? request.newValidTo() : null);
        addendum.setPaymentTermOverride(changeType == ChangeType.PAYMENT_TERMS
                ? RequestValues.blankToNull(request.paymentTermOverride()) : null);
        replaceServices(addendum, request.services());
    }

    /** Wholesale replacement, as for customer contacts: the list is the request's, not a delta. */
    private void replaceServices(Addendum addendum, List<AddendumRequest.ServiceLine> requested) {
        if (requested == null) {
            return;
        }
        addendum.getServices().clear();
        for (AddendumRequest.ServiceLine line : requested) {
            AddendumServiceLine service =
                    AddendumServiceLine.create(line.serviceCode(), line.serviceName());
            service.setServiceItemId(line.serviceItemId());
            service.setUnit(line.unit());
            service.setScopeNote(line.scopeNote());
            addendum.addService(service);
        }
    }

    private Map<String, Object> snapshot(Addendum addendum) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("changeType", addendum.getChangeType());
        fields.put("description", addendum.getDescription());
        fields.put("effectiveFrom", addendum.getEffectiveFrom());
        fields.put("newValidTo", addendum.getNewValidTo());
        fields.put("paymentTermOverride", addendum.getPaymentTermOverride());
        fields.put("services", addendum.getServices().stream()
                .map(s -> s.getServiceCode() + "|" + s.getServiceName())
                .sorted()
                .toList());
        return fields;
    }

    /**
     * The D14d flip: {@code APPROVED → ACTIVE} at {@code effective_from}, with the parent's
     * effects applied in the same transaction (registry §9²). Scheduler-triggered, so trigger
     * kind S — a user cannot activate an addendum by hand any more than a contract.
     */
    @Transactional
    public void activate(UUID id) {
        Addendum addendum = get(id);
        DocumentStatus before = addendum.getStatus();
        transitions.transition(EntityType.ADDENDUM, addendum.getId(), addendum.getAddendumNo(),
                before, DocumentStatus.ACTIVE, TriggerKind.S, null,
                "Effective date reached (D14d)");
        addendum.setStatus(DocumentStatus.ACTIVE);
        // Same transaction by construction: if the effect fails, the flip goes with it, and the
        // parent never keeps its old terms while the addendum claims to be in force.
        applyEffectsToParent(addendum);
    }

    /** Effective dates that have arrived — the D14d sweep's input (item 12). */
    @Transactional(readOnly = true)
    public List<Addendum> dueForActivation(LocalDate onOrBefore) {
        return addenda.findByStatusAndEffectiveFromLessThanEqual(DocumentStatus.APPROVED, onOrBefore);
    }
}
