package com.abclogistics.pas.notification.controller;

import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.notification.domain.NotificationCategory;
import com.abclogistics.pas.notification.dto.InboxResponse;
import com.abclogistics.pas.notification.service.NotificationService;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** REST API for the caller's own notification inbox. */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('notification:read')")
    public InboxResponse list(@RequestParam(defaultValue = "false") boolean unread,
                              @RequestParam(required = false) NotificationCategory category,
                              Pageable pageable) {
        return notifications.inbox(SecurityUtils.currentUserId(), unread, category, pageable);
    }

    @GetMapping("/unread-count")
    @PreAuthorize("hasAuthority('notification:read')")
    public Map<String, Long> unreadCount() {
        return Map.of("unreadCount", notifications.unreadCount(SecurityUtils.currentUserId()));
    }

    @PatchMapping("/{id}/read")
    @PreAuthorize("hasAuthority('notification:read')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable UUID id) {
        notifications.markRead(id, SecurityUtils.currentUserId());
    }

    @PatchMapping("/read-all")
    @PreAuthorize("hasAuthority('notification:read')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead() {
        notifications.markAllRead(SecurityUtils.currentUserId());
    }
}
