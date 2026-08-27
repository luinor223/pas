package com.abclogistics.pas.contract.domain;

/** registry §10 addendum change types (from Figma 07-addenda). */
public enum ChangeType {
    /** Price change — carries NO price data here; Sales creates a pricing version from it (D8). */
    UNIT_PRICE_CHANGE,
    /** Renewal (D14b): requires new_valid_to, applied to the parent's valid_to on ACTIVE. */
    TERM_EXTENSION,
    ADDED_SERVICE,
    /** Requires payment_term_override, applied to the parent's payment_term on ACTIVE. */
    PAYMENT_TERMS
}
