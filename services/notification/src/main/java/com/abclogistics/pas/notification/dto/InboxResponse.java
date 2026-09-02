package com.abclogistics.pas.notification.dto;

import java.util.List;
import java.util.Map;

/** Inbox rows plus unfiltered counters for each UI tab. */
public record InboxResponse(List<NotificationResponse> items, long total,
                            long unreadCount, Map<String, Long> counts) { }
