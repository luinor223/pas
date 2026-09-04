import { expect, test, type Page } from "@playwright/test";
import { signIn, signOut } from "../e2e-real/support/real-stack";
import {
  browserApi,
  ensureTestUsers,
  monthsBetween,
  runMarker,
  TEST_USERS,
  uniqueFutureDate,
} from "./support/stateful-stack";

type PriceList = { id: string; priceListNo: string; note: string | null };
type Version = { id: string; status: string; versionNo: number };
type Contract = { id: string; contractNo: string; validFrom: string; validTo: string; status: string };
type Period = { periodCode: string; status: string };
type Volume = { id: string; recordNo: string; note: string | null };

const admin = {
  username: process.env.PAS_E2E_USERNAME ?? "admin",
  password: process.env.PAS_E2E_PASSWORD ?? "admin12345",
};

test.beforeEach(async ({ page }) => {
  await signIn(page, admin);
  await ensureTestUsers(page);
  await signOut(page);
});

test("creates a labelled price list and completes its two-step approval workflow", async ({ page }, testInfo) => {
  const marker = runMarker(1);
  const validDate = uniqueFutureDate();
  testInfo.annotations.push({ type: "test-data", description: marker });

  await signIn(page, TEST_USERS.salesManager);
  await page.getByRole("navigation").getByRole("link", { name: "Price Lists", exact: true }).click();
  await page.getByRole("button", { name: "New price list" }).click();
  const createList = page.getByRole("dialog");
  await createList.getByLabel("Applies to").selectOption("SERVICE_GROUP");
  await createList.getByLabel("Service group *").selectOption("WAREHOUSING");
  await createList.getByLabel(/Note/).fill(marker);
  const createdResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname === "/api/v1/price-lists" && response.request().method() === "POST",
  );
  await createList.getByRole("button", { name: "Create price list" }).click();
  const createdBody = await (await createdResponse).json();
  const priceList = (createdBody.data ?? createdBody) as PriceList;
  testInfo.annotations.push({ type: "created-price-list", description: `${priceList.priceListNo} · ${marker}` });
  await expect(page.getByText(marker)).toBeVisible();

  await page.getByRole("button", { name: "New version" }).click();
  const versionDialog = page.getByRole("dialog");
  await versionDialog.getByLabel("Valid from *").fill(validDate);
  await versionDialog.getByLabel("Valid to *").fill(validDate);
  const versionResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname === `/api/v1/price-lists/${priceList.id}/versions`
      && response.request().method() === "POST",
  );
  await versionDialog.getByRole("button", { name: "Create version" }).click();
  const versionBody = await (await versionResponse).json();
  const version = (versionBody.data ?? versionBody) as Version;

  const priceInput = page.getByLabel(/Unit price for/).first();
  await priceInput.fill("1250000");
  const saveResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname === `/api/v1/price-lists/${priceList.id}/versions/${version.id}/lines`
      && response.request().method() === "PUT",
  );
  await page.getByRole("button", { name: "Save prices" }).click();
  expect((await saveResponse).ok()).toBe(true);

  await page.getByRole("button", { name: "Submit for approval" }).click();
  const submitDialog = page.getByRole("dialog");
  const submitResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname.endsWith(`/versions/${version.id}/submit`)
      && response.request().method() === "POST",
  );
  await submitDialog.getByRole("button", { name: "Submit for approval" }).click();
  await expectSuccessfulResponse(await submitResponse, "submit price-list version");

  await approveVisibleRequest(page, priceList.priceListNo);
  await signOut(page);

  await signIn(page, TEST_USERS.director);
  await approveVisibleRequest(page, priceList.priceListNo);

  await expect.poll(async () => {
    const detail = await browserApi<{ version: Version }>(page, `/price-lists/${priceList.id}/versions/${version.id}`);
    return detail.version.status;
  }, { timeout: 45_000, message: "workflow completion should approve the price-list version" }).toBe("APPROVED");

  await page.getByRole("navigation").getByRole("link", { name: "Notifications", exact: true }).click();
  await page.getByRole("button", { name: "Refresh" }).click();
  await expect(page.getByText(priceList.priceListNo).first()).toBeVisible();
  await signOut(page);

  await signIn(page, admin);
  await page.getByRole("navigation").getByRole("link", { name: "Audit Log", exact: true }).click();
  await page.getByLabel("Search audit records").fill(priceList.priceListNo);
  await expect(async () => {
    await page.getByRole("button", { name: /Refresh|Checking/ }).click();
    await expect(page.getByText(priceList.priceListNo).first()).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 45_000, intervals: [1_000, 2_000, 3_000] });
});

