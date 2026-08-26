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
        Path proto = Path.of("proto/src/main/proto/workflow/v1/workflow_internal.proto");
        // also try from project root when running via gradle (working dir = services/workflow)
        if (!Files.exists(proto)) proto = Path.of("../../proto/src/main/proto/workflow/v1/workflow_internal.proto");
        if (!Files.exists(proto)) proto = Path.of("C:/Keineik/Projects/pas/proto/src/main/proto/workflow/v1/workflow_internal.proto");
        assertThat(Files.exists(proto)).isTrue();
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
        Path openapi = Path.of("src/main/resources/openapi.yaml");
        if (!Files.exists(openapi)) openapi = Path.of("services/workflow/src/main/resources/openapi.yaml");
        if (!Files.exists(openapi)) openapi = Path.of("C:/Keineik/Projects/pas/services/workflow/src/main/resources/openapi.yaml");
        assertThat(Files.exists(openapi)).isTrue();
        String yaml = Files.readString(openapi);
        assertThat(yaml).contains("/workflow-definitions");
        assertThat(yaml).contains("/workflow-steps/{stepInstanceId}/actions");
        assertThat(yaml).contains("/inbox");
        assertThat(yaml).contains("workflow:configure");
        assertThat(yaml).contains("approval:act");
    }
}
