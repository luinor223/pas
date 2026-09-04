import { expect, test } from "@playwright/test";
import { envelope, installApiMocks } from "./support/api";

test("marks an unread notification as read and opens its record", async ({ page }) => {
  let markedRead = false;
  await installApiMocks(page, (request, url) => {
    if (url.pathname === "/api/v1/notifications" && url.searchParams.get("size") === "15") {
      return { body: envelope({
        items: [{ id: "notification-1", category: "APPROVAL", eventType: "workflow.assigned", documentType: "CONTRACT", documentId: "70000000-0000-4000-8000-000000000001", documentNo: "CTR-2026-0001", title: "Contract needs your approval", body: "Review the contract and decide whether it can proceed.", createdAt: "2026-09-04T01:00:00Z", readAt: markedRead ? "2026-09-04T02:00:00Z" : null }],
        total: 1, unreadCount: markedRead ? 0 : 1, counts: { all: markedRead ? 0 : 1, unread: markedRead ? 0 : 1, APPROVAL: markedRead ? 0 : 1 },
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
  await expect(page).toHaveURL(/\/contracts\?id=70000000-0000-4000-8000-000000000001/);
});

test("marks all notifications as read and refreshes the counters", async ({ page }) => {
  let unreadCount = 2;
  await installApiMocks(page, (request, url) => {
    if (url.pathname === "/api/v1/notifications" && url.searchParams.get("size") === "15") {
      return { body: envelope({ items: [], total: 0, unreadCount, counts: { all: unreadCount, unread: unreadCount } }) };
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

test("keeps the selected notification category after reload", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/notifications" && url.searchParams.get("size") === "15") {
      return { body: envelope({ items: [], total: 0, unreadCount: 0, counts: { all: 0, unread: 0, EXPIRY: 0 } }) };
    }
  });

  await page.goto("/notifications");
  const categoryRequest = page.waitForRequest((request) => request.url().includes("category=EXPIRY"));
  await page.getByRole("tab", { name: "Expiring" }).click();
  await categoryRequest;
  await expect(page).toHaveURL(/tab=EXPIRY/);
  await expect(page.getByText("No expiring notifications")).toBeVisible();

  await page.reload();
  await expect(page).toHaveURL(/tab=EXPIRY/);
  await expect(page.getByText("No expiring notifications")).toBeVisible();
});

test("supports notification tab history and keyboard navigation", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/notifications" && url.searchParams.get("size") === "15") {
      return { body: envelope({ items: [], total: 0, unreadCount: 0, counts: { all: 0, unread: 0, EXPIRY: 0, SYSTEM: 0 } }) };
    }
  });

  await page.goto("/notifications?tab=EXPIRY");

  await page.getByRole("tab", { name: "System" }).click();
  await expect(page).toHaveURL(/tab=SYSTEM/);
  await page.goBack();
  await expect(page).toHaveURL(/tab=EXPIRY/);
  const expiringTab = page.getByRole("tab", { name: "Expiring" });
  await expect(expiringTab).toHaveAttribute("aria-selected", "true");
  await expiringTab.focus();
  await expiringTab.press("ArrowRight");
  await expect(page).toHaveURL(/tab=SYSTEM/);
});

test("recovers when a notification page no longer exists", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/notifications" && url.searchParams.get("size") === "15") {
      return { body: envelope({ items: [], total: 0, unreadCount: 0, counts: { all: 0, unread: 0 } }) };
    }
  });

  await page.goto("/notifications?page=3");

  await expect(page).toHaveURL(/\/notifications$/);
});

test("keeps the user on notifications when marking read fails", async ({ page }) => {
  await installApiMocks(page, (request, url) => {
    if (url.pathname === "/api/v1/notifications" && url.searchParams.get("size") === "15") {
      return { body: envelope({
        items: [{ id: "notification-failure", category: "APPROVAL", eventType: "workflow.step_assigned", documentType: "CONTRACT", documentId: "contract-1", documentNo: "CTR-2026-0001", title: "Contract needs your approval", body: "Review this contract.", createdAt: "2026-09-04T01:00:00Z", readAt: null }],
        total: 1, unreadCount: 1, counts: { all: 1, unread: 1, APPROVAL: 1 },
      }) };
    }
    if (url.pathname === "/api/v1/notifications/notification-failure/read" && request.method() === "PATCH") {
      return { status: 500, body: envelope({ message: "temporary database failure" }) };
    }
  });

  await page.goto("/notifications");
  await page.getByRole("link", { name: /Contract needs your approval/ }).click();

  await expect(page).toHaveURL(/\/notifications$/);
  await expect(page.getByText("Could not update the notification")).toBeVisible();
});

for (const linked of [
  { documentType: "PRICE_LIST", documentId: "version-1", documentNo: "PRC-0001", expectedUrl: /\/price-lists\?versionId=version-1/ },
  { documentType: "OPERATION_PERIOD", documentId: null, documentNo: "September 2026", expectedUrl: /\/volume-records\?tab=periods/ },
]) {
  test(`opens a ${linked.documentType.toLowerCase()} notification deep link`, async ({ page }) => {
    await installApiMocks(page, (_request, url) => {
      if (url.pathname === "/api/v1/notifications" && url.searchParams.get("size") === "15") {
        return { body: envelope({
          items: [{ id: `notification-${linked.documentId}`, category: "SYSTEM", eventType: "record.updated", documentType: linked.documentType, documentId: linked.documentId, documentNo: linked.documentNo, title: `${linked.documentNo} was updated`, body: "Open the record to review the latest details.", createdAt: "2026-09-04T01:00:00Z", readAt: "2026-09-04T02:00:00Z" }],
          total: 1, unreadCount: 0, counts: { all: 0, unread: 0, SYSTEM: 0 },
        }) };
      }
    });

    await page.goto("/notifications");
    await page.getByRole("link", { name: new RegExp(`${linked.documentNo} was updated`) }).click();
    await expect(page).toHaveURL(linked.expectedUrl);
  });
}
