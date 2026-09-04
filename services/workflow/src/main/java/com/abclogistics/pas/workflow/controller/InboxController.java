package com.abclogistics.pas.workflow.controller;

import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.workflow.dto.InboxResponse;
import com.abclogistics.pas.workflow.service.InboxService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.Locale;

@RestController
@RequestMapping("/inbox")
public class InboxController {

    private final InboxService inboxService;

    public InboxController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public InboxResponse inbox(@RequestParam(defaultValue = "ASSIGNED") String tab,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "15") int size,
                               @RequestParam(required = false) String q,
                               @RequestParam(required = false) String documentType,
                               @RequestParam(required = false) String priority) {
        UUID userId = SecurityUtils.currentUserId();
        if (userId == null) throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        int pageNumber = Math.max(0, page);
        int pageSize = Math.max(1, Math.min(size, 100));
        return switch (tab.toUpperCase(Locale.ROOT)) {
            case "ASSIGNED" -> inboxService.assignedToMe(userId, pageNumber, pageSize, q, documentType, priority);
            case "SUBMITTED" -> inboxService.submittedByMe(userId, pageNumber, pageSize, q, documentType, priority);
            case "COMPLETED" -> inboxService.completed(userId, pageNumber, pageSize, q, documentType, priority);
            default -> throw new IllegalArgumentException("Unknown tab: " + tab + " (use ASSIGNED, SUBMITTED, COMPLETED)");
        };
    }
}
