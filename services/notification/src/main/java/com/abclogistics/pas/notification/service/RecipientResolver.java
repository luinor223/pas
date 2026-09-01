package com.abclogistics.pas.notification.service;

import com.abclogistics.pas.notification.event.EventEnvelope;
import org.springframework.stereotype.Service;

import java.util.List;
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

    /** Distinct recipients, in a stable order. Empty means the event notifies nobody. */
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

    private List<UUID> byRole(EventEnvelope event) {
        Object role = event.payload().get("recipient_role");
        return role == null ? List.of() : identity.listUsersByRole(role.toString());
    }

    private static List<UUID> ids(EventEnvelope event, String field) {
        if (!(event.payload().get(field) instanceof List<?> raw)) {
            return List.of();
        }
        // distinct: one user can hold two roles on the same step, and the producer snapshots both
        return raw.stream().map(Object::toString).map(RecipientResolver::uuid)
                .filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static List<UUID> id(EventEnvelope event, String field) {
        Object value = event.payload().get(field);
        UUID recipient = value == null ? null : uuid(value.toString());
        return recipient == null ? List.of() : List.of(recipient);
    }

    /**
     * A recipient field that is not a uuid notifies nobody rather than failing the event: the
     * alternative is a poison record blocking the partition over one bad row. {@code requested_by}
     * carried a display name until workflow-service was fixed, which is exactly this case.
     */
    private static UUID uuid(String value) {
        try {
            return value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException notARecipient) {
            return null;
        }
    }
}
