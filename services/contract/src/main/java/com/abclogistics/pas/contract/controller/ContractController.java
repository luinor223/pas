package com.abclogistics.pas.contract.controller;

import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.dto.CancelRequest;
import com.abclogistics.pas.contract.dto.CancelResponse;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.ContractResponse;
import com.abclogistics.pas.contract.dto.ProgressResponse;
import com.abclogistics.pas.contract.dto.StatusHistoryResponse;
import com.abclogistics.pas.contract.dto.SubmitResponse;
import com.abclogistics.pas.contract.service.ContractCancellationService.Outcome;
import com.abclogistics.pas.contract.service.ContractService;
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

/**
 * Contract endpoints (4.2). Send-for-signing arrives with Phase B item 13.
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
                                       @RequestParam(required = false) String serviceGroup,
                                       @RequestParam(required = false) String q,
                                       @PageableDefault(size = 20) Pageable pageable) {
        return contracts.search(customerId, status, serviceGroup, q, pageable)
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

    /**
     * CTR-02 checks, then D4: the status change and the dispatch intent commit together and a
     * relay makes the remote call. The response says INITIALIZATION_PENDING because that is what
     * has actually happened — nothing has been dispatched yet.
     */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('contract:write')")
    public SubmitResponse submit(@PathVariable UUID id) {
        contracts.submit(id);
        return SubmitResponse.pendingDispatch(DocumentStatus.SUBMITTED.name());
    }

    /**
     * DRAFT and ACTIVE cancel outright; SUBMITTED runs the M2 cancel-vs-dispatch handoff, which
     * may not resolve in one call. 202 means exactly that — nothing was changed and the same call
     * should be retried, because flipping the document to CANCELLED on an inconclusive read is
     * what would let a workflow instance start against an already-cancelled contract.
     *
     * <p>CTR-06's {@code contract:cancel_active} is checked in the service, not here: whether it
     * is required depends on the document's status.
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('contract:write')")
    public ResponseEntity<CancelResponse> cancel(@PathVariable UUID id,
                                                 @RequestBody(required = false) CancelRequest request) {
        Outcome outcome = contracts.cancel(id, request == null ? null : request.reason());
        return ResponseEntity
                .status(outcome == Outcome.CANCELLED ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(CancelResponse.of(outcome));
    }

    /**
     * CTR-04's audited opt-in: a REJECTED contract returns to DRAFT explicitly, and only then can
     * it be edited. It carries no body — reopening and editing are two separate, separately
     * audited acts.
     */
    @PostMapping("/{id}/revise")
    @PreAuthorize("hasAuthority('contract:write')")
    public ContractResponse revise(@PathVariable UUID id) {
        return ContractResponse.of(contracts.revise(id));
    }

    /**
     * Proxies GetInstanceByDocument and returns the snapshot chain that actually ran. An absent
     * instance, or a terminal one left over from a previous submission, is D4's dispatch window
     * rendered as INITIALIZATION_PENDING — not an error, and never retried here.
     */
    @GetMapping("/{id}/progress")
    @PreAuthorize("hasAuthority('contract:read')")
    public ProgressResponse progress(@PathVariable UUID id) {
        return ProgressResponse.of(contracts.progress(id));
    }

    /** The local D17 timeline. Domain data, synchronous — audit-service is the other half. */
    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('contract:read')")
    public List<StatusHistoryResponse> history(@PathVariable UUID id) {
        return contracts.history(id).stream().map(StatusHistoryResponse::of).toList();
    }

    /** CTR-01 + optimistic lock. Editing a REVISION_REQUESTED contract returns it to DRAFT. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('contract:write')")
    public ContractResponse update(@PathVariable UUID id, @Valid @RequestBody ContractRequest request) {
        return ContractResponse.of(contracts.update(id, request));
    }
}
