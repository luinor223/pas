package com.abclogistics.pas.contract.domain;

import java.util.UUID;

/**
 * What contracts and addenda have in common as far as the approval machinery is concerned: an
 * identity, a business number people read, and a position in the registry §9 status machine.
 *
 * <p>It exists so the M2 cancel handoff is written once. That mechanism is subtle enough — a
 * lease with no fencing token, a forced dispatch, a restricted edge — that a second copy for
 * addenda would drift from this one, and the copy that drifted would be the one nobody tested.
 */
public interface ApprovableDocument {

    UUID getId();

    /** {@code CTR-2026-0001} / {@code ADD-2026-0001} — what appears in errors and audit rows. */
    String getDocumentNo();

    DocumentStatus getStatus();

    void setStatus(DocumentStatus status);

    /** Also the workflow document type code this document submits under. */
    EntityType entityType();
}
