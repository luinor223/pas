package com.abclogistics.pas.notification.service;

import com.abclogistics.pas.common.events.MalformedEventException;
import com.abclogistics.pas.notification.domain.NotificationCategory;

import java.util.Map;

/**
 * {@code event_type} → Figma tab (registry §8). Every event registry §4 lists
 * notification-service against appears here exactly once; nothing else does.
 */
public final class NotificationCategories {

    private static final Map<String, NotificationCategory> BY_EVENT_TYPE = Map.of(
            "workflow.step_assigned", NotificationCategory.APPROVAL,
            "workflow.step_actioned", NotificationCategory.APPROVAL,
            "workflow.completed", NotificationCategory.APPROVAL,
            "workflow.step_overdue", NotificationCategory.APPROVAL,
            "esign.session_completed", NotificationCategory.ESIGN,
            "document.expiring", NotificationCategory.EXPIRY,
            "operations.period_locked", NotificationCategory.SYSTEM);

    private NotificationCategories() { }

    /**
     * Whether this service consumes the event type at all — the listener's pre-deserialization
     * filter. Separate from {@link #of} on purpose: a record for another service is routine and
     * skipped, while an event we claim to consume but cannot categorize is a bug.
     */
    public static boolean handles(String eventType) {
        return eventType != null && BY_EVENT_TYPE.containsKey(eventType);
    }

    /**
     * @throws MalformedEventException for an event type this service does not consume — no
     * redelivery makes it categorizable, so it belongs on the DLT. A silent {@code SYSTEM}
     * default would hide a new producer event from whoever added it.
     */
    public static NotificationCategory of(String eventType) {
        NotificationCategory category = BY_EVENT_TYPE.get(eventType);
        if (category == null) {
            throw new MalformedEventException("not a consumed event type: " + eventType);
        }
        return category;
    }
}
