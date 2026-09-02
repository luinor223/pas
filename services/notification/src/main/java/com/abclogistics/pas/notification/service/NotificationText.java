package com.abclogistics.pas.notification.service;

import com.abclogistics.pas.common.events.MalformedEventException;

import java.util.Map;

/** Builds the notification text stored at fan-out time. */
final class NotificationText {

    private NotificationText() { }

    static String title(String eventType, Map<String, Object> payload) {
        return switch (eventType) {
            case "workflow.step_assigned" -> "New document assigned to you";
            case "workflow.step_overdue" -> "Approval overdue";
            case "workflow.step_actioned" -> "Document reviewed";
            case "workflow.completed" -> outcomeTitle(text(payload, "outcome"));
            case "esign.session_completed" -> esignTitle(text(payload, "result"));
            case "document.expiring" -> "Document expiring in %s days"
                    .formatted(text(payload, "days_left"));
            case "operations.period_locked" -> "Volume period locked";
            default -> throw new MalformedEventException("no title for event type: " + eventType);
        };
    }

    static String body(String eventType, Map<String, Object> payload) {
        String documentNo = text(payload, "document_no");
        return switch (eventType) {
            case "workflow.step_assigned" -> "%s requires your review at the %s step."
                    .formatted(documentNo, text(payload, "step_name"));
            case "workflow.step_overdue" -> "%s has been awaiting %s for %s hours. SLA is %s hours."
                    .formatted(documentNo, text(payload, "step_name"),
                            text(payload, "waiting_hours"), text(payload, "sla_hours"));
            case "workflow.step_actioned" -> "%s: %s.%s".formatted(documentNo,
                    text(payload, "action"), comment(payload));
            case "workflow.completed" -> "%s: %s.".formatted(documentNo,
                    outcomeTitle(text(payload, "outcome")));
            case "esign.session_completed" -> "%s: %s.".formatted(documentNo,
                    esignTitle(text(payload, "result")));
            case "document.expiring" -> "%s expires on %s. Consider renewal or an addendum."
                    .formatted(documentNo, text(payload, "expires_on"));
            case "operations.period_locked" -> "%s locked volume period %s. Statements can now be generated."
                    .formatted(text(payload, "locked_by_name"), text(payload, "period_code"));
            default -> throw new MalformedEventException("no body for event type: " + eventType);
        };
    }

    private static String outcomeTitle(String outcome) {
        return switch (outcome) {
            case "APPROVED" -> "Document approved";
            case "REJECTED" -> "Document rejected";
            case "REVISION_REQUESTED" -> "Revision requested";
            case "CANCELLED" -> "Document cancelled";
            default -> "Workflow completed";
        };
    }

    private static String esignTitle(String result) {
        return switch (result) {
            case "SIGNED" -> "Signature completed";
            case "FAILED" -> "E-signature failed";
            case "CANCELLED" -> "E-signature cancelled";
            default -> "E-signature finished";
        };
    }

    private static String comment(Map<String, Object> payload) {
        String comment = text(payload, "comment");
        return comment.isBlank() ? "" : " Reason: " + comment;
    }

    private static String text(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        return value == null ? "" : value.toString();
    }
}
