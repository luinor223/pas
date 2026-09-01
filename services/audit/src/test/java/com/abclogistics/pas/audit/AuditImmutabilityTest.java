package com.abclogistics.pas.audit;

import com.abclogistics.pas.audit.domain.AuditRecord;
import com.abclogistics.pas.audit.repository.AuditRecordRepository;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * db-audit.md: INSERT + SELECT grants only — "a business service can no longer rewrite its own
 * history" is the real gain of centralizing, so it is asserted structurally rather than trusted.
 * Same shape as contract's status_history append-only test (D17).
 */
class AuditImmutabilityTest {

    @Test
    void everyMappedColumnIsNonUpdatable() {
        for (Field field : AuditRecord.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                assertThat(column.updatable())
                        .as("AuditRecord.%s must be updatable = false", field.getName())
                        .isFalse();
            }
        }
    }

    @Test
    void theEntityExposesNoSetter() {
        assertThat(Stream.of(AuditRecord.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("set"));
    }

    @Test
    void theRepositoryInheritsNoMutationPath() {
        // getMethods(), not getDeclaredMethods(): the previous version of this test used the
        // latter, which cannot see inherited methods, so it passed while the repository extended
        // JpaRepository and quietly offered save / saveAll / delete / deleteAll / deleteById to
        // every caller. The trail's whole guarantee was untested.
        assertThat(publicMethodNames())
                .noneMatch(name -> name.startsWith("delete")
                        || name.startsWith("remove")
                        || name.equals("save")
                        || name.equals("saveAll")
                        || name.equals("saveAndFlush"));
    }

    @Test
    void theRepositorySurfaceIsEnumeratedNotInherited() {
        // stated positively so a future `extends JpaRepository` fails here even if the base class
        // gains a mutation method under a name the blocklist above never anticipated
        assertThat(publicMethodNames()).containsExactlyInAnyOrder(
                "insertIgnoringDuplicate",
                "findByEntityTypeAndEntityIdOrderByOccurredAtDesc",
                "search",
                "findById",
                "count");
    }

    @Test
    void theOnlyWritePathRefusesToOverwrite() {
        // ON CONFLICT DO NOTHING, never DO UPDATE — asserted on the query text because that one
        // clause is the difference between a replay being a no-op and a replay rewriting history
        String sql = insertQuery().toLowerCase();
        assertThat(sql).contains("on conflict (id) do nothing");
        assertThat(sql).doesNotContain("do update");
        assertThat(sql).doesNotContain("update audit");
    }

    @Test
    void theRecordCarriesNoVersionOrUpdatedAt() {
        // not a BaseEntity: an updated_at on an immutable row would be a lie, and a version
        // column would imply someone expects to write it again
        assertThat(Stream.of(AuditRecord.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("version", "updatedAt", "updatedBy");
    }

    private static Stream<String> publicMethodNames() {
        return Stream.of(AuditRecordRepository.class.getMethods()).map(Method::getName);
    }

    private static String insertQuery() {
        return Stream.of(AuditRecordRepository.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("insertIgnoringDuplicate"))
                .map(m -> m.getAnnotation(org.springframework.data.jpa.repository.Query.class).value())
                .findFirst()
                .orElseThrow();
    }
}
