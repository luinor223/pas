package com.abclogistics.pas.workflow;

import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.workflow.controller.http.InboxController;
import com.abclogistics.pas.workflow.service.InboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InboxControllerTest {
    @Mock InboxService inbox;
    private final Locale originalLocale = Locale.getDefault();

    @AfterEach
    void cleanup() {
        Locale.setDefault(originalLocale);
        SecurityContextHolder.clearContext();
    }

    @Test
    void normalizesTabWithRootLocaleAndClampsPageSize() {
        UUID userId = authenticate();
        Locale.setDefault(Locale.forLanguageTag("tr"));

        new InboxController(inbox).inbox("submitted", -4, 1000, null, null, null);

        verify(inbox).submittedByMe(userId, 0, 100, null, null, null);
    }

    @Test
    void rejectsUnknownTab() {
        authenticate();
        assertThatThrownBy(() -> new InboxController(inbox).inbox("bogus", 0, 15, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown tab");
    }

    private UUID authenticate() {
        UUID userId = UUID.randomUUID();
        var principal = new AuthenticatedUser(userId, "reviewer", "Reviewer", "LEGAL", List.of("LEGAL_REVIEWER"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        return userId;
    }
}
