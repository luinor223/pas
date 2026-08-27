package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.security.SecurityUtils;
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

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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

    @Transactional(readOnly = true)
    public Page<Contract> search(UUID customerId, String status, Pageable pageable) {
        DocumentStatus parsed = status == null || status.isBlank()
                ? null : DocumentStatus.valueOf(status);
        return contracts.search(customerId, parsed, pageable);
    }

    @Transactional(readOnly = true)
    public Contract get(UUID id) {
        return contracts.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract %s not found".formatted(id)));
    }

    @Transactional
    public Contract create(ContractRequest request) {
        validateWindow(request);
        Customer customer = customers.get(request.customerId());

        Contract contract = Contract.create(
                numbers.nextDocumentNo(EntityType.CONTRACT, request.validFrom().getYear()),
                customer,
                ServiceGroup.valueOf(request.serviceGroup()),
                request.validFrom(), request.validTo());
        applyFields(contract, request);
        SecurityUtils.currentUser().ifPresent(user -> {
            contract.setCreatedBy(user.userId());
            contract.setCreatedByName(user.fullName());
            contract.setCreatedByDepartment(user.department());
        });
        contracts.save(contract);

        audit.record(EntityType.CONTRACT.name(), contract.getId(), contract.getContractNo(),
                "CREATE", null, contract.getStatus().name(), null,
                Map.of("customerId", customer.getId().toString()));
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
        validateWindow(request);

        if (!Objects.equals(contract.getCustomer().getId(), request.customerId())) {
            // A DRAFT may be re-pointed at another customer; anything past DRAFT is unreachable
            // here because the CTR-01 guard above already refused it.
            Customer replacement = customers.get(request.customerId());
            audit.record(EntityType.CONTRACT.name(), contract.getId(), contract.getContractNo(),
                    "REASSIGN_CUSTOMER", null, null, null,
                    Map.of("from", contract.getCustomer().getCode(), "to", replacement.getCode()));
            contract.setCustomer(replacement);
        }
        applyFields(contract, request);
        SecurityUtils.currentUser().ifPresent(user -> {
            contract.setUpdatedBy(user.userId());
            contract.setUpdatedByName(user.fullName());
        });

        if (before == DocumentStatus.REVISION_REQUESTED) {
            transitions.transition(EntityType.CONTRACT, contract.getId(), contract.getContractNo(),
                    before, DocumentStatus.DRAFT, TriggerKind.U, null,
                    "Returned to DRAFT by being edited after a revision request");
            contract.setStatus(DocumentStatus.DRAFT);
        } else {
            audit.record(EntityType.CONTRACT.name(), contract.getId(), contract.getContractNo(),
                    "UPDATE", null, null, null, Map.of());
        }
        return contract;
    }

    private void applyFields(Contract contract, ContractRequest request) {
        contract.setDescription(request.description());
        contract.setServiceGroup(ServiceGroup.valueOf(request.serviceGroup()));
        contract.setValue(request.value());
        if (request.currency() != null) {
            contract.setCurrency(request.currency());
        }
        contract.setValidFrom(request.validFrom());
        contract.setValidTo(request.validTo());
        contract.setPaymentTerm(request.paymentTerm());
        if (request.billingCycle() != null) {
            contract.setBillingCycle(request.billingCycle());
        }
        // vatRate is copied as-is, null included: null means "not stated" and must never become 0.
        contract.setVatRate(request.vatRate());
        contract.setPenaltyTerms(request.penaltyTerms());
        contract.setServiceClause(request.serviceClause());
    }

    private void validateWindow(ContractRequest request) {
        if (request.validFrom().isAfter(request.validTo())) {
            throw new UnprocessableEntityException(
                    "validFrom must not be after validTo (CTR-02)");
        }
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
