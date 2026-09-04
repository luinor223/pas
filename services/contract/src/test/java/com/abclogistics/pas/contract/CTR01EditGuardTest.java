package com.abclogistics.pas.contract;

import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.repository.ContractRepository;
import com.abclogistics.pas.contract.repository.StatusHistoryRepository;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CTR-01 — a contract is editable only in DRAFT or REVISION_REQUESTED, and the {@code version}
 * optimistic lock makes a concurrent edit lose rather than silently overwrite.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class CTR01EditGuardTest {

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

    @Autowired ContractService contracts;
    @Autowired CustomerService customers;
    @Autowired ContractRepository contractRepository;
    @Autowired StatusHistoryRepository historyRepository;
    @Autowired TransactionTemplate tx;

    @Test
    void draftIsEditable() {
        UUID id = tx.execute(s -> contracts.create(request(newCustomer())).getId());
        Contract updated = tx.execute(s -> contracts.update(id, requestFor(id, "revised terms")));

        assertThat(updated.getDescription()).isEqualTo("revised terms");
        assertThat(updated.getStatus()).isEqualTo(DocumentStatus.DRAFT);
    }

    @Test
    void editIsRejectedOutsideDraftAndRevisionRequested() {
        UUID id = tx.execute(s -> contracts.create(request(newCustomer())).getId());
        forceStatus(id, DocumentStatus.ACTIVE);

        assertThatThrownBy(() -> tx.execute(s -> contracts.update(id, requestFor(id, "sneaky"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("CTR-01")
                // CTR-07: the refusal must name the addendum route, or the user just retries.
                .hasMessageContaining("addendum");
    }

    @Test
    void staleVersionLosesTheOptimisticLock() {
        UUID id = tx.execute(s -> contracts.create(request(newCustomer())).getId());
        tx.execute(s -> contracts.update(id, requestFor(id, "first writer wins")));

        // A second writer still holding version 0 must be refused, not silently overwrite.
        ContractRequest stale = requestFor(id, "second writer", 0);
        assertThatThrownBy(() -> tx.execute(s -> contracts.update(id, stale)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        String persisted = tx.execute(s -> contracts.get(id).getDescription());
        assertThat(persisted).isEqualTo("first writer wins");
    }

    @Test
    void editingRevisionRequestedFlipsItBackToDraft() {
        // registry §9: REVISION_REQUESTED -> DRAFT happens BY editing; there is no separate action.
        UUID id = tx.execute(s -> contracts.create(request(newCustomer())).getId());
        forceStatus(id, DocumentStatus.REVISION_REQUESTED);
        long before = historyRepository.count();

        Contract updated = tx.execute(s -> contracts.update(id, requestFor(id, "addressed the comments")));

        assertThat(updated.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(updated.getDescription()).isEqualTo("addressed the comments");
        // D17: the flip wrote exactly one history row, in the same transaction as the edit.
        assertThat(historyRepository.count()).isEqualTo(before + 1);
        var rows = historyRepository
                .findByEntityTypeAndEntityIdOrderByOccurredAtAsc(EntityType.CONTRACT, id);
        var newest = rows.get(rows.size() - 1);
        assertThat(newest.getFromStatus()).isEqualTo(DocumentStatus.REVISION_REQUESTED);
        assertThat(newest.getToStatus()).isEqualTo(DocumentStatus.DRAFT);
    }

    @Test
    void updateWithoutVersionIsRefused() {
        UUID id = tx.execute(s -> contracts.create(request(newCustomer())).getId());
        ContractRequest noVersion = requestFor(id, "no lock supplied", null);

        assertThatThrownBy(() -> tx.execute(s -> contracts.update(id, noVersion)))
                .hasMessageContaining("version is required");
    }

    // ---- helpers -------------------------------------------------------------------------

    private UUID newCustomer() {
        return tx.execute(s -> customers.create(
                new CustomerRequest("ACME Logistics", null, null, null, null, null, null, List.of()))
                .getId());
    }

    private static ContractRequest request(UUID customerId) {
        return new ContractRequest(customerId, "initial", "TRANSPORTATION",
                new BigDecimal("1000000"), "VND",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, null);
    }

    private ContractRequest requestFor(UUID contractId, String description) {
        return requestFor(contractId, description, tx.execute(s -> contracts.get(contractId).getVersion()));
    }

    private ContractRequest requestFor(UUID contractId, String description, Integer version) {
        UUID customerId = tx.execute(s -> contracts.get(contractId).getCustomer().getId());
        return new ContractRequest(customerId, description, "TRANSPORTATION",
                new BigDecimal("1000000"), "VND",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, version);
    }

    /** Phase B items 5-9 own the real edges into these states; this shortcut only sets the column. */
    private void forceStatus(UUID id, DocumentStatus status) {
        tx.executeWithoutResult(s -> contractRepository.findById(id)
                .orElseThrow()
                .setStatus(status));
    }
}
