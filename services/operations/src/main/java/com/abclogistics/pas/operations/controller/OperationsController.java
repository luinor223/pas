package com.abclogistics.pas.operations.controller;

import com.abclogistics.pas.operations.dto.*;
import com.abclogistics.pas.operations.service.PeriodService;
import com.abclogistics.pas.operations.service.VolumeService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OperationsController {

    private final PeriodService periodService;
    private final VolumeService volumeService;

    OperationsController(PeriodService periodService, VolumeService volumeService) {
        this.periodService = periodService;
        this.volumeService = volumeService;
    }

    @GetMapping("/periods")
    @PreAuthorize("hasAuthority('period:list')")
    public PageResponse<PeriodResponse> listPeriods(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return periodService.list(page, size);
    }

    @PostMapping("/periods")
    @PreAuthorize("hasAuthority('period:create')")
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodResponse createPeriod(@RequestBody CreatePeriodRequest request) {
        return periodService.create(request);
    }

    @PutMapping("/periods/{periodCode}/lock")
    @PreAuthorize("hasAuthority('period:lock')")
    public PeriodResponse lockPeriod(@PathVariable String periodCode) {
        return periodService.lock(periodCode);
    }

    @GetMapping("/periods/{periodCode}/volumes")
    @PreAuthorize("hasAuthority('volume:list')")
    public PageResponse<VolumeResponse> listVolumes(
        @PathVariable String periodCode,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return volumeService.list(periodCode, page, size);
    }

    @GetMapping("/periods/{periodCode}/volumes/contract/{contractCode}")
    @PreAuthorize("hasAuthority('volume:list')")
    public java.util.List<VolumeResponse> listVolumesByContract(
        @PathVariable String periodCode,
        @PathVariable String contractCode
    ) {
        return volumeService.listByContract(periodCode, contractCode);
    }

    @PostMapping("/periods/{periodCode}/volumes")
    @PreAuthorize("hasAuthority('volume:create')")
    @ResponseStatus(HttpStatus.CREATED)
    public VolumeResponse createVolume(
        @PathVariable String periodCode,
        @RequestBody CreateVolumeRequest request
    ) {
        return volumeService.create(periodCode, request);
    }

    @PutMapping("/periods/{periodCode}/volumes/{id}")
    @PreAuthorize("hasAuthority('volume:edit')")
    public VolumeResponse updateVolume(
        @PathVariable String periodCode,
        @PathVariable Long id,
        @RequestBody UpdateVolumeRequest request
    ) {
        return volumeService.update(id, periodCode, request);
    }

    @DeleteMapping("/periods/{periodCode}/volumes/{id}")
    @PreAuthorize("hasAuthority('volume:delete')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVolume(
        @PathVariable String periodCode,
        @PathVariable Long id
    ) {
        volumeService.delete(id, periodCode);
    }
}
