package com.abclogistics.pas.operations.controller;

import com.abclogistics.pas.operations.dto.CreatePeriodRequest;
import com.abclogistics.pas.operations.dto.PeriodResponse;
import com.abclogistics.pas.operations.service.PeriodService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/periods")
@Validated
public class PeriodController {

    private final PeriodService periodService;

    public PeriodController(PeriodService periodService) {
        this.periodService = periodService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('volume:write')")
    public PeriodResponse create(@Valid @RequestBody CreatePeriodRequest request) {
        return periodService.create(request.periodCode());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('volume:read')")
    public List<PeriodResponse> list() {
        return periodService.list();
    }

    @GetMapping("/{periodCode}")
    @PreAuthorize("hasAuthority('volume:read')")
    public PeriodResponse get(@Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "period_code must be YYYY-MM") @PathVariable String periodCode) {
        return periodService.get(periodCode);
    }

    @PostMapping("/{periodCode}/lock")
    @PreAuthorize("hasAuthority('volume:lock_period')")
    public PeriodResponse lock(@Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "period_code must be YYYY-MM") @PathVariable String periodCode) {
        return periodService.lock(periodCode);
    }
}
