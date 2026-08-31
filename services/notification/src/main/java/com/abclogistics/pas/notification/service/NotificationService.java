package com.abclogistics.pas.notification.service;

import com.abclogistics.pas.notification.dto.InboxResponse;
import com.abclogistics.pas.notification.event.EventEnvelope;
import com.abclogistics.pas.notification.repository.NotificationRepository;
import com.abclogistics.pas.notification.repository.ProcessedEventRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * One event becomes one row per recipient, written in the same transaction as the
     * {@code processed_event} row — a redelivery must not double the inbox (D6). An event that
     * resolves to nobody is still marked processed, or it is retried for ever.
     *
     * @return how many notification rows this call wrote; 0 on a redelivery
     */
    @Transactional
    public int fanOut(EventEnvelope event) {
        throw new UnsupportedOperationException("Phase B: fan an event out to its recipients");
    }

    @Transactional(readOnly = true)
    public InboxResponse inbox(UUID recipient, boolean unreadOnly, Pageable pageable) {
        throw new UnsupportedOperationException("Phase B: read the inbox");
    }

    /** Idempotent: re-marking keeps the original {@code read_at}. */
    @Transactional
    public void markRead(UUID id, UUID recipient) {
        throw new UnsupportedOperationException("Phase B: mark one notification read");
    }

    @Transactional
    public long markAllRead(UUID recipient) {
        throw new UnsupportedOperationException("Phase B: bulk mark read");
    }
}
