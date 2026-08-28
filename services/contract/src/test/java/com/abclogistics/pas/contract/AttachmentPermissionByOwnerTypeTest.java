package com.abclogistics.pas.contract;

import com.abclogistics.pas.common.error.ForbiddenException;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.dto.AddendumRequest;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.service.AddendumService;
import com.abclogistics.pas.contract.service.AttachmentService;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One endpoint, two document types, two permissions (registry §10). Holding one never implied the
 * other, so the check must land on the owner type actually addressed — and for download and delete
 * that is a column on the row, not a parameter, which is why they get their own assertions.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class AttachmentPermissionByOwnerTypeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_contract").withUsername("pas").withPassword("pas");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    static final Path STORAGE = createTempStorage();

    private static Path createTempStorage() {
        try {
            return Files.createTempDirectory("pas-attachment-perms").toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("outbox.relay.enabled", () -> "false");
        registry.add("contract.kafka.listener-enabled", () -> "false");
        registry.add("contract.attachment-storage-path", STORAGE::toString);
        registry.add("contract.attachment-cleanup-enabled", () -> "false");
        registry.add("contract.attachment-cleanup-interval", () -> "PT1H");
        registry.add("contract.attachment-cleanup-grace", () -> "PT1H");
    }

    private static final AuthenticatedUser SALES = new AuthenticatedUser(
            UUID.randomUUID(), "lan.nt", "Nguyen Thi Lan", "SALES", List.of("SALES"));

    private static final String CONTRACT_READ = "contract:read";
    private static final String CONTRACT_WRITE = "contract:write";
    private static final String ADDENDUM_READ = "addendum:read";
    private static final String ADDENDUM_WRITE = "addendum:write";

    @Autowired AttachmentService attachments;
    @Autowired ContractService contracts;
    @Autowired AddendumService addenda;
    @Autowired CustomerService customers;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- upload ------------------------------------------------------------------------------

    @Test
    void contractWriteMayAttachToAContract() {
        UUID contractId = draftContract();

        as(CONTRACT_WRITE);
        assertThat(upload(EntityType.CONTRACT, contractId)).isNotNull();
    }

    @Test
    void contractWriteMayNotAttachToAnAddendum() {
        UUID addendumId = draftAddendum();

        as(CONTRACT_WRITE);
        assertThatThrownBy(() -> upload(EntityType.ADDENDUM, addendumId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining(ADDENDUM_WRITE);
    }

    @Test
    void addendumWriteMayAttachToAnAddendum() {
        UUID addendumId = draftAddendum();

        as(ADDENDUM_WRITE);
        assertThat(upload(EntityType.ADDENDUM, addendumId)).isNotNull();
    }

    @Test
    void addendumWriteMayNotAttachToAContract() {
        UUID contractId = draftContract();

        as(ADDENDUM_WRITE);
        assertThatThrownBy(() -> upload(EntityType.CONTRACT, contractId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining(CONTRACT_WRITE);
    }

    // ---- list / download / delete — owner type known only from the row ------------------------

    @Test
    void listingAnAddendumsFilesNeedsAddendumRead() {
        UUID addendumId = draftAddendum();

        as(CONTRACT_READ);
        assertThatThrownBy(() -> attachments.list(EntityType.ADDENDUM, addendumId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining(ADDENDUM_READ);

        as(ADDENDUM_READ);
        assertThat(attachments.list(EntityType.ADDENDUM, addendumId)).isEmpty();
    }

    @Test
    void downloadingAnAddendumsFileNeedsAddendumRead() {
        UUID addendumId = draftAddendum();
        as(ADDENDUM_WRITE);
        UUID attachmentId = upload(EntityType.ADDENDUM, addendumId);

        // Only the id is known here; the owner type comes off the row.
        as(CONTRACT_READ);
        assertThatThrownBy(() -> tx.execute(s -> attachments.download(attachmentId)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining(ADDENDUM_READ);

        as(ADDENDUM_READ);
        assertThat(tx.execute(s -> attachments.download(attachmentId)).metadata().getId())
                .isEqualTo(attachmentId);
    }

    @Test
    void deletingAnAddendumsFileNeedsAddendumWrite() {
        UUID addendumId = draftAddendum();
        as(ADDENDUM_WRITE);
        UUID attachmentId = upload(EntityType.ADDENDUM, addendumId);

        as(CONTRACT_WRITE);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> attachments.delete(attachmentId)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining(ADDENDUM_WRITE);

        as(ADDENDUM_WRITE, ADDENDUM_READ);
        tx.executeWithoutResult(s -> attachments.delete(attachmentId));
        assertThat(attachments.list(EntityType.ADDENDUM, addendumId)).isEmpty();
    }

    @Test
    void deletingAContractsFileNeedsContractWrite() {
        UUID contractId = draftContract();
        as(CONTRACT_WRITE);
        UUID attachmentId = upload(EntityType.CONTRACT, contractId);

        as(ADDENDUM_WRITE);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> attachments.delete(attachmentId)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining(CONTRACT_WRITE);
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private void as(String... permissions) {
        List<GrantedAuthority> authorities = Arrays.stream(permissions)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null, authorities));
    }

    private UUID upload(EntityType ownerType, UUID ownerId) {
        MockMultipartFile file = new MockMultipartFile("file", "signed.pdf", "application/pdf",
                "bytes".getBytes(StandardCharsets.UTF_8));
        return tx.execute(s -> attachments.upload(ownerType, ownerId, file).getId());
    }

    /** DRAFT, because CTR-01 freezes the file set once the document is past it. */
    private UUID draftContract() {
        UUID customerId = tx.execute(s -> customers.create(new CustomerRequest(
                "ACME Logistics", null, null, null, null, null, null, List.of())).getId());
        return tx.execute(s -> contracts.create(new ContractRequest(
                customerId, "initial", "TRANSPORTATION", new BigDecimal("1000000"), "VND",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, null)).getId());
    }

    /** An addendum amends a contract already in force, so its parent is forced ACTIVE first (4.3). */
    private UUID draftAddendum() {
        UUID contractId = draftContract();
        tx.executeWithoutResult(s -> contracts.get(contractId).setStatus(DocumentStatus.ACTIVE));
        return tx.execute(s -> addenda.create(new AddendumRequest(
                contractId, "TERM_EXTENSION", "renewal",
                LocalDate.of(2026, 6, 1), LocalDate.of(2027, 12, 31),
                null, null, null)).getId());
    }
}
