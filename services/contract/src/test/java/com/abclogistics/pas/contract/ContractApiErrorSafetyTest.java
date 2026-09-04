package com.abclogistics.pas.contract;

import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.error.ForbiddenException;
import com.abclogistics.pas.common.error.GlobalExceptionHandler;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Pins the public 4xx envelope while internal contract diagnostics stay available to logs. */
class ContractApiErrorSafetyTest {

    private static final String SECRET_ID = "50000000-0000-4000-8000-000000000001";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ErrorProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    static Stream<Arguments> unsafeErrors() {
        return Stream.of(
                Arguments.of("not-found", 404, "RESOURCE_NOT_FOUND",
                        "The requested resource was not found."),
                Arguments.of("conflict", 409, "STATE_CONFLICT",
                        "This action cannot be completed because the record is no longer in the required state."),
                Arguments.of("business-rule", 422, "BUSINESS_RULE_VIOLATION",
                        "The request does not satisfy a required business rule."),
                Arguments.of("transition", 412, "INVALID_STATE_TRANSITION",
                        "This action is not allowed in the document's current state."),
                Arguments.of("forbidden", 403, "ACTION_FORBIDDEN",
                        "You do not have permission to perform this action."),
                Arguments.of("access-denied", 403, "ACCESS_DENIED",
                        "You do not have permission to perform this action."),
                Arguments.of("invalid", 400, "INVALID_REQUEST",
                        "The request contains an invalid value."),
                Arguments.of("database", 400, "INVALID_DATA",
                        "The request contains a value that is not allowed."),
                Arguments.of("pricing-rule", 409, "PRICE_LIST_DATE_OVERLAP",
                        "These dates overlap an approved or effective price-list version for the same scope. Choose a non-overlapping period."),
                Arguments.of("billing-rule", 412, "STATEMENT_TOTAL_INVALID",
                        "The statement total cannot be negative."),
                Arguments.of("signing-status", 409, "SIGNING_SESSION_NOT_CANCELLABLE",
                        "This signing request can no longer be cancelled."));
    }

