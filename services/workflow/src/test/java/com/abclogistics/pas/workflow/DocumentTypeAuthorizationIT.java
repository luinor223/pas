package com.abclogistics.pas.workflow;

import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.workflow.controller.DocumentTypeController;
import com.abclogistics.pas.workflow.controller.WorkflowDefinitionController;
import com.abclogistics.pas.workflow.dto.UpdateDocumentTypeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 * Permission gates on the document-type endpoints (registry §7): reads need an authenticated
 * caller, writes need {@code doctype:configure} (Cấu hình loại hồ sơ). Exercised through the
 * controller bean's proxy, like EsignAuthorizationIT.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class DocumentTypeAuthorizationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_workflow").withUsername("pas").withPassword("pas");

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

    @Autowired DocumentTypeController controller;
    @Autowired WorkflowDefinitionController definitionController;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void updateRequiresDoctypeConfigure() {
        authWith("workflow:configure");   // related but not enough
        assertThatThrownBy(() -> controller.update("CONTRACT",
                new UpdateDocumentTypeRequest("Contract", true, "mock")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void updateIsAllowedWithDoctypeConfigure() {
        authWith("doctype:configure");
        assertThat(controller.update("CONTRACT",
                new UpdateDocumentTypeRequest("Contract", true, "mock")).code())
                .isEqualTo("CONTRACT");
    }

    @Test
    void listIsAllowedForAnyAuthenticatedCaller() {
        authWith("user:manage");
        assertThat(controller.list()).isNotEmpty();
    }

    @Test
    void definitionDeleteRequiresWorkflowConfigure() {
        authWith("doctype:configure");   // related but not enough
        assertThatThrownBy(() -> definitionController.delete(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static void authWith(String... permissions) {
        var user = new AuthenticatedUser(UUID.randomUUID(), "u", "User One", "IT", List.of("ADMIN"));
        var authorities = List.of(permissions).stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, authorities));
    }
}
