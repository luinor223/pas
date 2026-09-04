package com.abclogistics.pas.notification;

import com.abclogistics.pas.common.events.MalformedEventException;
import com.abclogistics.pas.notification.client.IdentityGrpcClient;
import com.abclogistics.pas.notification.repository.NotificationRepository;
import com.abclogistics.pas.notification.repository.ProcessedEventRepository;
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
 * The recipient is not the only field that can be broken. A missing text field used to render as
 * a hole — "requires your review at the  step." — and still mark the event processed, which is
 * the one outcome no corrected replay can undo.
 */
class MalformedPayloadReachesTheDltTest {

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
        when(processed.claim(any())).thenReturn(1);
    }

    @Test
    void anAssignmentWithNoStepNameIsMalformed() {
        assertThatThrownBy(() -> fanOut(without(assigned(), "step_name")))
                .isInstanceOf(MalformedEventException.class);
        nothingWasWritten();
    }

    @Test
    void anAssignmentWithNoDocumentNoIsMalformed() {
        assertThatThrownBy(() -> fanOut(without(assigned(), "document_no")))
                .isInstanceOf(MalformedEventException.class);
        nothingWasWritten();
    }

    @Test
    void aBlankFieldCountsAsMissing() {
        // "" passes every null check and renders exactly the same hole
        assertThatThrownBy(() -> fanOut(
                EventFixtures.withPayloadField(assigned(), "document_no", "   ")))
                .isInstanceOf(MalformedEventException.class);
        nothingWasWritten();
    }

    @Test
    void anOverdueWarningWithoutItsHoursIsMalformed() {
        ConsumerRecord<String, String> overdue = EventFixtures.stepOverdue(
                UUID.randomUUID(), UUID.randomUUID(), "2026-09-01T10:00:00Z",
                List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> fanOut(without(overdue, "sla_hours")))
                .isInstanceOf(MalformedEventException.class);
        nothingWasWritten();
    }

    @Test
    void anExpiryWarningWithoutItsWindowIsMalformed() {
        for (String field : List.of("days_left", "expires_on")) {
            ConsumerRecord<String, String> expiring = EventFixtures.documentExpiring(
                    UUID.randomUUID(), "2026-12-31", UUID.randomUUID());

            assertThatThrownBy(() -> fanOut(without(expiring, field)))
                    .as("missing %s", field)
                    .isInstanceOf(MalformedEventException.class);
        }
        nothingWasWritten();
    }

    @Test
    void aPeriodLockWithoutItsCodeOrLockerIsMalformed() {
        for (String field : List.of("period_code", "locked_by_name")) {
            when(identity.listUsersByRole("ACCOUNTANT")).thenReturn(List.of(UUID.randomUUID()));

            assertThatThrownBy(() -> fanOut(
                    without(EventFixtures.periodLocked("2026-08", "ACCOUNTANT"), field)))
                    .as("missing %s", field)
                    .isInstanceOf(MalformedEventException.class);
        }
        nothingWasWritten();
    }

    @Test
    void anOutcomeThisServiceCannotNameIsMalformed() {
        // the old default read "Workflow completed", which tells the approver nothing
        ConsumerRecord<String, String> completed = EventFixtures.completed(
                UUID.randomUUID(), UUID.randomUUID(), "SUPERSEDED");

        assertThatThrownBy(() -> fanOut(completed)).isInstanceOf(MalformedEventException.class);
        nothingWasWritten();
    }

    @Test
    void anEsignResultThisServiceCannotNameIsMalformed() {
        ConsumerRecord<String, String> esign = EventFixtures.esignCompleted(
                UUID.randomUUID(), UUID.randomUUID(), "EXPIRED");

        assertThatThrownBy(() -> fanOut(esign)).isInstanceOf(MalformedEventException.class);
        nothingWasWritten();
    }

    @Test
    void anAbsentApprovalCommentIsStillOptional() {
        // the counterexample: not every missing field is a defect
        assertThat(fanOut(without(EventFixtures.stepActioned(
                UUID.randomUUID(), UUID.randomUUID(), "APPROVE"), "comment"))).isOne();
        verify(processed).claim(any());
    }

    @Test
    void anEventThatAddressesNobodyIsStillValidated() {
        // the bypass: rendering happened inside the recipient loop, so an event with no
        // recipients was never rendered — and a broken payload was claimed as processed
        ConsumerRecord<String, String> nobody = EventFixtures.withPayloadField(
                without(assigned(), "step_name"), "assignee_ids", List.of());

        assertThatThrownBy(() -> fanOut(nobody)).isInstanceOf(MalformedEventException.class);
        nothingWasWritten();
    }

    @Test
    void aCorrectedReplayOfTheSameEventStillLands() {
        // what the processed marker would have destroyed: the producer's own fix
        ConsumerRecord<String, String> good = assigned();

        assertThatThrownBy(() -> fanOut(without(good, "step_name")))
                .isInstanceOf(MalformedEventException.class);

        // the failed attempt wrote nothing, so this is the replay's own row
        assertThat(fanOut(good)).isOne();
        verify(notifications).save(any());
    }

    private static ConsumerRecord<String, String> assigned() {
        return EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID()));
    }

    private static ConsumerRecord<String, String> without(ConsumerRecord<String, String> record,
                                                          String field) {
        return EventFixtures.withPayloadField(record, field, null);
    }

    /** A malformed event must not leave a half-written inbox behind; the claim rolls back with it. */
    private void nothingWasWritten() {
        verify(notifications, never()).save(any());
    }

    private int fanOut(ConsumerRecord<String, String> record) {
        return service.fanOut(EventFixtures.envelope(record));
    }
}
