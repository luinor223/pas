package com.abclogistics.pas.contract.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

public final class PageableGuard {

    private PageableGuard() {}

    /** Clamp page index and whitelist sort fields. Size cap lives in application.yml (max-page-size). */
    public static Pageable sanitize(Pageable pageable, Set<String> allowedSorts) {
        int page = pageable.getPageNumber() < 0 ? 0 : pageable.getPageNumber();

        Sort filtered = Sort.unsorted();
        for (Sort.Order order : pageable.getSort()) {
            if (allowedSorts.contains(order.getProperty())) {
                filtered = filtered.and(Sort.by(order));
            }
        }
        // Public sort fields are not unique. Stabilize every explicit ordering across page
        // boundaries without exposing UUID sorting as a public list option.
        if (filtered.isSorted()) {
            filtered = filtered.and(Sort.by(Sort.Direction.DESC, "id"));
        }
        // Invalid and omitted sorts get a stable newest-first default where the entity supports it.
        if (filtered.isUnsorted() && allowedSorts.contains("createdAt")) {
            filtered = Sort.by(Sort.Direction.DESC, "createdAt")
                    .and(Sort.by(Sort.Direction.DESC, "id"));
        }
        return PageRequest.of(page, pageable.getPageSize(), filtered);
    }

    public static final Set<String> CUSTOMER_SORTS = Set.of("code", "name", "status", "createdAt");
    public static final Set<String> CONTRACT_SORTS = Set.of("contractNo", "validFrom", "validTo", "createdAt", "updatedAt", "status", "serviceGroup", "customer.name");
    public static final Set<String> ADDENDUM_SORTS = Set.of("addendumNo", "effectiveFrom", "createdAt", "updatedAt", "status", "changeType");
    public static final Set<String> ATTACHMENT_SORTS = Set.of("fileName", "uploadedAt");
}
