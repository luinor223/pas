package com.abclogistics.pas.common.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.media.Content;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiEnvelopeConfig {

    private static final String PAGE_META_REF = "#/components/schemas/PageMeta";
    private static final String API_ERROR_REF = "#/components/schemas/ApiError";
    private static final String API_ERROR_RESPONSE_REF = "#/components/responses/ApiErrorResponse";

    @Bean
    public OpenApiCustomizer envelopeCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) openApi.setComponents(new Components());
            openApi.getComponents()
                    .addSchemas("PageMeta", pageMeta())
                    .addSchemas("ApiErrorFieldViolation", fieldViolation())
                    .addSchemas("ApiError", apiError())
                    .addResponses("ApiErrorResponse", apiErrorResponse());
            if (openApi.getPaths() == null) return;
            openApi.getPaths().values().forEach(path ->
                    path.readOperations().forEach(op -> {
                        if (op.getResponses() == null) return;
                        op.getResponses().forEach((code, resp) -> {
                            if (code.startsWith("2")) {
                                if (resp.getContent() != null) {
                                    resp.getContent().values().forEach(mt -> wrap(mt, openApi));
                                }
                            } else if (resp.getContent() == null) {
                                resp.setContent(apiErrorContent());
                            }
                        });
                        op.getResponses().putIfAbsent("default",
                                new ApiResponse().$ref(API_ERROR_RESPONSE_REF));
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

    private Schema<?> fieldViolation() {
        ObjectSchema violation = new ObjectSchema();
        violation.addProperty("field", new StringSchema());
        violation.addProperty("message", new StringSchema());
        violation.setRequired(List.of("field", "message"));
        return violation;
    }

    private Schema<?> apiError() {
        ObjectSchema error = new ObjectSchema();
        error.addProperty("timestamp", new StringSchema().format("date-time"));
        error.addProperty("status", new IntegerSchema().format("int32"));
        error.addProperty("error", new StringSchema());
        error.addProperty("code", new StringSchema());
        error.addProperty("message", new StringSchema());
        error.addProperty("path", new StringSchema());
        error.addProperty("violations", new ArraySchema().items(
                new Schema<>().$ref("#/components/schemas/ApiErrorFieldViolation")));
        error.setRequired(List.of("timestamp", "status", "error", "code", "message", "path", "violations"));
        return error;
    }

    private ApiResponse apiErrorResponse() {
        return new ApiResponse()
                .description("Request failed")
                .content(apiErrorContent());
    }

    private Content apiErrorContent() {
        return new Content().addMediaType("application/json",
                new MediaType().schema(new Schema<>().$ref(API_ERROR_REF)));
    }
}
