import { expect, test } from "@playwright/test";
import { currentUser, envelope, installApiMocks } from "./support/api";

const CUSTOMER_ID = "40000000-0000-4000-8000-000000000001";

const customer = {
  id: CUSTOMER_ID,
  code: "CUS-0042",
  name: "Metrics Logistics",
  shortName: "Metrics",
  taxCode: "0101234567",
  address: "1 Harbour Road",
  representativeName: "Tran Van A",
  representativePosition: "Director",
  segment: "ENTERPRISE",
  status: "ACTIVE",
  contacts: [],
  primaryContact: null,
  contractsCount: 16,
  createdAt: "2025-01-01T00:00:00Z",
  createdByName: "Sales User",
  updatedAt: "2025-01-01T00:00:00Z",
};

function contract(index: number) {
  return {
    id: `50000000-0000-4000-8000-${String(index).padStart(12, "0")}`,
    contractNo: `CTR-2026-${String(index).padStart(4, "0")}`,
    customerId: CUSTOMER_ID,
    customerName: customer.name,
    description: `Contract ${index}`,
    serviceGroup: "TRANSPORTATION",
    value: 1_000_000,
    currency: "VND",
    validFrom: "2026-01-01",
    validTo: "2026-12-31",
    paymentTerm: "NET30",
    billingCycle: "MONTHLY",
    vatRate: 10,
    penaltyTerms: null,
    serviceClause: null,
    status: "ACTIVE",
    editable: false,
    version: 0,
    createdAt: "2026-01-01T00:00:00Z",
    createdByName: "Sales User",
    updatedAt: "2026-01-01T00:00:00Z",
  };
}

test("uses complete currency metrics and paginates the contracts tab independently", async ({ page }) => {
  const contractPages: number[] = [];
  const contractRequests: Array<Record<string, string | null>> = [];
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/customers/${CUSTOMER_ID}`) return { body: envelope(customer) };
    if (url.pathname === `/api/v1/customers/${CUSTOMER_ID}/metrics`) {
      return {
        body: envelope({
          activeContracts: 16,
          approvedContractValues: [
            { currency: "USD", value: "125.50" },
            { currency: "VND", value: "9007199254740994.33" },
          ],
        }),
      };
    }
    if (url.pathname === "/api/v1/contracts") {
      const size = Number(url.searchParams.get("size"));
      const pageNumber = Number(url.searchParams.get("page") ?? "0");
      contractRequests.push({
        customerId: url.searchParams.get("customerId"),
        page: url.searchParams.get("page"),
        size: url.searchParams.get("size"),
        sort: url.searchParams.get("sort"),
      });
      if (size === 5) {
        return { body: envelope([1, 2, 3, 4, 5].map(contract), { page: 0, size: 5, totalElements: 16, totalPages: 4 }) };
      }
      contractPages.push(pageNumber);
      const rows = pageNumber === 0
        ? Array.from({ length: 15 }, (_, index) => contract(index + 1))
        : [contract(16)];
      return { body: envelope(rows, { page: pageNumber, size: 15, totalElements: 16, totalPages: 2 }) };
    }
  });

  await page.goto(`/customers?id=${CUSTOMER_ID}`);

  await expect(page.getByText("Active contracts").locator("..").getByText("16", { exact: true })).toBeVisible();
  await expect(page.getByText("125,5 USD", { exact: true })).toBeVisible();
  await expect(page.getByText("9.007.199.254.740.994,33 VND", { exact: true })).toBeVisible();
  await expect(page.getByText("CTR-2026-0001")).toBeVisible();
  expect(contractPages).toEqual([]);
  expect(contractRequests).toEqual([{
    customerId: CUSTOMER_ID,
    page: "0",
    size: "5",
    sort: "createdAt,desc",
  }]);

  await page.getByRole("tab", { name: "Contracts" }).click();
  await expect(page.getByText("Page 1 of 2")).toBeVisible();
  await page.getByRole("button", { name: "Next page" }).click();
  await expect(page.getByText("CTR-2026-0016")).toBeVisible();
  await expect(page.getByText("Page 2 of 2")).toBeVisible();
  expect(contractPages).toEqual([0, 1]);
  expect(contractRequests.slice(1)).toEqual([
    { customerId: CUSTOMER_ID, page: "0", size: "15", sort: "createdAt,desc" },
    { customerId: CUSTOMER_ID, page: "1", size: "15", sort: "createdAt,desc" },
  ]);
});

test("shows an explicit empty metric without inventing a currency", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/customers/${CUSTOMER_ID}`) {
      return { body: envelope({ ...customer, contractsCount: 0 }) };
    }
    if (url.pathname === `/api/v1/customers/${CUSTOMER_ID}/metrics`) {
      return { body: envelope({ activeContracts: 0, approvedContractValues: [] }) };
    }
    if (url.pathname === "/api/v1/contracts") {
      return { body: envelope([], { page: 0, size: 5, totalElements: 0, totalPages: 0 }) };
    }
  });

  await page.goto(`/customers?id=${CUSTOMER_ID}`);

  await expect(page.getByText("No approved contract value")).toBeVisible();
  await expect(page.getByText(/\bVND\b|\bUSD\b/)).toHaveCount(0);
});

