package com.abclogistics.pas.esign.scheduler;

import com.abclogistics.pas.esign.domain.SigningSession;
import com.abclogistics.pas.esign.service.SigningSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Scheduler that picks up PENDING_SEND sessions and dispatches them to the provider.
 * Replaces the direct sendToProvider call with a proper async dispatch pattern.
 * APR-07: provider outage never touches document data.
 */
@Component
public class EsignDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(EsignDispatchScheduler.class);

    private final SigningSessionService sessionService;

    public EsignDispatchScheduler(SigningSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * Poll for PENDING_SEND sessions and dispatch to provider.
     * Runs every 10 seconds.
     */
    @Scheduled(fixedDelayString = "${esign.dispatch-interval:10000}")
    @Transactional
    public void dispatchPending() {
        try {
            List<SigningSession> pending = sessionService.findPendingSendSessions();
            if (pending.isEmpty()) {
                return;
            }
            log.debug("Dispatching {} pending signing sessions", pending.size());
            for (SigningSession session : pending) {
                try {
                    sessionService.sendToProvider(session);
                } catch (Exception e) {
                    log.warn("Dispatch failed for session {}: {}", session.getSessionNo(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Esign dispatch scheduler cycle failed: {}", e.getMessage(), e);
        }
    }
}
