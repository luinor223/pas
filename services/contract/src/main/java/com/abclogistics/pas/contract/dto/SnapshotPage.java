package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.common.api.ApiResponse;
import org.springframework.data.domain.Page;

import java.util.List;

public final class SnapshotPage {
    private SnapshotPage() { }

    public record Meta(int page, int size, long totalElements, int totalPages, String cursor) { }

    public static <T> ApiResponse<List<T>> of(Page<T> page, String cursor) {
        return ApiResponse.of(page.getContent(), new Meta(page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), cursor));
    }
}
