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
        assertThat(Stream.of(AuditRecord.class.getDeclaredMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("set"));
    }

    @Test
    void theRepositoryExposesNoDeletePath() {
        // JpaRepository ships delete* by default; the trail must not inherit them
        assertThat(Stream.of(AuditRecordRepository.class.getDeclaredMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("delete") || name.startsWith("remove"));
    }

    @Test
    void theRecordCarriesNoVersionOrUpdatedAt() {
        // not a BaseEntity: an updated_at on an immutable row would be a lie, and a version
        // column would imply someone expects to write it again
        assertThat(Stream.of(AuditRecord.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("version", "updatedAt", "updatedBy");
    }
}
