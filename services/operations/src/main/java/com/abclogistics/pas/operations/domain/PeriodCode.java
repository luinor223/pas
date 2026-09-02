package com.abclogistics.pas.operations.domain;

import java.util.regex.Pattern;

public final class PeriodCode {

    public static final String REGEX = "^\\d{4}-(0[1-9]|1[0-2])$";
    public static final String MESSAGE = "period_code must be YYYY-MM";
    public static final Pattern PATTERN = Pattern.compile(REGEX);

    private PeriodCode() {}

    public static boolean isValid(String code) {
        return code != null && PATTERN.matcher(code).matches();
    }
}