test("distinguishes metric and contract request failures from empty data", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/customers/${CUSTOMER_ID}`) {
      return { body: envelope(customer) };
    }
    if (url.pathname === `/api/v1/customers/${CUSTOMER_ID}/metrics`) {
      return { status: 503, body: envelope({ message: "Unavailable" }) };
    }
    if (url.pathname === "/api/v1/contracts") {
      return { status: 503, body: envelope({ message: "Unavailable" }) };
    }
  });

  await page.goto(`/customers?id=${CUSTOMER_ID}`);

  await expect(page.getByText("Unavailable", { exact: true })).toBeVisible();
  await expect(page.getByText("Failed to load contract metrics")).toBeVisible();
  await expect(page.getByText("Failed to load recent contracts")).toBeVisible();
  await expect(page.getByText("No approved contract value")).toHaveCount(0);

  await page.getByRole("tab", { name: "Contracts" }).click();
  await expect(page.getByText("Failed to load contracts", { exact: true })).toBeVisible();
  await expect(page.getByText("No contracts", { exact: true })).toHaveCount(0);
});

test("does not expose or request contract data with customer-only read access", async ({ page }) => {
  let contractDataRequests = 0;
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/customers/${CUSTOMER_ID}`) {
      return { body: envelope({ ...customer, contractsCount: null }) };
    }
    if (url.pathname === `/api/v1/customers/${CUSTOMER_ID}/metrics`
      || url.pathname === "/api/v1/contracts") contractDataRequests += 1;
  }, { permissions: currentUser.permissions.filter((permission) => permission !== "contract:read") });

  await page.goto(`/customers?id=${CUSTOMER_ID}`);

  await expect(page.getByRole("heading", { name: customer.name })).toBeVisible();
  await expect(page.getByRole("tab", { name: "Contracts" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "View contracts" })).toHaveCount(0);
  await expect(page.getByText("Recent contracts")).toHaveCount(0);
  await expect(page.getByText("Approved contract value")).toHaveCount(0);
  expect(contractDataRequests).toBe(0);
});

test("does not expose contract count or navigation in the customer list without contract read", async ({ page }) => {
  let contractDataRequests = 0;
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/customers") {
      return { body: envelope([{ ...customer, contractsCount: null }], { page: 0, size: 15, totalElements: 1, totalPages: 1 }) };
    }
    if (url.pathname === "/api/v1/contracts" || url.pathname.endsWith("/metrics")) {
      contractDataRequests += 1;
    }
  }, { permissions: currentUser.permissions.filter((permission) => permission !== "contract:read") });

  await page.goto("/customers");

  await expect(page.getByRole("link", { name: customer.code })).toBeVisible();
  await expect(page.getByRole("columnheader", { name: "CONTRACTS" })).toHaveCount(0);
  await page.getByRole("button", { name: "Row actions" }).click();
  await expect(page.getByRole("menuitem", { name: "View details" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "View contracts" })).toHaveCount(0);
  expect(contractDataRequests).toBe(0);
});
