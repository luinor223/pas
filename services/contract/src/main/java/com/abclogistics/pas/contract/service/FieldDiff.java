package com.abclogistics.pas.contract.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the {@code {field: {from, to}}} payload every UPDATE audit row carries (D15).
 *
 * <p>"UPDATE" on its own tells a reviewer nothing, and the audit row is the only record of the
 * prior value — {@code status_history} covers transitions, and nothing else keeps what a field
 * used to say.
 */
final class FieldDiff {

    private FieldDiff() { }

    /** Only the fields that actually moved. A row claiming everything changed is as useless as one claiming nothing did. */
    static Map<String, Object> between(Map<String, Object> was, Map<String, Object> now) {
        Map<String, Object> changes = new LinkedHashMap<>();
        was.forEach((field, before) -> {
            Object after = now.get(field);
            if (!sameValue(before, after)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", text(before));
                change.put("to", text(after));
                changes.put(field, change);
            }
        });
        return changes;
    }

    /** 10 and 10.00 are the same number; {@code equals} on BigDecimal disagrees. */
    private static boolean sameValue(Object before, Object after) {
        if (before instanceof BigDecimal x && after instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(before, after);
    }

    /** Null stays null in the payload — "not stated" and the string "null" are different facts. */
    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
