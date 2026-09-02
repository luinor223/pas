package com.abclogistics.pas.contract.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

public final class PageableGuard {

    private PageableGuard() {}

    public static Pageable sanitize(Pageable pageable, Set<String> allowedSorts, int maxSize) {
        int size = Math.min(pageable.getPageSize(), maxSize);
        if (size < 1) size = 1;
        int page = pageable.getPageNumber() < 0 ? 0 : pageable.getPageNumber();

        Sort filtered = Sort.unsorted();
        for (Sort.Order order : pageable.getSort()) {
            if (allowedSorts.contains(order.getProperty())) {
                filtered = filtered.and(Sort.by(order));
            }
        }
        // default sort if none allowed
        if (filtered.isUnsorted() && !allowedSorts.isEmpty()) {
            // keep unsorted; repository query's ORDER BY will apply if needed, but we want deterministic
        }
        return PageRequest.of(page, size, filtered);
    }

    public static final Set<String> CUSTOMER_SORTS = Set.of("code", "name", "status", "createdAt");
    public static final Set<String> CONTRACT_SORTS = Set.of("contractNo", "validFrom", "validTo", "createdAt", "status", "serviceGroup", "customer.name");
    public static final Set<String> ADDENDUM_SORTS = Set.of("addendumNo", "effectiveFrom", "createdAt", "status", "changeType");
    public static final Set<String> ATTACHMENT_SORTS = Set.of("fileName", "uploadedAt");

    public static final int MAX_SIZE = 100;
}
