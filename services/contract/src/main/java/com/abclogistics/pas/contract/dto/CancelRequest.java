package com.abclogistics.pas.contract.dto;

/** Cancellation reason, carried into the {@code status_history} note and the audit record. */
public record CancelRequest(String reason) { }
