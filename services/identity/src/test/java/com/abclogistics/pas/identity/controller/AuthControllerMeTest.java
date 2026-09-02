package com.abclogistics.pas.identity.controller;

import com.abclogistics.pas.common.error.UnauthorizedException;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.identity.dto.UserSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthControllerMeTest {

    private final AuthController controller = new AuthController(null, null);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void meReturnsPrincipalSummary() {
        UUID id = UUID.randomUUID();
        AuthenticatedUser principal =
                new AuthenticatedUser(id, "jdoe", "Jane Doe", "Sales", List.of("SALES_OFFICER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));

        UserSummary summary = controller.me();

        assertThat(summary.id()).isEqualTo(id);
        assertThat(summary.username()).isEqualTo("jdoe");
        assertThat(summary.fullName()).isEqualTo("Jane Doe");
        assertThat(summary.department()).isEqualTo("Sales");
        assertThat(summary.roles()).containsExactly("SALES_OFFICER");
    }

    @Test
    void meThrowsWhenUnauthenticated() {
        assertThatThrownBy(controller::me).isInstanceOf(UnauthorizedException.class);
    }
}
