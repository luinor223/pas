package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.Addendum;
import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.CustomerStatus;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.TriggerKind;

import java.math.BigDecimal;

/** Permission- and state-aware actions exposed to document clients. */
record DocumentActionCapabilities(
        boolean canEdit,
        boolean canSubmit,
        String submitBlockedReason,
        boolean canRevise,
        boolean canCancel
) {
    private static final String CONTRACT_WRITE = "contract:write";
    private static final String ADDENDUM_WRITE = "addendum:write";
    private static final String CONTRACT_CANCEL_ACTIVE = "contract:cancel_active";

    static DocumentActionCapabilities forContract(Contract contract, boolean hasAttachment) {
        String blockedReason = contractSubmitBlockedReason(contract, hasAttachment);
        return forDocument(EntityType.CONTRACT, contract.getStatus(), blockedReason);
    }

    static DocumentActionCapabilities forAddendum(Addendum addendum, boolean hasAttachment) {
        Contract parent = addendum.getContract();
        boolean parentAmendable = parent.getStatus() == DocumentStatus.APPROVED
                || parent.getStatus() == DocumentStatus.ACTIVE;
        boolean effectiveDateValid = !addendum.getEffectiveFrom().isBefore(parent.getValidFrom())
                && !addendum.getEffectiveFrom().isAfter(parent.getValidTo());
        String blockedReason = !hasAttachment
                ? "Upload at least one attachment before submitting this addendum for approval."
                : !parentAmendable
                ? "The parent contract must be approved or active before this addendum can be submitted."
                : !effectiveDateValid
                ? "The addendum effective date must fall within the parent contract's current term."
                : null;
        return forDocument(EntityType.ADDENDUM, addendum.getStatus(), blockedReason);
    }

    private static DocumentActionCapabilities forDocument(
            EntityType type, DocumentStatus status, String submitBlockedReason) {
        boolean canWrite = SecurityUtils.hasPermission(
                type == EntityType.CONTRACT ? CONTRACT_WRITE : ADDENDUM_WRITE);
        boolean canCancelActiveContract = type != EntityType.CONTRACT
                || status != DocumentStatus.ACTIVE
                || SecurityUtils.hasPermission(CONTRACT_CANCEL_ACTIVE);

        boolean maySubmit = canWrite && status == DocumentStatus.DRAFT;
        return new DocumentActionCapabilities(
                canWrite && status.isEditable(),
                maySubmit && submitBlockedReason == null,
                maySubmit ? submitBlockedReason : null,
                canWrite && status == DocumentStatus.REJECTED,
                canWrite
                        && canCancelActiveContract
                        && status.canTransitionTo(DocumentStatus.CANCELLED, TriggerKind.U));
    }

    private static String contractSubmitBlockedReason(Contract contract, boolean hasAttachment) {
        BigDecimal vatRate = contract.getVatRate();
        if (!hasAttachment) {
            return "Upload at least one attachment before submitting this contract for approval.";
        }
        if (contract.getCustomer().getStatus() != CustomerStatus.ACTIVE) {
            return "Activate the customer before submitting this contract for approval.";
        }
        if (contract.getValidFrom().isAfter(contract.getValidTo())) {
            return "Correct the contract dates before submitting it for approval.";
        }
        if (vatRate == null || vatRate.compareTo(BigDecimal.ZERO) < 0
                || vatRate.compareTo(new BigDecimal("100")) > 0) {
            return "Enter a VAT rate between 0 and 100 before submitting this contract.";
        }
        if (contract.getPaymentTerm() == null || contract.getPaymentTerm().isBlank()) {
            return "Enter a payment term before submitting this contract for approval.";
        }
        return null;
    }
}
