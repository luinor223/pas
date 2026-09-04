import { expect, test } from "@playwright/test";
import { openAuthenticatedApp, openFeatureAndWaitForApi } from "./support/real-stack";

test.beforeEach(async ({ page }) => openAuthenticatedApp(page));

test("loads price-list paging data from the real pricing service", async ({ page }) => {
  const response = await openFeatureAndWaitForApi({ page, linkName: "Price Lists", apiPath: "/api/v1/price-lists" });
  const payload = await response.json();
  expect(payload.data.items).toEqual(expect.any(Array));
  expect(payload.data).toEqual(expect.objectContaining({ page: 0, size: 15 }));
  await expect(page.getByText(/Price lists \(\d+\)/)).toBeVisible();
});

test("sends price-list search and service-group filters to pricing", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Price Lists", apiPath: "/api/v1/price-lists" });
  const searchRequest = page.waitForRequest((request) => request.url().includes("/api/v1/price-lists") && request.url().includes("q=PRC"));
  await page.getByLabel("Search price lists").fill("PRC");
  expect(new URL((await searchRequest).url()).searchParams.get("q")).toBe("PRC");

  const groupRequest = page.waitForRequest((request) => request.url().includes("serviceGroup=STEVEDORING"));
  await page.getByLabel("Filter by service group").selectOption("STEVEDORING");
  expect(new URL((await groupRequest).url()).searchParams.get("serviceGroup")).toBe("STEVEDORING");
});

test("PRC-01 requires one clear scope before creating a price list", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Price Lists", apiPath: "/api/v1/price-lists" });
  await page.getByRole("button", { name: "New price list" }).click();
  const dialog = page.getByRole("dialog");
  const create = dialog.getByRole("button", { name: "Create price list" });
  await expect(create).toBeDisabled();
  await dialog.getByLabel("Applies to").selectOption("SERVICE_GROUP");
  await expect(create).toBeDisabled();
  await dialog.getByLabel("Service group *").selectOption("WAREHOUSING");
  await expect(create).toBeEnabled();
});

test("queries eligible contracts and supports keyboard selection", async ({ page }) => {
  await openFeatureAndWaitForApi({ page, linkName: "Price Lists", apiPath: "/api/v1/price-lists" });
  await page.getByRole("button", { name: "New price list" }).click();
  const picker = page.getByRole("combobox", { name: "Approved or active contract *" });
  const contractsResponse = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/v1/contracts");
  await picker.focus();
  expect((await contractsResponse).ok()).toBe(true);
  const options = page.getByRole("listbox").getByRole("option");
  await expect(options.first()).toBeVisible();
  await picker.press("ArrowDown");
  await picker.press("Enter");
  await expect(picker).not.toHaveValue("");
});

test("closing create leaves pricing data unchanged", async ({ page }) => {
  const initial = await openFeatureAndWaitForApi({ page, linkName: "Price Lists", apiPath: "/api/v1/price-lists" });
  const initialTotal = (await initial.json()).data.totalItems;
  await page.getByRole("button", { name: "New price list" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Cancel" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByText(`Price lists (${initialTotal})`)).toBeVisible();
});
