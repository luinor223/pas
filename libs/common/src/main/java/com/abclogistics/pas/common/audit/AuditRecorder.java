package com.abclogistics.pas.common.audit;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writes an {@code audit.recorded} row to the outbox in the caller's transaction.
 * Actor is taken from the security context, or null for system/scheduler actions.
 */
@Component
public class AuditRecorder {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final String sourceService;

    public AuditRecorder(OutboxRepository outbox, ObjectMapper objectMapper,
                         @Value("${spring.application.name}") String sourceService) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.sourceService = sourceService;
    }

    public void record(String entityType, UUID entityId, String action, String note,
                       Map<String, Object> changes) {
        record(entityType, entityId, action, null, null, note, changes);
    }

    public void record(String entityType, UUID entityId, String action,
                       String beforeStatus, String afterStatus, String note,
                       Map<String, Object> changes) {
        AuthenticatedUser actor = currentActor();
        AuditPayload payload = new AuditPayload(
                entityType, entityId, action,
                actor == null ? null : actor.userId(),
                actor == null ? "system" : actor.fullName(),
                actor == null ? null : actor.department(),
                Instant.now(), beforeStatus, afterStatus, note,
                changes == null ? Map.of() : changes,
                sourceService);
        outbox.save(OutboxEvent.audit(entityType, entityId, serialize(payload)));
    }

    private AuthenticatedUser currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        return null;
    }

    private String serialize(AuditPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize audit payload", e);
        }
    }
}
