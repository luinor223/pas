package com.abclogistics.pas.esign.scheduler;

import com.abclogistics.pas.esign.service.SigningSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EsignRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(EsignRetryScheduler.class);

    private final SigningSessionService sessionService;

    public EsignRetryScheduler(SigningSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * Retry pending sessions that failed to send to the provider.
     * Runs every 30 seconds.
     */
    @Scheduled(fixedDelayString = "${esign.retry-interval:30000}")
    public void retryPending() {
        try {
            sessionService.retryPendingSessions();
        } catch (Exception e) {
            log.warn("Esign retry scheduler cycle failed: {}", e.getMessage(), e);
        }
    }
}
