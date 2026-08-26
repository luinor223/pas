package com.abclogistics.pas.workflow;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test per 00-registry.md:115 — proto owned by callee, REST via OpenAPI.
 * Ensures StartInstanceRequest carries customer_name (D7 snapshot) and requested_by split.
 */
class WorkflowContractTest {

    @Test
    void protoHasCustomerNameAndRequestedBySplit() throws Exception {
        Path base = Path.of(System.getProperty("user.dir"));
        Path proto = base.resolve("proto/src/main/proto/workflow/v1/workflow_internal.proto");
        if (!Files.exists(proto)) proto = base.resolve("../../proto/src/main/proto/workflow/v1/workflow_internal.proto");
        if (!Files.exists(proto)) proto = Path.of("proto/src/main/proto/workflow/v1/workflow_internal.proto");
        if (!Files.exists(proto)) proto = Path.of("../../proto/src/main/proto/workflow/v1/workflow_internal.proto");
        assertThat(Files.exists(proto)).withFailMessage("proto not found at %s", proto.toAbsolutePath()).isTrue();
        String content = Files.readString(proto);
        assertThat(content).contains("customer_name");
        assertThat(content).contains("requested_by_id");
        assertThat(content).contains("requested_by_name");
        assertThat(content).contains("idempotency_key");
        assertThat(content).contains("service WorkflowInternal");
        assertThat(content).contains("rpc ValidateStartable");
        assertThat(content).contains("rpc StartInstance");
        assertThat(content).contains("rpc CancelInstance");
        assertThat(content).contains("rpc GetInstanceByDocument");
    }

    @Test
    void openApiExistsAndDocumentsWorkflowEndpoints() throws Exception {
        Path base = Path.of(System.getProperty("user.dir"));
        Path openapi = base.resolve("services/workflow/src/main/resources/openapi.yaml");
        if (!Files.exists(openapi)) openapi = base.resolve("src/main/resources/openapi.yaml");
        if (!Files.exists(openapi)) openapi = Path.of("src/main/resources/openapi.yaml");
        if (!Files.exists(openapi)) openapi = Path.of("services/workflow/src/main/resources/openapi.yaml");
        assertThat(Files.exists(openapi)).withFailMessage("openapi not found at %s", openapi.toAbsolutePath()).isTrue();
        String yaml = Files.readString(openapi);
        assertThat(yaml).contains("/workflow-definitions");
        assertThat(yaml).contains("/workflow-steps/{stepInstanceId}/actions");
        assertThat(yaml).contains("/inbox");
        assertThat(yaml).contains("workflow:configure");
        assertThat(yaml).contains("approval:act");
    }
}
