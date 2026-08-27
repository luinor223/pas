package com.abclogistics.pas.contract;

import com.abclogistics.pas.contract.domain.DocumentStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test per registry §5.1 — proto owned by the callee, REST surface via OpenAPI.
 * Mirrors WorkflowContractTest: no Spring context, so it runs without Docker.
 */
class ContractContractTest {

    private static Path resolve(String relative) {
        Path base = Path.of(System.getProperty("user.dir"));
        Path p = base.resolve(relative);
        if (!Files.exists(p)) p = base.resolve("../../" + relative);
        if (!Files.exists(p)) p = Path.of(relative);
        return p;
    }

    @Test
    void openApiDocumentsEveryContractSurface() throws Exception {
        Path openapi = resolve("services/contract/src/main/resources/openapi.yaml");
        assertThat(Files.exists(openapi))
                .withFailMessage("openapi not found at %s", openapi.toAbsolutePath()).isTrue();
        String yaml = Files.readString(openapi);

        assertThat(yaml).contains("/customers");
        assertThat(yaml).contains("/contracts");
        assertThat(yaml).contains("/addenda");
        assertThat(yaml).contains("/attachments");

        // the actions, not just the CRUD
        assertThat(yaml).contains("/contracts/{id}/submit");
        assertThat(yaml).contains("/contracts/{id}/cancel");
        assertThat(yaml).contains("/contracts/{id}/revise");
        assertThat(yaml).contains("/contracts/{id}/progress");
        assertThat(yaml).contains("/contracts/{id}/history");

        // D10 send-for-signing — contract-service owns the action (registry §6 third outbox use)
        assertThat(yaml).contains("/contracts/{id}/send-for-signing");

        // permissions are named, never roles
        assertThat(yaml).contains("contract:write");
        assertThat(yaml).contains("contract:cancel_active");
        assertThat(yaml).contains("customer:write");
        assertThat(yaml).contains("esign:send");
    }

    @Test
    void workflowProtoCarriesEverySubmitFieldWeSend() throws Exception {
        Path proto = resolve("proto/src/main/proto/workflow/v1/workflow_internal.proto");
        assertThat(Files.exists(proto))
                .withFailMessage("proto not found at %s", proto.toAbsolutePath()).isTrue();
        String content = Files.readString(proto);

        assertThat(content).contains("rpc ValidateStartable");
        assertThat(content).contains("rpc StartInstance");
        assertThat(content).contains("rpc CancelInstance");
        assertThat(content).contains("rpc GetInstanceByDocument");
        assertThat(content).contains("idempotency_key");
        assertThat(content).contains("customer_name");
        assertThat(content).contains("requested_by_id");
        assertThat(content).contains("requested_by_name");
    }

    @Test
    void documentStatusHasNoSigningStates() {
        // D14e / requirement 5.5: approval state and signing state are never mixed. The frontend
        // composes signing state from esign-service; it is never persisted on the document.
        String[] names = java.util.Arrays.stream(DocumentStatus.values())
                .map(Enum::name).toArray(String[]::new);
        assertThat(names).doesNotContain("SIGNING", "SIGNED");
        assertThat(names).containsExactlyInAnyOrder(
                "DRAFT", "SUBMITTED", "UNDER_REVIEW", "APPROVED", "ACTIVE",
                "EXPIRED", "REJECTED", "REVISION_REQUESTED", "CANCELLED");
    }
}