    @ParameterizedTest(name = "{0} returns a safe stable envelope")
    @MethodSource("unsafeErrors")
    void contractDomainErrorsDoNotExposeTechnicalDetails(
            String kind, int status, String code, String message) throws Exception {
        String body = mvc.perform(get("/error-probe/{kind}/{id}", kind, SECRET_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        JsonNode json = MAPPER.readTree(body);

        assertThat(json.get("status").asInt()).isEqualTo(status);
        assertThat(json.get("code").asText()).isEqualTo(code);
        assertThat(json.get("message").asText()).isEqualTo(message);
        assertThat(json.get("path").asText()).isEqualTo("/error-probe/%s/{id}".formatted(kind));
        assertThat(body).doesNotContain(
                SECRET_ID, "CTR-01", "CTR-02", "D10", "contract:cancel_active",
                "DRAFT", "SUBMITTED", "UNDER_REVIEW", "trigger W", "SQLSTATE",
                "ck_contract_status", "org.hibernate", "PRC-03", "PAY-04",
                "total_amount", "FAILED");
    }

    @ParameterizedTest
    @MethodSource("safeErrors")
    void alreadySafeBusinessMessagesRemainActionable(String kind, String expected) throws Exception {
        String body = mvc.perform(get("/error-probe/{kind}/{id}", kind, SECRET_ID))
                .andReturn().getResponse().getContentAsString();
        assertThat(MAPPER.readTree(body).get("message").asText()).isEqualTo(expected);
    }

    static Stream<Arguments> safeErrors() {
        return Stream.of(Arguments.of("safe-signing",
                "This document must be approved before it can be sent for signature."));
    }

    @ParameterizedTest
    @MethodSource("allCanonicalUuidShapes")
    void everyCanonicalUuidShapeIsRedacted(String id) throws Exception {
        String body = mvc.perform(get("/error-probe/not-found/{id}", id))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(id);
        assertThat(MAPPER.readTree(body).get("path").asText())
                .isEqualTo("/error-probe/not-found/{id}");
    }

    static Stream<String> allCanonicalUuidShapes() {
        return Stream.of(
                "01941f2e-7c00-7000-8000-000000000001",
                "00000000-0000-0000-0000-000000000000");
    }

    @ParameterizedTest(name = "framework binding error {0} has a stable safe envelope")
    @MethodSource("frameworkBindingErrors")
    void frameworkBindingErrorsUseTheSamePublicContract(
            String kind, int status, String code, String message) throws Exception {
        String body = switch (kind) {
            case "uuid" -> mvc.perform(get("/error-probe/binding/uuid/not-a-uuid"))
                    .andReturn().getResponse().getContentAsString();
            case "json" -> mvc.perform(post("/error-probe/json")
                            .contentType(MediaType.APPLICATION_JSON).content("{not-json}"))
                    .andReturn().getResponse().getContentAsString();
            case "validation" -> mvc.perform(post("/error-probe/validated")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getContentAsString();
            case "missing" -> mvc.perform(get("/error-probe/required"))
                    .andReturn().getResponse().getContentAsString();
            case "header" -> mvc.perform(get("/error-probe/header"))
                    .andReturn().getResponse().getContentAsString();
            case "method" -> mvc.perform(put("/error-probe/json")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ok\"}"))
                    .andReturn().getResponse().getContentAsString();
            case "media" -> mvc.perform(post("/error-probe/json")
                            .contentType(MediaType.TEXT_PLAIN).content("plain"))
                    .andReturn().getResponse().getContentAsString();
            case "oversize" -> mvc.perform(get("/error-probe/oversize"))
                    .andReturn().getResponse().getContentAsString();
            case "multipart" -> mvc.perform(get("/error-probe/multipart"))
                    .andReturn().getResponse().getContentAsString();
            default -> throw new IllegalArgumentException("Unknown test case");
        };
        JsonNode json = MAPPER.readTree(body);

        assertThat(json.get("status").asInt()).isEqualTo(status);
        assertThat(json.get("code").asText()).isEqualTo(code);
        assertThat(json.get("message").asText()).isEqualTo(message);
        assertThat(body).doesNotContain("MethodArgumentTypeMismatchException", "JsonParseException", "not-json");
    }

    static Stream<Arguments> frameworkBindingErrors() {
        return Stream.of(
                Arguments.of("uuid", 400, "INVALID_REQUEST", "The request contains an invalid value."),
                Arguments.of("json", 400, "MALFORMED_REQUEST", "The request body is malformed."),
                Arguments.of("validation", 400, "VALIDATION_FAILED", "Validation failed"),
                Arguments.of("missing", 400, "MISSING_REQUEST_VALUE", "A required request value is missing."),
                Arguments.of("header", 400, "MISSING_REQUEST_VALUE", "A required request value is missing."),
                Arguments.of("method", 405, "METHOD_NOT_ALLOWED",
                        "This request method is not supported for the requested resource."),
                Arguments.of("media", 415, "UNSUPPORTED_MEDIA_TYPE", "The request content type is not supported."),
                Arguments.of("oversize", 413, "UPLOAD_TOO_LARGE", "The uploaded file is too large."),
                Arguments.of("multipart", 400, "MALFORMED_MULTIPART_REQUEST",
                        "The multipart request is malformed."));
    }

    @RestController
    @RequestMapping("/error-probe")
    static class ErrorProbeController {
        @GetMapping("/{kind}/{id}")
        void fail(@PathVariable String kind, @PathVariable String id) {
            throw switch (kind) {
                case "not-found" -> new NotFoundException("Contract %s not found".formatted(id));
                case "conflict" -> new ConflictException(
                        "Contract CTR-2026-0001 is DRAFT and cannot be edited (CTR-01)");
                case "business-rule" -> new UnprocessableEntityException(
                        "Customer CUS-1 is SUSPENDED; only ACTIVE customers may submit (CTR-02)");
                case "transition" -> new FailedPreconditionException(
                        "CONTRACT CTR-2026-0001 cannot move SUBMITTED -> UNDER_REVIEW under trigger W");
                case "forbidden" -> new ForbiddenException(
                        "Cancelling an ACTIVE contract requires contract:cancel_active (CTR-06)");
                case "access-denied" -> new AccessDeniedException("Missing contract:read");
                case "invalid" -> new IllegalArgumentException("id is not a uuid: '%s'".formatted(id));
                case "database" -> new DataIntegrityViolationException(
                        "could not execute statement",
                        new SQLException("violates check constraint ck_contract_status SQLSTATE 23514", "23514"));
                case "pricing-rule" -> new ConflictException(
                        "PRICE_LIST_DATE_OVERLAP",
                        "These dates overlap an approved or effective price-list version for the same scope. Choose a non-overlapping period.",
                        "Validity overlaps an existing effective version of the same scope (PRC-03)");
                case "billing-rule" -> new FailedPreconditionException(
                        "STATEMENT_TOTAL_INVALID", "The statement total cannot be negative.",
                        "total_amount must be >= 0 (PAY-04)");
                case "signing-status" -> new ConflictException(
                        "SIGNING_SESSION_NOT_CANCELLABLE",
                        "This signing request can no longer be cancelled.",
                        "Cannot cancel session in status FAILED");
                case "safe-signing" -> new ConflictException(
                        "DOCUMENT_NOT_APPROVED",
                        "This document must be approved before it can be sent for signature.",
                        "This document must be approved before it can be sent for signature.");
                default -> new IllegalArgumentException("unknown probe");
            };
        }

        @GetMapping("/binding/uuid/{id}")
        void uuid(@PathVariable UUID id) { }

        @PostMapping(path = "/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        void json(@RequestBody ProbeBody body) { }

        @PostMapping("/validated")
        void validated(@Valid @RequestBody ProbeBody body) { }

        @GetMapping("/required")
        void required(@RequestParam String ownerType) { }

        @GetMapping("/header")
        void header(@RequestHeader("X-Required") String required) { }

        @GetMapping("/oversize")
        void oversize() {
            throw new MaxUploadSizeExceededException(1);
        }

        @GetMapping("/multipart")
        void multipart() {
            throw new MultipartException("broken boundary details");
        }
    }

    record ProbeBody(@NotBlank String name) { }
}
