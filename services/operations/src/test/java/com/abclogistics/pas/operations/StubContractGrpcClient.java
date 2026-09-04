package com.abclogistics.pas.operations;

import com.abclogistics.pas.contract.grpc.GetContractResponse;
import com.abclogistics.pas.operations.client.ContractClient;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StubContractGrpcClient implements ContractClient {

    private final Map<UUID, GetContractResponse> contracts = new ConcurrentHashMap<>();
    private GetContractResponse defaultResponse;

    public StubContractGrpcClient() {
        defaultResponse = GetContractResponse.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setContractNo("CTR-2026-0001")
                .setStatus("ACTIVE")
                .setValidFrom("2026-01-01")
                .setValidTo("2026-12-31")
                .setServiceGroup("STEVEDORING")
                .setVatRate(0.08)
                .setPaymentTerm("NET30")
                .setCustomerId(UUID.randomUUID().toString())
                .setCustomerName("Test Customer")
                .setCurrency("VND")
                .build();
    }

    @Override
    public GetContractResponse getContract(UUID contractId) {
        return contracts.getOrDefault(contractId, defaultResponse.toBuilder().setId(contractId.toString()).build());
    }

    public void setContract(UUID contractId, GetContractResponse response) {
        contracts.put(contractId, response);
    }

    public void setDefaultContract(GetContractResponse response) {
        this.defaultResponse = response;
    }

    public void clear() {
        contracts.clear();
    }
}
