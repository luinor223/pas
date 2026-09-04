package com.abclogistics.pas.common.audit;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.common.security.SystemActor;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Writes {@code audit.recorded} to the caller's outbox transaction. */
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
        record(entityType, entityId, null, action, null, null, note, changes);
    }

    public void record(String entityType, UUID entityId, String action,
                       String beforeStatus, String afterStatus, String note,
                       Map<String, Object> changes) {
        record(entityType, entityId, null, action, beforeStatus, afterStatus, note, changes);
    }

    public void record(String entityType, UUID entityId, String entityNo, String action,
                       String beforeStatus, String afterStatus, String note,
                       Map<String, Object> changes) {
        AuthenticatedUser actor = SecurityUtils.currentUser().orElse(null);
        String ip = currentIpAddress();
        AuditPayload payload = new AuditPayload(
                sourceService,
                entityType, entityId, entityNo, action,
                actor == null ? SystemActor.ID : actor.userId(),
                actor == null ? SystemActor.NAME : actor.fullName(),
                actor == null ? null : actor.department(),
                beforeStatus, afterStatus,
                changes == null ? Map.of() : changes,
                note, ip, Instant.now());
        outbox.save(OutboxEvent.audit(entityType, entityId, serialize(payload)));
    }

    private String currentIpAddress() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                HttpServletRequest req = sra.getRequest();
                String forwarded = req.getHeader("X-Forwarded-For");
                if (forwarded != null && !forwarded.isBlank()) {
                    return forwarded.split(",")[0].trim();
                }
                return req.getRemoteAddr();
            }
        } catch (Exception ignored) {
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
