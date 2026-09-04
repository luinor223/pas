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
import com.abclogistics.pas.contract.domain.StatusHistory;
import com.abclogistics.pas.contract.domain.TriggerKind;
import com.abclogistics.pas.contract.dto.AddendumRequest;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.event.WorkflowStartRequested;
import com.abclogistics.pas.contract.repository.AddendumRepository;
import com.abclogistics.pas.contract.repository.AttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Addendum lifecycle (4.3). Same status machine and D4 submit as a contract. */
@Service
public class AddendumService {

    private static final Logger log = LoggerFactory.getLogger(AddendumService.class);

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
    private final TransactionTemplate tx;
    private final SigningRequestService signingRequests;

    public AddendumService(AddendumRepository addenda, ContractService contracts,
                           DocumentNumberService numbers, StatusTransitionService transitions,
                           AttachmentRepository attachments, WorkflowGrpcClient workflow,
                           OutboxRepository outbox, ObjectMapper objectMapper, AuditRecorder audit,
                           DocumentCancellationService cancellation, TransactionTemplate tx,
                           SigningRequestService signingRequests) {
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
        this.tx = tx;
        this.signingRequests = signingRequests;
    }

    @Transactional(readOnly = true)
    public Page<Addendum> search(UUID contractId, String status, String changeType, String q,
                                 String effectiveFromFrom, String effectiveFromTo, Pageable pageable) {
        return addenda.search(contractId,
                RequestValues.parseOptional("status", status,
                        DocumentStatus::valueOf, DocumentStatus.values()),
                RequestValues.parseOptional("changeType", changeType,
                        ChangeType::valueOf, ChangeType.values()),
                RequestValues.likePattern(q),
                RequestValues.parseOptionalDate("effectiveFromFrom", effectiveFromFrom),
                RequestValues.parseOptionalDate("effectiveFromTo", effectiveFromTo),
                pageable);
    }

    @Transactional(readOnly = true)
    public Page<Addendum> search(UUID contractId, String status, Pageable pageable) {
        return search(contractId, status, null, null, null, null, pageable);
    }

    @Transactional(readOnly = true)
    public Addendum get(UUID id) {
        return addenda.findById(id)
                .orElseThrow(() -> new NotFoundException("Addendum %s not found".formatted(id)));
    }

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
        RecordStamp.creator(addendum);
        Addendum saved = addenda.save(addendum);

