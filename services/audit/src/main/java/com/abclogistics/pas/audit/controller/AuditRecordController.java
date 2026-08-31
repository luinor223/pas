package com.abclogistics.pas.audit.controller;

import com.abclogistics.pas.audit.dto.AuditRecordResponse;
import com.abclogistics.pas.audit.service.AuditQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * The admin UC "Tra cứu audit log" (seq-02). User traffic, so REST rather than gRPC (D16), and
 * gated by `audit:view_all` — held by SYSTEM_ADMIN alone (registry §7).
 */
@RestController
@RequestMapping("/audit-records")
public class AuditRecordController {

    private final AuditQueryService audit;

    public AuditRecordController(AuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit:view_all')")
    public Page<AuditRecordResponse> search(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityNo,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String sourceService,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Pageable pageable) {
        return audit.search(entityType, entityNo, actorId, sourceService, action, from, to, pageable)
                .map(AuditRecordResponse::of);
    }
}
