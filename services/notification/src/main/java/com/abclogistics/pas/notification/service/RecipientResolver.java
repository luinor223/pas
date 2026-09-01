package com.abclogistics.pas.notification.service;

import com.abclogistics.pas.common.events.MalformedEventException;
import com.abclogistics.pas.notification.event.EventEnvelope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Who a given event notifies (registry §4, db-notification.md). Recipient ids that the producer
 * already knows travel in the payload (`assignee_ids`, `requested_by`, `owner_user_id`);
 * role-addressed events carry `recipient_role` and resolve through identity.
 */
@Service
public class RecipientResolver {

    private final IdentityGrpcClient identity;

    public RecipientResolver(IdentityGrpcClient identity) {
        this.identity = identity;
    }

    /**
     * Distinct recipients, in a stable order. Empty means the event genuinely addresses nobody —
     * a role with no members. A recipient field the producer got wrong is a
     * {@link MalformedEventException} instead, so it reaches the DLT rather than being marked
     * processed and lost.
     */
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
            default -> throw new IllegalArgumentException(
                    "no recipient rule for event type: " + event.eventType());
        };
    }

    /** An empty result here is an answer: the role exists and nobody holds it. */
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
        // distinct: one user can hold two roles on the same step, and the producer snapshots both
        return raw.stream().map(value -> uuid(event, field, value)).distinct().toList();
    }

    private static List<UUID> id(EventEnvelope event, String field) {
        return List.of(uuid(event, field, event.payload().get(field)));
    }

    /**
     * A recipient field that is absent or not a uuid is a producer defect no redelivery fixes.
     * Notifying nobody would silently drop the event; the DLT keeps it, and the person who owns
     * the producer gets a record to replay once it is fixed.
     */
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
