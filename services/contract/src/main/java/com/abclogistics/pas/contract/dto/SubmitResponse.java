package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.service.ContractService;

/**
 * The submit response (4.2). Two fields because they answer two different questions, and D14e
 * forbids collapsing them: {@code status} is the document's own status, {@code workflowState} is
 * what the approval engine is doing.
 *
 * <p>{@code workflowState} is always INITIALIZATION_PENDING here — that is D4, not a lookup. The
 * dispatch is queued in the outbox and has not run yet, so there is no instance to report.
 */
public record SubmitResponse(String status, String workflowState) {

    public static SubmitResponse pendingDispatch(String status) {
        return new SubmitResponse(status, ContractService.INITIALIZATION_PENDING);
    }
}
