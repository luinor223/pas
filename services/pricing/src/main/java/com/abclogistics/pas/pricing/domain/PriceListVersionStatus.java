package com.abclogistics.pas.pricing.domain;

/** PRICE_LIST version states (registry §3/§9). No UNDER_REVIEW — SUBMITTED displays as
 *  "Under Review" (db-pricing.md figma note). */
public enum PriceListVersionStatus {
    DRAFT, SUBMITTED, APPROVED, EFFECTIVE, SUPERSEDED, EXPIRED, REJECTED
}
