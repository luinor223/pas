// Inbox DTOs mirroring services/notification NotificationController.

export type NotificationCategory = "APPROVAL" | "ESIGN" | "EXPIRY" | "SYSTEM";

export type NotificationResponse = {
  id: string;
  category: NotificationCategory;
  eventType: string;
  documentType: string | null;
  documentId: string | null;
  documentNo: string | null;
  title: string;
  body: string;
  createdAt: string;
  readAt: string | null;
};

// Not a Page: total is the filtered list total, while counts are unread totals per tab.
export type InboxResponse = {
  items: NotificationResponse[];
  total: number;
  unreadCount: number;
  counts: Record<string, number>;
};
