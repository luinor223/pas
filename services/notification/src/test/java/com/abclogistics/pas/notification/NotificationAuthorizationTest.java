package com.abclogistics.pas.notification;

import com.abclogistics.pas.notification.controller.NotificationController;
import com.abclogistics.pas.notification.dto.InboxResponse;
import com.abclogistics.pas.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D11 layer 2: the service checks a named permission, not a role. Without a test here the
 * annotations are decoration — nothing else in Phase A exercises them.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = NotificationAuthorizationTest.Config.class)
class NotificationAuthorizationTest {

    @Autowired NotificationController controller;
    @Autowired NotificationService notifications;

    /** Method security alone — no Boot autoconfiguration, so the gate is all that is under test. */
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean NotificationService notifications() { return mock(NotificationService.class); }

        @Bean NotificationController controller(NotificationService notifications) {
            return new NotificationController(notifications);
        }
    }

    @Test
    @WithMockUser(authorities = "notification:read")
    void thePermissionOpensTheInbox() {
        when(notifications.inbox(any(), anyBoolean(), any(), any()))
                .thenReturn(new InboxResponse(List.of(), 0, 0, Map.of()));

        assertThat(controller.list(false, null, Pageable.ofSize(20))).isNotNull();
    }

    @Test
    @WithMockUser(authorities = "notification:read")
    void thePermissionOpensTheUnreadCount() {
        when(notifications.unreadCount(any())).thenReturn(4L);

        assertThat(controller.unreadCount()).containsEntry("unreadCount", 4L);
    }

    @Test
    @WithMockUser(authorities = "contract:read")
    void anotherPermissionDoesNotOpenIt() {
        assertThatThrownBy(() -> controller.list(false, null, Pageable.ofSize(20)))
                .isInstanceOf(AccessDeniedException.class);
        verify(notifications, never()).inbox(any(), anyBoolean(), any(), any());
    }

    @Test
    @WithMockUser(authorities = "contract:read")
    void anotherPermissionDoesNotOpenTheUnreadCount() {
        assertThatThrownBy(controller::unreadCount).isInstanceOf(AccessDeniedException.class);
        verify(notifications, never()).unreadCount(any());
    }

    @Test
    @WithMockUser(authorities = "contract:read")
    void markReadIsGatedToo() {
        assertThatThrownBy(() -> controller.markRead(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(authorities = "contract:read")
    void markAllReadIsGatedToo() {
        // the widest write here, so it must not be the one left ungated
        assertThatThrownBy(controller::markAllRead).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anUnauthenticatedCallerIsRefused() {
        // no authentication at all fails earlier than a wrong permission, and is still a refusal
        assertThatThrownBy(() -> controller.list(false, null, Pageable.ofSize(20)))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }
}
