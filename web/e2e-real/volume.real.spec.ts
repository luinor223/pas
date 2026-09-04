import { expect, test } from "@playwright/test";
import { expectApiSuccess, openAuthenticatedApp, openFeatureAndWaitForApi } from "./support/real-stack";

test.beforeEach(async ({ page }) => openAuthenticatedApp(page));

test("loads volume records with real periods and service catalog data", async ({ page }) => {
  const periods = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/v1/periods");
  const services = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/v1/service-items");
  const records = await openFeatureAndWaitForApi({ page, linkName: "Volume Records", apiPath: "/api/v1/volume-records" });
  await expectApiSuccess(await periods, "GET /api/v1/periods");
  await expectApiSuccess(await services, "GET /api/v1/service-items");
  expect((await records.json()).data.items).toEqual(expect.any(Array));
  await expect(page.getByLabel("Search volume records")).toBeVisible();
});

test("keeps the periods tab in browser history", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Volume Records", apiPath: "/api/v1/volume-records" });
  await page.getByRole("tab", { name: /Periods/ }).click();
  await expect(page).toHaveURL(/tab=periods/);
  await expect(page.getByRole("tab", { name: /Periods/ })).toHaveAttribute("aria-selected", "true");
  await page.goBack();
  await expect(page.getByRole("tab", { name: /Volume records/i })).toHaveAttribute("aria-selected", "true");
});

test("sends service filters to operations and clears them", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Volume Records", apiPath: "/api/v1/volume-records" });
  const serviceSelect = page.getByLabel("Filter by service");
  const value = await serviceSelect.locator("option").nth(1).getAttribute("value");
  expect(value).toBeTruthy();
  const filteredRequest = page.waitForRequest((request) => request.url().includes(`serviceCode=${value}`));
  await serviceSelect.selectOption(value!);
  expect(new URL((await filteredRequest).url()).searchParams.get("serviceCode")).toBe(value);
  await page.getByRole("button", { name: "Clear filters" }).click();
  await expect(serviceSelect).toHaveValue("");
});

test("searches contracts through the real contract service", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Volume Records", apiPath: "/api/v1/volume-records" });
  const picker = page.getByRole("combobox", { name: "Contract" });
  const response = page.waitForResponse((item) => new URL(item.url()).pathname === "/api/v1/contracts");
  await picker.focus();
  expect((await response).ok()).toBe(true);
  await expect(page.getByRole("listbox")).toBeVisible();
  await expect(page.getByRole("listbox").getByRole("option").first()).toBeVisible();
  await picker.press("Escape");
  await expect(page.getByRole("listbox")).toHaveCount(0);
});

test("requires all inputs before creating a volume record", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Volume Records", apiPath: "/api/v1/volume-records" });
  await page.getByRole("button", { name: "New volume record" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("button", { name: "Create record" })).toBeDisabled();
  await expect(dialog.getByLabel("Active contract *")).toBeVisible();
  await expect(dialog.getByLabel("Service *")).toBeVisible();
  await expect(dialog.getByLabel(/Quantity/)).toBeVisible();
});
