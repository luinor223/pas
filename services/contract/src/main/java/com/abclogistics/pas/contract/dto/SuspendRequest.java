package com.abclogistics.pas.contract.dto;

import jakarta.validation.constraints.NotBlank;

/** Suspending is an audited action, so a reason is required rather than optional. */
public record SuspendRequest(@NotBlank String reason) { }
