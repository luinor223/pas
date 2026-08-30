package com.abclogistics.pas.operations;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsContractTest {

    @Test
    void protoHasListVolumesWithPeriodBounds() throws Exception {
        Path base = Path.of(System.getProperty("user.dir"));
        Path proto = base.resolve("proto/src/main/proto/operations/v1/operations_internal.proto");
        if (!Files.exists(proto)) proto = base.resolve("../../proto/src/main/proto/operations/v1/operations_internal.proto");
        if (!Files.exists(proto)) proto = Path.of("proto/src/main/proto/operations/v1/operations_internal.proto");
        if (!Files.exists(proto)) proto = Path.of("../../proto/src/main/proto/operations/v1/operations_internal.proto");
        assertThat(Files.exists(proto)).withFailMessage("proto not found at %s", proto.toAbsolutePath()).isTrue();
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
    void openApiDocumentsOperationsEndpoints() throws Exception {
        Path base = Path.of(System.getProperty("user.dir"));
        Path openapi = base.resolve("services/operations/src/main/resources/openapi.yaml");
        if (!Files.exists(openapi)) openapi = base.resolve("src/main/resources/openapi.yaml");
        if (!Files.exists(openapi)) openapi = Path.of("src/main/resources/openapi.yaml");
        if (!Files.exists(openapi)) openapi = Path.of("services/operations/src/main/resources/openapi.yaml");
        assertThat(Files.exists(openapi)).withFailMessage("openapi not found at %s", openapi.toAbsolutePath()).isTrue();
        String yaml = Files.readString(openapi);
        assertThat(yaml).contains("/periods");
        assertThat(yaml).contains("/volume-records");
        assertThat(yaml).contains("/lock");
        assertThat(yaml).contains("volume:lock_period");
        assertThat(yaml).contains("volume:edit_locked");
        assertThat(yaml).contains("volume:write");
        assertThat(yaml).contains("volume:read");
    }

    @Test
    void flywayMigrationExistsAndMentionsOutbox() throws Exception {
        Path base = Path.of(System.getProperty("user.dir"));
        Path sql = base.resolve("services/operations/src/main/resources/db/migration/V1__init_operations.sql");
        if (!Files.exists(sql)) sql = Path.of("src/main/resources/db/migration/V1__init_operations.sql");
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
