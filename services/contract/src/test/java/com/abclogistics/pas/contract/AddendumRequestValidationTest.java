package com.abclogistics.pas.contract;

import com.abclogistics.pas.contract.dto.AddendumRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean validation skips a collection's elements unless the field carries {@code @Valid}. Without
 * it an ADDED_SERVICE line with no {@code serviceCode} is a 500 — a NOT NULL violation on create,
 * an NPE sorting the audit snapshot on update. Plain validator: the annotation is the subject.
 */
class AddendumRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void aServiceLineWithNoCodeIsRejectedBeforeItReachesTheDatabase() {
        Set<ConstraintViolation<AddendumRequest>> violations = validator.validate(
                addendumWith(new AddendumRequest.ServiceLine(null, null, "Kho bãi", "m2", null)));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("services[0].serviceCode");
    }

    @Test
    void aServiceLineWithNoNameIsRejectedToo() {
        Set<ConstraintViolation<AddendumRequest>> violations = validator.validate(
                addendumWith(new AddendumRequest.ServiceLine(null, "WH-01", null, "m2", null)));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("services[0].serviceName");
    }

    @Test
    void blankIsNotAName() {
        // service_code is only NOT NULL in the DDL, so "" would otherwise be stored and take the
        // row's slot in uq_addendum_service_code.
        Set<ConstraintViolation<AddendumRequest>> violations = validator.validate(
                addendumWith(new AddendumRequest.ServiceLine(null, "   ", "Kho bãi", null, null)));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("services[0].serviceCode");
    }

    @Test
    void aCompleteServiceLinePasses() {
        Set<ConstraintViolation<AddendumRequest>> violations = validator.validate(
                addendumWith(new AddendumRequest.ServiceLine(
                        UUID.randomUUID(), "WH-01", "Kho bãi", "m2", "Zone A only")));

        assertThat(violations).isEmpty();
    }

    private static AddendumRequest addendumWith(AddendumRequest.ServiceLine line) {
        return new AddendumRequest(UUID.randomUUID(), "ADDED_SERVICE", "adds warehousing",
                LocalDate.of(2026, 6, 1), null, null, List.of(line), null);
    }
}
