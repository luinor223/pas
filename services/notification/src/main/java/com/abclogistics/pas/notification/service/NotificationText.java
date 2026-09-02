package com.abclogistics.pas.notification.service;

import com.abclogistics.pas.common.events.MalformedEventException;

import java.util.Map;

/**
 * The title/body a notification is written with. Composed once at fan-out and stored, so a row
 * stays readable after the source document is renamed or cancelled (db-notification.md).
 */
final class NotificationText {

    private NotificationText() { }

    static String title(String eventType, Map<String, Object> payload) {
        return switch (eventType) {
            case "workflow.step_assigned" -> "Hồ sơ cần xử lý";
            case "workflow.step_overdue" -> "Quá hạn duyệt";
            case "workflow.step_actioned" -> "Hồ sơ đã được xử lý";
            case "workflow.completed" -> outcomeTitle(text(payload, "outcome"));
            case "esign.session_completed" -> esignTitle(text(payload, "result"));
            case "document.expiring" -> "Hồ sơ sắp hết hạn";
            case "operations.period_locked" -> "Kỳ số liệu đã khoá";
            default -> throw new MalformedEventException("no title for event type: " + eventType);
        };
    }

    static String body(String eventType, Map<String, Object> payload) {
        String documentNo = text(payload, "document_no");
        return switch (eventType) {
            case "workflow.step_assigned" -> "%s đang chờ bạn duyệt ở bước %s."
                    .formatted(documentNo, text(payload, "step_name"));
            case "workflow.step_overdue" -> "%s đã chờ %s giờ ở bước %s (SLA %s giờ)."
                    .formatted(documentNo, text(payload, "waiting_hours"),
                            text(payload, "step_name"), text(payload, "sla_hours"));
            case "workflow.step_actioned" -> "%s: %s.%s".formatted(documentNo,
                    text(payload, "action"), comment(payload));
            case "workflow.completed" -> "%s: %s.".formatted(documentNo,
                    outcomeTitle(text(payload, "outcome")));
            case "esign.session_completed" -> "%s: %s.".formatted(documentNo,
                    esignTitle(text(payload, "result")));
            case "document.expiring" -> "%s hết hạn ngày %s (còn %s ngày)."
                    .formatted(documentNo, text(payload, "expires_on"), text(payload, "days_left"));
            case "operations.period_locked" -> "Kỳ %s đã được %s khoá."
                    .formatted(text(payload, "period_code"), text(payload, "locked_by_name"));
            default -> throw new MalformedEventException("no body for event type: " + eventType);
        };
    }

    /** Four outcomes, not two: a revision request is not a rejection (registry §9). */
    private static String outcomeTitle(String outcome) {
        return switch (outcome) {
            case "APPROVED" -> "Hồ sơ đã được duyệt";
            case "REJECTED" -> "Hồ sơ bị từ chối";
            case "REVISION_REQUESTED" -> "Hồ sơ cần chỉnh sửa";
            case "CANCELLED" -> "Hồ sơ đã bị huỷ";
            default -> "Hồ sơ đã kết thúc quy trình";
        };
    }

    /** SIGNED | FAILED | CANCELLED (registry §4). */
    private static String esignTitle(String result) {
        return switch (result) {
            case "SIGNED" -> "Ký số hoàn tất";
            case "FAILED" -> "Ký số không thành công";
            case "CANCELLED" -> "Ký số đã bị huỷ";
            default -> "Ký số đã kết thúc";
        };
    }

    private static String comment(Map<String, Object> payload) {
        String comment = text(payload, "comment");
        return comment.isBlank() ? "" : " Lý do: " + comment;
    }

    private static String text(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        return value == null ? "" : value.toString();
    }
}
