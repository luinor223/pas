package com.abclogistics.pas.operations;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsContractTest {

    private static Path findFile(String relativeFromRoot) {
        // Walk up from user.dir and from class location to find repo root (where settings.gradle.kts exists)
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path cur = start; cur != null; cur = cur.getParent()) {
            Path candidate = cur.resolve(relativeFromRoot);
            if (Files.exists(candidate)) return candidate;
            if (Files.exists(cur.resolve("settings.gradle.kts")) || Files.exists(cur.resolve("build.gradle.kts"))) {
                Path alt = cur.resolve(relativeFromRoot);
                if (Files.exists(alt)) return alt;
            }
        }
        // fallback to classpath resource (for CI where file may be copied)
        try {
            var res = OperationsContractTest.class.getClassLoader().getResource(relativeFromRoot);
            if (res != null) return Path.of(res.toURI());
        } catch (Exception ignored) {}
        return Path.of(relativeFromRoot);
    }

    @Test
    void protoHasListVolumesWithPeriodBounds() throws Exception {
        Path proto = findFile("proto/src/main/proto/operations/v1/operations_internal.proto");
        assertThat(Files.exists(proto)).withFailMessage("proto not found, searched from %s", Path.of(System.getProperty("user.dir")).toAbsolutePath()).isTrue();
        String content = Files.readString(proto);
        assertThat(content).contains("service OperationsInternal");
        assertThat(content).contains("rpc ListVolumes");
        assertThat(content).contains("period_state");
        assertThat(content).contains("period_start");
        assertThat(content).contains("period_end");
        assertThat(content).contains("service_code");
        assertThat(content).contains("quantity");
        assertThat(content).contains("service_name");
    }

    @Test
    void flywayMigrationExistsAndMentionsOutbox() throws Exception {
        Path sql = findFile("services/operations/src/main/resources/db/migration/V1__init_operations.sql");
        assertThat(Files.exists(sql)).isTrue();
        String content = Files.readString(sql);
        assertThat(content).contains("operation_period");
        assertThat(content).contains("volume_record");
        assertThat(content).contains("outbox");
        assertThat(content).contains("period_code");
        assertThat(content).contains("record_no");
        assertThat(content).contains("OPEN");
        assertThat(content).contains("LOCKED");
        assertThat(content).contains("quantity");
    }
}
