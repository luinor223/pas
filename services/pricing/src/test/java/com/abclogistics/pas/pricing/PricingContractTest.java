package com.abclogistics.pas.pricing;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test (00-registry.md) — proto owned by the callee, REST via OpenAPI. Locks the
 * PricingInternal surface (GetServiceItem, GetEffectivePriceList) and the documented REST paths.
 */
class PricingContractTest {

    private static Path locate(String relative) {
        for (Path candidate : new Path[]{
                Path.of(System.getProperty("user.dir")).resolve(relative),
                Path.of(relative),
                Path.of("../../").resolve(relative)}) {
            if (Files.exists(candidate)) return candidate;
        }
        return Path.of(relative);
    }

    @Test
    void protoDeclaresPricingInternalSurface() throws Exception {
        Path proto = locate("proto/src/main/proto/pricing/v1/pricing_internal.proto");
        assertThat(Files.exists(proto)).withFailMessage("proto not found at %s", proto.toAbsolutePath()).isTrue();
        String content = Files.readString(proto);
        assertThat(content).contains("service PricingInternal");
        assertThat(content).contains("rpc GetServiceItem");
        assertThat(content).contains("rpc GetEffectivePriceList");
        assertThat(content).contains("string service_group");
        assertThat(content).contains("string date"); // period_end, historical lookup
    }

    @Test
    void openApiDocumentsCatalogAndVersionEndpoints() throws Exception {
        Path openapi = locate("services/pricing/src/main/resources/openapi.yaml");
        if (!Files.exists(openapi)) openapi = locate("src/main/resources/openapi.yaml");
        assertThat(Files.exists(openapi)).withFailMessage("openapi not found at %s", openapi.toAbsolutePath()).isTrue();
        String yaml = Files.readString(openapi);
        assertThat(yaml).contains("/service-items");
        assertThat(yaml).contains("/price-lists");
        assertThat(yaml).contains("/versions/{versionId}/submit");
        assertThat(yaml).contains("pricelist:read");
        assertThat(yaml).contains("pricelist:write");
    }
}
