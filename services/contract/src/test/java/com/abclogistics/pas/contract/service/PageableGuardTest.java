package com.abclogistics.pas.contract.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

class PageableGuardTest {

    @Test
    void omittedAndRejectedSortsUseTheStableDefault() {
        for (var pageable : new PageRequest[] {
                PageRequest.of(0, 20),
                PageRequest.of(0, 20, Sort.by("notAllowed")) }) {
            assertThat(PageableGuard.sanitize(pageable, PageableGuard.CONTRACT_SORTS).getSort())
                    .containsExactly(
                            Sort.Order.desc("createdAt"),
                            Sort.Order.desc("id"));
        }
    }

    @Test
    void everyAllowedExplicitSortGetsAnIdTieBreaker() {
        for (var allowed : java.util.List.of(PageableGuard.CUSTOMER_SORTS,
                PageableGuard.CONTRACT_SORTS, PageableGuard.ADDENDUM_SORTS,
                PageableGuard.ATTACHMENT_SORTS)) {
            for (String property : allowed) {
                assertThat(PageableGuard.sanitize(
                        PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, property)),
                        allowed).getSort())
                        .containsExactly(Sort.Order.asc(property), Sort.Order.desc("id"));
            }
        }
    }

    @Test
    void entitiesWithoutCreatedAtKeepAnUnsortedDefault() {
        assertThat(PageableGuard.sanitize(
                PageRequest.of(0, 20), PageableGuard.ATTACHMENT_SORTS).getSort().isUnsorted())
                .isTrue();
    }
}
