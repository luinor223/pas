package com.abclogistics.pas.contract.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Builds the {@code {field: {from, to}}} payload every UPDATE audit row carries (D15). */
final class FieldDiff {

    private FieldDiff() { }

    static Map<String, Object> between(Map<String, Object> was, Map<String, Object> now) {
        Map<String, Object> changes = new LinkedHashMap<>();
        was.forEach((field, before) -> {
            Object after = now.get(field);
            if (!sameValue(before, after)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", render(before));
                change.put("to", render(after));
                changes.put(field, change);
            }
        });
        return changes;
    }

    private static boolean sameValue(Object before, Object after) {
        if (before instanceof BigDecimal x && after instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(before, after);
    }

    private static Object render(Object value) {
        if (value == null || value instanceof Iterable<?> || value instanceof Map<?, ?>) {
            return value;
        }
        return value.toString();
    }
}
