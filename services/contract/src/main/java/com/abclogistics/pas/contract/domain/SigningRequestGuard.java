package com.abclogistics.pas.contract.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "signing_request_guard", schema = "contract")
@IdClass(SigningRequestGuard.Key.class)
public class SigningRequestGuard {

    @Id
    @Column(name = "document_type", nullable = false, updatable = false)
    private String documentType;

    @Id
    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SigningRequestGuard() { }

    public static SigningRequestGuard active(String documentType, UUID documentId,
                                              UUID idempotencyKey) {
        SigningRequestGuard guard = new SigningRequestGuard();
        guard.documentType = documentType;
        guard.documentId = documentId;
        guard.activate(idempotencyKey);
        return guard;
    }

    public void activate(UUID idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
        this.sessionId = null;
        this.active = true;
        this.updatedAt = Instant.now();
    }

    public void release() {
        this.active = false;
        this.updatedAt = Instant.now();
    }

    public void associateSession(UUID sessionId) {
        this.sessionId = sessionId;
        this.updatedAt = Instant.now();
    }

    public UUID getIdempotencyKey() { return idempotencyKey; }
    public UUID getSessionId() { return sessionId; }
    public boolean isActive() { return active; }

    public static final class Key implements Serializable {
        private String documentType;
        private UUID documentId;

        public Key() { }

        public Key(String documentType, UUID documentId) {
            this.documentType = documentType;
            this.documentId = documentId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(documentType, key.documentType)
                    && Objects.equals(documentId, key.documentId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(documentType, documentId);
        }
    }
}
