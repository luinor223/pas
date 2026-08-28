package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.domain.StatusHistory;

import java.time.Instant;
import java.util.UUID;

/**
 * One D17 transition. This is the synchronous, local half of the History tab; the eventually
 * consistent half comes from audit-service and is never read by a business rule.
 */
public record StatusHistoryResponse(
        UUID id,
        String fromStatus,
        String toStatus,
        String trigger,
        UUID triggerRef,
        UUID actorId,
        String actorName,
        String note,
        Instant occurredAt) {

    public static StatusHistoryResponse of(StatusHistory history) {
        return new StatusHistoryResponse(
                history.getId(),
                // null on the very first row of an entity's life, and it stays null: "created"
                // is not a transition from anything.
                history.getFromStatus() == null ? null : history.getFromStatus().name(),
                history.getToStatus().name(),
                history.getTriggerKind().name(),
                history.getTriggerRef(),
                history.getActorId(),
                history.getActorName(),
                history.getNote(),
                history.getOccurredAt());
    }
}
