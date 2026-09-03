import { keepPreviousData, queryOptions } from "@tanstack/react-query";
import { notificationApi, type InboxParams } from "../services/notificationApi";

export const inboxQuery = (params: InboxParams = {}) =>
  queryOptions({
    queryKey: ["inbox", params],
    queryFn: () => notificationApi.inbox(params),
    // Paging changes the key. Without this the rows blank out and the page
    // total reads as zero mid-fetch, which resets the pager to page one.
    placeholderData: keepPreviousData,
  });

// One row is enough: the counters are unfiltered, so the badge needs no list.
export const unreadCountQuery = () =>
  queryOptions({
    queryKey: ["inbox", { size: 1 }],
    queryFn: () => notificationApi.inbox({ size: 1 }),
    select: (d) => d.unreadCount,
  });
