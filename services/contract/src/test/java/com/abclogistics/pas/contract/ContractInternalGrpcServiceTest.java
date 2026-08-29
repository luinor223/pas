package com.abclogistics.pas.contract;

import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.dto.AddendumRequest;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerContactRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.grpc.ContractInternalGrpcService;
import com.abclogistics.pas.contract.grpc.GetContractRequest;
import com.abclogistics.pas.contract.grpc.GetContractResponse;
import com.abclogistics.pas.contract.grpc.GetSigningPayloadRequest;
import com.abclogistics.pas.contract.grpc.GetSigningPayloadResponse;
import com.abclogistics.pas.contract.service.AddendumService;
import com.abclogistics.pas.contract.service.AttachmentService;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Item 13 — the read side other services see (registry §5, D16). Two callers depend on it and
 * neither exists yet, so the contract is defended here rather than at the first integration:
 * billing snapshots GetContract (PAY-03), esign fetches GetSigningPayload (D10).
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class ContractInternalGrpcServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_contract").withUsername("pas").withPassword("pas");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    static final java.nio.file.Path STORAGE = createTempStorage();

    private static java.nio.file.Path createTempStorage() {
        try {
            return java.nio.file.Files.createTempDirectory("pas-grpc").toRealPath();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
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
    }

    private static final AuthenticatedUser SALES = new AuthenticatedUser(
            UUID.randomUUID(), "lan.nt", "Nguyen Thi Lan", "SALES", List.of("SALES"));

    private static final List<GrantedAuthority> SALES_OFFICER_PERMISSIONS = Stream.of(
            "customer:read", "customer:write", "contract:read", "contract:write",
            "addendum:read", "addendum:write")
            .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();

    private static final byte[] SIGNED_PDF = "%PDF-1.7 the contract as uploaded"
            .getBytes(StandardCharsets.UTF_8);

    @MockitoBean WorkflowGrpcClient workflow;

    @Autowired ContractInternalGrpcService service;
    @Autowired ContractService contracts;
    @Autowired AddendumService addenda;
    @Autowired CustomerService customers;
    @Autowired AttachmentService attachments;
    @Autowired JdbcTemplate jdbc;
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

    // --- GetContract ----------------------------------------------------------------------------

    @Test
    void getContractReturnsEverythingBillingSnapshots() {
        // registry §5: the whole row PAY-03 stores, so billing never has to call twice
        UUID id = contract(DocumentStatus.ACTIVE);

        GetContractResponse response = getContract(id.toString());

        assertThat(response.getContractNo()).startsWith("CTR-");
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        assertThat(response.getValidFrom()).isEqualTo(LocalDate.now().minusDays(1).toString());
        assertThat(response.getValidTo()).isEqualTo(LocalDate.now().plusYears(1).toString());
        assertThat(response.getServiceGroup()).isEqualTo("TRANSPORTATION");
        assertThat(response.getVatRate()).isEqualTo(10.0);
        assertThat(response.getPaymentTerm()).isEqualTo("NET30");
        assertThat(response.getCustomerName()).isEqualTo("ACME Logistics");
        assertThat(response.getCurrency()).isEqualTo("VND");
        assertThat(response.getCustomerId()).isNotBlank();
    }

    @Test
    void getContractReturnsTheEffectiveValuesNotTheOriginalOnes() {
        // §9²: an activated addendum has already rewritten the row, so there is nothing for
        // billing to recompose — it snapshots what it is handed.
        UUID id = contract(DocumentStatus.ACTIVE);
        UUID addendum = approvedAddendum(new AddendumRequest(id, "TERM_EXTENSION", "renewal",
                LocalDate.now(), LocalDate.now().plusYears(2), null, null, null));
        tx.executeWithoutResult(s -> addenda.activate(addendum));

        GetContractResponse response = getContract(id.toString());

        assertThat(response.getValidTo()).isEqualTo(LocalDate.now().plusYears(2).toString());
    }

    @Test
    void anUnknownContractIsNotFound() {
        assertThatThrownBy(() -> getContract(UUID.randomUUID().toString()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void anIdThatIsNotAUuidIsAnInvalidArgumentNotAnInternalError() {
        assertThatThrownBy(() -> getContract("not-a-uuid"))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void aNullVatRateIsRefusedRatherThanReportedAsZero() {
        // proto3 cannot carry the difference and billing snapshots the field, so "not yet stated"
        // arriving as "0% VAT" would be exactly the invoice drift the design forbids.
        UUID id = contract(DocumentStatus.DRAFT);
        jdbc.update("update contract.contract set vat_rate = null where id = ?", id);

        assertThatThrownBy(() -> getContract(id.toString()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("never reported as 0");
    }

    // --- GetSigningPayload ----------------------------------------------------------------------

    @Test
    void getSigningPayloadServesAnApprovedContract() {
        UUID id = contract(DocumentStatus.APPROVED);
        attach(EntityType.CONTRACT, id);

        GetSigningPayloadResponse response = getSigningPayload("CONTRACT", id.toString());

        assertThat(response.getDocumentNo()).startsWith("CTR-");
        assertThat(response.getSignerName()).isEqualTo("Tran Thi B");
        assertThat(response.getSignerEmail()).isEqualTo("b@acme.vn");
        // the document as uploaded, not a rendering invented here: nobody approved a generated one
        assertThat(response.getPdfContent().toByteArray()).isEqualTo(SIGNED_PDF);
    }

    @Test
    void getSigningPayloadServesAnApprovedAddendumThroughItsParentsCustomer() {
        // D10 covers addenda too, and an addendum has no customer of its own.
        UUID contractId = contract(DocumentStatus.ACTIVE);
        UUID id = approvedAddendum(new AddendumRequest(contractId, "PAYMENT_TERMS", "terms",
                LocalDate.now(), null, "NET60", null, null));
        attach(EntityType.ADDENDUM, id);

        GetSigningPayloadResponse response = getSigningPayload("ADDENDUM", id.toString());

        assertThat(response.getDocumentNo()).startsWith("ADD-");
        assertThat(response.getSignerEmail()).isEqualTo("b@acme.vn");
    }

    @Test
    void aDocumentThatIsNotApprovedIsRefused() {
        // registry §5's guard. Unlike billing's it is not widened: nothing here flips a status
        // before dispatching, so APPROVED is always reachable.
        UUID id = contract(DocumentStatus.ACTIVE);
        attach(EntityType.CONTRACT, id);

        assertThatThrownBy(() -> getSigningPayload("CONTRACT", id.toString()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void anUnsupportedDocumentTypeIsAnInvalidArgument() {
        // contract-service owns two document types; a statement belongs to billing's endpoint.
        assertThatThrownBy(() -> getSigningPayload("STATEMENT", UUID.randomUUID().toString()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void aDocumentWithNoAttachmentIsRefusedRatherThanSentEmpty() {
        // An empty pdf_content would reach the provider as a blank document to sign.
        UUID id = contract(DocumentStatus.APPROVED);

        assertThatThrownBy(() -> getSigningPayload("CONTRACT", id.toString()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("no attachment");
    }

    @Test
    void theNewestAttachmentIsTheOneSent() {
        // A re-upload before approval corrects the one before it; sending the superseded file
        // would put the wrong terms in front of the signer.
        UUID id = contract(DocumentStatus.APPROVED);
        attach(EntityType.CONTRACT, id);
        byte[] corrected = "%PDF-1.7 corrected".getBytes(StandardCharsets.UTF_8);
        tx.execute(s -> attachments.upload(EntityType.CONTRACT, id,
                new MockMultipartFile("file", "contract-v2.pdf", "application/pdf", corrected)));

        GetSigningPayloadResponse response = getSigningPayload("CONTRACT", id.toString());

        assertThat(response.getPdfContent().toByteArray()).isEqualTo(corrected);
    }

    @Test
    void aCustomerWithNoPrimaryContactIsRefused() {
        UUID id = contractFor(new CustomerRequest("Beta Trading", null, null, null, null, null,
                null, List.of()), DocumentStatus.APPROVED);
        attach(EntityType.CONTRACT, id);

        assertThatThrownBy(() -> getSigningPayload("CONTRACT", id.toString()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("primary customer contact");
    }

    // --- helpers --------------------------------------------------------------------------------

    private GetContractResponse getContract(String id) {
        Collector<GetContractResponse> collector = new Collector<>();
        service.getContract(GetContractRequest.newBuilder().setId(id).build(), collector);
        return collector.result();
    }

    private GetSigningPayloadResponse getSigningPayload(String documentType, String id) {
        Collector<GetSigningPayloadResponse> collector = new Collector<>();
        service.getSigningPayload(GetSigningPayloadRequest.newBuilder()
                .setDocumentType(documentType).setId(id).build(), collector);
        return collector.result();
    }

    /** The service answers through a StreamObserver, so the test needs somewhere to put it. */
    private static final class Collector<T> implements StreamObserver<T> {
        private final AtomicReference<T> value = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override public void onNext(T v) { value.set(v); }
        @Override public void onError(Throwable t) { error.set(t); }
        @Override public void onCompleted() { }

        T result() {
            if (error.get() != null) {
                throw (RuntimeException) error.get();
            }
            return value.get();
        }
    }

    private UUID contract(DocumentStatus status) {
        return contractFor(new CustomerRequest("ACME Logistics", null, null, null, null, null, null,
                List.of(new CustomerContactRequest("Tran Thi B", "Director", "b@acme.vn",
                        "0900000000", true))), status);
    }

    private UUID contractFor(CustomerRequest customer, DocumentStatus status) {
        UUID customerId = tx.execute(s -> customers.create(customer).getId());
        UUID id = tx.execute(s -> contracts.create(new ContractRequest(
                customerId, "internal read", "TRANSPORTATION", new BigDecimal("1000000"), "VND",
                LocalDate.now().minusDays(1), LocalDate.now().plusYears(1),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, null)).getId());
        tx.executeWithoutResult(s -> contracts.get(id).setStatus(status));
        return id;
    }

    private UUID approvedAddendum(AddendumRequest request) {
        UUID id = tx.execute(s -> addenda.create(request).getId());
        tx.executeWithoutResult(s -> addenda.get(id).setStatus(DocumentStatus.APPROVED));
        return id;
    }

    private void attach(EntityType ownerType, UUID ownerId) {
        tx.execute(s -> attachments.upload(ownerType, ownerId,
                new MockMultipartFile("file", "contract.pdf", "application/pdf", SIGNED_PDF)));
    }
}
