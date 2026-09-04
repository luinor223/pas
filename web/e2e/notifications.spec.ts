import { expect, test } from "@playwright/test";
import { envelope, installApiMocks } from "./support/api";

test("marks an unread notification as read and opens its record", async ({ page }) => {
  let markedRead = false;
  await installApiMocks(page, (request, url) => {
    if (url.pathname === "/api/v1/notifications" && url.searchParams.get("size") === "15") {
      return { body: envelope({
        items: [{ id: "notification-1", category: "APPROVAL", eventType: "workflow.assigned", documentType: "CONTRACT", documentId: "contract-1", documentNo: "CTR-2026-0001", title: "Contract needs your approval", body: "Review the contract and decide whether it can proceed.", createdAt: "2026-09-04T01:00:00Z", readAt: markedRead ? "2026-09-04T02:00:00Z" : null }],
        total: 1, unreadCount: markedRead ? 0 : 1, counts: { all: 1, unread: markedRead ? 0 : 1, APPROVAL: 1 },
      }) };
    }
    if (url.pathname === "/api/v1/notifications/notification-1/read" && request.method() === "PATCH") {
      markedRead = true;
      return { body: envelope(null) };
    }
  });

  await page.goto("/notifications");
  await expect(page.getByText("Contract needs your approval")).toBeVisible();
  const markRequest = page.waitForRequest((request) => request.url().endsWith("/notifications/notification-1/read") && request.method() === "PATCH");
  await page.getByRole("link", { name: /Contract needs your approval/ }).click();
  await markRequest;
  await expect(page).toHaveURL(/\/contracts\?id=contract-1/);
});

test("marks all notifications as read and refreshes the counters", async ({ page }) => {
  let unreadCount = 2;
  await installApiMocks(page, (request, url) => {
    if (url.pathname === "/api/v1/notifications" && url.searchParams.get("size") === "15") {
      return { body: envelope({ items: [], total: 0, unreadCount, counts: { all: 2, unread: unreadCount } }) };
    }
    if (url.pathname === "/api/v1/notifications/read-all" && request.method() === "PATCH") {
      unreadCount = 0;
      return { body: envelope(null) };
    }
  });

  await page.goto("/notifications");
  await expect(page.getByText("Notifications (2 unread)")).toBeVisible();
  const markAllRequest = page.waitForRequest((request) => request.url().endsWith("/notifications/read-all") && request.method() === "PATCH");
  await page.getByRole("button", { name: "Mark all read" }).click();
  await markAllRequest;
  await expect(page.getByText("Notifications (0 unread)")).toBeVisible();
  await expect(page.getByRole("button", { name: "Mark all read" })).toBeDisabled();
});

test("requests the selected notification category", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/notifications" && url.searchParams.get("size") === "15") {
      return { body: envelope({ items: [], total: 0, unreadCount: 0, counts: { all: 0, unread: 0, EXPIRY: 0 } }) };
    }
  });

  await page.goto("/notifications");
  const categoryRequest = page.waitForRequest((request) => request.url().includes("category=EXPIRY"));
  await page.getByRole("button", { name: "Expiring" }).click();
  await categoryRequest;
  await expect(page.getByText("No expiring notifications")).toBeVisible();
});
