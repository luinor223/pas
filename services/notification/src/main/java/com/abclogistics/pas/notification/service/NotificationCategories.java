package com.abclogistics.pas.notification.service;

import com.abclogistics.pas.notification.domain.NotificationCategory;

import java.util.Map;

/**
 * {@code event_type} → Figma tab (registry §8). Every event registry §4 lists notification-service
 * against appears here exactly once; nothing else does.
 *
 * <p>{@code workflow.instance_started} is deliberately absent. §4 listed notification as a consumer
 * of it, but its payload carries no {@code assignee_ids}, no {@code requested_by}, no
 * {@code owner_user_id} and no {@code recipient_role} — there is no one to notify. It fires in the
 * same transaction as {@code workflow.step_assigned}, which does address its reviewers, so the row
 * was consumer-list optimism rather than a designed notification and was dropped from §4.
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
     * @throws IllegalArgumentException for an event type this service does not consume. A silent
     *         {@code SYSTEM} default would hide a new producer event from whoever added it.
     */
    public static NotificationCategory of(String eventType) {
        NotificationCategory category = BY_EVENT_TYPE.get(eventType);
        if (category == null) {
            throw new IllegalArgumentException("not a consumed event type: " + eventType);
        }
        return category;
    }
}