        audit.record(EntityType.ADDENDUM.name(), saved.getId(), saved.getAddendumNo(),
                "CREATE", null, DocumentStatus.DRAFT.name(), null,
                Map.of("contractNo", parent.getContractNo(), "changeType", changeType.name()));
        return saved;
    }

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
        if (!Objects.equals(addendum.getContract().getId(), request.contractId())) {
            throw new UnprocessableEntityException(
                    ("Addendum %s belongs to contract %s and cannot be moved to %s; "
                            + "create a new addendum against the other contract instead")
                            .formatted(addendum.getAddendumNo(),
                                    addendum.getContract().getId(), request.contractId()));
        }
        ChangeType changeType = RequestValues.parseRequired("changeType", request.changeType(),
                ChangeType::valueOf, ChangeType.values());
        validate(changeType, request, addendum.getContract());

        Map<String, Object> was = snapshot(addendum);
        applyFields(addendum, request, changeType);
        RecordStamp.editor(addendum);
        audit.record(EntityType.ADDENDUM.name(), addendum.getId(), addendum.getAddendumNo(),
                "UPDATE", null, null, null, FieldDiff.between(was, snapshot(addendum)));

        if (before == DocumentStatus.REVISION_REQUESTED) {
            transitions.transition(addendum, DocumentStatus.DRAFT, TriggerKind.U, null,
                    "Returned to DRAFT by being edited after a revision request");
        }
        return addendum;
    }

    /** D4 submit — the same two-phase shape as {@link ContractService#submit(UUID)}. */
    public void submit(UUID id) {
        ContractService.requireNoTransaction();
        tx.executeWithoutResult(s -> requireSubmittable(requireDraft(id)));

        workflow.validateStartable(DOCUMENT_TYPE);

        tx.executeWithoutResult(s -> commitSubmission(id));
    }

    private Addendum requireDraft(UUID id) {
        Addendum addendum = get(id);
        if (addendum.getStatus() != DocumentStatus.DRAFT) {
            throw new ConflictException(
                    "Addendum %s is %s; only a DRAFT can be submitted"
                            .formatted(addendum.getAddendumNo(), addendum.getStatus()));
        }
        return addendum;
    }

    private Addendum requireDraftForUpdate(UUID id) {
        Addendum addendum = addenda.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Addendum %s not found".formatted(id)));
        if (addendum.getStatus() != DocumentStatus.DRAFT) {
            throw new ConflictException(
                    "Addendum %s is %s; only a DRAFT can be submitted"
                            .formatted(addendum.getAddendumNo(), addendum.getStatus()));
        }
        return addendum;
    }

    private void commitSubmission(UUID id) {
        Addendum addendum = requireDraftForUpdate(id);
        requireSubmittable(addendum);

        transitions.transition(addendum, DocumentStatus.SUBMITTED, TriggerKind.U, null,
                "Submitted for approval");

        // submit is an authenticated endpoint, and the instance records who asked for it
        AuthenticatedUser actor = SecurityUtils.currentUser().orElseThrow(
                () -> new IllegalStateException("submit requires an authenticated user"));
        WorkflowStartRequested payload = new WorkflowStartRequested(
                UUID.randomUUID(), DOCUMENT_TYPE, addendum.getId(), addendum.getAddendumNo(),
                addendum.getContract().getCustomer().getName(), "NORMAL",
                actor.userId(), actor.fullName());
        outbox.save(OutboxEvent.event(WorkflowStartRequested.EVENT_TYPE,
                EntityType.ADDENDUM.name(), addendum.getId(),
                objectMapper.writeValueAsString(payload)));
    }

    public DocumentCancellationService.Outcome cancel(UUID id, String reason) {
        return cancellation.cancel(EntityType.ADDENDUM, id, reason);
    }

    @Transactional
    public Addendum revise(UUID id) {
        Addendum addendum = get(id);
        DocumentStatus before = addendum.getStatus();
        if (before != DocumentStatus.REJECTED) {
            throw new ConflictException(
                    "Addendum %s is %s; only a REJECTED addendum is revised (CTR-04)"
                            .formatted(addendum.getAddendumNo(), before));
        }
        transitions.transition(addendum, DocumentStatus.DRAFT, TriggerKind.U, null,
                "Reopened for revision after rejection (CTR-04)");
        return addendum;
    }

    @Transactional
    public SigningRequestService.State sendForSigning(UUID id) {
        Addendum addendum = addenda.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Addendum %s not found".formatted(id)));
        signingRequests.queue(addendum, addendum.getContract().getCustomer());
        return signingRequests.state(addendum);
    }

    @Transactional(readOnly = true)
    public SigningRequestService.State signingRequestState(UUID id) {
        return signingRequests.state(get(id));
    }

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

    @Transactional(readOnly = true)
    public List<StatusHistory> history(UUID id) {
        get(id);
        return transitions.history(EntityType.ADDENDUM, id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void applyEffectsToParent(Addendum addendum) {
        // asserted, not just annotated: the proxy does not see calls from inside this class
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "applyEffectsToParent must run in the addendum's own transaction");
        }
        Contract parent = addendum.getContract();
        // re-checked: a contract cancelled or expired mid-approval must not be rewritten
        requireAmendable(parent);

        Map<String, Object> was = Map.of(
                "validTo", parent.getValidTo(),
                "paymentTerm", String.valueOf(parent.getPaymentTerm()));

        boolean superseded = false;
        switch (addendum.getChangeType()) {
            // addenda activate in effective-date order, so valid_to only ever moves forward
            case TERM_EXTENSION -> {
                if (addendum.getNewValidTo().isAfter(parent.getValidTo())) {
                    parent.setValidTo(addendum.getNewValidTo());
                } else {
                    // already covered by a longer extension; refusing would wedge it APPROVED
                    superseded = true;
                    log.info("Addendum {} extends {} to {}, but the contract already runs to {};"
                                    + " activating with no term change",
                            addendum.getAddendumNo(), parent.getContractNo(),
                            addendum.getNewValidTo(), parent.getValidTo());
                }
            }
            case PAYMENT_TERMS -> parent.setPaymentTerm(addendum.getPaymentTermOverride());
            case UNIT_PRICE_CHANGE -> { }
            // record only: contract.serviceGroup stays the single enforced scope value
            case ADDED_SERVICE -> { }
        }

        Map<String, Object> now = Map.of(
                "validTo", parent.getValidTo(),
                "paymentTerm", String.valueOf(parent.getPaymentTerm()));
        Map<String, Object> changes = FieldDiff.between(was, now);
        if (superseded) {
            // its own row: "extended by nothing" is not the same fact as "no effect"
            audit.record(EntityType.CONTRACT.name(), parent.getId(), parent.getContractNo(),
                    "ADDENDUM_SUPERSEDED", null, null,
                    "Addendum %s activated with no term change".formatted(addendum.getAddendumNo()),
                    Map.of("newValidTo", String.valueOf(addendum.getNewValidTo()),
                            "contractValidTo", String.valueOf(parent.getValidTo())));
            return;
        }
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

    private void validate(ChangeType changeType, AddendumRequest request, Contract parent) {
        switch (changeType) {
            case TERM_EXTENSION -> {
                if (request.newValidTo() == null) {
                    throw new UnprocessableEntityException(
                            "newValidTo is required for a TERM_EXTENSION addendum (D14b renewal)");
                }
                // before the extension rule: self-contradictory dates answered on their own terms
                if (request.effectiveFrom().isAfter(request.newValidTo())) {
                    throw new UnprocessableEntityException(
                            ("effectiveFrom (%s) must not be after newValidTo (%s); an addendum "
                                    + "cannot take effect after the expiry date it sets")
                                    .formatted(request.effectiveFrom(), request.newValidTo()));
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
        requireWithinParentValidity(request.effectiveFrom(), parent);
    }

    private void requireWithinParentValidity(LocalDate effectiveFrom, Contract parent) {
        if (effectiveFrom.isBefore(parent.getValidFrom())) {
            throw new UnprocessableEntityException(
                    ("effectiveFrom (%s) precedes the contract's validFrom (%s)")
                            .formatted(effectiveFrom, parent.getValidFrom()));
        }
        if (effectiveFrom.isAfter(parent.getValidTo())) {
            throw new UnprocessableEntityException(
                    ("effectiveFrom (%s) is after the contract's current validTo (%s); the "
                            + "contract would already have expired, and renewing an EXPIRED "
                            + "contract is not an allowed transition")
                            .formatted(effectiveFrom, parent.getValidTo()));
        }
    }

    private void requireSubmittable(Addendum addendum) {
        if (!attachments.existsByOwnerTypeAndOwnerId(EntityType.ADDENDUM, addendum.getId())) {
            throw new UnprocessableEntityException(
                    "Addendum %s has no attachment; at least one is required to submit (CTR-02)"
                            .formatted(addendum.getAddendumNo()));
        }
        requireAmendable(addendum.getContract());
        requireWithinParentValidity(addendum.getEffectiveFrom(), addendum.getContract());
    }

    // --- mapping ------------------------------------------------------------------------------

    private void applyFields(Addendum addendum, AddendumRequest request, ChangeType changeType) {
        addendum.setChangeType(changeType);
        addendum.setDescription(request.description());
        addendum.setEffectiveFrom(request.effectiveFrom());
        // cleared on retype, or a later retype back would silently reinstate a stale value
        addendum.setNewValidTo(changeType == ChangeType.TERM_EXTENSION ? request.newValidTo() : null);
        addendum.setPaymentTermOverride(changeType == ChangeType.PAYMENT_TERMS
                ? RequestValues.blankToNull(request.paymentTermOverride()) : null);
        // only ADDED_SERVICE carries service lines; retyping away drops them
        replaceServices(addendum,
                changeType == ChangeType.ADDED_SERVICE ? request.services() : List.of());
    }

    private void replaceServices(Addendum addendum, List<AddendumRequest.ServiceLine> requested) {
        if (requested == null) {
            return;
        }
        addendum.getServices().clear();
        // flush delete before inserts, or a re-added service_code hits uq_addendum_service_code
        addenda.flush();
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
        // structured, not stringified, so a real null stays distinct from the literal "null";
        // sorted by service_code so a reorder of the request's list is not a change
        fields.put("services", addendum.getServices().stream()
                .sorted(java.util.Comparator.comparing(AddendumServiceLine::getServiceCode))
                .map(AddendumService::describe)
                .toList());
        return fields;
    }

    /**
     * APPROVED to ACTIVE at effective_from, effects applied to the parent in the same transaction
     * (§9²). The guard is for a stale candidate read outside this transaction — {@code @Version}
     * covers the concurrent case; this only keeps the sequential one quiet.
     */
    @Transactional
    public void activate(UUID id) {
        Addendum addendum = get(id);
        DocumentStatus before = addendum.getStatus();
        if (before != DocumentStatus.APPROVED) {
            return;
        }
        transitions.transition(addendum, DocumentStatus.ACTIVE, TriggerKind.S, null,
                "Effective date reached (D14d)");
        // same transaction: if the effect fails, the flip goes with it
        applyEffectsToParent(addendum);
    }

    private static Map<String, Object> describe(AddendumServiceLine line) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("serviceItemId", line.getServiceItemId());
        fields.put("serviceCode", line.getServiceCode());
        fields.put("serviceName", line.getServiceName());
        fields.put("unit", line.getUnit());
        fields.put("scopeNote", line.getScopeNote());
        return fields;
    }

    @Transactional(readOnly = true)
    public List<Addendum> dueForActivation(LocalDate onOrBefore) {
        return addenda.dueForActivation(DocumentStatus.APPROVED, onOrBefore);
    }
}
