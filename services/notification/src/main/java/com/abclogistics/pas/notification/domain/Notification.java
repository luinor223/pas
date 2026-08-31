package com.abclogistics.pas.notification.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per recipient per event (4.9). {@code title}/{@code body} are write-time snapshots, so a
 * notification stays readable after the source document is renamed or cancelled (db-notification.md).
 */
@Entity
@Table(name = "notification")
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationCategory category;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "document_type")
    private String documentType;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "document_no")
    private String documentNo;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 1000)
    private String body;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() { }

    public static Notification of(UUID recipientUserId, NotificationCategory category,
                                  UUID eventId, String eventType, String documentType,
                                  UUID documentId, String documentNo, String title, String body) {
        Notification n = new Notification();
        n.recipientUserId = recipientUserId;
        n.category = category;
        n.eventId = eventId;
        n.eventType = eventType;
        n.documentType = documentType;
        n.documentId = documentId;
        n.documentNo = documentNo;
        n.title = title;
        n.body = body;
        return n;
    }

    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getRecipientUserId() { return recipientUserId; }
    public NotificationCategory getCategory() { return category; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getDocumentType() { return documentType; }
    public UUID getDocumentId() { return documentId; }
    public String getDocumentNo() { return documentNo; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Instant getReadAt() { return readAt; }
}
