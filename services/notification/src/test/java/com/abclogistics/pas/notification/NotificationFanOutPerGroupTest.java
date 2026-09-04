package com.abclogistics.pas.notification;

import com.abclogistics.pas.notification.client.IdentityGrpcClient;
import com.abclogistics.pas.notification.domain.Notification;
import com.abclogistics.pas.notification.domain.NotificationCategory;
import com.abclogistics.pas.notification.repository.NotificationRepository;
import com.abclogistics.pas.notification.repository.ProcessedEventRepository;
import com.abclogistics.pas.notification.service.NotificationService;
import com.abclogistics.pas.notification.service.RecipientResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 4.9's fan-out: one event becomes one row per recipient. The rule that gives this test its
 * name is registry §4 — fan-out is a consumer group per service, so this service resolves the
 * whole recipient set itself rather than relying on the broker to route.
 */
class NotificationFanOutPerGroupTest {

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
    void everyAssigneeGetsTheirOwnRow() {
        List<UUID> assignees = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        int written = fanOut(EventFixtures.stepAssigned(UUID.randomUUID(), assignees));

        assertThat(written).isEqualTo(3);
        assertThat(savedRecipients()).containsExactlyInAnyOrderElementsOf(assignees);
    }

    @Test
    void aRecipientRepeatedInThePayloadIsNotNotifiedTwice() {
        // the producer snapshots assignees per step; the same user can hold two roles on one chain
        UUID twice = UUID.randomUUID();

        assertThat(fanOut(EventFixtures.stepAssigned(UUID.randomUUID(), List.of(twice, twice)))).isEqualTo(1);
        assertThat(savedRecipients()).containsExactly(twice);
    }

    @Test
    void aRoleAddressedEventResolvesThroughIdentity() {
        // operations.period_locked carries recipient_role, never ids — registry §4
        List<UUID> accountants = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(identity.listUsersByRole("ACCOUNTANT")).thenReturn(accountants);

        int written = fanOut(EventFixtures.periodLocked("2026-08", "ACCOUNTANT"));

        assertThat(written).isEqualTo(2);
        assertThat(savedRecipients()).containsExactlyInAnyOrderElementsOf(accountants);
    }

    @Test
    void anEventCarryingRecipientIdsNeverCallsIdentity() {
        // the payload already knows; a gRPC hop per event would couple the sink to identity's uptime
        fanOut(EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID())));

        verify(identity, never()).listUsersByRole(anyString());
    }

    @Test
    void anEventThatResolvesToNobodyIsStillMarkedProcessed() {
        // otherwise it is redelivered for ever: no recipients is an answer, not a failure
        when(identity.listUsersByRole("ACCOUNTANT")).thenReturn(List.of());

        assertThat(fanOut(EventFixtures.periodLocked("2026-08", "ACCOUNTANT"))).isZero();
        verify(processed).claim(any());
        verify(notifications, never()).save(any());
    }

    @Test
    void theRequesterIsNotifiedOfTheirOwnDocumentsOutcome() {
        UUID requestedBy = UUID.randomUUID();

        assertThat(fanOut(EventFixtures.completed(UUID.randomUUID(), requestedBy, "APPROVED"))).isEqualTo(1);
        assertThat(savedRecipients()).containsExactly(requestedBy);
    }

    @Test
    void theRequesterIsNotifiedWhenAStepIsActioned() {
        // The submitter needs the outcome.
        UUID requestedBy = UUID.randomUUID();

        assertThat(fanOut(EventFixtures.stepActioned(UUID.randomUUID(), requestedBy, "REQUEST_REVISION")))
                .isEqualTo(1);
        assertThat(savedRecipients()).containsExactly(requestedBy);
    }

    @Test
    void everyAssigneeStillHoldingAnOverdueStepIsNagged() {
        // workflow.step_overdue addresses the people who can clear it, not the submitter
        List<UUID> assignees = List.of(UUID.randomUUID(), UUID.randomUUID());

        int written = fanOut(EventFixtures.stepOverdue(
                UUID.randomUUID(), UUID.randomUUID(), "2026-09-01T10:00:00Z", assignees));

        assertThat(written).isEqualTo(2);
        assertThat(savedRecipients()).containsExactlyInAnyOrderElementsOf(assignees);
    }

    @Test
    void theRequesterIsNotifiedWhenSigningFinishes() {
        UUID requestedBy = UUID.randomUUID();

        assertThat(fanOut(EventFixtures.esignCompleted(UUID.randomUUID(), requestedBy, "SIGNED")))
                .isEqualTo(1);
        assertThat(savedRecipients()).containsExactly(requestedBy);
    }

    @Test
    void aFailedSigningIsStillNews() {
        // result carries SIGNED | FAILED | CANCELLED (registry §4) and all three are worth tell
        UUID requestedBy = UUID.randomUUID();

        assertThat(fanOut(EventFixtures.esignCompleted(UUID.randomUUID(), requestedBy, "FAILED")))
                .isEqualTo(1);
    }

    @Test
    void theWarningGoesToTheDocumentOwnerAlone() {
        UUID owner = UUID.randomUUID();

        assertThat(fanOut(EventFixtures.documentExpiring(UUID.randomUUID(), "2026-12-31", owner)))
                .isEqualTo(1);
        assertThat(savedRecipients()).containsExactly(owner);
    }

    @Test
    void eachRowIsFiledUnderItsEventsTab() {
        // the category is written at fan-out, not derived on read
        fanOut(EventFixtures.esignCompleted(UUID.randomUUID(), UUID.randomUUID(), "SIGNED"));

        assertThat(saved().getFirst().getCategory()).isEqualTo(NotificationCategory.ESIGN);
    }

    @Test
    void theRowSnapshotsTheDocumentItIsAbout() {
        // title and body are written once and never re-resolved
        UUID documentId = UUID.randomUUID();

        fanOut(EventFixtures.stepAssigned(documentId, List.of(UUID.randomUUID())));

        Notification row = saved().getFirst();
        assertThat(row.getDocumentId()).isEqualTo(documentId);
        assertThat(row.getDocumentNo()).isEqualTo("HD-2026-0001");
        assertThat(row.getTitle()).isNotBlank();
        assertThat(row.getBody()).isNotBlank();
    }

    private int fanOut(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record) {
        return service.fanOut(EventFixtures.envelope(record));
    }

    private List<Notification> saved() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private List<UUID> savedRecipients() {
        return saved().stream().map(Notification::getRecipientUserId).toList();
    }
}
