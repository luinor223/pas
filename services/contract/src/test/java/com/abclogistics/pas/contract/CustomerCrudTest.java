package com.abclogistics.pas.contract;

import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.domain.Customer;
import com.abclogistics.pas.contract.domain.CustomerContact;
import com.abclogistics.pas.contract.domain.CustomerStatus;
import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerContactRequest;
import com.abclogistics.pas.contract.dto.CustomerMetricsResponse;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.dto.CustomerResponse;
import com.abclogistics.pas.common.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.repository.StatusHistoryRepository;
import com.abclogistics.pas.contract.repository.ContractRepository;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import com.abclogistics.pas.contract.service.PageableGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase B item 1 — customer master data (4.1).
 *
 * <p>Customers are the one aggregate here with no approval workflow: they move between ACTIVE and
 * SUSPENDED directly, so these tests assert that nothing writes {@code status_history} (D17 is for
 * documents with a state machine) while every change still lands in the audit outbox (D15).
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class CustomerCrudTest {

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

    @Autowired CustomerService customers;
    @Autowired ContractService contracts;
    @Autowired WebApplicationContext webContext;
    @Autowired FilterChainProxy securityFilterChain;
    @Autowired StringRedisTemplate redisTemplate;

    /** Built by hand rather than @AutoConfigureMockMvc: the security filter chain has to be in
     *  front of the controller, since that is what turns the edge's headers into permissions. */
    private MockMvc mvc;
    @Autowired OutboxRepository outbox;
    @Autowired StatusHistoryRepository history;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @MockitoSpyBean ContractRepository contractRepository;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null,
                        List.of(new SimpleGrantedAuthority("contract:read"))));
        // The HTTP tests go through HeaderAuthenticationFilter, which resolves permissions from
        // this key — without it customer:write is absent and every write would 403.
        mvc = MockMvcBuilders.webAppContextSetup(webContext).addFilters(securityFilterChain).build();
        redisTemplate.opsForValue().set("perm:role:SALES",
                "[\"customer:read\",\"customer:write\",\"contract:read\"]");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- create / read -------------------------------------------------------------------

    @Test
    void createAllocatesTheCodeAndStampsTheCreator() {
        Customer created = tx.execute(s -> customers.create(
                request("ACME Logistics", "0101234567")));

        assertThat(created.getCode()).matches("CUS-\\d{4}");
        assertThat(created.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(created.getCreatedBy()).isEqualTo(SALES.userId());
        assertThat(created.getCreatedByName()).isEqualTo(SALES.fullName());
        assertThat(created.getCreatedByDepartment()).isEqualTo(SALES.department());
    }

    @Test
    void codeIsServerGeneratedAndUniquePerCustomer() {
        // registry §2: the client cannot supply a code — CustomerRequest has no such field, and
        // two creates in a row must not collide.
        String first = tx.execute(s -> customers.create(request("First Co")).getCode());
        String second = tx.execute(s -> customers.create(request("Second Co")).getCode());

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void getUnknownCustomerIsNotFound() {
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> tx.execute(s -> customers.get(missing)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(missing.toString());
    }

    @Test
    void searchMatchesNameCodeAndTaxCode() {
        Customer target = tx.execute(s -> customers.create(
                request("Vietnam Cold Chain", "0312345678")));

        assertThat(ids(search("Cold Chain", null))).contains(target.getId());
        assertThat(ids(search(target.getCode(), null))).contains(target.getId());
        assertThat(ids(search("0312345678", null))).contains(target.getId());
        // case-insensitive, per the lower(...) like lower(...) in the query
        assertThat(ids(search("vietnam cold", null))).contains(target.getId());
    }

    @Test
    void searchFiltersByStatus() {
        Customer suspended = tx.execute(s -> customers.create(request("Dormant Co")));
        tx.executeWithoutResult(s -> customers.suspend(suspended.getId(), "no longer trading"));

        assertThat(ids(search(null, "SUSPENDED"))).contains(suspended.getId());
        assertThat(ids(search(null, "ACTIVE"))).doesNotContain(suspended.getId());
    }

    @Test
    void listCarriesExactContractsCount() {
        // The CONTRACTS column is server-counted per customer — exact at any scale, unlike
        // counting a size-capped contract page client-side.
        UUID withTwo = tx.execute(s -> customers.create(request("Two Contract Co")).getId());
        UUID withNone = tx.execute(s -> customers.create(request("No Contract Co")).getId());
        tx.executeWithoutResult(s -> {
            contracts.create(contractRequest(withTwo));
            contracts.create(contractRequest(withTwo));
        });

        Map<UUID, Long> counts = tx.execute(s -> customers
                .searchResponses(null, null, PageRequest.of(0, 50)).stream()
                .collect(Collectors.toMap(CustomerResponse::id, CustomerResponse::contractsCount)));

        assertThat(counts.get(withTwo)).isEqualTo(2L);
        assertThat(counts.get(withNone)).isEqualTo(0L);
    }

    @Test
    void detailCarriesContractsCount() throws Exception {
        UUID id = tx.execute(s -> customers.create(request("Counted Co")).getId());
        tx.executeWithoutResult(s -> contracts.create(contractRequest(id)));

        CustomerResponse detail = tx.execute(s -> customers.toResponse(customers.get(id)));

        assertThat(detail.contractsCount()).isEqualTo(1L);
        mvc.perform(get("/customers/{id}", id).headers(salesHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractsCount").value(1));
    }

    @Test
    void metricsAggregateEveryContractAndKeepCurrenciesSeparate() throws Exception {
        UUID id = tx.execute(s -> customers.create(request("Metrics Co")).getId());
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 16; i++) {
                createContract(id, "1000000", "VND", DocumentStatus.ACTIVE);
            }
            createContract(id, "2500000", "VND", DocumentStatus.APPROVED);
            createContract(id, "125.50", "USD", DocumentStatus.APPROVED);
            createContract(id, "999999999", "VND", DocumentStatus.DRAFT);
            createContract(id, "999", "USD", DocumentStatus.CANCELLED);
        });

        assertThat(tx.execute(s -> contracts.search(id, null, null, null,
                PageRequest.of(0, 15))).getContent()).hasSize(15);

        CustomerMetricsResponse metrics = tx.execute(s -> customers.metrics(id));
        assertThat(metrics.activeContracts()).isEqualTo(16);
        assertThat(metrics.approvedContractValues())
                .extracting(CustomerMetricsResponse.CurrencyValue::currency,
                        CustomerMetricsResponse.CurrencyValue::value)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("USD", "125.50"),
                        org.assertj.core.groups.Tuple.tuple("VND", "18500000.00"));

        mvc.perform(get("/customers/{id}/metrics", id).headers(salesHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeContracts").value(16))
                .andExpect(jsonPath("$.data.approvedContractValues[0].currency").value("USD"))
                .andExpect(jsonPath("$.data.approvedContractValues[0].value").value("125.50"))
                .andExpect(jsonPath("$.data.approvedContractValues[1].currency").value("VND"))
                .andExpect(jsonPath("$.data.approvedContractValues[1].value").value("18500000.00"));
    }

    @Test
    void metricsPreserveLargeDecimalsOmitNullsAndKeepZero() throws Exception {
        UUID id = tx.execute(s -> customers.create(request("Precise Metrics Co")).getId());
        tx.executeWithoutResult(s -> {
            createContract(id, "9007199254740993.11", "VND", DocumentStatus.ACTIVE);
            createContract(id, "1.22", "VND", DocumentStatus.APPROVED);
            createContract(id, null, "EUR", DocumentStatus.APPROVED);
            createContract(id, "0", "JPY", DocumentStatus.APPROVED);
        });

        CustomerMetricsResponse metrics = tx.execute(s -> customers.metrics(id));
        assertThat(metrics.activeContracts()).isEqualTo(1);
        assertThat(metrics.approvedContractValues())
                .extracting(CustomerMetricsResponse.CurrencyValue::currency,
                        CustomerMetricsResponse.CurrencyValue::value)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("JPY", "0.00"),
                        org.assertj.core.groups.Tuple.tuple("VND", "9007199254740994.33"));

        mvc.perform(get("/customers/{id}/metrics", id).headers(salesHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approvedContractValues[1].value")
                        .value("9007199254740994.33"));
    }

    @Test
    void customerContractPagesUseStableDefaultsForOmittedAndInvalidSorts() throws Exception {
        UUID id = tx.execute(s -> customers.create(request("Stable Pages Co")).getId());
        Contract first = tx.execute(s -> createContract(
                id, "1", "VND", DocumentStatus.DRAFT));
        Contract second = tx.execute(s -> createContract(
                id, "2", "VND", DocumentStatus.DRAFT));
        jdbc.update("update contract.contract set created_at = '2026-09-04T00:00:00Z' "
                + "where id in (?, ?)", first.getId(), second.getId());
        var firstPage = PageableGuard.sanitize(
                PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt")),
                PageableGuard.CONTRACT_SORTS);
        var secondPage = PageableGuard.sanitize(
                PageRequest.of(1, 1, Sort.by(Sort.Direction.DESC, "createdAt")),
                PageableGuard.CONTRACT_SORTS);

        // PostgreSQL compares UUID bytes as unsigned values, equivalent to canonical hex text.
        List<UUID> expected = java.util.stream.Stream.of(first.getId(), second.getId())
                .map(UUID::toString).sorted(Comparator.reverseOrder()).map(UUID::fromString).toList();
        assertThat(tx.execute(s -> contracts.search(id, null, null, null, firstPage)).getContent())
                .extracting(Contract::getId).containsExactly(expected.get(0));
        assertThat(tx.execute(s -> contracts.search(id, null, null, null, secondPage)).getContent())
                .extracting(Contract::getId).containsExactly(expected.get(1));

        for (String sortQuery : List.of("", "&sort=notAllowed,asc")) {
            mvc.perform(get("/contracts?customerId={id}&size=1&page=0" + sortQuery, id)
                            .headers(salesHeaders()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(expected.get(0).toString()));
            mvc.perform(get("/contracts?customerId={id}&size=1&page=1" + sortQuery, id)
                            .headers(salesHeaders()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(expected.get(1).toString()));
        }
    }

    @Test
    void snapshotCursorPreventsConcurrentInsertFromShiftingContractPages() throws Exception {
        UUID customerId = tx.execute(s -> customers.create(request("Snapshot Pages Co")).getId());
        List<UUID> originalIds = tx.execute(s -> List.of(
                contracts.create(contractRequest(customerId)).getId(),
                contracts.create(contractRequest(customerId)).getId(),
                contracts.create(contractRequest(customerId)).getId()));

        String firstBody = mvc.perform(get("/contracts")
                        .param("customerId", customerId.toString())
                        .param("size", "2")
                        .param("page", "0")
                        .headers(salesHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andReturn().getResponse().getContentAsString();
        JsonNode firstJson = objectMapper.readTree(firstBody);
        String cursor = firstJson.get("meta").get("cursor").asText();

        UUID insertedId;
        try (var executor = Executors.newSingleThreadExecutor()) {
            insertedId = executor.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(SALES, null,
                                List.of(new SimpleGrantedAuthority("contract:read"))));
                try {
                    return tx.execute(s -> contracts.create(contractRequest(customerId)).getId());
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }).get();
        }
        // Make the insertion boundary unambiguous even on databases with coarse clock precision.
        jdbc.update("update contract.contract set created_at = clock_timestamp() + interval '10 seconds' where id = ?",
                insertedId);

        String secondBody = mvc.perform(get("/contracts")
                        .param("customerId", customerId.toString())
                        .param("size", "2")
                        .param("page", "1")
                        .param("cursor", cursor)
                        .headers(salesHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andReturn().getResponse().getContentAsString();

        List<UUID> pagedIds = new ArrayList<>();
        firstJson.get("data").forEach(node -> pagedIds.add(UUID.fromString(node.get("id").asText())));
        objectMapper.readTree(secondBody).get("data")
                .forEach(node -> pagedIds.add(UUID.fromString(node.get("id").asText())));

        assertThat(pagedIds).hasSize(3).doesNotHaveDuplicates();
        assertThat(Set.copyOf(pagedIds)).isEqualTo(Set.copyOf(originalIds));
        assertThat(pagedIds).doesNotContain(insertedId);
    }

    @Test
    void invalidAndTamperedPageCursorsReturnSafeClientErrors() throws Exception {
        String firstBody = mvc.perform(get("/contracts").headers(salesHeaders()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String issued = objectMapper.readTree(firstBody).get("meta").get("cursor").asText();
        int signatureStart = issued.lastIndexOf('.') + 1;
        char firstSignatureCharacter = issued.charAt(signatureStart);
        String tampered = issued.substring(0, signatureStart)
                + (firstSignatureCharacter == 'A' ? 'B' : 'A')
                + issued.substring(signatureStart + 1);

        for (String cursor : List.of("not-a-valid-cursor", tampered)) {
            mvc.perform(get("/contracts").param("cursor", cursor).headers(salesHeaders()))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value(
                            "The page cursor is invalid or has expired; return to the first page."));
        }
    }

    @Test
    void metricsForACustomerWithoutContractsAreEmpty() throws Exception {
        UUID id = tx.execute(s -> customers.create(request("Empty Metrics Co")).getId());

        CustomerMetricsResponse metrics = tx.execute(s -> customers.metrics(id));
        assertThat(metrics.activeContracts()).isZero();
        assertThat(metrics.approvedContractValues()).isEmpty();

        mvc.perform(get("/customers/{id}/metrics", id).headers(salesHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeContracts").value(0))
                .andExpect(jsonPath("$.data.approvedContractValues").isEmpty());
    }

    @Test
    void metricsEndpointRejectsCustomerOnlyPermission() throws Exception {
        UUID id = tx.execute(s -> customers.create(request("Private Metrics Co")).getId());
        tx.executeWithoutResult(s -> contracts.create(contractRequest(id)));
        clearInvocations(contractRepository);
        redisTemplate.opsForValue().set("perm:role:SALES", "[\"customer:read\"]");

        mvc.perform(get("/customers/{id}", id).headers(salesHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractsCount").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/customers").param("q", "Private Metrics Co").headers(salesHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].contractsCount").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/customers/{id}/metrics", id).headers(salesHeaders()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.path").value("/customers/{id}/metrics"));

        verify(contractRepository, never()).countByCustomerId(any());
        verify(contractRepository, never()).countByCustomerIds(anyList());
    }

    @Test
    void metricsEndpointRejectsContractOnlyPermission() throws Exception {
        UUID id = tx.execute(s -> customers.create(request("Private Metrics Co")).getId());
        redisTemplate.opsForValue().set("perm:role:SALES", "[\"contract:read\"]");

        mvc.perform(get("/customers/{id}/metrics", id).headers(salesHeaders()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.path").value("/customers/{id}/metrics"));
    }

    @Test
    void metricsEndpointReturnsNotFoundForAnUnknownCustomer() throws Exception {
        UUID missing = UUID.fromString("00000000-0000-4000-8000-000000000099");

        mvc.perform(get("/customers/{id}/metrics", missing).headers(salesHeaders()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/customers/{id}/metrics"));
    }

    @Test
    void attachmentEndpointMissingOwnerTypeReturnsAStableSafeError() throws Exception {
        mvc.perform(get("/attachments")
                        .param("ownerId", UUID.randomUUID().toString())
                        .headers(salesHeaders()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_REQUEST_VALUE"))
                .andExpect(jsonPath("$.message").value("A required request value is missing."))
                .andExpect(jsonPath("$.path").value("/attachments"));
    }

    @Test
    void attachmentUploadMissingFileReturnsAStableSafeError() throws Exception {
        mvc.perform(multipart("/attachments")
                        .param("ownerType", "CONTRACT")
                        .param("ownerId", UUID.randomUUID().toString())
                        .headers(salesHeaders()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_REQUEST_VALUE"))
                .andExpect(jsonPath("$.message").value("A required request value is missing."))
                .andExpect(jsonPath("$.path").value("/attachments"));
    }

    @Test
    void unknownRouteUsesTheStableEnvelopeAndRedactsItsUuid() throws Exception {
        UUID secret = UUID.fromString("90000000-0000-4000-8000-000000000001");

        mvc.perform(get("/unknown-resource/{id}", secret).headers(salesHeaders()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("The requested resource was not found."))
                .andExpect(jsonPath("$.path").value("/unknown-resource/{id}"));
    }

    @Test
    void generatedOpenApiPublishesTheSharedErrorContract() throws Exception {
        String document = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(document);
        JsonNode error = root.path("components").path("schemas").path("ApiError");

        assertThat(error.path("required").valueStream().map(JsonNode::asString).toList())
                .contains("code", "violations", "message", "path");
        assertThat(error.path("properties").path("code").path("type").asString())
                .isEqualTo("string");
        assertThat(error.path("properties").path("violations").path("items").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApiErrorFieldViolation");
        assertThat(root.at("/paths/~1customers/get/responses/default/$ref").asString())
                .isEqualTo("#/components/responses/ApiErrorResponse");
    }

    @Test
    void generatedOpenApiMatchesTheVersionedContract() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replace("\r\n", "\n");
        Path snapshot = Path.of(System.getProperty("contract.openapi.snapshot"));
        if (Boolean.getBoolean("contract.openapi.update")) {
            Files.writeString(snapshot, generated);
        }

        assertThat(Files.readString(snapshot).replace("\r\n", "\n"))
                .as("Run ./gradlew :services:contract-service:test "
                        + "--tests com.abclogistics.pas.contract.CustomerCrudTest "
                        + "-PincludeIntegration -PupdateContractOpenApi after an intentional API change")
                .isEqualTo(generated);
    }

    // ---- update --------------------------------------------------------------------------

    @Test
    void updateChangesFieldsAndKeepsTheCode() {
        Customer created = tx.execute(s -> customers.create(request("Old Name")));
        String code = created.getCode();

        Customer updated = tx.execute(s -> customers.update(created.getId(),
                new CustomerRequest("New Name", "NN", "0100000000", "12 Le Loi",
                        "Tran Van B", "Director", "ENTERPRISE", List.of())));

        assertThat(updated.getCode()).isEqualTo(code);
        assertThat(updated.getName()).isEqualTo("New Name");
        assertThat(updated.getTaxCode()).isEqualTo("0100000000");
        assertThat(updated.getRepresentativeName()).isEqualTo("Tran Van B");
        assertThat(updated.getUpdatedBy()).isEqualTo(SALES.userId());
    }

    // ---- contact replacement semantics ---------------------------------------------------

    @Test
    void contactsAreReplacedWholesale() {
        UUID id = tx.execute(s -> customers.create(withContacts("Contactful Co", List.of(contact("Alpha", true), contact("Beta", false)))).getId());
        assertThat(namesOf(id)).containsExactlyInAnyOrder("Alpha", "Beta");

        tx.executeWithoutResult(s -> customers.update(id,
                withContacts("Contactful Co", List.of(contact("Gamma", true)))));

        // replaced, not merged — the previous two are gone
        assertThat(namesOf(id)).containsExactly("Gamma");
    }

    @Test
    void nullContactsOnUpdateIsRejected() {
        UUID id = tx.execute(s -> customers.create(withContacts("Careful Co", List.of(contact("Keep Me", true)))).getId());
        CustomerRequest noContacts = withContacts("Careful Co", null);

        // PUT is full replacement, so an omitted contacts is an incomplete request — not a
        // licence to wipe the set behind the caller's back
        assertThatThrownBy(() -> tx.execute(s -> customers.update(id, noContacts)))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, ex -> {
                    assertThat(ex.getPublicCode()).isEqualTo("CUSTOMER_CONTACTS_REQUIRED");
                    assertThat(ex.getPublicMessage())
                            .isEqualTo("Include customer contacts when saving. To remove all contacts, submit an empty contact list.");
                    assertThat(ex.getMessage()).contains("omitted the contacts collection");
                });
        assertThat(namesOf(id)).containsExactly("Keep Me");
    }

    @Test
    void emptyContactsOnUpdateRemovesThemAll() {
        UUID id = tx.execute(s -> customers.create(withContacts("Careful Co", List.of(contact("Keep Me", true)))).getId());

        // [] is how you ask for the removal deliberately
        tx.executeWithoutResult(s -> customers.update(id, withContacts("Careful Co", List.of())));
        assertThat(namesOf(id)).isEmpty();
    }

    // The service-level tests above call CustomerService directly; these two go through the
    // controller so the published contract — status code and message a client actually sees —
    // is pinned, not just the service's behaviour.

    @Test
    void putWithoutContactsIs422OverHttp() throws Exception {
        UUID id = tx.execute(s -> customers.create(withContacts("Wire Co", List.of(contact("Keep Me", true)))).getId());

        mvc.perform(put("/customers/{id}", id)
                        .headers(salesHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Wire Co\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CUSTOMER_CONTACTS_REQUIRED"))
                .andExpect(jsonPath("$.message").value(
                        "Include customer contacts when saving. To remove all contacts, submit an empty contact list."));

        assertThat(namesOf(id)).containsExactly("Keep Me");
    }

    @Test
    void putWithEmptyContactsIs200OverHttp() throws Exception {
        UUID id = tx.execute(s -> customers.create(withContacts("Wire Co", List.of(contact("Drop Me", true)))).getId());

        mvc.perform(put("/customers/{id}", id)
                        .headers(salesHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Wire Co\",\"contacts\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contacts").isEmpty());

        assertThat(namesOf(id)).isEmpty();
    }

    @Test
    void atMostOnePrimaryContact() {
        UUID id = tx.execute(s -> customers.create(request("Two Bosses Co")).getId());
        CustomerRequest twoPrimaries = withContacts("Two Bosses Co", List.of(contact("Alpha", true), contact("Beta", true)));

        assertThatThrownBy(() -> tx.execute(s -> customers.update(id, twoPrimaries)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("primary");
    }

    @Test
    void replacingThePrimaryContactDoesNotTripTheUniqueIndex() {
        // The partial unique index is enforced per statement, so the delete of the old primary must
        // be flushed before the new one is inserted. Without that flush this fails on a duplicate.
        UUID id = tx.execute(s -> customers.create(withContacts("Handover Co", List.of(contact("Outgoing", true)))).getId());

        tx.executeWithoutResult(s -> customers.update(id,
                withContacts("Handover Co", List.of(contact("Incoming", true)))));

        List<CustomerContact> contacts = tx.execute(s -> customers.contactsOf(id));
        assertThat(contacts).singleElement()
                .satisfies(c -> {
                    assertThat(c.getFullName()).isEqualTo("Incoming");
                    assertThat(c.isPrimary()).isTrue();
                });
    }

    @Test
    void contactDetailsAreCarriedThrough() {
        UUID id = tx.execute(s -> customers.create(withContacts("Detailed Co", List.of(
                new CustomerContactRequest("Le Thi C", "Head of Ops", "c@acme.vn", "0900000001", true)))).getId());

        List<CustomerContact> contacts = tx.execute(s -> customers.contactsOf(id));
        assertThat(contacts).singleElement()
                .satisfies(c -> {
                    assertThat(c.getTitle()).isEqualTo("Head of Ops");
                    assertThat(c.getEmail()).isEqualTo("c@acme.vn");
                    assertThat(c.getPhone()).isEqualTo("0900000001");
                });
    }

    @Test
    void updateAuditNamesEveryChangedFieldWithItsOldAndNewValue() {
        Customer created = tx.execute(s -> customers.create(new CustomerRequest(
                "Old Name", "ON", "0100000000", "1 Nguyen Hue", "Tran Van A", "Director",
                "SME", List.of())));

        tx.executeWithoutResult(s -> customers.update(created.getId(), new CustomerRequest(
                "Old Name", "NN", "0209999999", "99 Le Loi", "Le Thi B", "CEO",
                "ENTERPRISE", List.of())));

        String payload = newestAuditFor(created.getId());
        assertThat(payload).containsPattern(field("action", "UPDATE"));
        // every field that moved, not just the name
        assertThat(payload).contains("taxCode").contains("0100000000").contains("0209999999");
        assertThat(payload).contains("address").contains("1 Nguyen Hue").contains("99 Le Loi");
        assertThat(payload).contains("representativeName").contains("Tran Van A").contains("Le Thi B");
        assertThat(payload).contains("representativePosition").contains("Director").contains("CEO");
        assertThat(payload).contains("segment").contains("SME").contains("ENTERPRISE");
        assertThat(payload).contains("shortName").contains("ON").contains("NN");
        // and the name, which did not move, is absent
        assertThat(payload).doesNotContain("\"name\"");
    }

    @Test
    void replacingTheContactSetIsAudited() {
        // Who the counterparty actually talks to is part of the customer record; an audit trail
        // that shows only scalar fields hides a contact handover completely.
        UUID id = tx.execute(s -> customers.create(withContacts("Contacted Co",
                List.of(contact("Outgoing", true)))).getId());

        tx.executeWithoutResult(s -> customers.update(id,
                withContacts("Contacted Co", List.of(contact("Incoming", true)))));

        String payload = newestAuditFor(id);
        assertThat(payload).contains("contacts").contains("Outgoing").contains("Incoming");
    }

    @Test
    void anUnchangedCustomerAuditsNoFieldChanges() {
        Customer created = tx.execute(s -> customers.create(request("Static Co", "0100000000")));

        tx.executeWithoutResult(s -> customers.update(created.getId(),
                updateOf("Static Co", "0100000000")));

        String payload = newestAuditFor(created.getId());
        assertThat(payload).containsPattern(field("action", "UPDATE"));
        assertThat(payload).containsPattern("\"changes\"\\s*:\\s*\\{\\s*\\}");
    }

    @Test
    void aReorderedContactSetIsNotAChange() {
        // The set is what matters, not the order it arrived in.
        UUID id = tx.execute(s -> customers.create(withContacts("Ordered Co",
                List.of(contact("Alpha", true), contact("Beta", false)))).getId());

        tx.executeWithoutResult(s -> customers.update(id,
                withContacts("Ordered Co", List.of(contact("Beta", false), contact("Alpha", true)))));

        assertThat(newestAuditFor(id)).doesNotContain("contacts");
    }

    // ---- filter parsing --------------------------------------------------------------------

    @Test
    void theStatusFilterIsCaseInsensitive() {
        Customer created = tx.execute(s -> customers.create(request("Lowercase Co")));

        assertThat(ids(search(null, "active"))).contains(created.getId());
        assertThat(ids(search(null, " Active "))).contains(created.getId());
    }

    @Test
    void anUnknownStatusFilterIsA422NamingWhatIsAllowed() {
        // A bare valueOf here is an IllegalArgumentException -- a 500 for a typo'd query
        // parameter, and inconsistent with how the contract filters answer.
        assertThatThrownBy(() -> search(null, "DORMANT"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("status")
                .hasMessageContaining("SUSPENDED");
    }

    // ---- suspend / activate --------------------------------------------------------------

    @Test
    void suspendAndActivateFlipTheStatus() {
        UUID id = tx.execute(s -> customers.create(request("Flipper Co")).getId());

        tx.executeWithoutResult(s -> customers.suspend(id, "unpaid invoices"));
        assertThat(statusOf(id)).isEqualTo(CustomerStatus.SUSPENDED);

        tx.executeWithoutResult(s -> customers.activate(id));
        assertThat(statusOf(id)).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    void repeatingTheCurrentStatusIsRejected() {
        UUID id = tx.execute(s -> customers.create(request("Steady Co")).getId());

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> customers.activate(id)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already active");

        tx.executeWithoutResult(s -> customers.suspend(id, "first time"));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> customers.suspend(id, "again")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already suspended");
    }

    @Test
    void suspendAuditCarriesTheReasonAndBothStatuses() {
        UUID id = tx.execute(s -> customers.create(request("Audited Co")).getId());
        tx.executeWithoutResult(s -> customers.suspend(id, "unpaid invoices"));

        String payload = newestAuditFor(id);
        // jsonb round-trips with its own spacing, so match the field, not the formatting
        assertThat(payload).containsPattern(field("action", "SUSPEND"));
        assertThat(payload).containsPattern(field("before_status", "ACTIVE"));
        assertThat(payload).containsPattern(field("after_status", "SUSPENDED"));
        assertThat(payload).contains("unpaid invoices");
        // D15: the actor comes from the security context, not from the request body
        assertThat(payload).contains(SALES.userId().toString());
        assertThat(payload).contains(SALES.fullName());
    }

    @Test
    void everyCustomerActionWritesOneAuditRow() {
        long before = tx.execute(s -> outbox.count());
        UUID id = tx.execute(s -> customers.create(request("Busy Co")).getId());
        tx.executeWithoutResult(s -> customers.update(id, updateOf("Busy Co Ltd", null)));
        tx.executeWithoutResult(s -> customers.suspend(id, "pause"));
        tx.executeWithoutResult(s -> customers.activate(id));

        List<String> actions = tx.execute(s -> outbox.findAll().stream()
                .filter(e -> id.equals(e.getAggregateId()))
                .sorted(Comparator.comparing(OutboxEvent::getCreatedAt))
                .map(OutboxEvent::getPayload)
                .map(CustomerCrudTest::actionOf)
                .toList());

        assertThat(actions).containsExactly("CREATE", "UPDATE", "SUSPEND", "ACTIVATE");
        long after = tx.execute(s -> outbox.count());
        assertThat(after).isEqualTo(before + 4);
    }

    @Test
    void customerChangesNeverWriteStatusHistory() {
        // D17 covers documents with a state machine. A customer is not one — its ACTIVE/SUSPENDED
        // flag has no workflow behind it, and a history row here would imply one.
        long before = tx.execute(s -> history.count());
        UUID id = tx.execute(s -> customers.create(request("Historyless Co")).getId());
        tx.executeWithoutResult(s -> customers.suspend(id, "pause"));
        tx.executeWithoutResult(s -> customers.activate(id));

        long after = tx.execute(s -> history.count());
        assertThat(after).isEqualTo(before);
    }

    // ---- helpers -------------------------------------------------------------------------

    private CustomerStatus statusOf(UUID id) {
        return tx.execute(s -> customers.get(id).getStatus());
    }

    private List<Customer> search(String q, String status) {
        return tx.execute(s -> customers.search(q, status, PageRequest.of(0, 50)).getContent());
    }

    private static List<UUID> ids(List<Customer> found) {
        return found.stream().map(Customer::getId).toList();
    }

    private List<String> namesOf(UUID customerId) {
        return tx.execute(s -> customers.contactsOf(customerId).stream()
                .map(CustomerContact::getFullName).toList());
    }

    private String newestAuditFor(UUID aggregateId) {
        return tx.execute(s -> outbox.findAll().stream()
                .filter(e -> aggregateId.equals(e.getAggregateId()))
                .max(Comparator.comparing(OutboxEvent::getCreatedAt))
                .orElseThrow()
                .getPayload());
    }

    /** A jsonb string field, tolerant of the whitespace Postgres chooses. */
    private static String field(String name, String value) {
        return "\"" + name + "\"\\s*:\\s*\"" + value + "\"";
    }

    private static String actionOf(String payload) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"action\"\\s*:\\s*\"([A-Z_]+)\"").matcher(payload);
        return m.find() ? m.group(1) : payload;
    }

    private static CustomerRequest request(String name) {
        return request(name, null);
    }

    private static ContractRequest contractRequest(UUID customerId) {
        return new ContractRequest(customerId, "counted work", "TRANSPORTATION",
                new BigDecimal("1000000"), "VND",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, null);
    }

    private Contract createContract(UUID customerId, String value, String currency,
                                    DocumentStatus status) {
        Contract contract = contracts.create(new ContractRequest(
                customerId, "metric work", "TRANSPORTATION",
                value == null ? null : new BigDecimal(value), currency,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, null));
        contract.setStatus(status);
        return contract;
    }

    private static CustomerRequest request(String name, String taxCode) {
        return new CustomerRequest(name, null, taxCode, null, null, null, null, null);
    }

    /** The identity headers the edge injects; the filter turns these into the SALES principal. */
    private static HttpHeaders salesHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", SALES.userId().toString());
        headers.set("X-Username", SALES.username());
        headers.set("X-Full-Name", SALES.fullName());
        headers.set("X-Department", SALES.department());
        headers.set("X-Roles", "[\"SALES\"]");
        return headers;
    }

    /** An update payload must carry contacts; these tests are about the scalar fields. */
    private static CustomerRequest updateOf(String name, String taxCode) {
        return new CustomerRequest(name, null, taxCode, null, null, null, null, List.of());
    }

    /** Null contacts is legal on create ("none supplied") and rejected on update. */
    private static CustomerRequest withContacts(String name, List<CustomerContactRequest> contacts) {
        return new CustomerRequest(name, null, null, null, null, null, null, contacts);
    }

    private static CustomerContactRequest contact(String fullName, boolean primary) {
        return new CustomerContactRequest(fullName, null, null, null, primary);
    }
}
