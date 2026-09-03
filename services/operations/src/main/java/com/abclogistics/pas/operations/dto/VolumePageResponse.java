package com.abclogistics.pas.operations.dto;

import java.util.List;

public record VolumePageResponse(
        List<VolumeResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {}
