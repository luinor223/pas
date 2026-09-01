package com.abclogistics.pas.notification.dto;

import java.util.List;
import java.util.Map;

/**
 * The bell list plus the tab counters the Figma header renders (14-notifications.png: All 32,
 * Unread 8, Approvals 5, E-signature 3, Expiring 4), so one call fills the whole screen.
 *
 * @param counts rows each tab would show, keyed by {@code all}, {@code unread} and category name.
 *               Unfiltered by this request's own filters — a tab's badge must not change because
 *               that tab is the one currently open.
 */
public record InboxResponse(List<NotificationResponse> items, long total,
                            long unreadCount, Map<String, Long> counts) { }
