package com.abclogistics.pas.pricing;

import com.abclogistics.pas.pricing.domain.PriceList;
import com.abclogistics.pas.pricing.domain.PriceListVersion;
import com.abclogistics.pas.pricing.domain.PriceListVersionStatus;
import com.abclogistics.pas.pricing.domain.StatusHistory;
import com.abclogistics.pas.pricing.domain.TriggerKind;
import com.abclogistics.pas.pricing.listener.WorkflowEventListener;
import com.abclogistics.pas.pricing.repository.PriceListVersionRepository;
import com.abclogistics.pas.pricing.repository.ProcessedEventRepository;
import com.abclogistics.pas.pricing.repository.StatusHistoryRepository;
import com.abclogistics.pas.pricing.service.EffectivePriceService;
import com.abclogistics.pas.pricing.service.EffectivePriceService.ResolvedPriceList;
import com.abclogistics.pas.pricing.service.PriceListService;
import com.abclogistics.pas.pricing.service.PriceListService.LineInput;
import com.abclogistics.pas.pricing.service.PriceListVersionService;
import com.abclogistics.pas.pricing.service.WorkflowGrpcClient;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PRC-03 overlap, truncate-then-approve (§9³), and the historical/precedence effective lookup. */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EffectivePricingIT {

    @MockitoBean WorkflowGrpcClient workflow;

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
        r.add("spring.grpc.server.port", () -> "0");
        r.add("outbox.relay.enabled", () -> "false");
        r.add("pricing.kafka.listener-enabled", () -> "false");
        r.add("pricing.status-sweep-enabled", () -> "false");
    }

    @Autowired PriceListService lists;
    @Autowired PriceListVersionService versionService;
    @Autowired EffectivePriceService effective;
    @Autowired WorkflowEventListener listener;
    @Autowired PriceListVersionRepository versions;
    @Autowired StatusHistoryRepository history;
    @Autowired ProcessedEventRepository processed;

    private PriceListVersion version(UUID priceListId, LocalDate from, LocalDate to) {
        PriceListVersion v = lists.addVersion(priceListId, from, to, null);
        lists.replaceLines(v.getId(), List.of(new LineInput("LIFT_ON_OFF", new BigDecimal("10.00"))));
        return v;
    }

    /** Approves via the consumer path (its @Transactional runs the truncate-then-approve). */
    private void approve(UUID versionId) {
        versionService.submit(versionId);
        listener.onEvent("{\"instance_id\":\"" + UUID.randomUUID() + "\",\"outcome\":\"APPROVED\"}",
                "workflow.completed", "PRICE_LIST", UUID.randomUUID().toString(), versionId.toString());
    }

    @Test
    void approvingSuccessorTruncatesPredecessorInOneTx() {
        PriceList list = lists.create(UUID.randomUUID(), null, "STEVEDORING", null);
        PriceListVersion v1 = version(list.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        approve(v1.getId());
        versionService.activate(v1.getId());   // EFFECTIVE

        PriceListVersion v2 = version(list.getId(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));
        approve(v2.getId());   // truncates v1 to 2026-06-30 in the same tx, then flips v2 APPROVED

        assertThat(versions.findById(v1.getId()).orElseThrow().getValidTo()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(versions.findById(v2.getId()).orElseThrow().getStatus()).isEqualTo(PriceListVersionStatus.APPROVED);
        assertThat(history.findByVersionIdOrderByCreatedAt(v1.getId()))
                .anyMatch(h -> h.getTriggerKind() == TriggerKind.S && h.getNote().startsWith("Truncated"));
    }

    @Test
    void overlappingApprovedVersionsRejectedByExclusion_PRC03() {
        PriceList list = lists.create(UUID.randomUUID(), null, "WAREHOUSING", null);
        PriceListVersion v1 = version(list.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        PriceListVersion v2 = version(list.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31));

        v1.setStatus(PriceListVersionStatus.APPROVED);
        versions.saveAndFlush(v1);
        v2.setStatus(PriceListVersionStatus.APPROVED);
        assertThatThrownBy(() -> versions.saveAndFlush(v2)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void historicalLookupResolvesSupersededVersion() {
        PriceList list = lists.create(UUID.randomUUID(), null, "TRANSPORTATION", null);
        UUID customerId = list.getCustomerId();
        PriceListVersion v1 = version(list.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        approve(v1.getId());
        versionService.activate(v1.getId());

        PriceListVersion v2 = version(list.getId(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));
        approve(v2.getId());
        versionService.activate(v2.getId());   // v1 -> SUPERSEDED (truncated to 2026-06-30)

        assertThat(versions.findById(v1.getId()).orElseThrow().getStatus()).isEqualTo(PriceListVersionStatus.SUPERSEDED);
        Optional<ResolvedPriceList> march = effective.resolve(null, customerId, "TRANSPORTATION", LocalDate.of(2026, 3, 1));
        Optional<ResolvedPriceList> september = effective.resolve(null, customerId, "TRANSPORTATION", LocalDate.of(2026, 9, 1));
        assertThat(march).get().extracting(ResolvedPriceList::versionNo).isEqualTo(1);   // the SUPERSEDED one
        assertThat(september).get().extracting(ResolvedPriceList::versionNo).isEqualTo(2);
    }

    @Test
    void contractScopeShadowsCustomerScope() {
        UUID customerId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 1, 1), to = LocalDate.of(2026, 12, 31);

        PriceList customerList = lists.create(customerId, null, "CONTAINER_HANDLING", null);
        approve(version(customerList.getId(), from, to).getId());

        PriceList contractList = lists.create(null, contractId, null, null);
        approve(version(contractList.getId(), from, to).getId());

        Optional<ResolvedPriceList> resolved =
                effective.resolve(contractId, customerId, "CONTAINER_HANDLING", LocalDate.of(2026, 6, 1));
        assertThat(resolved).get().extracting(ResolvedPriceList::priceListNo).isEqualTo(contractList.getPriceListNo());
    }

    @Test
    void emptyVersionCannotBeSubmitted() {
        PriceList list = lists.create(null, UUID.randomUUID(), null, null);
        PriceListVersion empty = lists.addVersion(
                list.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);

        assertThatThrownBy(() -> versionService.submit(empty.getId()))
                .isInstanceOf(FailedPreconditionException.class)
                .hasMessage("Add at least one price before submitting this version");
        assertThat(versions.findById(empty.getId()).orElseThrow().getStatus())
                .isEqualTo(PriceListVersionStatus.DRAFT);
    }

    @Test
    void priceListsAreFilteredAndPagedOnTheServer() {
        PriceList matching = lists.create(null, UUID.randomUUID(), null, "Annual terminal prices");
        lists.create(null, null, "WAREHOUSING", "Different scope");

        var result = lists.searchPage(null, null, null, "terminal", 0, 15);

        assertThat(result.getContent()).extracting(PriceList::getId).containsExactly(matching.getId());
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void competingOverlappingApprovalIsRejectedWithoutPoisoningTheEvent() {
        PriceList list = lists.create(null, UUID.randomUUID(), null, null);
        PriceListVersion later = version(list.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31));
        PriceListVersion earlier = version(list.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30));
        versionService.submit(later.getId());
        versionService.submit(earlier.getId());

        UUID firstEvent = UUID.randomUUID();
        listener.onEvent("{\"instance_id\":\"" + UUID.randomUUID() + "\",\"outcome\":\"APPROVED\"}",
                "workflow.completed", "PRICE_LIST", firstEvent.toString(), later.getId().toString());
        UUID conflictingEvent = UUID.randomUUID();
        listener.onEvent("{\"instance_id\":\"" + UUID.randomUUID() + "\",\"outcome\":\"APPROVED\"}",
                "workflow.completed", "PRICE_LIST", conflictingEvent.toString(), earlier.getId().toString());

        assertThat(versions.findById(later.getId()).orElseThrow().getStatus()).isEqualTo(PriceListVersionStatus.APPROVED);
        assertThat(versions.findById(earlier.getId()).orElseThrow().getStatus()).isEqualTo(PriceListVersionStatus.REJECTED);
        assertThat(processed.existsById(conflictingEvent)).isTrue();
    }
}
