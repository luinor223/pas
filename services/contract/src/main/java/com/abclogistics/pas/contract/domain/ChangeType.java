package com.abclogistics.pas.contract.domain;

/** registry §10 addendum change types. None of them carries price data (D8). */
public enum ChangeType {
    UNIT_PRICE_CHANGE,
    TERM_EXTENSION,
    ADDED_SERVICE,
    PAYMENT_TERMS
}
