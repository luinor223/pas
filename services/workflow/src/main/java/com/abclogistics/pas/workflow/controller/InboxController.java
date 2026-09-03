package com.abclogistics.pas.workflow.controller;

import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.workflow.dto.InboxResponse;
import com.abclogistics.pas.workflow.service.InboxService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/inbox")
public class InboxController {

    private final InboxService inboxService;

    public InboxController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @GetMapping
    public InboxResponse inbox(@RequestParam(defaultValue = "ASSIGNED") String tab,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "15") int size,
                               @RequestParam(required = false) String q,
                               @RequestParam(required = false) String documentType,
                               @RequestParam(required = false) String priority) {
        UUID userId = SecurityUtils.currentUserId();
        if (userId == null) throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        if (page < 0) throw new IllegalArgumentException("Page cannot be negative");
        int pageSize = Math.max(1, Math.min(size, 100));
        return switch (tab.toUpperCase()) {
            case "ASSIGNED" -> inboxService.assignedToMe(userId, page, pageSize, q, documentType, priority);
            case "SUBMITTED" -> inboxService.submittedByMe(userId, page, pageSize, q, documentType, priority);
            case "COMPLETED" -> inboxService.completed(userId, page, pageSize, q, documentType, priority);
            default -> throw new IllegalArgumentException("Unknown tab: " + tab + " (use ASSIGNED, SUBMITTED, COMPLETED)");
        };
    }
}
