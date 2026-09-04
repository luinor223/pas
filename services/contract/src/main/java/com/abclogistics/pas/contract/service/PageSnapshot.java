package com.abclogistics.pas.contract.service;

import java.time.Instant;

/** Opaque, short-lived boundary that keeps offset pages on one immutable insertion snapshot. */
public record PageSnapshot(Instant createdAt, String cursor) { }
