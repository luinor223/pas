import { expect, test } from "@playwright/test";
import { currentUser, envelope, installApiMocks } from "./support/api";

const meta = (page: number) => ({ page, size: 15, totalElements: 16, totalPages: 2, cursor: "snapshot-token" });

const contractRow = (id: string, status: string, canCreateAddendum: boolean, capabilities: Partial<{
  canEdit: boolean; canSubmit: boolean; submitBlockedReason: string | null; canRevise: boolean; canCancel: boolean;
}> = {}) => ({
  id, contractNo: `CTR-${id.slice(-1)}`, customerId: "40000000-0000-4000-8000-000000000001",
  customerName: "Customer", description: null, serviceGroup: "TRANSPORTATION", value: 1, currency: "VND",
  validFrom: "2026-01-01", validTo: "2026-12-31", paymentTerm: "NET30", billingCycle: "MONTHLY",
  vatRate: 10, penaltyTerms: null, serviceClause: null, status, editable: status === "DRAFT",
  canEdit: status === "DRAFT" || status === "REVISION_REQUESTED",
  canSubmit: status === "DRAFT",
  submitBlockedReason: null,
  canRevise: status === "REJECTED",
  canCancel: ["DRAFT", "SUBMITTED", "UNDER_REVIEW", "ACTIVE"].includes(status),
  ...capabilities,
  canCreateAddendum, version: 0, createdAt: "2026-01-01T00:00:00Z", createdByName: null,
  updatedAt: "2026-01-01T00:00:00Z",
});

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

test("contract actions follow backend capabilities and do not send unavailable actions", async ({ page }) => {
  let addendumRequests = 0;
  let lifecycleRequests = 0;
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/contracts") {
      return { body: envelope([
        contractRow("50000000-0000-4000-8000-000000000001", "DRAFT", false, {
          canEdit: false, canSubmit: false, canRevise: false, canCancel: false,
        }),
        contractRow("50000000-0000-4000-8000-000000000002", "APPROVED", true),
      ], { page: 0, size: 15, totalElements: 2, totalPages: 1, cursor: "actions-token" }) };
    }
    if (url.pathname === "/api/v1/addenda") addendumRequests += 1;
    if (/^\/api\/v1\/contracts\/[^/]+\/(submit|revise|cancel)$/.test(url.pathname)) lifecycleRequests += 1;
  }, { permissions: [...currentUser.permissions, "contract:write", "addendum:read", "addendum:write"] });

  await page.goto("/contracts");
  const actionButtons = page.getByRole("button", { name: "Row actions" });
  await actionButtons.nth(0).click();
  await expect(page.getByRole("menuitem", { name: "Create addendum" })).toHaveCount(0);
  await expect(page.getByRole("menuitem", { name: "Renew contract" })).toHaveCount(0);
  await expect(page.getByRole("menuitem", { name: "Edit" })).toHaveCount(0);
  await expect(page.getByRole("menuitem", { name: "Submit for approval" })).toHaveCount(0);
  await expect(page.getByRole("menuitem", { name: "Revise" })).toHaveCount(0);
  await expect(page.getByRole("menuitem", { name: "Cancel contract" })).toHaveCount(0);
  await page.keyboard.press("Escape");

  await actionButtons.nth(1).click();
  await expect(page.getByRole("menuitem", { name: "Create addendum" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Renew contract" })).toBeVisible();
  expect(addendumRequests).toBe(0);
  expect(lifecycleRequests).toBe(0);
});

test("contract forms request only active customers", async ({ page }) => {
  const customerStatuses: Array<string | null> = [];
  const draft = contractRow("50000000-0000-4000-8000-000000000001", "DRAFT", false);
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/contracts") {
      return { body: envelope([draft], { page: 0, size: 15, totalElements: 1, totalPages: 1, cursor: "contract-token" }) };
    }
    if (url.pathname === "/api/v1/customers") {
      customerStatuses.push(url.searchParams.get("status"));
      return { body: envelope([], { page: 0, size: 10, totalElements: 0, totalPages: 0, cursor: "customer-token" }) };
    }
    if (url.pathname === `/api/v1/customers/${draft.customerId}`) {
      return { body: envelope({ id: draft.customerId, code: "CUS-1", name: "Customer", status: "ACTIVE" }) };
    }
  }, { permissions: [...currentUser.permissions, "contract:write"] });

  await page.goto("/contracts");
  await page.getByPlaceholder("All customers").click();
  await expect.poll(() => customerStatuses).toContain(null);
  await page.keyboard.press("Escape");

  await page.getByRole("button", { name: "+ New Contract" }).click();
  const createDialog = page.getByRole("dialog", { name: "Create contract" });
  await createDialog.getByLabel("Customer *").click();
  await expect.poll(() => customerStatuses.filter((status) => status === "ACTIVE").length).toBeGreaterThanOrEqual(1);
  await createDialog.getByRole("button", { name: "Cancel" }).click();

  await page.getByRole("button", { name: "Row actions" }).click();
  await page.getByRole("menuitem", { name: "Edit" }).click();
  const editCustomer = page.getByRole("dialog", { name: "Edit contract" }).getByLabel("Customer *");
  await editCustomer.click();
  await editCustomer.fill("replacement");
  await expect.poll(() => customerStatuses.filter((status) => status === "ACTIVE").length).toBeGreaterThanOrEqual(2);
  expect(customerStatuses.every((status) => status === null || status === "ACTIVE")).toBe(true);
});
