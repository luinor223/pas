package com.abclogistics.pas.workflow.repository;

/** Shared JPQL filters for all three workflow inbox views. Aliases are intentionally wi/si. */
final class InboxQueryFilters {
    static final String COMMON = """
              and (:q is null or lower(wi.documentNo) like :q or lower(coalesce(wi.customerName, '')) like :q
                   or lower(coalesce(si.name, '')) like :q or lower(coalesce(wi.requestedByName, '')) like :q)
              and (:documentType is null or wi.documentTypeCode = :documentType)
              and (:priority is null or wi.priority = :priority)
            """;

    private InboxQueryFilters() {}
}
