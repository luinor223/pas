import { expect, test } from "@playwright/test";
import { currentUser, envelope, installApiMocks } from "./support/api";

const meta = (page: number) => ({ page, size: 15, totalElements: 16, totalPages: 2, cursor: "snapshot-token" });

test("customer list requests server pages without page-local sorting", async ({ page }) => {
  const requests: Array<[string, string | null, string | null]> = [];
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/customers") {
      const p = url.searchParams.get("page") ?? "0";
      requests.push([p, url.searchParams.get("sort"), url.searchParams.get("cursor")]);
      const n = p === "1" ? 16 : 1;
      return { body: envelope([{ id: `40000000-0000-4000-8000-${String(n).padStart(12, "0")}`, code: `CUS-${n}`, name: `Customer ${n}`, status: "ACTIVE", contacts: [], primaryContact: null, contractsCount: 0, createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z" }], meta(Number(p)))};
    }
  });
  await page.goto("/customers");
  await expect(page.locator("thead button")).toHaveCount(0);
  await page.getByRole("button", { name: "Next page" }).click();
  await expect(page.getByText("CUS-16")).toBeVisible();
  await page.getByRole("button", { name: "Previous page" }).click();
  await expect(page.getByText("CUS-1")).toBeVisible();
  await page.getByRole("button", { name: "Next page" }).click();
  await expect(page.getByText("CUS-16")).toBeVisible();
  expect(requests).toEqual([
    ["0", null, null], ["1", null, "snapshot-token"],
    ["0", null, "snapshot-token"],
  ]);
});

test("contract list requests server pages without a client sort", async ({ page }) => {
  const requests: Array<[string, string | null, string | null]> = [];
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/customers") return { body: envelope([], { page: 0, size: 10, totalElements: 0, totalPages: 0 }) };
    if (url.pathname === "/api/v1/contracts") {
      const p = url.searchParams.get("page") ?? "0";
      requests.push([p, url.searchParams.get("sort"), url.searchParams.get("cursor")]);
      const n = p === "1" ? 16 : 1;
      return { body: envelope([{ id: `50000000-0000-4000-8000-${String(n).padStart(12, "0")}`, contractNo: `CTR-${n}`, customerId: "40000000-0000-4000-8000-000000000001", customerName: "Customer", serviceGroup: "TRANSPORTATION", value: 1, currency: "VND", validFrom: "2026-01-01", validTo: "2026-12-31", status: "DRAFT", version: 0 }], meta(Number(p))) };
    }
  });
  await page.goto("/contracts");
  await expect(page.locator("thead button")).toHaveCount(0);
  await page.getByRole("button", { name: "Next page" }).click();
  await expect(page.getByText("CTR-16")).toBeVisible();
  await page.getByRole("button", { name: "Previous page" }).click();
  await expect(page.getByText("CTR-1")).toBeVisible();
  await page.getByRole("button", { name: "Next page" }).click();
  await expect(page.getByText("CTR-16")).toBeVisible();
  expect(requests).toEqual([
    ["0", null, null], ["1", null, "snapshot-token"],
    ["0", null, "snapshot-token"],
  ]);
});

test("addendum list requests server pages without a client sort", async ({ page }) => {
  const requests: Array<[string, string | null, string | null]> = [];
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/contracts") return { body: envelope([], { page: 0, size: 100, totalElements: 0, totalPages: 0 }) };
    if (url.pathname === "/api/v1/addenda") {
      const p = url.searchParams.get("page") ?? "0";
      requests.push([p, url.searchParams.get("sort"), url.searchParams.get("cursor")]);
      const n = p === "1" ? 16 : 1;
      return { body: envelope([{ id: `60000000-0000-4000-8000-${String(n).padStart(12, "0")}`, addendumNo: `ADD-${n}`, contractId: "50000000-0000-4000-8000-000000000001", contractNo: "CTR-1", changeType: "TERM_EXTENSION", effectiveFrom: "2026-01-01", status: "DRAFT", services: [], version: 0 }], meta(Number(p))) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read"] });
  await page.goto("/addenda");
  await expect(page.locator("thead button")).toHaveCount(0);
  await page.getByRole("button", { name: "Next page" }).click();
  await expect(page.getByText("ADD-16")).toBeVisible();
  await page.getByRole("button", { name: "Previous page" }).click();
  await expect(page.getByText("ADD-1")).toBeVisible();
  await page.getByRole("button", { name: "Next page" }).click();
  await expect(page.getByText("ADD-16")).toBeVisible();
  expect(requests).toEqual([
    ["0", null, null], ["1", null, "snapshot-token"],
    ["0", null, "snapshot-token"],
  ]);
});

test("contract list can recover when its snapshot cursor expires while navigating back", async ({ page }) => {
  const requests: Array<[string, string | null]> = [];
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/customers") return { body: envelope([], { page: 0, size: 10, totalElements: 0, totalPages: 0 }) };
    if (url.pathname === "/api/v1/contracts") {
      const p = url.searchParams.get("page") ?? "0";
      const cursor = url.searchParams.get("cursor");
      requests.push([p, cursor]);
      if (p === "0" && cursor) return { status: 422, body: { message: "The page cursor is invalid or has expired; return to the first page." } };
      const n = p === "1" ? 16 : 1;
      return { body: envelope([{ id: `50000000-0000-4000-8000-${String(n).padStart(12, "0")}`, contractNo: `CTR-${n}`, customerId: "40000000-0000-4000-8000-000000000001", customerName: "Customer", serviceGroup: "TRANSPORTATION", value: 1, currency: "VND", validFrom: "2026-01-01", validTo: "2026-12-31", status: "DRAFT", version: 0 }], meta(Number(p))) };
    }
  });

  await page.goto("/contracts");
  await page.getByRole("button", { name: "Next page" }).click();
  await expect(page.getByText("CTR-16")).toBeVisible();
  await page.getByRole("button", { name: "Previous page" }).click();
  await expect(page.getByText(/cursor is invalid or has expired/)).toBeVisible();
  await page.getByRole("button", { name: "Return to first page" }).click();
  await expect(page.getByText("CTR-1")).toBeVisible();
  await expect.poll(() => requests.at(-1)).toEqual(["0", null]);
  expect(requests[0]).toEqual(["0", null]);
  expect(requests.slice(1, -1)).not.toHaveLength(0);
  expect(requests.slice(1, -1).every(([p, cursor]) =>
    (p === "1" || p === "0") && cursor === "snapshot-token")).toBe(true);
});
