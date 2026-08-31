package com.abclogistics.pas.pricing.dto;

import java.time.LocalDate;
import java.util.UUID;

/** Everything the D9 expiry-warning event needs, in one projection — no per-row entity reloads. */
public record ExpiryWarningRow(UUID versionId, int versionNo, LocalDate validTo, UUID ownerId, String priceListNo) {}
