package com.abclogistics.pas.notification;

import com.abclogistics.pas.notification.domain.Notification;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Notification text is snapshotted at fan-out. */
class NotificationTextTest {

    private NotificationRepository notifications;
    private NotificationService service;
    private IdentityGrpcClient identity;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationRepository.class);
        identity = mock(IdentityGrpcClient.class);
        service = new NotificationService(notifications, mock(ProcessedEventRepository.class),
                new RecipientResolver(identity));
    }

    @Test
    void aRevisionRequestIsNotCalledARejection() {
        assertThat(titleOf("REVISION_REQUESTED")).isEqualTo("Revision requested");
    }

    @Test
    void aRejectionStillReadsAsOne() {
        assertThat(titleOf("REJECTED")).isEqualTo("Document rejected");
    }

    @Test
    void anApprovalReadsAsOne() {
        assertThat(titleOf("APPROVED")).isEqualTo("Document approved");
    }

    @Test
    void aCancellationIsItsOwnOutcome() {
        assertThat(titleOf("CANCELLED")).isEqualTo("Document cancelled");
    }

    @Test
    void theBodyCarriesReadableEnglishRatherThanTheEnumName() {
        service.fanOut(EventFixtures.envelope(
                EventFixtures.completed(UUID.randomUUID(), UUID.randomUUID(), "REVISION_REQUESTED")));

        assertThat(saved().getBody()).doesNotContain("REVISION_REQUESTED");
    }

    @Test
    void assignedCopyMatchesTheEnglishNotificationScreen() {
        service.fanOut(EventFixtures.envelope(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID()))));

        assertThat(saved().getTitle()).isEqualTo("New document assigned to you");
        assertThat(saved().getBody())
                .isEqualTo("HD-2026-0001 requires your review at the Legal review step.");
    }

    @Test
    void signatureCopyMatchesTheEnglishNotificationScreen() {
        service.fanOut(EventFixtures.envelope(EventFixtures.esignCompleted(
                UUID.randomUUID(), UUID.randomUUID(), "SIGNED")));

        assertThat(saved().getTitle()).isEqualTo("Signature completed");
    }

    @Test
    void expiryTitleIncludesTheWarningWindow() {
        service.fanOut(EventFixtures.envelope(EventFixtures.documentExpiring(
                UUID.randomUUID(), "2026-09-30", UUID.randomUUID())));

        assertThat(saved().getTitle()).isEqualTo("Document expiring in 30 days");
    }

    @Test
    void periodCopyMatchesTheEnglishNotificationScreen() {
        when(identity.listUsersByRole("ACCOUNTANT")).thenReturn(List.of(UUID.randomUUID()));
        service.fanOut(EventFixtures.envelope(EventFixtures.periodLocked("2026-08", "ACCOUNTANT")));

        assertThat(saved().getTitle()).isEqualTo("Volume period locked");
    }

    private String titleOf(String outcome) {
        service.fanOut(EventFixtures.envelope(
                EventFixtures.completed(UUID.randomUUID(), UUID.randomUUID(), outcome)));
        return saved().getTitle();
    }

    private Notification saved() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(captor.capture());
        return captor.getValue();
    }
}
