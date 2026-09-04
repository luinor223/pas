package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.error.UnprocessableEntityException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;

/** Request text to domain values. A bad reference value is a 422 naming what IS allowed. */
final class RequestValues {

    private RequestValues() { }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static <E extends Enum<E>> E parseRequired(String field, String raw,
                                               Function<String, E> parser, E[] allowed) {
        E parsed = parseOptional(field, raw, parser, allowed);
        if (parsed == null) {
            throw new UnprocessableEntityException("%s is required".formatted(field));
        }
        return parsed;
    }

    static <E extends Enum<E>> E parseOptional(String field, String raw,
                                               Function<String, E> parser, E[] allowed) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return parser.apply(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnprocessableEntityException("%s must be one of %s (got \"%s\")"
                    .formatted(field, Arrays.toString(allowed), raw));
        }
    }

    static String likePattern(String q) {
        String term = blankToNull(q);
        return term == null ? null : "%" + term.trim().toLowerCase(Locale.ROOT) + "%";
    }

    static LocalDate parseOptionalDate(String field, String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new UnprocessableEntityException("%s must be ISO date YYYY-MM-DD (got \"%s\")".formatted(field, raw));
        }
    }
}
