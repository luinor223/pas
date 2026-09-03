import { queryOptions } from "@tanstack/react-query";
import { notificationApi, type InboxParams } from "../services/notificationApi";

export const inboxQuery = (params: InboxParams = {}) =>
  queryOptions({ queryKey: ["inbox", params], queryFn: () => notificationApi.inbox(params) });

// One row is enough: the counters are unfiltered, so the badge needs no list.
export const unreadCountQuery = () =>
  queryOptions({
    queryKey: ["inbox", { size: 1 }],
    queryFn: () => notificationApi.inbox({ size: 1 }),
    select: (d) => d.unreadCount,
  });
