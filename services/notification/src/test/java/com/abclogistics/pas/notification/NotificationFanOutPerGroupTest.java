package com.abclogistics.pas.notification;

import com.abclogistics.pas.notification.domain.Notification;
import com.abclogistics.pas.notification.event.EventEnvelope;
import com.abclogistics.pas.notification.repository.NotificationRepository;
import com.abclogistics.pas.notification.repository.ProcessedEventRepository;
import com.abclogistics.pas.notification.service.IdentityGrpcClient;
import com.abclogistics.pas.notification.service.NotificationService;
import com.abclogistics.pas.notification.service.RecipientResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 4.9's fan-out: one event becomes one row per recipient. The rule that gives this test its name
 * is registry §4 — fan-out is a consumer group per service, so this service resolves the whole
 * recipient set itself rather than relying on the broker to route.
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
    }

    @Test
    void everyAssigneeGetsTheirOwnRow() {
        List<UUID> assignees = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        EventEnvelope event = EventFixtures.stepAssigned(UUID.randomUUID(), assignees);

        int written = service.fanOut(event);

        assertThat(written).isEqualTo(3);
        assertThat(savedRecipients()).containsExactlyInAnyOrderElementsOf(assignees);
    }

    @Test
    void aRecipientRepeatedInThePayloadIsNotNotifiedTwice() {
        // the producer snapshots assignees per step; the same user can hold two roles on one chain
        UUID twice = UUID.randomUUID();
        EventEnvelope event = EventFixtures.stepAssigned(UUID.randomUUID(), List.of(twice, twice));

        assertThat(service.fanOut(event)).isEqualTo(1);
        assertThat(savedRecipients()).containsExactly(twice);
    }

    @Test
    void aRoleAddressedEventResolvesThroughIdentity() {
        // operations.period_locked carries recipient_role, never ids — registry §4
        List<UUID> accountants = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(identity.listUsersByRole("ACCOUNTANT")).thenReturn(accountants);

        int written = service.fanOut(EventFixtures.periodLocked("2026-08", "ACCOUNTANT"));

        assertThat(written).isEqualTo(2);
        assertThat(savedRecipients()).containsExactlyInAnyOrderElementsOf(accountants);
    }

    @Test
    void anEventCarryingRecipientIdsNeverCallsIdentity() {
        // the payload already knows; a gRPC hop per event would couple the sink to identity's uptime
        service.fanOut(EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID())));

        verify(identity, never()).listUsersByRole(anyString());
    }

    @Test
    void anEventThatResolvesToNobodyIsStillMarkedProcessed() {
        // otherwise it is redelivered for ever: no recipients is an answer, not a failure
        when(identity.listUsersByRole("ACCOUNTANT")).thenReturn(List.of());

        assertThat(service.fanOut(EventFixtures.periodLocked("2026-08", "ACCOUNTANT"))).isZero();
        verify(processed).save(org.mockito.ArgumentMatchers.any());
        verify(notifications, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theRequesterIsNotifiedOfTheirOwnDocumentsOutcome() {
        UUID requestedBy = UUID.randomUUID();

        assertThat(service.fanOut(EventFixtures.completed(UUID.randomUUID(), requestedBy, "APPROVED")))
                .isEqualTo(1);
        assertThat(savedRecipients()).containsExactly(requestedBy);
    }

    private List<UUID> savedRecipients() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream().map(Notification::getRecipientUserId).toList();
    }
}
