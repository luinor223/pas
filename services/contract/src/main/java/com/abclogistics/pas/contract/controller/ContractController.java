package com.abclogistics.pas.contract.controller;

import com.abclogistics.pas.common.api.ApiResponse;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.dto.CancelRequest;
import com.abclogistics.pas.contract.dto.CancelResponse;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.ContractResponse;
import com.abclogistics.pas.contract.dto.ProgressResponse;
import com.abclogistics.pas.contract.dto.StatusHistoryResponse;
import com.abclogistics.pas.contract.dto.SigningRequestStateResponse;
import com.abclogistics.pas.contract.dto.SnapshotPage;
import com.abclogistics.pas.contract.dto.SubmitResponse;
import com.abclogistics.pas.contract.service.DocumentCancellationService.Outcome;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.PageableGuard;
import com.abclogistics.pas.contract.service.PageSnapshot;
import com.abclogistics.pas.contract.service.PageSnapshotCodec;
import jakarta.validation.Valid;
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
    private final PageSnapshotCodec pageSnapshots;

    public ContractController(ContractService contracts, PageSnapshotCodec pageSnapshots) {
        this.contracts = contracts;
        this.pageSnapshots = pageSnapshots;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('contract:read')")
    public ApiResponse<List<ContractResponse>> list(@RequestParam(required = false) UUID customerId,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String serviceGroup,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) String validFromFrom,
                                       @RequestParam(required = false) String validFromTo,
                                       @RequestParam(required = false) String validToFrom,
                                       @RequestParam(required = false) String validToTo,
                                       @RequestParam(required = false) String cursor,
                                       @PageableDefault(size = 20) Pageable pageable) {
        PageSnapshot snapshot = pageSnapshots.resolve(cursor);
        Pageable safe = PageableGuard.sanitize(pageable, PageableGuard.CONTRACT_SORTS);
        return SnapshotPage.of(contracts.search(customerId, status, serviceGroup, q,
                        validFromFrom, validFromTo, validToFrom, validToTo,
                        snapshot.createdAt(), safe).map(ContractResponse::of), snapshot.cursor());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('contract:read')")
    public ContractResponse get(@PathVariable UUID id) {
        return ContractResponse.of(contracts.get(id));
    }

    @GetMapping("/lookup")
    @PreAuthorize("hasAuthority('contract:read')")
    public List<ContractResponse> lookup(@RequestParam List<UUID> ids) {
        return contracts.lookupResponses(ids.stream().distinct().limit(100).toList());
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
    public SigningRequestStateResponse sendForSigning(@PathVariable UUID id) {
        return SigningRequestStateResponse.of(contracts.sendForSigning(id));
    }

    @GetMapping("/{id}/signing-request")
    @PreAuthorize("hasAuthority('contract:read') or hasAuthority('esign:send')")
    public SigningRequestStateResponse signingRequestState(@PathVariable UUID id) {
        return SigningRequestStateResponse.of(contracts.signingRequestState(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('contract:write')")
    public ContractResponse update(@PathVariable UUID id, @Valid @RequestBody ContractRequest request) {
        return ContractResponse.of(contracts.update(id, request));
    }
}
