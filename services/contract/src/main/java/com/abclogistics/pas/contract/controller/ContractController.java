package com.abclogistics.pas.contract.controller;

import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.ContractResponse;
import com.abclogistics.pas.contract.service.ContractService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Contract endpoints (4.2). Only the CRUD surface is live; submit, cancel, revise,
 * send-for-signing, progress and history arrive with Phase B items 5-10.
 */
@RestController
@RequestMapping("/contracts")
public class ContractController {

    private final ContractService contracts;

    public ContractController(ContractService contracts) {
        this.contracts = contracts;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('contract:read')")
    public Page<ContractResponse> list(@RequestParam(required = false) UUID customerId,
                                       @RequestParam(required = false) String status,
                                       @PageableDefault(size = 20) Pageable pageable) {
        return contracts.search(customerId, status, pageable).map(ContractResponse::of);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('contract:read')")
    public ContractResponse get(@PathVariable UUID id) {
        return ContractResponse.of(contracts.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('contract:write')")
    public ContractResponse create(@Valid @RequestBody ContractRequest request) {
        return ContractResponse.of(contracts.create(request));
    }

    /** CTR-01 + optimistic lock. Editing a REVISION_REQUESTED contract returns it to DRAFT. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('contract:write')")
    public ContractResponse update(@PathVariable UUID id, @Valid @RequestBody ContractRequest request) {
        return ContractResponse.of(contracts.update(id, request));
    }
}
