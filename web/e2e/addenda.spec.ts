import { expect, test } from "@playwright/test";
import { currentUser, envelope, installApiMocks } from "./support/api";

const addendum = {
  id: "addendum-1",
  addendumNo: "ADD-2026-0001",
  contractId: "contract-1",
  contractNo: "CTR-2026-0001",
  changeType: "TERM_EXTENSION",
  description: "Extend the service term",
  effectiveFrom: "2026-10-01",
  newValidTo: "2027-09-30",
  paymentTermOverride: null,
  status: "DRAFT",
  services: [],
  version: 0,
};

const pageMeta = { page: 0, size: 15, totalElements: 1, totalPages: 1 };

test("opens an addendum detail link and restores it after refresh and browser Back", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/addenda") return { body: envelope([addendum], pageMeta) };
    if (url.pathname === "/api/v1/addenda/addendum-1") return { body: envelope(addendum) };
  }, { permissions: [...currentUser.permissions, "addendum:read"] });

  await page.goto("/addenda");
  await page.getByRole("link", { name: addendum.addendumNo }).click();
  await expect(page).toHaveURL(/\/addenda\?id=addendum-1/);
  await expect(page.getByRole("heading", { name: addendum.addendumNo })).toBeVisible();
  await expect(page.getByText("Extend the service term")).toBeVisible();

  await page.reload();
  await expect(page.getByRole("heading", { name: addendum.addendumNo })).toBeVisible();

  await page.goBack();
  await expect(page).not.toHaveURL(/id=/);
  await expect(page.getByRole("link", { name: addendum.addendumNo })).toBeVisible();
});

test("preserves list filters when opening a row and returning with the detail Back control", async ({ page }) => {
  const requestedChangeTypes: Array<string | null> = [];
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/addenda/addendum-1") return { body: envelope(addendum) };
    if (url.pathname === "/api/v1/addenda") {
      requestedChangeTypes.push(url.searchParams.get("changeType"));
      return { body: envelope([addendum], pageMeta) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read"] });

  await page.goto("/addenda?changeType=TERM_EXTENSION");
  await page.getByRole("link", { name: addendum.addendumNo }).click();
  await expect.poll(() => new URL(page.url()).searchParams.get("id")).toBe("addendum-1");
  await expect.poll(() => new URL(page.url()).searchParams.get("changeType")).toBe("TERM_EXTENSION");

  await page.getByRole("link", { name: "Back to addenda" }).click();
  await expect.poll(() => new URL(page.url()).searchParams.get("id")).toBeNull();
  await expect.poll(() => new URL(page.url()).searchParams.get("changeType")).toBe("TERM_EXTENSION");
  await expect(page.getByLabel("Filter by change type")).toHaveValue("TERM_EXTENSION");
  await expect.poll(() => requestedChangeTypes.at(-1)).toBe("TERM_EXTENSION");
  await expect(page.getByRole("heading", { name: /Addenda/ })).toBeVisible();
});

test("shows read-only detail access without write permission", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/addenda/addendum-1") return { body: envelope(addendum) };
  }, { permissions: [...currentUser.permissions, "addendum:read"] });

  await page.goto("/addenda?id=addendum-1");
  await expect(page.getByText("Read-only access")).toBeVisible();
  await expect(page.getByRole("heading", { name: addendum.addendumNo })).toBeVisible();
});

test("does not request detail data without addendum read permission", async ({ page }) => {
  let detailRequests = 0;
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/addenda/addendum-1") detailRequests += 1;
  });

  await page.goto("/addenda?id=addendum-1");
  await expect(page.getByText("You do not have access to addenda.")).toBeVisible();
  expect(detailRequests).toBe(0);
});

test("shows a not-found state for a missing addendum", async ({ page }) => {
  const missingId = "00000000-0000-4000-8000-000000000099";
  const requestedPaths: string[] = [];
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/addenda/${missingId}`) {
      requestedPaths.push(url.pathname);
      return { status: 404, body: envelope({ message: "Not found" }) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read"] });

  await page.goto(`/addenda?id=${missingId}`);
  await expect(page.getByText("Addendum not found.")).toBeVisible();
  expect(requestedPaths).toEqual([`/api/v1/addenda/${missingId}`]);
});

test("preserves deep-link context when navigating to a newly created addendum and back", async ({ page }) => {
  let submittedContractId: string | undefined;
  const detailRequestPaths: string[] = [];
  await installApiMocks(page, async (request, url) => {
    if (url.pathname === "/api/v1/addenda" && request.method() === "GET") {
      return { body: envelope([], { page: 0, size: 15, totalElements: 0, totalPages: 0 }) };
    }
    if (url.pathname === "/api/v1/contracts") {
      return { body: envelope([{ id: "contract-1", contractNo: "CTR-2026-0001", status: "APPROVED" }], { page: 0, size: 100, totalElements: 1, totalPages: 1 }) };
    }
    if (url.pathname === "/api/v1/addenda" && request.method() === "POST") {
      submittedContractId = (await request.postDataJSON()).contractId;
      return { status: 201, body: envelope(addendum) };
    }
    if (url.pathname === "/api/v1/addenda/addendum-1" && request.method() === "GET") {
      detailRequestPaths.push(url.pathname);
      return { body: envelope(addendum) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto("/addenda?contractId=contract-1&changeType=TERM_EXTENSION");
  await page.getByRole("button", { name: "+ New Addendum" }).click();
  const dialog = page.getByRole("dialog", { name: "Create addendum" });
  await expect(dialog.locator("select").first()).toHaveValue("contract-1");
  await expect(dialog.locator("select").nth(1)).toHaveValue("TERM_EXTENSION");
  await dialog.locator('input[type="date"]').nth(1).fill("2027-09-30");
  await dialog.getByRole("button", { name: "Create", exact: true }).click();

  await expect.poll(() => new URL(page.url()).searchParams.get("id")).toBe("addendum-1");
  await expect.poll(() => new URL(page.url()).searchParams.get("contractId")).toBe("contract-1");
  await expect.poll(() => new URL(page.url()).searchParams.get("changeType")).toBe("TERM_EXTENSION");
  await expect(page.getByRole("heading", { name: addendum.addendumNo })).toBeVisible();
  expect(submittedContractId).toBe("contract-1");

  await page.reload();
  await expect(page.getByRole("heading", { name: addendum.addendumNo })).toBeVisible();
  expect(detailRequestPaths).toEqual(["/api/v1/addenda/addendum-1"]);

  await page.getByRole("link", { name: "Back to addenda" }).click();
  await expect.poll(() => new URL(page.url()).searchParams.get("id")).toBeNull();
  await expect.poll(() => new URL(page.url()).searchParams.get("contractId")).toBe("contract-1");
  await expect.poll(() => new URL(page.url()).searchParams.get("changeType")).toBe("TERM_EXTENSION");
});
