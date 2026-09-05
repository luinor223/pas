package com.abclogistics.pas.pricing;

import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.pricing.client.WorkflowGrpcClient;
import com.abclogistics.pas.pricing.domain.PriceList;
import com.abclogistics.pas.pricing.domain.PriceListVersion;
import com.abclogistics.pas.pricing.domain.PriceListVersionStatus;
import com.abclogistics.pas.pricing.domain.TriggerKind;
import com.abclogistics.pas.pricing.listener.WorkflowEventListener;
import com.abclogistics.pas.pricing.repository.ProcessedEventRepository;
import com.abclogistics.pas.pricing.repository.StatusHistoryRepository;
import com.abclogistics.pas.pricing.service.PriceListService;
import com.abclogistics.pas.pricing.service.PriceListService.LineInput;
import com.abclogistics.pas.pricing.service.PriceListVersionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DRAFT → SUBMITTED (D4 outbox) → APPROVED (workflow consumer), plus PRC-05 and D8 provenance. */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PriceListLifecycleIT {

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
    }

    @Autowired PriceListService lists;
    @Autowired PriceListVersionService versionService;
    @Autowired WorkflowEventListener listener;
    @Autowired OutboxRepository outbox;
    @Autowired StatusHistoryRepository history;
    @Autowired ProcessedEventRepository processed;

    private PriceListVersion draftVersion() {
        PriceList list = lists.create(UUID.randomUUID(), null, "STEVEDORING", "test list");
        PriceListVersion v = lists.addVersion(list.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);
        lists.replaceLines(v.getId(), List.of(new LineInput("LIFT_ON_OFF", new BigDecimal("12.50"))));
        return v;
    }

    @Test
    void submitWritesStartRequestedOutboxRow() {
        PriceListVersion v = draftVersion();
        versionService.submit(v.getId());

        assertThat(lists.getVersion(v.getId()).getStatus()).isEqualTo(PriceListVersionStatus.SUBMITTED);

        List<OutboxEvent> startRows = outbox.findAll().stream()
                .filter(e -> "workflow.start_requested".equals(e.getEventType()))
                .filter(e -> e.getAggregateId().equals(v.getId()))
                .toList();
        assertThat(startRows).hasSize(1);
        assertThat(startRows.get(0).getPayload()).contains("idempotency_key").contains(v.getId().toString());

        assertThat(history.findByVersionIdOrderByCreatedAt(v.getId()))
                .anyMatch(h -> h.getToStatus() == PriceListVersionStatus.SUBMITTED && h.getTriggerKind() == TriggerKind.U);
    }

    @Test
    void linesAreReadOnlyPastDraft_PRC05() {
        PriceListVersion v = draftVersion();
        versionService.submit(v.getId());
        assertThatThrownBy(() -> lists.replaceLines(v.getId(),
                List.of(new LineInput("LIFT_ON_OFF", new BigDecimal("9.99")))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void datesEditableWhileDraft() {
        PriceListVersion v = draftVersion();
        lists.updateVersionDates(v.getId(), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 11, 30));
        PriceListVersion reloaded = lists.getVersion(v.getId());
        assertThat(reloaded.getValidFrom()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(reloaded.getValidTo()).isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    void datesReadOnlyPastDraft_PRC05() {
        PriceListVersion v = draftVersion();
        versionService.submit(v.getId());
        assertThatThrownBy(() -> lists.updateVersionDates(v.getId(),
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 11, 30)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void datesRejectedWhenFromAfterTo_PRC02() {
        PriceListVersion v = draftVersion();
        assertThatThrownBy(() -> lists.updateVersionDates(v.getId(),
                LocalDate.of(2026, 12, 31), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addendumIdIsStoredWithGivenValidFrom_D8() {
        PriceList list = lists.create(null, null, "WAREHOUSING", null);
        UUID addendumId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 6, 1);
        PriceListVersion v = lists.addVersion(list.getId(), from, LocalDate.of(2026, 12, 31), addendumId);
        PriceListVersion reloaded = lists.getVersion(v.getId());
        assertThat(reloaded.getAddendumId()).isEqualTo(addendumId);
        assertThat(reloaded.getValidFrom()).isEqualTo(from);
    }

    @Test
    void versionsAreListedInVersionOrder() {
        PriceList list = lists.create(null, null, "WAREHOUSING", null);
        PriceListVersion first = lists.addVersion(list.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), null);
        PriceListVersion second = lists.addVersion(list.getId(),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31), null);

        assertThat(lists.versionsOf(list.getId()))
                .extracting(PriceListVersion::getId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void nestedVersionLookupRejectsTheWrongPriceList() {
        PriceList owner = lists.create(null, null, "WAREHOUSING", null);
        PriceList other = lists.create(null, null, "STEVEDORING", null);
        PriceListVersion version = lists.addVersion(owner.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);

        assertThatThrownBy(() -> lists.getVersion(other.getId(), version.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void workflowCompletedApprovesAndIsIdempotent() {
        PriceListVersion v = draftVersion();
        versionService.submit(v.getId());

        UUID eventId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        String payload = "{\"instance_id\":\"" + instanceId + "\",\"outcome\":\"APPROVED\"}";

        listener.onEvent(payload, "workflow.completed", "PRICE_LIST", eventId.toString(), v.getId().toString());
        listener.onEvent(payload, "workflow.completed", "PRICE_LIST", eventId.toString(), v.getId().toString());

        assertThat(lists.getVersion(v.getId()).getStatus()).isEqualTo(PriceListVersionStatus.APPROVED);
        assertThat(processed.existsById(eventId)).isTrue();
        long approvals = history.findByVersionIdOrderByCreatedAt(v.getId()).stream()
                .filter(h -> h.getToStatus() == PriceListVersionStatus.APPROVED)
                .peek(h -> assertThat(h.getTriggerKind()).isEqualTo(TriggerKind.W))
                .count();
        assertThat(approvals).isEqualTo(1);
    }
}
