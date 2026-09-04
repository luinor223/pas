package com.abclogistics.pas.contract;

import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.domain.Attachment;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.common.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.repository.AttachmentRepository;
import com.abclogistics.pas.contract.repository.ContractRepository;
import com.abclogistics.pas.contract.service.AttachmentCleanupSweep;
import com.abclogistics.pas.contract.service.AttachmentService;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase B item 4 — attachments (the ">= 1 file" CTR-02 depends on).
 *
 * <p>The bytes and the metadata row live in two stores with no shared transaction, so the tests
 * that matter most here are the reconciliation ones: a rolled-back upload must leave no file, a
 * committed delete must leave no file, and a rolled-back delete must leave the file intact.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class AttachmentLifecycleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_contract").withUsername("pas").withPassword("pas");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    /** A real directory, not a mock filesystem — path containment is the thing under test. */
    static final Path STORAGE = createTempStorage();

    private static Path createTempStorage() {
        try {
            return Files.createTempDirectory("pas-attachments").toRealPath();
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
        // the D14d sweep runs on a schedule; these tests drive their own dates and statuses
        registry.add("contract.status-sweep-enabled", () -> "false");
        registry.add("contract.attachment-storage-path", STORAGE::toString);
        // The sweep must never fire on its own here; each test drives it explicitly.
        registry.add("contract.attachment-cleanup-enabled", () -> "false");
        registry.add("contract.attachment-cleanup-interval", () -> "PT1H");
        registry.add("contract.attachment-cleanup-grace", () -> "PT0S");
    }

    private static final AuthenticatedUser SALES = new AuthenticatedUser(
            UUID.randomUUID(), "lan.nt", "Nguyen Thi Lan", "SALES", List.of("SALES"));

    /** SALES_OFFICER's grants: AttachmentService checks them, and these tests bypass the controller. */
    private static final List<GrantedAuthority> SALES_OFFICER_PERMISSIONS = Stream.of(
            "customer:read", "customer:write", "contract:read", "contract:write",
            "addendum:read", "addendum:write")
            .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();

    @Autowired AttachmentService attachments;
    @Autowired AttachmentCleanupSweep sweep;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired ContractService contracts;
    @Autowired CustomerService customers;
    @Autowired ContractRepository contractRepository;
    @Autowired OutboxRepository outbox;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null, SALES_OFFICER_PERMISSIONS));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- upload / list / download ---------------------------------------------------------

    @Test
    void uploadStoresTheBytesAndTheMetadata() {
        UUID contractId = draftContract();
        String contractNo = contractRepository.findById(contractId).orElseThrow().getContractNo();
        Attachment saved = upload(contractId, "signed-terms.pdf", "application/pdf", "hello contract");

        assertThat(saved.getOwnerType()).isEqualTo(EntityType.CONTRACT);
        assertThat(saved.getOwnerId()).isEqualTo(contractId);
        assertThat(saved.getFileName()).isEqualTo("signed-terms.pdf");
        assertThat(saved.getContentType()).isEqualTo("application/pdf");
        assertThat(saved.getSizeBytes()).isEqualTo("hello contract".length());
        assertThat(saved.getUploadedBy()).isEqualTo(SALES.userId());
        assertThat(Path.of(saved.getStoragePath())).exists();
        assertThat(outbox.findAll())
                .filteredOn(event -> "audit.recorded".equals(event.getEventType()))
                .extracting(OutboxEvent::getPayload)
                .anySatisfy(payload -> {
                    var json = objectMapper.readTree(payload);
                    assertThat(json.get("action").asString()).isEqualTo("ATTACH");
                    assertThat(json.get("entity_no").asString()).isEqualTo(contractNo);
                });
    }

    @Test
    void listIsScopedToOneOwner() {
        UUID a = draftContract();
        UUID b = draftContract();
        upload(a, "a1.pdf", "application/pdf", "a1");
        upload(a, "a2.pdf", "application/pdf", "a2");
        upload(b, "b1.pdf", "application/pdf", "b1");

        List<Attachment> listed = tx.execute(s -> attachments.list(EntityType.CONTRACT, a));
        assertThat(listed).extracting(Attachment::getFileName)
                .containsExactlyInAnyOrder("a1.pdf", "a2.pdf");
    }

    @Test
    void submissionCapabilityTracksCommittedAttachmentChanges() {
        UUID contractId = draftContract();

        assertThat(contracts.toResponse(contracts.get(contractId)).canSubmit()).isFalse();
        assertThat(contracts.toResponse(contracts.get(contractId)).submitBlockedReason())
                .contains("attachment");

        Attachment saved = upload(contractId, "ready.pdf", "application/pdf", "ready");

        assertThat(contracts.toResponse(contracts.get(contractId)).canSubmit()).isTrue();
        assertThat(contracts.toResponse(contracts.get(contractId)).submitBlockedReason()).isNull();

        tx.executeWithoutResult(s -> attachments.delete(saved.getId()));

        assertThat(contracts.toResponse(contracts.get(contractId)).canSubmit()).isFalse();
        assertThat(contracts.toResponse(contracts.get(contractId)).submitBlockedReason())
                .contains("attachment");
    }

    @Test
    void downloadReturnsTheBytesThatWereUploaded() throws Exception {
        UUID contractId = draftContract();
        Attachment saved = upload(contractId, "terms.txt", "text/plain", "the exact bytes");

        AttachmentService.AttachmentContent content =
                tx.execute(s -> attachments.download(saved.getId()));

        assertThat(content.metadata().getFileName()).isEqualTo("terms.txt");
        assertThat(content.resource().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("the exact bytes");
    }

    @Test
    void downloadStaysAvailableAfterTheOwnerIsFrozen() {
        // CTR-01 freezes which files are on the document; it does not hide them. An APPROVED
        // contract's attachments are precisely the ones that must stay readable.
        UUID contractId = draftContract();
        Attachment saved = upload(contractId, "terms.txt", "text/plain", "still readable");
        forceStatus(contractId, DocumentStatus.ACTIVE);

        assertThat(tx.execute(s -> attachments.download(saved.getId())).metadata().getId())
                .isEqualTo(saved.getId());
    }

    @Test
    void downloadingAnUnknownAttachmentIsNotFound() {
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> tx.execute(s -> attachments.download(missing)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void emptyUploadIsRejected() {
        UUID contractId = draftContract();
        MockMultipartFile empty = new MockMultipartFile("file", "nothing.pdf",
                "application/pdf", new byte[0]);

        assertThatThrownBy(() -> tx.execute(s ->
                attachments.upload(EntityType.CONTRACT, contractId, empty)))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    // ---- filename safety -------------------------------------------------------------------

    @Test
    void aTraversingFilenameCannotEscapeTheStorageRoot() throws Exception {
        UUID contractId = draftContract();
        String hostile = "../../../../../../etc/passwd";

        Attachment saved = upload(contractId, hostile, "text/plain", "pwned");

        Path stored = Path.of(saved.getStoragePath()).toAbsolutePath().normalize();
        assertThat(stored).startsWith(STORAGE);
        // the name on disk is the generated id, and carries nothing from the client's string
        assertThat(stored.getFileName().toString()).matches(UUID_PATTERN);
        assertThat(stored.toString()).doesNotContain("..").doesNotContain("passwd");
        // the client's string survives as display metadata only
        assertThat(saved.getFileName()).isEqualTo(hostile);
        // and nothing was written anywhere else under the root
        assertThat(filesUnderStorage()).contains(stored).allSatisfy(p -> assertThat(p).startsWith(STORAGE));
    }

    @Test
    void twoUploadsOfTheSameFilenameDoNotOverwriteEachOther() {
        // The name on disk is generated, so "contract.pdf" twice is two files, not one clobbered.
        UUID contractId = draftContract();
        Attachment first = upload(contractId, "contract.pdf", "application/pdf", "version one");
        Attachment second = upload(contractId, "contract.pdf", "application/pdf", "version two");

        assertThat(first.getStoragePath()).isNotEqualTo(second.getStoragePath());
        assertThat(read(first)).isEqualTo("version one");
        assertThat(read(second)).isEqualTo("version two");
    }

    // ---- CTR-01 owner guard ------------------------------------------------------------------

    @Test
    void uploadIsRejectedWhenTheOwnerIsNoLongerEditable() {
        UUID contractId = draftContract();
        forceStatus(contractId, DocumentStatus.ACTIVE);
        MockMultipartFile file = multipart("late.pdf", "application/pdf", "too late");
        List<Path> storedBefore = filesUnderStorage();

        assertThatThrownBy(() -> tx.execute(s ->
                attachments.upload(EntityType.CONTRACT, contractId, file)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("CTR-01");
        assertThat(filesUnderStorage()).containsExactlyInAnyOrderElementsOf(storedBefore);
    }

    @Test
    void deleteIsRejectedWhenTheOwnerIsNoLongerEditable() {
        UUID contractId = draftContract();
        Attachment saved = upload(contractId, "evidence.pdf", "application/pdf", "keep me");
        forceStatus(contractId, DocumentStatus.APPROVED);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> attachments.delete(saved.getId())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("CTR-01");
        // refused means refused on both stores — the file is still there
        assertThat(Path.of(saved.getStoragePath())).exists();
        assertThat(rowExists(saved)).isTrue();
    }

    @Test
    void uploadingToAnUnknownOwnerIsNotFound() {
        MockMultipartFile file = multipart("orphan.pdf", "application/pdf", "no owner");
        UUID missing = UUID.randomUUID();
        List<Path> storedBefore = filesUnderStorage();

        assertThatThrownBy(() -> tx.execute(s ->
                attachments.upload(EntityType.CONTRACT, missing, file)))
                .isInstanceOf(NotFoundException.class);
        assertThat(filesUnderStorage()).containsExactlyInAnyOrderElementsOf(storedBefore);
    }

    // ---- bytes/row reconciliation -------------------------------------------------------------

    @Test
    void aRolledBackUploadLeavesNoOrphanFile() {
        UUID contractId = draftContract();
        MockMultipartFile file = multipart("doomed.pdf", "application/pdf", "never committed");

        String storagePath = tx.execute(s -> {
            String path = attachments.upload(EntityType.CONTRACT, contractId, file).getStoragePath();
            s.setRollbackOnly();
            return path;
        });

        // The row is gone with the transaction; the file must go with it, or the volume fills up
        // with bytes no row will ever reference again.
        assertThat(Path.of(storagePath)).doesNotExist();
        List<Attachment> remaining = tx.execute(s -> attachments.list(EntityType.CONTRACT, contractId));
        assertThat(remaining).isEmpty();
        assertThat(filesUnderStorage()).doesNotContain(Path.of(storagePath));
    }

    @Test
    void deleteRemovesTheBytesOnceTheRowIsActuallyGone() {
        UUID contractId = draftContract();
        Attachment saved = upload(contractId, "temporary.pdf", "application/pdf", "delete me");
        Path stored = Path.of(saved.getStoragePath());
        assertThat(stored).exists();

        tx.executeWithoutResult(s -> attachments.delete(saved.getId()));

        assertThat(stored).doesNotExist();
        assertThat(rowExists(saved)).isFalse();
    }

    @Test
    void aRolledBackDeleteKeepsTheBytes() {
        // The other half of the trade: deleting the file before commit would destroy it here,
        // where the row survives and still points at it.
        UUID contractId = draftContract();
        Attachment saved = upload(contractId, "survivor.pdf", "application/pdf", "still needed");
        Path stored = Path.of(saved.getStoragePath());

        tx.executeWithoutResult(s -> {
            attachments.delete(saved.getId());
            s.setRollbackOnly();
        });

        assertThat(stored).exists();
        assertThat(rowExists(saved)).isTrue();
        assertThat(read(saved)).isEqualTo("still needed");
    }

    // ---- metadata validation -----------------------------------------------------------------

    @Test
    void anUploadWithoutAFilenameIsRejected() {
        // file_name is NOT NULL; without this check the request dies as a constraint violation at
        // flush time instead of telling the caller what was wrong.
        UUID contractId = draftContract();

        assertThatThrownBy(() -> tx.execute(s -> attachments.upload(EntityType.CONTRACT, contractId,
                new MockMultipartFile("file", null, "application/pdf", "bytes".getBytes(StandardCharsets.UTF_8)))))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("file name");

        assertThatThrownBy(() -> tx.execute(s -> attachments.upload(EntityType.CONTRACT, contractId,
                new MockMultipartFile("file", "   ", "application/pdf", "bytes".getBytes(StandardCharsets.UTF_8)))))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void anUnparseableContentTypeFallsBackToOctetStream() {
        // The content type is client text and is only ever read back on download. Stored verbatim,
        // "not/a/media/type" makes a successfully uploaded file impossible to retrieve.
        UUID contractId = draftContract();

        Attachment saved = upload(contractId, "odd.bin", "not/a/media/type", "bytes");

        assertThat(saved.getContentType()).isEqualTo("application/octet-stream");
        assertThat(tx.execute(s -> attachments.download(saved.getId())).metadata().getContentType())
                .isEqualTo("application/octet-stream");
    }

    @Test
    void anAbsentContentTypeFallsBackToOctetStream() {
        UUID contractId = draftContract();
        Attachment saved = upload(contractId, "unknown.bin", null, "bytes");

        assertThat(saved.getContentType()).isEqualTo("application/octet-stream");
    }

    // ---- orphan recovery ------------------------------------------------------------------------

    @Test
    void theSweepDeletesAFileNoRowReferences() throws Exception {
        // The two cases a transaction synchronization cannot cover: dying between the write and
        // the commit, and a post-commit delete whose IO failed. Both leave exactly this.
        Path orphan = STORAGE.resolve("contract").resolve(UUID.randomUUID().toString());
        Files.createDirectories(orphan.getParent());
        Files.writeString(orphan, "nobody's file");

        int deleted = sweep.removeOrphans();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(orphan).doesNotExist();
    }

    @Test
    void theSweepLeavesReferencedFilesAlone() {
        UUID contractId = draftContract();
        Attachment saved = upload(contractId, "keep.pdf", "application/pdf", "referenced");

        sweep.removeOrphans();

        assertThat(Path.of(saved.getStoragePath())).exists();
        assertThat(rowExists(saved)).isTrue();
    }

    @Test
    void theSweepNeverRemovesTheRowForAMissingFile() throws Exception {
        // Direction matters: the row is the record of truth, so a missing file is a fault to be
        // reported, not tidied away. Deleting rows here would silently drop CTR-02 evidence.
        UUID contractId = draftContract();
        Attachment saved = upload(contractId, "vanished.pdf", "application/pdf", "gone soon");
        Files.delete(Path.of(saved.getStoragePath()));

        sweep.removeOrphans();

        assertThat(rowExists(saved)).isTrue();
    }

    @Test
    void aRolledBackUploadsFileIsGoneEvenIfTheSweepNeverRuns() {
        // Belt and braces: the sweep is recovery, not the primary mechanism.
        UUID contractId = draftContract();
        MockMultipartFile file = multipart("doomed.pdf", "application/pdf", "never committed");

        String path = tx.execute(s -> {
            String stored = attachments.upload(EntityType.CONTRACT, contractId, file).getStoragePath();
            s.setRollbackOnly();
            return stored;
        });

        assertThat(Path.of(path)).doesNotExist();
    }

    // ---- audit ---------------------------------------------------------------------------------

    @Test
    void attachAndDetachAreAudited() {
        UUID contractId = draftContract();
        Attachment saved = upload(contractId, "audited.pdf", "application/pdf", "trace me");
        tx.executeWithoutResult(s -> attachments.delete(saved.getId()));

        List<String> payloads = tx.execute(s -> outbox.findAll().stream()
                .filter(e -> contractId.equals(e.getAggregateId()))
                .sorted(Comparator.comparing(OutboxEvent::getCreatedAt))
                .map(OutboxEvent::getPayload)
                .toList());

        // jsonb round-trips with its own spacing, so match the field, not the formatting
        assertThat(payloads).anySatisfy(p -> assertThat(p)
                .containsPattern(action("ATTACH")).contains("audited.pdf"));
        assertThat(payloads).anySatisfy(p -> assertThat(p)
                .containsPattern(action("DETACH")).contains("audited.pdf"));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static String action(String value) {
        return "\"action\"\\s*:\\s*\"" + value + "\"";
    }

    private boolean rowExists(Attachment attachment) {
        Boolean exists = tx.execute(s -> attachmentRepository.existsById(attachment.getId()));
        return Boolean.TRUE.equals(exists);
    }

    private Attachment upload(UUID contractId, String fileName, String contentType, String body) {
        MockMultipartFile file = multipart(fileName, contentType, body);
        return tx.execute(s -> attachments.upload(EntityType.CONTRACT, contractId, file));
    }

    private static MockMultipartFile multipart(String fileName, String contentType, String body) {
        return new MockMultipartFile("file", fileName, contentType,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Attachment attachment) {
        try {
            return Files.readString(Path.of(attachment.getStoragePath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> filesUnderStorage() {
        try (Stream<Path> walk = Files.walk(STORAGE)) {
            return walk.filter(Files::isRegularFile).map(Path::toAbsolutePath).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private UUID draftContract() {
        UUID customerId = tx.execute(s -> customers.create(
                new CustomerRequest("ACME Logistics", null, null, null, null, null, null, List.of()))
                .getId());
        return tx.execute(s -> contracts.create(new ContractRequest(
                customerId, "initial", "TRANSPORTATION",
                new BigDecimal("1000000"), "VND",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, null)).getId());
    }

    /** Phase B items 5-9 own the real edges into these states; this shortcut only sets the column. */
    private void forceStatus(UUID id, DocumentStatus status) {
        tx.executeWithoutResult(s -> contractRepository.findById(id).orElseThrow().setStatus(status));
    }
}
