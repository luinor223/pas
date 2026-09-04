package com.abclogistics.pas.workflow;

import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.workflow.dto.DocumentTypeResponse;
import com.abclogistics.pas.workflow.dto.UpdateDocumentTypeRequest;
import com.abclogistics.pas.workflow.service.DocumentTypeService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
 * Document-type config CRUD (admin backend for the Document Types tab):
 * seeded types are listed, a type is fetched by code, and name/e-sign flags
 * are updatable while code + numberPrefix stay immutable.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestIdentityConfig.class)
class DocumentTypeConfigIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas").withUsername("pas").withPassword("pas");
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("outbox.relay.enabled", () -> "false");
        r.add("identity.grpc.host", () -> "localhost");
        r.add("identity.grpc.port", () -> "50051");
    }

    @Autowired DocumentTypeService service;

    private void setAdminAuth() {
        var user = new com.abclogistics.pas.common.security.AuthenticatedUser(UUID.randomUUID(), "admin", "Admin", "IT", List.of("SYSTEM_ADMIN"));
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of(() -> "workflow:configure"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void listReturnsSeededTypes() {
        setAdminAuth();
        try {
            List<DocumentTypeResponse> types = service.list();
            assertThat(types).extracting(DocumentTypeResponse::code)
                    .containsExactlyInAnyOrder("CONTRACT", "ADDENDUM", "PRICE_LIST", "PAYMENT_STATEMENT");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void getByCodeReturnsFields() {
        setAdminAuth();
        try {
            DocumentTypeResponse contract = service.get("CONTRACT");
            assertThat(contract.code()).isEqualTo("CONTRACT");
            assertThat(contract.numberPrefix()).isEqualTo("CTR");
            assertThat(contract.esignEnabled()).isTrue();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void getUnknownCodeIsNotFound() {
        setAdminAuth();
        try {
            assertThatThrownBy(() -> service.get("NOPE"))
                    .isInstanceOf(NotFoundException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void updateNameAndEsignPersists() {
        setAdminAuth();
        try {
            DocumentTypeResponse updated = service.update("PRICE_LIST",
                    new UpdateDocumentTypeRequest("Price List (edited)", true, "mock"));
            assertThat(updated.name()).isEqualTo("Price List (edited)");
            assertThat(updated.esignEnabled()).isTrue();
            assertThat(updated.esignProvider()).isEqualTo("mock");
            // code + prefix are immutable · the response still carries the originals
            assertThat(updated.code()).isEqualTo("PRICE_LIST");
            assertThat(updated.numberPrefix()).isEqualTo("PRC");

            DocumentTypeResponse reloaded = service.get("PRICE_LIST");
            assertThat(reloaded.name()).isEqualTo("Price List (edited)");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void enablingEsignWithoutProviderFails() {
        setAdminAuth();
        try {
            assertThatThrownBy(() -> service.update("CONTRACT",
                    new UpdateDocumentTypeRequest("Contract", true, null)))
                    .isInstanceOf(FailedPreconditionException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void disablingEsignClearsProvider() {
        setAdminAuth();
        try {
            DocumentTypeResponse updated = service.update("CONTRACT",
                    new UpdateDocumentTypeRequest("Contract", false, null));
            assertThat(updated.esignEnabled()).isFalse();
            assertThat(updated.esignProvider()).isNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