test("creates a labelled volume, locks its period, and blocks ordinary editing", async ({ page }, testInfo) => {
  const marker = runMarker(2);
  testInfo.annotations.push({ type: "test-data", description: marker });
  await signIn(page, TEST_USERS.operations);

  const contracts = await browserApi<Contract[]>(page, "/contracts?status=ACTIVE&size=100");
  const periods = await browserApi<Period[]>(page, "/periods");
  const used = new Set(periods.map((period) => period.periodCode));
  const contract = contracts.find((candidate) => monthsBetween(candidate.validFrom, candidate.validTo).some((month) => !used.has(month)));
  expect(contract, "The stateful suite needs an ACTIVE contract with at least one unused monthly period").toBeTruthy();
  const periodCode = monthsBetween(contract!.validFrom, contract!.validTo).find((month) => !used.has(month))!;
  testInfo.annotations.push({ type: "created-period", description: `${periodCode} · used by ${marker}` });

  await page.getByRole("navigation").getByRole("link", { name: "Volume Records", exact: true }).click();
  await page.getByRole("tab", { name: /Periods/ }).click();
  await page.getByRole("button", { name: "New period" }).click();
  const periodDialog = page.getByRole("dialog");
  await periodDialog.getByLabel("Month *").fill(periodCode);
  const periodResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname === "/api/v1/periods" && response.request().method() === "POST",
  );
  await periodDialog.getByRole("button", { name: "Create period" }).click();
  expect((await periodResponse).status()).toBe(201);

  await page.getByRole("tab", { name: /Volume records/i }).click();
  await page.getByRole("button", { name: "New volume record" }).click();
  const volumeDialog = page.getByRole("dialog");
  await volumeDialog.getByLabel("Period *").selectOption(periodCode);
  const picker = volumeDialog.getByRole("combobox", { name: "Active contract *" });
  await picker.fill(contract!.contractNo);
  await volumeDialog.getByRole("option", { name: new RegExp(contract!.contractNo) }).click();
  await volumeDialog.getByLabel("Service *").selectOption({ index: 1 });
  await volumeDialog.getByLabel(/Quantity/).fill("12.345");
  await volumeDialog.getByLabel(/Note/).fill(marker);
  const createResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname === "/api/v1/volume-records" && response.request().method() === "POST",
  );
  await volumeDialog.getByRole("button", { name: "Create record" }).click();
  const volumeBody = await (await createResponse).json();
  const volume = (volumeBody.data ?? volumeBody) as Volume;
  testInfo.annotations.push({ type: "created-volume", description: `${volume.recordNo} · ${marker}` });

  await page.getByRole("searchbox", { name: "Search volume records" }).fill(volume.recordNo);
  let row = page.getByRole("row").filter({ hasText: volume.recordNo });
  await expect(row).toContainText(marker);
  await expect(row.getByRole("button", { name: "Edit" })).toBeVisible();

  await page.getByRole("tab", { name: /Periods/ }).click();
  const periodRow = page.getByRole("row").filter({ hasText: periodCode });
  await periodRow.getByRole("button", { name: "Lock period" }).click();
  const lockResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname === `/api/v1/periods/${periodCode}/lock`
      && response.request().method() === "POST",
  );
  await page.getByRole("dialog").getByRole("button", { name: "Lock period" }).click();
  expect((await lockResponse).ok()).toBe(true);
  await expect(periodRow).toContainText("Locked");

  await page.getByRole("tab", { name: /Volume records/i }).click();
  await page.getByRole("searchbox", { name: "Search volume records" }).fill(volume.recordNo);
  row = page.getByRole("row").filter({ hasText: volume.recordNo });
  await expect(row).toContainText("Locked");
  await expect(row.getByRole("button", { name: "Edit" })).toHaveCount(0);
});

async function approveVisibleRequest(page: Page, documentNo: string) {
  await page.goto(`/approvals?q=${encodeURIComponent(documentNo)}`);
  const row = page.getByRole("row").filter({ hasText: documentNo });
  await expect(async () => {
    await page.reload();
    await expect(row).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 45_000, intervals: [1_000, 2_000, 3_000] });
  await row.getByRole("button", { name: "Approve" }).click();
  const actionResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname.match(/\/api\/v1\/workflow-steps\/[^/]+\/actions$/) !== null
      && response.request().method() === "POST",
  );
  await page.getByRole("dialog").getByRole("button", { name: "Approve request" }).click();
  await expectSuccessfulResponse(await actionResponse, `approve ${documentNo}`);
  await expect(page.getByRole("status")).toContainText(documentNo);
  await expect(page.getByRole("status")).toContainText("approved");
  await expect(row).toHaveCount(0);
}

async function expectSuccessfulResponse(response: import("@playwright/test").Response, action: string) {
  const body = await response.text();
  expect(response.ok(), `${action} returned ${response.status()}: ${body.slice(0, 800)}`).toBe(true);
}
