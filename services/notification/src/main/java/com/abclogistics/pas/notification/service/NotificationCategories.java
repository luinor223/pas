package com.abclogistics.pas.notification.service;

import com.abclogistics.pas.notification.domain.NotificationCategory;

/** event_type -> Figma tab (registry §8). Phase B fills this in. */
public final class NotificationCategories {

    private NotificationCategories() { }

    public static NotificationCategory of(String eventType) {
        throw new UnsupportedOperationException("Phase B: map event_type to a category");
    }
}
