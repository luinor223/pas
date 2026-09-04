package com.abclogistics.pas.contract.controller;

import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.dto.AddendumRequest;
import com.abclogistics.pas.contract.dto.AddendumResponse;
import com.abclogistics.pas.contract.dto.CancelRequest;
import com.abclogistics.pas.contract.dto.CancelResponse;
import com.abclogistics.pas.contract.dto.ProgressResponse;
import com.abclogistics.pas.contract.dto.StatusHistoryResponse;
import com.abclogistics.pas.contract.dto.SubmitResponse;
import com.abclogistics.pas.contract.service.AddendumService;
import com.abclogistics.pas.contract.service.PageableGuard;
import com.abclogistics.pas.contract.service.DocumentCancellationService.Outcome;
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
@RequestMapping("/addenda")
public class AddendumController {

    private final AddendumService addenda;

    public AddendumController(AddendumService addenda) {
        this.addenda = addenda;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('addendum:read')")
    public Page<AddendumResponse> list(@RequestParam(required = false) UUID contractId,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String changeType,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) String effectiveFromFrom,
                                       @RequestParam(required = false) String effectiveFromTo,
                                       @PageableDefault(size = 20) Pageable pageable) {
        Pageable safe = PageableGuard.sanitize(pageable, PageableGuard.ADDENDUM_SORTS);
        return addenda.search(contractId, status, changeType, q, effectiveFromFrom, effectiveFromTo, safe)
                .map(AddendumResponse::of);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('addendum:read')")
    public AddendumResponse get(@PathVariable UUID id) {
        return AddendumResponse.of(addenda.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('addendum:write')")
    public AddendumResponse create(@Valid @RequestBody AddendumRequest request) {
        return AddendumResponse.of(addenda.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('addendum:write')")
    public AddendumResponse update(@PathVariable UUID id, @Valid @RequestBody AddendumRequest request) {
        return AddendumResponse.of(addenda.update(id, request));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('addendum:write')")
    public SubmitResponse submit(@PathVariable UUID id) {
        addenda.submit(id);
        return SubmitResponse.pendingDispatch(DocumentStatus.SUBMITTED.name());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('addendum:write')")
    public ResponseEntity<CancelResponse> cancel(@PathVariable UUID id,
                                                 @RequestBody(required = false) CancelRequest request) {
        Outcome outcome = addenda.cancel(id, request == null ? null : request.reason());
        return ResponseEntity
                .status(outcome == Outcome.CANCELLED ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(CancelResponse.of(outcome));
    }

    @PostMapping("/{id}/revise")
    @PreAuthorize("hasAuthority('addendum:write')")
    public AddendumResponse revise(@PathVariable UUID id) {
        return AddendumResponse.of(addenda.revise(id));
    }

    @GetMapping("/{id}/progress")
    @PreAuthorize("hasAuthority('addendum:read')")
    public ProgressResponse progress(@PathVariable UUID id) {
        return ProgressResponse.of(addenda.progress(id));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('addendum:read')")
    public List<StatusHistoryResponse> history(@PathVariable UUID id) {
        return addenda.history(id).stream().map(StatusHistoryResponse::of).toList();
    }
}
