import { expect, test } from "@playwright/test";
import { expectApiSuccess, openFeatureAndWaitForApi, signIn } from "./support/real-stack";

test.beforeEach(async ({ page }) => {
  await signIn(page);
});

test("Approvals loads through the real workflow service", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Approvals", apiPath: "/api/v1/inbox" });
  await expect(page).toHaveURL(/\/approvals/);
  await expect(page.getByRole("tab", { name: /Assigned to me/ })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByLabel("Search approvals")).toBeVisible();
});

test("Price Lists loads through the real pricing service", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Price Lists", apiPath: "/api/v1/price-lists" });
  await expect(page).toHaveURL(/\/price-lists/);
  await expect(page.getByText(/Price lists \(\d+\)/)).toBeVisible();
  await expect(page.getByLabel("Search price lists")).toBeVisible();
});

test("Volume Records loads periods, services, and records from the real stack", async ({ page }) => {
  const periodsResponse = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/v1/periods");
  const servicesResponse = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/v1/service-items");
  await openFeatureAndWaitForApi({ page, linkName: "Volume Records", apiPath: "/api/v1/volume-records" });
  await expectApiSuccess(await periodsResponse, "GET /api/v1/periods");
  await expectApiSuccess(await servicesResponse, "GET /api/v1/service-items");
  await expect(page).toHaveURL(/\/volume-records/);
  await expect(page.getByRole("tab", { name: /Volume records/i })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByLabel("Search volume records")).toBeVisible();
});

test("Audit Log loads through the real audit service", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Audit Log", apiPath: "/api/v1/audit-records" });
  await expect(page).toHaveURL(/\/audit-log/);
  await expect(page.getByLabel("Search audit records")).toBeVisible();
  await expect(page.getByRole("button", { name: "Refresh" })).toBeVisible();
});

test("Notifications loads through the real notification service", async ({ page }) => {
  const listResponse = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === "/api/v1/notifications" && url.searchParams.get("size") === "15";
  });
  await page.getByRole("navigation").getByRole("link", { name: "Notifications", exact: true }).click();
  await expectApiSuccess(await listResponse, "GET /api/v1/notifications");
  await expect(page).toHaveURL(/\/notifications/);
  await expect(page.getByText(/Notifications \(\d+ unread\)/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Refresh" })).toBeVisible();
});
