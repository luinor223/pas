package com.abclogistics.pas.contract.listener;

import org.springframework.stereotype.Component;

/**
 * Consumes {@code workflow.instance_started} and {@code workflow.completed} from
 * {@code pas.events} (group {@code contract-service}), discriminating on the
 * {@code document_type} header so records for other owners are skipped before deserialization.
 *
 * <p>Every handler is idempotent via {@code processed_event}, inserted in the same transaction
 * as the effect: offsets commit after processing, so a mid-batch death re-reads records that were
 * already applied.
 *
 * <p>Order-tolerant by design (registry §9 footnote ¹): a {@code workflow.completed} that arrives
 * while the document is still SUBMITTED applies the skipped {@code SUBMITTED → UNDER_REVIEW}
 * edge first and then the outcome, in one transaction, one history row each. Kafka orders sends,
 * not commits, so this is a reachable ordering and not a defensive nicety — rejecting it would
 * wedge the document permanently.
 */
@Component
public class WorkflowEventListener {

    /** {@code workflow.instance_started} → SUBMITTED → UNDER_REVIEW. */
    public void onInstanceStarted(String payload, String documentType, String eventId) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /** {@code workflow.completed} → APPROVED / REJECTED / REVISION_REQUESTED. */
    public void onCompleted(String payload, String documentType, String eventId) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }
}
