package com.abclogistics.pas.esign.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "signing_session", schema = "esign")
public class SigningSession {

    public enum SessionStatus {
        PENDING_SEND, SIGNING, SIGNED, FAILED, CANCELLED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_no", nullable = false, unique = true, updatable = false)
    private String sessionNo;

    @Column(name = "document_type_code", nullable = false, updatable = false)
    private String documentTypeCode;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "document_no")
    private String documentNo;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "signer_name", nullable = false)
    private String signerName;

    @Column(name = "signer_email", nullable = false)
    private String signerEmail;

    @Column(name = "provider", nullable = false)
    private String provider = "MockSign";

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private SessionStatus status = SessionStatus.PENDING_SEND;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_by_name", updatable = false)
    private String requestedByName;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("occurredAt ASC")
    private List<StatusHistory> statusHistory = new ArrayList<>();

    protected SigningSession() {}

    public static SigningSession create(String documentTypeCode, UUID documentId,
                                         String documentNo, String customerName,
                                         String signerName, String signerEmail,
                                         UUID idempotencyKey, UUID requestedBy, String requestedByName) {
        SigningSession s = new SigningSession();
        s.id = UUID.randomUUID();
        s.sessionNo = generateSessionNo();
        s.documentTypeCode = documentTypeCode;
        s.documentId = documentId;
        s.documentNo = documentNo;
        s.customerName = customerName;
        s.signerName = signerName;
        s.signerEmail = signerEmail;
        s.idempotencyKey = idempotencyKey;
        s.requestedBy = requestedBy;
        s.requestedByName = requestedByName;
        s.status = SessionStatus.PENDING_SEND;
        s.attempts = 0;
        s.createdAt = Instant.now();
        return s;
    }

    private static String generateSessionNo() {
        return "SIG-" + (1000 + (int) (Math.random() * 9000));
    }

    public void markSent(String providerRef) {
        this.status = SessionStatus.SIGNING;
        this.providerRef = providerRef;
        this.sentAt = Instant.now();
        this.attempts++;
    }

    public void markFailed(String error) {
        this.lastError = error;
        this.attempts++;
    }

    public void markTerminal(SessionStatus terminalStatus) {
        this.status = terminalStatus;
        this.completedAt = Instant.now();
    }

    public void addStatusHistory(StatusHistory history) {
        this.statusHistory.add(history);
        history.setSession(this);
    }

    public boolean canCancel() {
        return status == SessionStatus.PENDING_SEND || status == SessionStatus.SIGNING;
    }

    // Getters
    public UUID getId() { return id; }
    public String getSessionNo() { return sessionNo; }
    public String getDocumentTypeCode() { return documentTypeCode; }
    public UUID getDocumentId() { return documentId; }
    public String getDocumentNo() { return documentNo; }
    public String getCustomerName() { return customerName; }
    public String getSignerName() { return signerName; }
    public String getSignerEmail() { return signerEmail; }
    public String getProvider() { return provider; }
    public String getProviderRef() { return providerRef; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public SessionStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getRequestedByName() { return requestedByName; }
    public int getVersion() { return version; }
    public Instant getSentAt() { return sentAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<StatusHistory> getStatusHistory() { return statusHistory; }

    // Setters
    public void setStatus(SessionStatus status) { this.status = status; }
    public void setVersion(int version) { this.version = version; }
    public void setProviderRef(String providerRef) { this.providerRef = providerRef; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
