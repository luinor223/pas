package com.abclogistics.pas.esign.outbox;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelay;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.esign.domain.SigningSession;
import com.abclogistics.pas.esign.client.MockProviderClient;
import com.abclogistics.pas.esign.repository.SigningSessionRepository;
import com.abclogistics.pas.esign.service.SigningSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.util.concurrent.TimeUnit;

/**
 * Dual-sink relay: {@code esign.provider_send} rows dispatch to the external provider over HTTP (D16);
 * everything else (the completion event) goes to Kafka. The send inherits the shared claim/retry/park
 * machinery, so a provider outage is retried and, once attempts exhaust or the provider refuses (4xx),
 * parked with the session flipped to FAILED (APR-07). The HTTP call runs outside any DB transaction.
 */
@Component
@ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EsignOutboxRelay extends OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(EsignOutboxRelay.class);

    private final KafkaTemplate<String, String> kafka;
    private final MockProviderClient provider;
    private final SigningSessionService sessions;
    private final SigningSessionRepository sessionRepo;
    private final String callbackBaseUrl;
    private final int maxAttempts;

    public EsignOutboxRelay(OutboxRepository outbox, OutboxRelayProperties props, TransactionTemplate tx,
                            KafkaTemplate<String, String> kafka, MockProviderClient provider,
                            SigningSessionService sessions, SigningSessionRepository sessionRepo,
                            @Value("${esign.callback-base-url:http://esign-service:8007/callbacks/esign}") String callbackBaseUrl,
                            @Value("${esign.provider.max-attempts:3}") int maxAttempts) {
        super(outbox, props, tx);
        this.kafka = kafka;
        this.provider = provider;
        this.sessions = sessions;
        this.sessionRepo = sessionRepo;
        this.callbackBaseUrl = callbackBaseUrl;
        this.maxAttempts = maxAttempts;
    }

    @Override
    protected void dispatch(OutboxEvent event) throws Exception {
        if (SigningSessionService.PROVIDER_SEND.equals(event.getEventType())) {
            dispatchProviderSend(event);
        } else {
            kafka.send(kafkaRecord(event)).get(5, TimeUnit.SECONDS);
            log.debug("Published esign outbox {} type={} topic={}", event.getId(), event.getEventType(), event.topic());
        }
    }

    private void dispatchProviderSend(OutboxEvent event) {
        SigningSession session = sessionRepo.findById(event.getAggregateId()).orElse(null);
        if (session == null || session.getStatus() != SigningSession.SessionStatus.PENDING_SEND) {
            // already sent, cancelled or gone (a re-run after a lost ack) — nothing to do
            return;
        }
        int attempt = event.getRetryCount() + 1;
        String callbackUrl = callbackBaseUrl + "/" + session.getSessionNo();
        try {
            String providerRef = provider.sendForSigning(session.getSessionNo(), session.getDocumentNo(),
                    session.getSignerName(), session.getSignerEmail(), callbackUrl);
            sessions.markSent(session.getId(), providerRef, attempt);
        } catch (HttpClientErrorException e) {
            throw new PermanentSendException(attempt, e);   // 4xx: the provider refused, a retry cannot help
        } catch (RuntimeException e) {
            if (attempt >= maxAttempts) {
                throw new PermanentSendException(attempt, e);
            }
            throw e;   // transport failure — release the claim and retry next poll
        }
    }

    @Override
    protected boolean isPermanentFailure(Exception e) {
        return e instanceof PermanentSendException;
    }

    @Override
    protected void onParked(OutboxEvent event, Exception cause) {
        int attempt = cause instanceof PermanentSendException p ? p.attempt() : event.getRetryCount();
        sessions.failSend(event.getAggregateId(), attempt, cause.getCause() != null
                ? cause.getCause().getMessage() : cause.getMessage());
    }

    @Override
    protected String destination(OutboxEvent event) {
        return SigningSessionService.PROVIDER_SEND.equals(event.getEventType())
                ? "EsignProvider.Send" : event.topic();
    }

    /** Marks a send that must not be retried: the provider refused (4xx) or attempts are exhausted. */
    private static final class PermanentSendException extends RuntimeException {
        private final int attempt;

        PermanentSendException(int attempt, Throwable cause) {
            super(cause);
            this.attempt = attempt;
        }

        int attempt() {
            return attempt;
        }
    }
}
