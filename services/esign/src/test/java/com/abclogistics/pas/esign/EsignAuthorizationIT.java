package com.abclogistics.pas.esign;

import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.esign.controller.SigningSessionController;
import com.abclogistics.pas.esign.scheduler.EsignDispatchScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The permission gates on the signing-session endpoints (registry §7): listing/reading is esign:send,
 * cancelling is esign:cancel. Method security is exercised through the controller bean's proxy.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class EsignAuthorizationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_esign").withUsername("pas").withPassword("pas");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("spring.grpc.server.port", () -> 0);
        registry.add("outbox.relay.enabled", () -> "false");
    }

    @MockitoBean
    EsignDispatchScheduler dispatchScheduler;

    @Autowired SigningSessionController controller;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listingRequiresEsignSend() {
        authWith("esign:cancel");   // wrong permission
        assertThatThrownBy(() -> controller.listSessions(null, PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void listingIsAllowedWithEsignSend() {
        authWith("esign:send");
        assertThat(controller.listSessions(null, PageRequest.of(0, 20)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void cancellingRequiresEsignCancel() {
        authWith("esign:send");   // send is not enough to cancel
        assertThatThrownBy(() -> controller.cancelSession(UUID.randomUUID(), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static void authWith(String... permissions) {
        var user = new AuthenticatedUser(UUID.randomUUID(), "u", "User One", "SALES", List.of("SALES"));
        var authorities = List.of(permissions).stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, authorities));
    }
}
