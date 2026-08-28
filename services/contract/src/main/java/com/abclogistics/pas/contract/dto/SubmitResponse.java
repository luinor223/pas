package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.service.ContractService;

public record SubmitResponse(String status, String workflowState) {

    public static SubmitResponse pendingDispatch(String status) {
        return new SubmitResponse(status, ContractService.INITIALIZATION_PENDING);
    }
}
