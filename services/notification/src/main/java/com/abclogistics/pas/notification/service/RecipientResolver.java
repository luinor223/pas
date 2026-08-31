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
        throw new UnsupportedOperationException("Phase B: resolve recipients per event type");
    }
}
