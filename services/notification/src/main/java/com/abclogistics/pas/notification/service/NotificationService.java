package com.abclogistics.pas.notification.service;

import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.notification.domain.Notification;
import com.abclogistics.pas.notification.domain.NotificationCategory;
import com.abclogistics.pas.notification.dto.NotificationResponse;
import com.abclogistics.pas.notification.dto.InboxResponse;
import com.abclogistics.pas.notification.event.EventEnvelope;
import com.abclogistics.pas.notification.repository.NotificationRepository;
import com.abclogistics.pas.notification.repository.ProcessedEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The fan-out sink and the inbox read model (4.9). */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final ProcessedEventRepository processed;
    private final RecipientResolver recipients;

    public NotificationService(NotificationRepository notifications,
                               ProcessedEventRepository processed,
                               RecipientResolver recipients) {
        this.notifications = notifications;
        this.processed = processed;
        this.recipients = recipients;
    }

    /** Creates one row per recipient and records the event atomically. */
    @Transactional
    public int fanOut(EventEnvelope event) {
        // Zero recipients is still a completed event, so the claim is what marks it processed.
        if (processed.claim(event.eventId()) == 0) {
            return 0;
        }
        NotificationCategory category = NotificationCategories.of(event.eventType());
        NotificationContent content = NotificationText.render(event);
        List<UUID> targets = recipients.recipientsOf(event);
        for (UUID recipient : targets) {
            notifications.save(Notification.of(recipient, category, event.eventId(),
                    event.eventType(), event.documentType(), event.documentId(),
                    documentNo(event), content.title(), content.body()));
        }
        return targets.size();
    }

    /** Returns the filtered inbox and unfiltered tab counters. */
    @Transactional(readOnly = true)
    public InboxResponse inbox(UUID recipient, boolean unreadOnly, NotificationCategory category,
                               Pageable pageable) {
        Page<Notification> page = notifications.inboxOf(recipient, unreadOnly, category, pageable);
        Map<String, Long> counts = counts(recipient);
        return new InboxResponse(page.map(NotificationResponse::of).getContent(),
                page.getTotalElements(), counts.get("unread"), counts);
    }

    private static String documentNo(EventEnvelope event) {
        Object value = event.payload().get("document_no");
        return value == null ? null : value.toString();
    }

    private Map<String, Long> counts(UUID recipient) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (NotificationCategory c : NotificationCategory.values()) {
            counts.put(c.name(), 0L);
        }
        long all = 0;
        for (var row : notifications.countByCategoryFor(recipient)) {
            counts.put(row.getCategory().name(), row.getTotal());
            all += row.getTotal();
        }
        counts.put("all", all);
        counts.put("unread", notifications.countByRecipientUserIdAndReadAtIsNull(recipient));
        return counts;
    }

    /** Re-marking preserves the original {@code read_at}. */
    @Transactional
    public void markRead(UUID id, UUID recipient) {
        notifications.findById(id)
                .filter(n -> n.getRecipientUserId().equals(recipient))
                // 404 rather than 403: the caller must not learn that the id exists
                .orElseThrow(() -> new NotFoundException("Notification not found: " + id));
        notifications.markReadFor(id, recipient, Instant.now());
    }

    @Transactional
    public long markAllRead(UUID recipient) {
        return notifications.markAllReadFor(recipient, Instant.now());
    }
}
