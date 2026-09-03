package com.abclogistics.pas.notification.dto;

import com.abclogistics.pas.notification.domain.Notification;
import com.abclogistics.pas.notification.domain.NotificationCategory;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(UUID id, NotificationCategory category, String eventType,
                                   String documentType, UUID documentId, String documentNo,
                                   String title, String body, Instant createdAt, Instant readAt) {

    public static NotificationResponse of(Notification n) {
        return new NotificationResponse(n.getId(), n.getCategory(), n.getEventType(),
                n.getDocumentType(), n.getDocumentId(), n.getDocumentNo(),
                n.getTitle(), n.getBody(), n.getCreatedAt(), n.getReadAt());
    }
}
