package com.abclogistics.pas.contract;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.ForbiddenException;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.controller.ContractController;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



/**
 * CTR-06 — an ACTIVE contract is never deleted; it is cancelled or expired, and the controlled
 * cancel needs {@code contract:cancel_active}.
 * CTR-07 — a terms change on an APPROVED or ACTIVE contract must go through an addendum.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class CTR06And07GuardTest {

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

    @MockitoBean WorkflowGrpcClient workflow;

    @Autowired ContractService contracts;
    @Autowired CustomerService customers;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void activeContractHasNoDeletePath() {
        // CTR-06: a live contract is cancelled or expired, never deleted. The rule is enforced by
        // the route not existing at all -- a guard inside a delete handler would still leave a
        // handler someone could later relax.
        assertThat(httpMethodsOf(ContractController.class))
                .doesNotContain(RequestMethod.DELETE);
    }

    @Test
    void cancellingActiveRequiresTheDedicatedPermission() {
        // contract:write is not enough -- cancelling a live contract is its own permission.
        // Code checks permissions, never roles.
        UUID id = activeContract();
        grant("contract:write");

        assertThatThrownBy(() -> contracts.cancel(id, "terminated early"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("contract:cancel_active");
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.ACTIVE);

        // SALES is in the principal's roles throughout and buys nothing on its own.
        grant("contract:write", "contract:cancel_active");
        contracts.cancel(id, "terminated early");
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.CANCELLED);
    }

    @Test
    void termsChangeOnApprovedOrActiveIsRefusedAndNamesTheAddendumRoute() {
        // The refusal must tell the user what to do instead, or they will retry the same edit.
        for (DocumentStatus status : List.of(DocumentStatus.APPROVED, DocumentStatus.ACTIVE)) {
            UUID id = contractIn(status);
            Integer version = tx.execute(s -> contracts.get(id).getVersion());

            assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                    contracts.update(id, editWith(id, version, "renegotiated terms"))))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("CTR-01")
                    .hasMessageContaining("addendum")
                    .hasMessageContaining("CTR-07")
                    // the placeholders have to be substituted: .formatted binds to the last
                    // operand of a concatenation, which silently leaves %s in the message
                    .hasMessageContaining(status.name())
                    .hasMessageNotContaining("%s");

            assertThat(descriptionOf(id)).isEqualTo("initial");
        }
    }

    @Test
    void aDraftIsStillFreelyEditable() {
        // The CTR-07 guard must not have widened into a block on ordinary drafting.
        UUID id = contractIn(DocumentStatus.DRAFT);
        Integer version = tx.execute(s -> contracts.get(id).getVersion());

        tx.executeWithoutResult(s -> contracts.update(id, editWith(id, version, "second pass")));

        assertThat(descriptionOf(id)).isEqualTo("second pass");
    }

    // --- helpers ----------------------------------------------------------------------------

    private static List<RequestMethod> httpMethodsOf(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(httpMethods(method)))
                .toList();
    }

    private static RequestMethod[] httpMethods(Method method) {
        if (method.isAnnotationPresent(DeleteMapping.class)) {
            return new RequestMethod[] { RequestMethod.DELETE };
        }
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        return mapping == null ? new RequestMethod[0] : mapping.method();
    }

    private void grant(String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null,
                        Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }

    private ContractRequest editWith(UUID id, Integer version, String description) {
        UUID customerId = tx.execute(s -> contracts.get(id).getCustomer().getId());
        return new ContractRequest(customerId, description, "TRANSPORTATION",
                new BigDecimal("1000000"), "VND",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, version);
    }

    private UUID activeContract() {
        return contractIn(DocumentStatus.ACTIVE);
    }

    private UUID contractIn(DocumentStatus status) {
        UUID customerId = tx.execute(s -> customers.create(new CustomerRequest(
                "ACME Logistics", null, null, null, null, null, null, List.of())).getId());
        UUID id = tx.execute(s -> contracts.create(new ContractRequest(
                customerId, "initial", "TRANSPORTATION", new BigDecimal("1000000"), "VND",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, null)).getId());
        if (status != DocumentStatus.DRAFT) {
            tx.executeWithoutResult(s -> contracts.get(id).setStatus(status));
        }
        return id;
    }

    private DocumentStatus statusOf(UUID id) {
        return tx.execute(s -> contracts.get(id).getStatus());
    }

    private String descriptionOf(UUID id) {
        return tx.execute(s -> contracts.get(id).getDescription());
    }
}
