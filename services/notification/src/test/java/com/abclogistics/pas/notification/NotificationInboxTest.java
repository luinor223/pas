package com.abclogistics.pas.notification;

import com.abclogistics.pas.notification.domain.Notification;
import com.abclogistics.pas.notification.domain.NotificationCategory;
import com.abclogistics.pas.notification.repository.NotificationRepository;
import com.abclogistics.pas.notification.repository.ProcessedEventRepository;
import com.abclogistics.pas.notification.service.IdentityGrpcClient;
import com.abclogistics.pas.notification.service.NotificationService;
import com.abclogistics.pas.notification.service.RecipientResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 4.9's read half: view your own list, mark read. */
class NotificationInboxTest {

    private NotificationRepository notifications;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationRepository.class);
        service = new NotificationService(notifications, mock(ProcessedEventRepository.class),
                new RecipientResolver(mock(IdentityGrpcClient.class)));
    }

    @Test
    void theUnreadBadgeIsNotFilteredByTheListFilter() {
        // the Figma header shows the list and the badge together; filtering to unread must not
        // make the badge describe the filtered page instead of the inbox
        UUID me = UUID.randomUUID();
        when(notifications.countByRecipientUserIdAndReadAtIsNull(me)).thenReturn(7L);

        assertThat(service.inbox(me, true, PageRequest.of(0, 20)).unreadCount()).isEqualTo(7L);
        assertThat(service.inbox(me, false, PageRequest.of(0, 20)).unreadCount()).isEqualTo(7L);
    }

    @Test
    void markingReadTwiceKeepsTheFirstTimestamp() {
        UUID me = UUID.randomUUID();
        Notification n = notificationFor(me);
        when(notifications.findById(n.getId())).thenReturn(Optional.of(n));

        service.markRead(n.getId(), me);
        var firstReadAt = n.getReadAt();
        service.markRead(n.getId(), me);

        assertThat(n.getReadAt()).isEqualTo(firstReadAt);
    }

    @Test
    void anotherUsersNotificationIsNotFound() {
        // 404 rather than 403: the caller must not learn that the id exists
        Notification theirs = notificationFor(UUID.randomUUID());
        when(notifications.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> service.markRead(theirs.getId(), UUID.randomUUID()))
                .isInstanceOf(com.abclogistics.pas.common.error.NotFoundException.class);
    }

    private static Notification notificationFor(UUID recipient) {
        return Notification.of(recipient, NotificationCategory.APPROVAL, UUID.randomUUID(),
                "workflow.step_assigned", "CONTRACT", UUID.randomUUID(), "HD-2026-0001",
                "Hồ sơ cần xử lý", "HD-2026-0001 đang chờ bạn duyệt");
    }
}
