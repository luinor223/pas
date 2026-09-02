package com.abclogistics.pas.contract.controller;

import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.dto.CancelRequest;
import com.abclogistics.pas.contract.dto.CancelResponse;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.ContractResponse;
import com.abclogistics.pas.contract.dto.ProgressResponse;
import com.abclogistics.pas.contract.dto.StatusHistoryResponse;
import com.abclogistics.pas.contract.dto.SubmitResponse;
import com.abclogistics.pas.contract.service.DocumentCancellationService.Outcome;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.PageableGuard;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

import java.util.List;
import java.util.UUID;

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
                                       @RequestParam(required = false) String serviceGroup,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) String validFromFrom,
                                       @RequestParam(required = false) String validFromTo,
                                       @RequestParam(required = false) String validToFrom,
                                       @RequestParam(required = false) String validToTo,
                                       @PageableDefault(size = 20) Pageable pageable) {
        Pageable safe = PageableGuard.sanitize(pageable, PageableGuard.CONTRACT_SORTS, PageableGuard.MAX_SIZE);
        return contracts.search(customerId, status, serviceGroup, q,
                        validFromFrom, validFromTo, validToFrom, validToTo, safe)
                .map(ContractResponse::of);
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

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('contract:write')")
    public SubmitResponse submit(@PathVariable UUID id) {
        contracts.submit(id);
        return SubmitResponse.pendingDispatch(DocumentStatus.SUBMITTED.name());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('contract:write')")
    public ResponseEntity<CancelResponse> cancel(@PathVariable UUID id,
                                                 @RequestBody(required = false) CancelRequest request) {
        Outcome outcome = contracts.cancel(id, request == null ? null : request.reason());
        return ResponseEntity
                .status(outcome == Outcome.CANCELLED ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(CancelResponse.of(outcome));
    }

    @PostMapping("/{id}/revise")
    @PreAuthorize("hasAuthority('contract:write')")
    public ContractResponse revise(@PathVariable UUID id) {
        return ContractResponse.of(contracts.revise(id));
    }

    @GetMapping("/{id}/progress")
    @PreAuthorize("hasAuthority('contract:read')")
    public ProgressResponse progress(@PathVariable UUID id) {
        return ProgressResponse.of(contracts.progress(id));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('contract:read')")
    public List<StatusHistoryResponse> history(@PathVariable UUID id) {
        return contracts.history(id).stream().map(StatusHistoryResponse::of).toList();
    }

    /**
     * D10 — accepted and queued, not done: the relay dispatches CreateSigningSession afterwards.
     * 202 rather than 200, and the contract's status is deliberately unchanged (5.5, D14e).
     */
    @PostMapping("/{id}/send-for-signing")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('esign:send')")
    public void sendForSigning(@PathVariable UUID id) {
        contracts.sendForSigning(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('contract:write')")
    public ContractResponse update(@PathVariable UUID id, @Valid @RequestBody ContractRequest request) {
        return ContractResponse.of(contracts.update(id, request));
    }
}
