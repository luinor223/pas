package com.abclogistics.pas.esign.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "signing_callback_log", schema = "esign")
public class SigningCallbackLog {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private SigningSession session;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "result")
    private String result;

    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    protected SigningCallbackLog() {}

    public static SigningCallbackLog create(SigningSession session, String providerRef,
                                             String result, String rawPayload) {
        SigningCallbackLog log = new SigningCallbackLog();
        log.id = UUID.randomUUID();
        log.session = session;
        log.providerRef = providerRef;
        log.result = result;
        log.rawPayload = rawPayload;
        log.receivedAt = Instant.now();
        return log;
    }

    public static SigningCallbackLog createOrphan(String providerRef, String result, String rawPayload) {
        SigningCallbackLog log = new SigningCallbackLog();
        log.id = UUID.randomUUID();
        log.providerRef = providerRef;
        log.result = result;
        log.rawPayload = rawPayload;
        log.receivedAt = Instant.now();
        return log;
    }

    // Getters
    public UUID getId() { return id; }
    public SigningSession getSession() { return session; }
    public String getProviderRef() { return providerRef; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getResult() { return result; }
    public String getRawPayload() { return rawPayload; }
}
