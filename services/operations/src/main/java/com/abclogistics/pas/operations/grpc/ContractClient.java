package com.abclogistics.pas.operations.grpc;

import com.abclogistics.pas.contract.grpc.GetContractResponse;

import java.util.UUID;

public interface ContractClient {
    GetContractResponse getContract(UUID contractId);
}
