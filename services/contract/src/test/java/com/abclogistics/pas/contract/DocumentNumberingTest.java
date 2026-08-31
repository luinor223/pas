package com.abclogistics.pas.contract;

import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.service.DocumentNumberService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * registry §2 business keys. Numbers are server-generated and never client-supplied.
 *
 * <p>Customer codes carry NO year segment, so their sequence must run unbroken across years —
 * which is why {@code customer_counter} is a separate single-row table and not a
 * {@code (doc_type, year)} row in {@code document_counter}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class DocumentNumberingTest {

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

    @Autowired
    DocumentNumberService numbers;

    @Autowired
    TransactionTemplate tx;

    @Test
    void contractNumbersAreCtrYearSeq() {
        String first = tx.execute(s -> numbers.nextDocumentNo(EntityType.CONTRACT, 2026));
        String second = tx.execute(s -> numbers.nextDocumentNo(EntityType.CONTRACT, 2026));

        assertThat(first).matches("CTR-2026-\\d{4}");
        assertThat(second).matches("CTR-2026-\\d{4}");
        assertThat(seq(second)).isEqualTo(seq(first) + 1);
    }

    @Test
    void addendumNumbersAreAddYearSeq() {
        String no = tx.execute(s -> numbers.nextDocumentNo(EntityType.ADDENDUM, 2026));
        assertThat(no).matches("ADD-2026-\\d{4}");
    }

    @Test
    void documentSequenceRestartsEachYearPerType() {
        // Each (type, year) is its own counter row, so a new year starts at 1 again -- which is
        // safe precisely because the year is part of the number.
        tx.execute(s -> numbers.nextDocumentNo(EntityType.CONTRACT, 2030));
        tx.execute(s -> numbers.nextDocumentNo(EntityType.CONTRACT, 2030));
        String nextYear = tx.execute(s -> numbers.nextDocumentNo(EntityType.CONTRACT, 2031));

        assertThat(seq(nextYear)).isEqualTo(1);
    }

    @Test
    void customerSequenceDoesNotRestartAcrossYears() {
        // A year-keyed customer counter would hand out CUS-0001 again every January and collide
        // on customer.code's UNIQUE. There is only ever one counter row, so the sequence is
        // monotonic for the life of the system.
        String first = tx.execute(s -> numbers.nextCustomerCode());
        String second = tx.execute(s -> numbers.nextCustomerCode());

        assertThat(first).matches("CUS-\\d{4}");
        assertThat(seq(second)).isEqualTo(seq(first) + 1);
    }

    @Test
    void concurrentCreatesNeverShareASequence() throws Exception {
        // The row lock is what prevents this. Without it, concurrent readers see the same
        // next_seq and the race surfaces later as a UNIQUE violation on contract_no.
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> jobs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                jobs.add(() -> tx.execute(s -> numbers.nextDocumentNo(EntityType.CONTRACT, 2040)));
            }
            Set<String> allocated = pool.invokeAll(jobs).stream()
                    .map(DocumentNumberingTest::join)
                    .collect(Collectors.toSet());

            assertThat(allocated).hasSize(threads);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void allocationOutsideATransactionIsRefused() {
        // MANDATORY propagation: allocating in its own transaction would release the row lock
        // before the caller inserts the row carrying the number, reopening the race.
        assertThatThrownBy(() -> numbers.nextDocumentNo(EntityType.CONTRACT, 2026))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    private static int seq(String documentNo) {
        return Integer.parseInt(documentNo.substring(documentNo.lastIndexOf('-') + 1));
    }

    private static String join(Future<String> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
