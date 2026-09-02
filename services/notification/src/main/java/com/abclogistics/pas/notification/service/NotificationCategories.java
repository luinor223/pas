package com.abclogistics.pas.notification.service;

import com.abclogistics.pas.common.events.MalformedEventException;
import com.abclogistics.pas.notification.domain.NotificationCategory;

import java.util.Map;

/** Maps consumed event types to inbox categories. */
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

    /** Used by the listener before payload deserialization. */
    public static boolean handles(String eventType) {
        return eventType != null && BY_EVENT_TYPE.containsKey(eventType);
    }

    /** Fails instead of silently assigning an unknown event to {@code SYSTEM}. */
    public static NotificationCategory of(String eventType) {
        NotificationCategory category = BY_EVENT_TYPE.get(eventType);
        if (category == null) {
            throw new MalformedEventException("not a consumed event type: " + eventType);
        }
        return category;
    }
}
