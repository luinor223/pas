package com.abclogistics.pas.operations.controller;

import com.abclogistics.pas.operations.dto.CreateVolumeRequest;
import com.abclogistics.pas.operations.dto.UpdateVolumeRequest;
import com.abclogistics.pas.operations.dto.VolumeResponse;
import com.abclogistics.pas.operations.dto.VolumePageResponse;
import com.abclogistics.pas.operations.service.VolumeService;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/volume-records")
public class VolumeController {

    private final VolumeService volumeService;

    public VolumeController(VolumeService volumeService) {
        this.volumeService = volumeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('volume:write')")
    public VolumeResponse create(@Valid @RequestBody CreateVolumeRequest request) {
        return volumeService.create(
                request.contractId(),
                request.periodCode(),
                request.serviceCode(),
                request.quantity(),
                request.note());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('volume:read')")
    public VolumePageResponse list(
            @RequestParam(required = false) String periodCode,
            @RequestParam(required = false) UUID contractId,
            @RequestParam(required = false) String serviceCode,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        if (page < 0) throw new IllegalArgumentException("Page cannot be negative");
        return volumeService.search(periodCode, contractId, serviceCode, q, page, Math.max(1, Math.min(size, 100)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('volume:read')")
    public VolumeResponse get(@PathVariable UUID id) {
        return volumeService.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('volume:write')")
    public VolumeResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateVolumeRequest request) {
        return volumeService.update(id, request.quantity(), request.note());
    }
}
