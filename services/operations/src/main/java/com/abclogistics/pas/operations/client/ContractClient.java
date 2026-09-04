package com.abclogistics.pas.operations.client;

import com.abclogistics.pas.contract.grpc.GetContractResponse;

import java.util.UUID;

public interface ContractClient {
    GetContractResponse getContract(UUID contractId);
}
