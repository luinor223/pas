package com.abclogistics.pas.notification;

import com.abclogistics.pas.notification.domain.NotificationCategory;
import com.abclogistics.pas.notification.service.NotificationCategories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registry §8 gives the bell four tabs, so every event this service consumes must land in
 * exactly one. The coverage rule (§7.2) is per-row of registry §4's Consumers column: if an
 * event is consumed, it is listed here — and if it is listed here, {@link
 * NotificationFanOutPerGroupTest} says who receives it.
 */
class NotificationCategoryMappingTest {

    @ParameterizedTest
    @CsvSource({
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
        assertThat(NotificationCategories.handles(eventType)).isTrue();
    }

    @Test
    void instanceStartedIsNotConsumedBecauseItAddressesNobody() {
        // registry §4 listed notification against it, but its payload carries no assignee_ids
        assertThat(NotificationCategories.handles("workflow.instance_started")).isFalse();
        assertThatThrownBy(() -> NotificationCategories.of("workflow.instance_started"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownEventTypeIsRejectedRatherThanFiledAsSystem() {
        // a silent SYSTEM default would hide a new producer event from whoever added it
        assertThatThrownBy(() -> NotificationCategories.of("billing.something_new"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void auditRecordedIsNotConsumedHere() {
        // it has its own topic and a single consumer (registry §4) — audit-service, not this one
        assertThat(NotificationCategories.handles("audit.recorded")).isFalse();
        assertThatThrownBy(() -> NotificationCategories.of("audit.recorded"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "WORKFLOW.STEP_ASSIGNED", "workflow.step_assigned " })
    void theFilterDoesNotGuessAtNearMisses(String eventType) {
        // the header is verbatim from the producer; a fuzzy match would consume another's event
        assertThat(NotificationCategories.handles(eventType)).isFalse();
    }

    @Test
    void aMissingHeaderIsNotAConsumedEvent() {
        assertThat(NotificationCategories.handles(null)).isFalse();
    }
}
