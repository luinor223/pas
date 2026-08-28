package com.abclogistics.pas.contract.domain;

import java.util.UUID;

/** What contracts and addenda share, so the M2 cancel handoff is written once. */
public interface ApprovableDocument {

    UUID getId();

    String getDocumentNo();

    DocumentStatus getStatus();

    void setStatus(DocumentStatus status);

    EntityType entityType();
}
