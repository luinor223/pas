package com.abclogistics.pas.billing.controller.http;

import com.abclogistics.pas.billing.dto.*;
import com.abclogistics.pas.billing.service.StatementService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/payment-statements")
public class StatementController {

    private final StatementService statementService;

    StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('statement:read')")
    public Page<StatementResponse> listStatements(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return statementService.list(page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('statement:read')")
    public StatementResponse getStatement(@PathVariable UUID id) {
        return statementService.getById(id);
    }

    @PostMapping("/calculate")
    @PreAuthorize("hasAuthority('statement:write')")
    @ResponseStatus(HttpStatus.CREATED)
    public StatementResponse calculateStatement(@Valid @RequestBody CalculateStatementRequest request) {
        return statementService.calculate(request);
    }

    @PostMapping("/{id}/recalculate")
    @PreAuthorize("hasAuthority('statement:write')")
    public StatementResponse recalculate(@PathVariable UUID id) {
        return statementService.recalculate(id);
    }

    @PostMapping("/{id}/revise")
    @PreAuthorize("hasAuthority('statement:write')")
    public StatementResponse revise(@PathVariable UUID id) {
        return statementService.revise(id);
    }

    @PostMapping("/{id}/send-sign")
    @PreAuthorize("hasAuthority('statement:write') and hasAuthority('esign:send')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StatementResponse sendForSigning(@PathVariable UUID id) {
        return statementService.sendForSigning(id);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('statement:write')")
    public StatementResponse publish(@PathVariable UUID id) {
        return statementService.publish(id);
    }

    @PostMapping("/{id}/lines")
    @PreAuthorize("hasAuthority('statement:write')")
    @ResponseStatus(HttpStatus.CREATED)
    public StatementResponse addLine(
        @PathVariable UUID id,
        @Valid @RequestBody AddLineRequest request
    ) {
        return statementService.addLine(id, request);
    }

    @PostMapping("/{id}/reconcile")
    @PreAuthorize("hasAuthority('statement:write')")
    public StatementResponse reconcileStatement(@PathVariable UUID id) {
        return statementService.reconcile(id);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('statement:write')")
    public StatementResponse submitStatement(@PathVariable UUID id) {
        return statementService.submit(id);
    }

    @PatchMapping("/{id}/lines")
    @PreAuthorize("hasAuthority('statement:write')")
    public StatementResponse editLine(
        @PathVariable UUID id,
        @Valid @RequestBody EditLineRequest request
    ) {
        return statementService.editLine(id, request);
    }

    @PostMapping("/{id}/adjustments")
    @PreAuthorize("hasAuthority('statement:write')")
    @ResponseStatus(HttpStatus.CREATED)
    public StatementResponse createAdjustment(
        @PathVariable UUID id,
        @Valid @RequestBody AdjustmentRequest request
    ) {
        return statementService.createAdjustment(id, request);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('statement:cancel_approved')")
    public StatementResponse cancelStatement(
        @PathVariable UUID id,
        @RequestBody(required = false) Map<String, String> body
    ) {
        String reason = body != null ? body.getOrDefault("reason", "") : "";
        return statementService.cancelStatement(id, reason);
    }

    @GetMapping("/{id}/workflow-progress")
    @PreAuthorize("hasAuthority('statement:read')")
    public WorkflowProgressResponse getWorkflowProgress(@PathVariable UUID id) {
        return statementService.getWorkflowProgress(id);
    }
}
