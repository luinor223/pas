package com.abclogistics.pas.common.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiEnvelopeConfig {

    private static final String PAGE_META_REF = "#/components/schemas/PageMeta";

    @Bean
    public OpenApiCustomizer envelopeCustomizer() {
        return openApi -> {
            if (openApi.getComponents() != null) {
                openApi.getComponents().addSchemas("PageMeta", pageMeta());
            }
            if (openApi.getPaths() == null) return;
            openApi.getPaths().values().forEach(path ->
                    path.readOperations().forEach(op -> {
                        if (op.getResponses() == null) return;
                        op.getResponses().forEach((code, resp) -> {
                            if (!code.startsWith("2") || resp.getContent() == null) return;
                            resp.getContent().values().forEach(mt -> wrap(mt, openApi));
                        });
                    }));
        };
    }

    private void wrap(MediaType mt, OpenAPI openApi) {
        Schema<?> original = mt.getSchema();
        if (original == null) return;
        Schema<?> data = original;
        boolean paged = false;

        String ref = original.get$ref();
        if (ref != null) {
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            if (name.startsWith("Page") && openApi.getComponents() != null) {
                Schema<?> pageSchema = openApi.getComponents().getSchemas().get(name);
                Schema<?> content = pageSchema == null || pageSchema.getProperties() == null
                        ? null : pageSchema.getProperties().get("content");
                if (content != null) {
                    data = content;
                    paged = true;
                }
            }
        }

        ObjectSchema envelope = new ObjectSchema();
        envelope.addProperty("data", data);
        if (paged) envelope.addProperty("meta", new Schema<>().$ref(PAGE_META_REF));
        mt.setSchema(envelope);
    }

    private Schema<?> pageMeta() {
        ObjectSchema meta = new ObjectSchema();
        meta.addProperty("page", new IntegerSchema());
        meta.addProperty("size", new IntegerSchema());
        meta.addProperty("totalElements", new IntegerSchema().format("int64"));
        meta.addProperty("totalPages", new IntegerSchema());
        return meta;
    }
}
