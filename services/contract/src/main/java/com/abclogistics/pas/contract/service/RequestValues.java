package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.contract.error.UnprocessableEntityException;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;

/**
 * Turning request text into domain values, the same way everywhere.
 *
 * <p>A bad reference value or filter is the caller's mistake, so it is a 422 naming what IS
 * allowed. A bare {@code valueOf} would be an IllegalArgumentException — a 500 for a typo — and
 * would also reject {@code active} for a value space that is case-insensitive to every user.
 */
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

    /**
     * A ready lower-cased {@code %pattern%}. Building it in SQL means {@code '%' || ? || '%'},
     * and Postgres has no type to infer for that parameter when it is null — it resolves the
     * concatenation to bytea and the query dies on {@code lower(bytea) does not exist}.
     */
    static String likePattern(String q) {
        String term = blankToNull(q);
        return term == null ? null : "%" + term.trim().toLowerCase(Locale.ROOT) + "%";
    }
}
