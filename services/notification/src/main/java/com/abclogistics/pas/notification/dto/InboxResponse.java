package com.abclogistics.pas.notification.dto;

import java.util.List;

/** The bell list plus its unread badge, which the Figma header renders from one call. */
public record InboxResponse(List<NotificationResponse> items, long total, long unreadCount) { }
