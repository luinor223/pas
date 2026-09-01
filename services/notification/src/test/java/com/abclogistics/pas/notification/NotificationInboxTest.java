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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 4.9's read half: view your own list, filter it by tab, mark read. */
class NotificationInboxTest {

    private static final Pageable FIRST_PAGE = PageRequest.of(0, 20);

    private NotificationRepository notifications;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationRepository.class);
        service = new NotificationService(notifications, mock(ProcessedEventRepository.class),
                new RecipientResolver(mock(IdentityGrpcClient.class)));
        when(notifications.inboxOf(any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
    }

    @Test
    void theUnreadBadgeIsNotFilteredByTheListFilter() {
        // the Figma header shows the list and the badge together; filtering to unread must not
        // make the badge describe the filtered page instead of the inbox
        UUID me = UUID.randomUUID();
        when(notifications.countByRecipientUserIdAndReadAtIsNull(me)).thenReturn(7L);

        assertThat(service.inbox(me, true, null, FIRST_PAGE).unreadCount()).isEqualTo(7L);
        assertThat(service.inbox(me, false, null, FIRST_PAGE).unreadCount()).isEqualTo(7L);
        assertThat(service.inbox(me, false, NotificationCategory.APPROVAL, FIRST_PAGE).unreadCount())
                .isEqualTo(7L);
    }

    @Test
    void unreadTrueActuallyFiltersTheList() {
        // asserting the badge alone was the gap: the count could be right while the list ignored
        // the filter entirely
        UUID me = UUID.randomUUID();

        service.inbox(me, true, null, FIRST_PAGE);

        verify(notifications).inboxOf(eq(me), eq(true), isNull(), eq(FIRST_PAGE));
    }

    @Test
    void theDefaultListIsUnfiltered() {
        UUID me = UUID.randomUUID();

        service.inbox(me, false, null, FIRST_PAGE);

        verify(notifications).inboxOf(eq(me), eq(false), isNull(), eq(FIRST_PAGE));
    }

    @Test
    void aTabFiltersOnItsCategory() {
        UUID me = UUID.randomUUID();

        service.inbox(me, false, NotificationCategory.ESIGN, FIRST_PAGE);

        verify(notifications).inboxOf(eq(me), eq(false), eq(NotificationCategory.ESIGN), eq(FIRST_PAGE));
    }

    @Test
    void unreadAndCategoryCombineRatherThanOverride() {
        // "unread approvals" is a reachable tab state, so the two axes AND
        UUID me = UUID.randomUUID();

        service.inbox(me, true, NotificationCategory.APPROVAL, FIRST_PAGE);

        verify(notifications).inboxOf(eq(me), eq(true), eq(NotificationCategory.APPROVAL), eq(FIRST_PAGE));
    }

    @Test
    void theInboxIsScopedToTheCallerNotFilteredAfterwards() {
        // the recipient is a query predicate: a post-filter would page over other users' rows and
        // return short pages, and one missed filter would leak an inbox
        UUID me = UUID.randomUUID();

        service.inbox(me, false, null, FIRST_PAGE);

        verify(notifications).inboxOf(eq(me), anyBoolean(), isNull(), any());
        verify(notifications, org.mockito.Mockito.never()).findAll(any(Pageable.class));
    }

    @Test
    void theListIsNewestFirstAndCarriesTheTotal() {
        UUID me = UUID.randomUUID();
        Notification older = notificationFor(me, "HD-2026-0001");
        Notification newer = notificationFor(me, "HD-2026-0002");
        when(notifications.inboxOf(eq(me), eq(false), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(newer, older), FIRST_PAGE, 42));

        var response = service.inbox(me, false, null, FIRST_PAGE);

        // the repository orders by created_at desc; the service must not reshuffle the page
        assertThat(response.items()).extracting(r -> r.documentNo())
                .containsExactly("HD-2026-0002", "HD-2026-0001");
        // the total is the query's, not the page's — the Figma list pages against it
        assertThat(response.total()).isEqualTo(42);
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

    @Test
    void anIdThatDoesNotExistIsAlsoNotFound() {
        when(notifications.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(com.abclogistics.pas.common.error.NotFoundException.class);
    }

    @Test
    void markAllReadTouchesOnlyTheCallersUnreadRows() {
        // scoped in the update itself. "Mark all as read" is the one write that could reach beyond
        // the caller, and an unscoped update would silently clear the whole system's inboxes.
        UUID me = UUID.randomUUID();

        service.markAllRead(me);

        verify(notifications).markAllReadFor(eq(me), any());
    }

    private static Notification notificationFor(UUID recipient) {
        return notificationFor(recipient, "HD-2026-0001");
    }

    private static Notification notificationFor(UUID recipient, String documentNo) {
        return Notification.of(recipient, NotificationCategory.APPROVAL, UUID.randomUUID(),
                "workflow.step_assigned", "CONTRACT", UUID.randomUUID(), documentNo,
                "Hồ sơ cần xử lý", documentNo + " đang chờ bạn duyệt");
    }
}
