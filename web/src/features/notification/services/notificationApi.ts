import { api, toParams } from "@/shared/api/client";
import type { InboxResponse, NotificationCategory } from "../types/notificationTypes";

export type InboxParams = {
  unread?: boolean;
  category?: NotificationCategory;
  page?: number;
  size?: number;
  sort?: string;
};

export const notificationApi = {
  // InboxResponse is a plain body, so it arrives whole - no toPage, no meta.
  inbox: (params: InboxParams = {}) =>
    api.get<InboxResponse>(`/notifications${toParams(params as Record<string, unknown>)}`).then((r) => r.data),
  markRead: (id: string) => api.patch(`/notifications/${id}/read`).then(() => undefined),
  markAllRead: () => api.patch("/notifications/read-all").then(() => undefined),
};
