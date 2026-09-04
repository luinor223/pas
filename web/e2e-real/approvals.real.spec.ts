import { expect, test } from "@playwright/test";
import { openAuthenticatedApp, openFeatureAndWaitForApi } from "./support/real-stack";

test.beforeEach(async ({ page }) => openAuthenticatedApp(page));

test("loads the assigned inbox from the real workflow service", async ({ page }) => {
  const response = await openFeatureAndWaitForApi({ page, linkName: "Approvals", apiPath: "/api/v1/inbox" });
  const payload = await response.json();
  expect(payload.data.items).toEqual(expect.any(Array));
  expect(payload.data).toEqual(expect.objectContaining({ page: 0, size: 15 }));
  await expect(page.getByRole("tab", { name: /Assigned to me/ })).toHaveAttribute("aria-selected", "true");
});

test("requests each approval inbox independently and preserves tab history", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Approvals", apiPath: "/api/v1/inbox" });
  const submitted = page.waitForResponse((response) => response.url().includes("/api/v1/inbox") && response.url().includes("tab=SUBMITTED"));
  await page.getByRole("tab", { name: "Submitted by me" }).click();
  expect((await submitted).ok()).toBe(true);
  await expect(page).toHaveURL(/tab=SUBMITTED/);

  const completed = page.waitForResponse((response) => response.url().includes("/api/v1/inbox") && response.url().includes("tab=COMPLETED"));
  await page.getByRole("tab", { name: "Completed" }).click();
  expect((await completed).ok()).toBe(true);
  await page.goBack();
  await expect(page.getByRole("tab", { name: "Submitted by me" })).toHaveAttribute("aria-selected", "true");
});

test("sends document type and priority filters to workflow", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Approvals", apiPath: "/api/v1/inbox" });
  const documentRequest = page.waitForRequest((request) => request.url().includes("documentType=CONTRACT"));
  await page.getByLabel("Filter by document type").selectOption("CONTRACT");
  expect(new URL((await documentRequest).url()).searchParams.get("documentType")).toBe("CONTRACT");

  const priorityRequest = page.waitForRequest((request) => request.url().includes("priority=HIGH"));
  await page.getByLabel("Filter by priority").selectOption("HIGH");
  const params = new URL((await priorityRequest).url()).searchParams;
  expect(params.get("documentType")).toBe("CONTRACT");
  expect(params.get("priority")).toBe("HIGH");
});

test("debounces approval search and keeps it in the URL", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Approvals", apiPath: "/api/v1/inbox" });
  const searchResponse = page.waitForResponse((response) => response.url().includes("/api/v1/inbox") && response.url().includes("q=CTR"));
  await page.getByLabel("Search approvals").fill("CTR");
  expect((await searchResponse).ok()).toBe(true);
  await expect(page).toHaveURL(/q=CTR/);
  await expect(page.getByRole("button", { name: "Clear filters" })).toBeVisible();
});
