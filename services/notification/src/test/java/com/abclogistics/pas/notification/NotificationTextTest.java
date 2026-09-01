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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The text is snapshotted at fan-out, so a wrong word here is stored for ever. Registry §9 has
 * four terminal outcomes and e-signing has three results — neither is a boolean.
 */
class NotificationTextTest {

    private NotificationRepository notifications;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationRepository.class);
        service = new NotificationService(notifications, mock(ProcessedEventRepository.class),
                new RecipientResolver(mock(IdentityGrpcClient.class)));
    }

    @Test
    void aRevisionRequestIsNotCalledARejection() {
        // REVISION_REQUESTED means "sửa rồi nộp lại", and the document is not dead
        assertThat(titleOf("REVISION_REQUESTED")).isEqualTo("Hồ sơ cần chỉnh sửa");
    }

    @Test
    void aRejectionStillReadsAsOne() {
        assertThat(titleOf("REJECTED")).isEqualTo("Hồ sơ bị từ chối");
    }

    @Test
    void anApprovalReadsAsOne() {
        assertThat(titleOf("APPROVED")).isEqualTo("Hồ sơ đã được duyệt");
    }

    @Test
    void aCancellationIsItsOwnOutcome() {
        assertThat(titleOf("CANCELLED")).isEqualTo("Hồ sơ đã bị huỷ");
    }

    @Test
    void theBodyCarriesTheOutcomeInVietnameseNotTheEnumName() {
        service.fanOut(EventFixtures.envelope(
                EventFixtures.completed(UUID.randomUUID(), UUID.randomUUID(), "REVISION_REQUESTED")));

        assertThat(saved().getBody()).doesNotContain("REVISION_REQUESTED");
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
