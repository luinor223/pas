import { expect, test } from "@playwright/test";
import { openAuthenticatedApp, openFeatureAndWaitForApi } from "./support/real-stack";

test.beforeEach(async ({ page }) => openAuthenticatedApp(page));

test("loads immutable audit snapshots from the real audit service", async ({ page }) => {
  const response = await openFeatureAndWaitForApi({ page, linkName: "Audit Log", apiPath: "/api/v1/audit-records" });
  const payload = await response.json();
  expect(payload.data).toEqual(expect.any(Array));
  expect(payload.meta).toEqual(expect.objectContaining({ page: 0, size: 15 }));
  await expect(page.getByLabel("Search audit records")).toBeVisible();
});

test("sends audit module, record-type, and activity filters to the server", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Audit Log", apiPath: "/api/v1/audit-records" });
  const moduleRequest = page.waitForRequest((request) => request.url().includes("sourceService=contract-service"));
  await page.getByLabel("Filter by module").selectOption("contract-service");
  expect(new URL((await moduleRequest).url()).searchParams.get("sourceService")).toBe("contract-service");

  const typeRequest = page.waitForRequest((request) => request.url().includes("entityType=CONTRACT"));
  await page.getByLabel("Filter by record type").selectOption("CONTRACT");
  expect(new URL((await typeRequest).url()).searchParams.get("entityType")).toBe("CONTRACT");

  const activityRequest = page.waitForRequest((request) => request.url().includes("action=CREATE"));
  await page.getByLabel("Filter by activity").selectOption("CREATE");
  expect(new URL((await activityRequest).url()).searchParams.get("action")).toBe("CREATE");
});

test("blocks an invalid audit date range before another API call", async ({ page }) => {
  let calls = 0;
  page.on("request", (request) => { if (new URL(request.url()).pathname === "/api/v1/audit-records") calls += 1; });
  await openFeatureAndWaitForApi({ page, linkName: "Audit Log", apiPath: "/api/v1/audit-records" });
  const validRangeResponse = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/v1/audit-records");
  await page.getByLabel("To", { exact: true }).fill("2026-09-01");
  await validRangeResponse;
  const beforeInvalidRange = calls;
  await page.getByLabel("From", { exact: true }).fill("2026-09-10");
  await expect(page.getByRole("alert")).toContainText("must be the same as or later");
  await page.waitForTimeout(400);
  expect(calls).toBe(beforeInvalidRange);
});

test("refreshes audit data explicitly", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Audit Log", apiPath: "/api/v1/audit-records" });
  const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/v1/audit-records");
  await page.getByRole("button", { name: "Refresh" }).click();
  expect((await refreshed).ok()).toBe(true);
});

test("opens human-readable audit details when records exist", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Audit Log", apiPath: "/api/v1/audit-records" });
  const details = page.getByRole("button", { name: /View details for/ });
  if (await details.count() === 0) {
    await expect(page.getByText(/No activity/)).toBeVisible();
    return;
  }
  await details.first().click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("Audit details");
  await expect(dialog.getByText("Date and time")).toBeVisible();
  await expect(dialog.getByText("Performed by")).toBeVisible();
  await expect(dialog.getByText("Affected record")).toBeVisible();
});
