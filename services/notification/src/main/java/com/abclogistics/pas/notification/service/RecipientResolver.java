package com.abclogistics.pas.notification.service;

import com.abclogistics.pas.common.events.MalformedEventException;
import com.abclogistics.pas.notification.client.IdentityGrpcClient;
import com.abclogistics.pas.notification.event.EventEnvelope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Resolves each event to user IDs using registry §4. */
@Service
public class RecipientResolver {

    private final IdentityGrpcClient identity;

    public RecipientResolver(IdentityGrpcClient identity) {
        this.identity = identity;
    }

    /** Returns distinct recipients; malformed recipient data goes to the DLT. */
    public List<UUID> recipientsOf(EventEnvelope event) {
        return switch (event.eventType()) {
            // the people who can act on it
            case "workflow.step_assigned", "workflow.step_overdue" -> ids(event, "assignee_ids");
            // the submitter, who is the one who has to do something about the outcome
            case "workflow.completed", "workflow.step_actioned", "esign.session_completed" ->
                    id(event, "requested_by");
            case "document.expiring" -> id(event, "owner_user_id");
            // the only event addressed by role rather than by id (registry §4)
            case "operations.period_locked" -> byRole(event);
            default -> throw new MalformedEventException(
                    "no recipient rule for event type: " + event.eventType());
        };
    }

    private List<UUID> byRole(EventEnvelope event) {
        Object role = event.payload().get("recipient_role");
        if (role == null || role.toString().isBlank()) {
            throw new MalformedEventException(
                    "%s carries no recipient_role".formatted(event.eventType()));
        }
        return identity.listUsersByRole(role.toString()).stream().distinct().toList();
    }

    private static List<UUID> ids(EventEnvelope event, String field) {
        if (!(event.payload().get(field) instanceof List<?> raw)) {
            throw new MalformedEventException("%s carries no %s array".formatted(
                    event.eventType(), field));
        }
        // A user may match the same step more than once.
        return raw.stream().map(value -> uuid(event, field, value)).distinct().toList();
    }

    private static List<UUID> id(EventEnvelope event, String field) {
        return List.of(uuid(event, field, event.payload().get(field)));
    }

    private static UUID uuid(EventEnvelope event, String field, Object value) {
        String text = Objects.toString(value, "");
        try {
            if (text.isBlank()) {
                throw new IllegalArgumentException("blank");
            }
            return UUID.fromString(text);
        } catch (IllegalArgumentException notARecipient) {
            throw new MalformedEventException("%s.%s is not a uuid: '%s'".formatted(
                    event.eventType(), field, text));
        }
    }
}
