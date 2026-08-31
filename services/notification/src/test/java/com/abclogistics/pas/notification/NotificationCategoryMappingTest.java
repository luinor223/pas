package com.abclogistics.pas.notification;

import com.abclogistics.pas.notification.domain.NotificationCategory;
import com.abclogistics.pas.notification.service.NotificationCategories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registry §8 gives the bell four tabs, so every event this service consumes must land in exactly
 * one. The coverage rule (§7.2) is per-row of registry §4's Consumers column: if an event is
 * consumed, it is listed here.
 */
class NotificationCategoryMappingTest {

    @ParameterizedTest
    @CsvSource({
            "workflow.instance_started,  APPROVAL",
            "workflow.step_assigned,     APPROVAL",
            "workflow.step_actioned,     APPROVAL",
            "workflow.completed,         APPROVAL",
            "workflow.step_overdue,      APPROVAL",
            "esign.session_completed,    ESIGN",
            "document.expiring,          EXPIRY",
            "operations.period_locked,   SYSTEM"
    })
    void everyConsumedEventTypeMapsToItsTab(String eventType, NotificationCategory expected) {
        assertThat(NotificationCategories.of(eventType)).isEqualTo(expected);
    }

    @Test
    void anUnknownEventTypeIsRejectedRatherThanFiledAsSystem() {
        // a silent SYSTEM default would hide a new producer event from whoever added it; the
        // listener's header filter is what skips events this service does not handle
        assertThatThrownBy(() -> NotificationCategories.of("billing.something_new"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void auditRecordedIsNotConsumedHere() {
        // it has its own topic and a single consumer (registry §4) — audit-service, not this one
        assertThatThrownBy(() -> NotificationCategories.of("audit.recorded"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
