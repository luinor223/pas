package com.abclogistics.pas.billing.controller;

import com.abclogistics.pas.billing.dto.*;
import com.abclogistics.pas.billing.service.StatementService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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

    @GetMapping("/{statementNo}")
    @PreAuthorize("hasAuthority('statement:read')")
    public StatementResponse getStatement(@PathVariable String statementNo) {
        return statementService.getByStatementNo(statementNo);
    }

    @PostMapping("/calculate")
    @PreAuthorize("hasAuthority('statement:write')")
    @ResponseStatus(HttpStatus.CREATED)
    public StatementResponse calculateStatement(@RequestBody CalculateStatementRequest request) {
        return statementService.calculate(request);
    }

    @PostMapping("/{statementNo}/reconcile")
    @PreAuthorize("hasAuthority('statement:write')")
    public StatementResponse reconcileStatement(@PathVariable String statementNo) {
        return statementService.reconcile(statementNo);
    }

    @PostMapping("/{statementNo}/submit")
    @PreAuthorize("hasAuthority('statement:write')")
    public StatementResponse submitStatement(@PathVariable String statementNo) {
        return statementService.submit(statementNo);
    }

    @PatchMapping("/{statementNo}/lines")
    @PreAuthorize("hasAuthority('statement:write')")
    public StatementResponse editLine(
        @PathVariable String statementNo,
        @RequestBody EditLineRequest request
    ) {
        return statementService.editLine(statementNo, request);
    }

    @PostMapping("/{statementNo}/adjustments")
    @PreAuthorize("hasAuthority('statement:write')")
    @ResponseStatus(HttpStatus.CREATED)
    public StatementResponse createAdjustment(
        @PathVariable String statementNo,
        @RequestBody AdjustmentRequest request
    ) {
        return statementService.createAdjustment(statementNo, request);
    }

    @PostMapping("/{statementNo}/cancel")
    @PreAuthorize("hasAuthority('statement:cancel_approved')")
    public StatementResponse cancelStatement(
        @PathVariable String statementNo,
        @RequestBody(required = false) Map<String, String> body
    ) {
        String reason = body != null ? body.getOrDefault("reason", "") : "";
        return statementService.cancelStatement(statementNo, reason);
    }

    @GetMapping("/{statementNo}/workflow-progress")
    @PreAuthorize("hasAuthority('statement:read')")
    public WorkflowProgressResponse getWorkflowProgress(@PathVariable String statementNo) {
        return statementService.getWorkflowProgress(statementNo);
    }
}
