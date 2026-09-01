package com.abclogistics.pas.notification;

import com.abclogistics.pas.common.events.MalformedEventException;
import com.abclogistics.pas.notification.repository.NotificationRepository;
import com.abclogistics.pas.notification.repository.ProcessedEventRepository;
import com.abclogistics.pas.notification.service.IdentityGrpcClient;
import com.abclogistics.pas.notification.service.NotificationService;
import com.abclogistics.pas.notification.service.RecipientResolver;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The difference between "this event addresses nobody" and "the producer got the recipient
 * wrong". The first is an answer and is marked processed; the second is a defect, and marking it
 * processed would destroy the only copy — no retry, no DLT, no trace.
 */
class MalformedRecipientReachesTheDltTest {

    private NotificationRepository notifications;
    private ProcessedEventRepository processed;
    private IdentityGrpcClient identity;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationRepository.class);
        processed = mock(ProcessedEventRepository.class);
        identity = mock(IdentityGrpcClient.class);
        service = new NotificationService(notifications, processed, new RecipientResolver(identity));
    }

    @Test
    void aRequestedByThatIsNotAUuidIsNotSilentlyDropped() {
        // the exact regression: workflow-service put "Nguyen Van A" in this field
        ConsumerRecord<String, String> record = EventFixtures.withPayloadField(
                EventFixtures.completed(UUID.randomUUID(), UUID.randomUUID(), "APPROVED"),
                "requested_by", "Nguyen Van A");

        assertThatThrownBy(() -> fanOut(record)).isInstanceOf(MalformedEventException.class);
        nothingWasWritten();
    }

    @Test
    void aMissingRequestedByIsMalformedToo() {
        ConsumerRecord<String, String> record = EventFixtures.withPayloadField(
                EventFixtures.completed(UUID.randomUUID(), UUID.randomUUID(), "APPROVED"),
                "requested_by", null);

        assertThatThrownBy(() -> fanOut(record)).isInstanceOf(MalformedEventException.class);
        nothingWasWritten();
    }

    @Test
    void oneBadIdInAnAssigneeArrayFailsTheWholeEvent() {
        // notifying the other two and losing the third would look like success
        ConsumerRecord<String, String> record = EventFixtures.withPayloadField(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID())),
                "assignee_ids", List.of(UUID.randomUUID().toString(), "not-a-uuid"));

        assertThatThrownBy(() -> fanOut(record)).isInstanceOf(MalformedEventException.class);
        nothingWasWritten();
    }

    @Test
    void anAbsentAssigneeArrayIsMalformed() {
        ConsumerRecord<String, String> record = EventFixtures.withPayloadField(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID())),
                "assignee_ids", null);

        assertThatThrownBy(() -> fanOut(record)).isInstanceOf(MalformedEventException.class);
        nothingWasWritten();
    }

    @Test
    void anAbsentOwnerOnAnExpiryWarningIsMalformed() {
        ConsumerRecord<String, String> record = EventFixtures.withPayloadField(
                EventFixtures.documentExpiring(UUID.randomUUID(), "2026-12-31", UUID.randomUUID()),
                "owner_user_id", "");

        assertThatThrownBy(() -> fanOut(record)).isInstanceOf(MalformedEventException.class);
        nothingWasWritten();
    }

    @Test
    void aPeriodLockWithNoRoleIsMalformed() {
        ConsumerRecord<String, String> record = EventFixtures.withPayloadField(
                EventFixtures.periodLocked("2026-08", "ACCOUNTANT"), "recipient_role", null);

        assertThatThrownBy(() -> fanOut(record)).isInstanceOf(MalformedEventException.class);
        nothingWasWritten();
    }

    @Test
    void anEmptyAssigneeArrayIsAnAnswerNotADefect() {
        // the producer said "nobody holds this step", which is a claim, not a broken field
        ConsumerRecord<String, String> record = EventFixtures.withPayloadField(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID())),
                "assignee_ids", List.of());

        assertThat(fanOut(record)).isZero();
        verify(processed).save(any());
    }

    @Test
    void aRoleNobodyHoldsIsAnAnswerNotADefect() {
        when(identity.listUsersByRole("ACCOUNTANT")).thenReturn(List.of());

        assertThat(fanOut(EventFixtures.periodLocked("2026-08", "ACCOUNTANT"))).isZero();
        verify(processed).save(any());
    }

    /** A malformed event must not leave a half-written inbox or a processed_event behind. */
    private void nothingWasWritten() {
        verify(notifications, never()).save(any());
        verify(processed, never()).save(any());
    }

    private int fanOut(ConsumerRecord<String, String> record) {
        return service.fanOut(EventFixtures.envelope(record));
    }
}
