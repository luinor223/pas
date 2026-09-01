package com.abclogistics.pas.notification.service;

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
            case "workflow.completed" -> "APPROVED".equals(text(payload, "outcome"))
                    ? "Hồ sơ đã được duyệt" : "Hồ sơ bị từ chối";
            case "esign.session_completed" -> "SIGNED".equals(text(payload, "result"))
                    ? "Ký số hoàn tất" : "Ký số không thành công";
            case "document.expiring" -> "Hồ sơ sắp hết hạn";
            case "operations.period_locked" -> "Kỳ số liệu đã khoá";
            default -> throw new IllegalArgumentException("no title for event type: " + eventType);
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
            case "workflow.completed" -> "%s: %s.".formatted(documentNo, text(payload, "outcome"));
            case "esign.session_completed" -> "%s: %s.".formatted(documentNo, text(payload, "result"));
            case "document.expiring" -> "%s hết hạn ngày %s (còn %s ngày)."
                    .formatted(documentNo, text(payload, "expires_on"), text(payload, "days_left"));
            case "operations.period_locked" -> "Kỳ %s đã được %s khoá."
                    .formatted(text(payload, "period_code"), text(payload, "locked_by_name"));
            default -> throw new IllegalArgumentException("no body for event type: " + eventType);
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
