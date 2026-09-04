import { expect, test } from "@playwright/test";
import { expectApiSuccess, openAuthenticatedApp } from "./support/real-stack";

test.beforeEach(async ({ page }) => openAuthenticatedApp(page));

async function openNotifications(page: import("@playwright/test").Page) {
  const response = page.waitForResponse((item) => {
    const url = new URL(item.url());
    return url.pathname === "/api/v1/notifications" && url.searchParams.get("size") === "15";
  });
  await page.getByRole("navigation").getByRole("link", { name: "Notifications", exact: true }).click();
  const result = await response;
  await expectApiSuccess(result, "GET /api/v1/notifications");
  return result;
}

test("loads recipient-isolated notification counts from the real service", async ({ page }) => {
  const response = await openNotifications(page);
  const payload = (await response.json()).data;
  expect(payload.items).toEqual(expect.any(Array));
  expect(payload.counts).toEqual(expect.objectContaining({ all: expect.any(Number), unread: expect.any(Number) }));
  await expect(page.getByText(new RegExp(`Notifications \\(${payload.unreadCount} unread\\)`))).toBeVisible();
});

test("loads the lightweight header count from the real service", async ({ page }) => {
  const responsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === "/api/v1/notifications/unread-count"
      && response.request().method() === "GET";
  });

  await page.reload();
  const response = await responsePromise;
  await expectApiSuccess(response, "GET /api/v1/notifications/unread-count");
  const payload = (await response.json()).data;
  expect(payload).toEqual({ unreadCount: expect.any(Number) });
  expect(payload.unreadCount).toBeGreaterThanOrEqual(0);
});

test("requests the expiry category independently", async ({ page }) => {
  await openNotifications(page);
  const response = page.waitForResponse((item) => item.url().includes("category=EXPIRY"));
  await page.getByRole("tab", { name: /Expiring/ }).click();
  expect((await response).ok()).toBe(true);
});

test("requests unread notifications independently", async ({ page }) => {
  await openNotifications(page);
  const response = page.waitForResponse((item) => item.url().includes("unread=true"));
  await page.getByRole("tab", { name: /Unread/ }).click();
  const payload = (await response).ok();
  expect(payload).toBe(true);
});

test("refreshes the current notification page", async ({ page }) => {
  await openNotifications(page);
  const response = page.waitForResponse((item) => {
    const url = new URL(item.url());
    return url.pathname === "/api/v1/notifications" && url.searchParams.get("size") === "15";
  });
  await page.getByRole("main").getByRole("button", { name: "Refresh" }).click();
  expect((await response).ok()).toBe(true);
});

test("marks unread notifications read when present and remains rerunnable", async ({ page }) => {
  await openNotifications(page);
  const markAll = page.getByRole("button", { name: "Mark all read" });
  if (await markAll.isDisabled()) {
    await expect(page.getByText("Notifications (0 unread)")).toBeVisible();
    return;
  }
  const request = page.waitForResponse((response) => response.url().endsWith("/notifications/read-all") && response.request().method() === "PATCH");
  await markAll.click();
  expect((await request).ok()).toBe(true);
  await expect(page.getByText("Notifications (0 unread)")).toBeVisible();
});
