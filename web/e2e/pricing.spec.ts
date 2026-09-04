import { expect, test } from "@playwright/test";
import { envelope, installApiMocks } from "./support/api";

test("opens a price-list version, shows saved lines, and confirms submission", async ({ page }) => {
  await installApiMocks(page, (request, url) => {
    if (url.pathname === "/api/v1/price-lists" && request.method() === "GET") {
      return { body: envelope({ items: [{ id: "price-1", priceListNo: "PRC-0001", customerId: null, contractId: "contract-1", serviceGroup: null, scopeKey: "CONTRACT:contract-1", note: "Port services" }], page: 0, size: 15, totalItems: 1, totalPages: 1 }) };
    }
    if (url.pathname === "/api/v1/contracts/lookup") {
      return { body: envelope([{ id: "contract-1", contractNo: "CTR-2026-0001", customerName: "Saigon Port Services" }]) };
    }
    if (url.pathname === "/api/v1/price-lists/price-1/versions" && request.method() === "GET") {
      return { body: envelope([{ id: "version-1", priceListId: "price-1", versionNo: 1, status: "DRAFT", validFrom: "2026-01-01", validTo: "2026-12-31", addendumId: null }]) };
    }
    if (url.pathname === "/api/v1/price-lists/price-1/versions/version-1") {
      return { body: envelope({ version: { id: "version-1", priceListId: "price-1", versionNo: 1, status: "DRAFT", validFrom: "2026-01-01", validTo: "2026-12-31", addendumId: null }, lines: [{ serviceCode: "LEGACY", serviceName: "Legacy Storage", unit: "TON", unitPrice: 125000 }] }) };
    }
    if (url.pathname === "/api/v1/service-items") {
      return { body: envelope([{ code: "STEV", name: "Stevedoring", unit: "TON", active: true }]) };
    }
    if (url.pathname === "/api/v1/price-lists/price-1/versions/version-1/submit" && request.method() === "POST") {
      return { body: envelope({ id: "version-1", status: "SUBMITTED" }) };
    }
  });

  await page.goto("/price-lists");
  await expect(page.getByText("Contract CTR-2026-0001")).toBeVisible();
  await page.getByRole("button", { name: "PRC-0001" }).click();
  await expect(page.getByText("Legacy Storage")).toBeVisible();
  await expect(page.getByText("Inactive", { exact: true })).toBeVisible();
  await expect(page.getByText(/Clear this price to remove/)).toBeVisible();

  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(page.getByRole("dialog")).toContainText("Submit PRC-0001 version 1?");
  const submitRequest = page.waitForRequest((request) => request.url().endsWith("/version-1/submit") && request.method() === "POST");
  await page.getByRole("dialog").getByRole("button", { name: "Submit for approval" }).click();
  await submitRequest;
});

test("creates a service-group price list with an unambiguous scope", async ({ page }) => {
  await installApiMocks(page, async (request, url) => {
    if (url.pathname === "/api/v1/price-lists" && request.method() === "GET") {
      return { body: envelope({ items: [], page: 0, size: 15, totalItems: 0, totalPages: 0 }) };
    }
    if (url.pathname === "/api/v1/price-lists" && request.method() === "POST") {
      return { body: envelope({ id: "price-2", priceListNo: "PRC-0002", ...(await request.postDataJSON()), scopeKey: "SERVICE_GROUP:WAREHOUSING" }) };
    }
    if (url.pathname === "/api/v1/price-lists/price-2/versions") return { body: envelope([]) };
  });

  await page.goto("/price-lists");
  await page.getByRole("button", { name: "New price list" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.locator("select").first().selectOption("SERVICE_GROUP");
  await dialog.locator("select").nth(1).selectOption("WAREHOUSING");
  await dialog.getByPlaceholder("Describe when or why this price list is used...").fill("Standard warehouse rates");
  const createRequest = page.waitForRequest((request) => request.url().endsWith("/price-lists") && request.method() === "POST");
  await dialog.getByRole("button", { name: "Create price list" }).click();
  expect((await createRequest).postDataJSON()).toEqual({ customerId: null, contractId: null, serviceGroup: "WAREHOUSING", note: "Standard warehouse rates" });
});

test("requests the next server page without offering page-local sorting", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/price-lists") {
      const pageNumber = Number(url.searchParams.get("page") ?? 0);
      return { body: envelope({
        items: [{ id: `price-${pageNumber}`, priceListNo: `PRC-00${pageNumber + 1}`, customerId: null, contractId: null, serviceGroup: "WAREHOUSING", scopeKey: "SERVICE_GROUP:WAREHOUSING", note: null }],
        page: pageNumber, size: 15, totalItems: 16, totalPages: 2,
      }) };
    }
  });

  await page.goto("/price-lists");
  await expect(page.getByRole("columnheader", { name: "PRICE LIST" }).getByRole("button")).toHaveCount(0);
  const nextRequest = page.waitForRequest((request) => request.url().includes("/price-lists") && request.url().includes("page=1"));
  await page.getByRole("button", { name: "Next page" }).click();
  await nextRequest;
  await expect(page.getByText("PRC-002")).toBeVisible();
});
