package com.abclogistics.pas.contract;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.dto.AddendumRequest;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.grpc.ContractInternalGrpcService;
import com.abclogistics.pas.contract.grpc.GetContractRequest;
import com.abclogistics.pas.contract.grpc.GetContractResponse;
import com.abclogistics.pas.contract.service.AddendumService;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient;

import io.grpc.stub.StreamObserver;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ContractInternal.GetContract} returns the STORED effective values — what billing
 * snapshots for PAY-03. After a TERM_EXTENSION or PAYMENT_TERMS addendum takes effect, the parent
 * row already carries the new values (registry §9 footnote ²), so there is no addendum replay at
 * read time and no way for a caller to see stale terms.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class GetContractReturnsEffectiveValuesTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_contract").withUsername("pas").withPassword("pas");

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
        registry.add("outbox.relay.enabled", () -> "false");
        registry.add("contract.kafka.listener-enabled", () -> "false");
        // the D14d sweep runs on a schedule; these tests drive their own dates and statuses
        registry.add("contract.status-sweep-enabled", () -> "false");
    }

    private static final AuthenticatedUser SALES = new AuthenticatedUser(
            UUID.randomUUID(), "lan.nt", "Nguyen Thi Lan", "SALES", List.of("SALES"));

    private static final List<GrantedAuthority> SALES_OFFICER_PERMISSIONS = Stream.of(
            "customer:read", "customer:write", "contract:read", "contract:write",
            "addendum:read", "addendum:write")
            .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();

    private static final LocalDate TODAY = LocalDate.now();

    @MockitoBean WorkflowGrpcClient workflow;

    @Autowired ContractInternalGrpcService service;
    @Autowired ContractService contracts;
    @Autowired AddendumService addenda;
    @Autowired CustomerService customers;
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

    @Test
    void returnsPostAddendumValidToAndPaymentTerm() {
        UUID id = activeContract();
        assertThat(getContract(id).getValidTo()).isEqualTo(TODAY.plusYears(1).toString());
        assertThat(getContract(id).getPaymentTerm()).isEqualTo("NET30");

        activate(new AddendumRequest(id, "TERM_EXTENSION", "renewal",
                TODAY, TODAY.plusYears(3), null, null, null));
        activate(new AddendumRequest(id, "PAYMENT_TERMS", "terms change",
                TODAY, null, "NET60", null, null));

        GetContractResponse response = getContract(id);

        // both effects are already on the parent row: nothing here replays an addendum, and a
        // caller that snapshots this cannot end up invoicing against superseded terms (PAY-03)
        assertThat(response.getValidTo()).isEqualTo(TODAY.plusYears(3).toString());
        assertThat(response.getPaymentTerm()).isEqualTo("NET60");
    }

    @Test
    void returnsServiceGroupAsTheSingleEnforcedScope() {
        // addendum_service lines are record-only and must NOT appear here: operations-service
        // validates volume entries against service_group alone (session 5 owns any change to that).
        UUID id = activeContract();

        activate(new AddendumRequest(id, "ADDED_SERVICE", "adds a warehousing line",
                TODAY, null, null,
                List.of(new AddendumRequest.ServiceLine(null, "WH-01", "Warehousing",
                        "pallet/month", "Zone A")),
                null));

        GetContractResponse response = getContract(id);

        // the contract's own group, unchanged by an ADDED_SERVICE addendum
        assertThat(response.getServiceGroup()).isEqualTo("TRANSPORTATION");
        // and it is the ONLY scope on the wire: the proto carries no service-line field for a
        // caller to prefer, so operations cannot start validating volumes against one
        assertThat(GetContractResponse.getDescriptor().getFields())
                .extracting(com.google.protobuf.Descriptors.FieldDescriptor::getName)
                .contains("service_group")
                .noneMatch(name -> name.contains("service_line") || name.contains("services"));
    }

    @Test
    void expiredContractIsStillReadable() {
        // PAY-01: a contract ending 30/06 is EXPIRED by the time its June statement is built,
        // so an ACTIVE-only guard would make every contract's final period unbillable.
        UUID id = activeContract();
        tx.executeWithoutResult(s -> {
            contracts.get(id).setValidTo(TODAY.minusDays(1));
            contracts.get(id).setStatus(DocumentStatus.EXPIRED);
        });

        GetContractResponse response = getContract(id);

        assertThat(response.getStatus()).isEqualTo("EXPIRED");
        assertThat(response.getValidTo()).isEqualTo(TODAY.minusDays(1).toString());
        // the terms billing needs are all still there — the read is not narrowed by the status
        assertThat(response.getPaymentTerm()).isEqualTo("NET30");
        assertThat(response.getVatRate()).isEqualTo(10.0d);
        assertThat(response.getServiceGroup()).isEqualTo("TRANSPORTATION");
    }

    // --- helpers --------------------------------------------------------------------------------

    private UUID activeContract() {
        UUID customerId = tx.execute(s -> customers.create(new CustomerRequest(
                "ACME Logistics", null, null, null, null, null, null, List.of())).getId());
        UUID id = tx.execute(s -> contracts.create(new ContractRequest(
                customerId, "effective values", "TRANSPORTATION", new BigDecimal("1000000"), "VND",
                TODAY.minusDays(1), TODAY.plusYears(1),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, null)).getId());
        tx.executeWithoutResult(s -> contracts.get(id).setStatus(DocumentStatus.ACTIVE));
        return id;
    }

    /** Raise the addendum, approve it, and let D14d apply its effects to the parent. */
    private void activate(AddendumRequest request) {
        UUID addendumId = tx.execute(s -> addenda.create(request).getId());
        tx.executeWithoutResult(s -> addenda.get(addendumId).setStatus(DocumentStatus.APPROVED));
        tx.executeWithoutResult(s -> addenda.activate(addendumId));
    }

    private GetContractResponse getContract(UUID id) {
        AtomicReference<GetContractResponse> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        service.getContract(GetContractRequest.newBuilder().setId(id.toString()).build(),
                new StreamObserver<>() {
                    @Override public void onNext(GetContractResponse v) { value.set(v); }
                    @Override public void onError(Throwable t) { error.set(t); }
                    @Override public void onCompleted() { }
                });
        if (error.get() != null) {
            throw (RuntimeException) error.get();
        }
        return value.get();
    }
}
