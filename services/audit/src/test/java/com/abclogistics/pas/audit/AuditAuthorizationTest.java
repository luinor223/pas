package com.abclogistics.pas.audit;

import com.abclogistics.pas.audit.controller.http.AuditRecordController;
import com.abclogistics.pas.audit.service.AuditQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code audit:view_all} is held by SYSTEM_ADMIN alone (registry §7), and this endpoint reads
 * every service's trail across every entity — the widest read in the system.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AuditAuthorizationTest.Config.class)
class AuditAuthorizationTest {

    @Autowired AuditRecordController controller;
    @Autowired AuditQueryService audit;

    /** Method security alone — no Boot autoconfiguration, so the gate is all that is under test. */
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean AuditQueryService audit() { return mock(AuditQueryService.class); }

        @Bean AuditRecordController controller(AuditQueryService audit) {
            return new AuditRecordController(audit);
        }
    }

    @Test
    @WithMockUser(authorities = "audit:view_all")
    void thePermissionOpensTheSearch() {
        when(audit.search(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(search()).isNotNull();
    }

    @Test
    @WithMockUser(authorities = "contract:read")
    void aReaderOfTheirOwnContextCannotReadTheTrail() {
        assertThatThrownBy(this::search).isInstanceOf(AccessDeniedException.class);
        verify(audit, never()).search(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @WithMockUser(authorities = "user:manage")
    void anotherAdminPermissionIsNotEnoughOnItsOwn() {
        // the SYSTEM_ADMIN bundle grants both, but the gate is the named permission, not the role
        assertThatThrownBy(this::search).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anUnauthenticatedCallerIsRefused() {
        // no authentication at all fails earlier than a wrong permission, and is still a refusal
        assertThatThrownBy(this::search)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    private Object search() {
        return controller.search(null, null, null, null, null, null, null, null, PageRequest.of(0, 20));
    }
}
