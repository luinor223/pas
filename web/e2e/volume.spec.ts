import { expect, test } from "@playwright/test";
import { envelope, installApiMocks } from "./support/api";

const basePeriod = { id: "period-1", periodCode: "2026-08", startDate: "2026-08-01", endDate: "2026-08-31", status: "OPEN", volumeCount: 1, lockedBy: null, lockedByName: null, lockedAt: null, createdAt: "2026-08-01T00:00:00Z", updatedAt: "2026-08-01T00:00:00Z" };
const baseVolume = { id: "volume-1", recordNo: "VOL-2026-0001", periodCode: "2026-08", contractId: "contract-1", contractNo: "CTR-2026-0001", customerName: "Saigon Port Services", serviceCode: "STEV", serviceName: "Stevedoring", unit: "TON", quantity: 12.5, note: "August handling", createdAt: "2026-08-20T00:00:00Z", createdBy: null, updatedAt: "2026-08-20T00:00:00Z" };

function volumePage(item = baseVolume) {
  return { items: [item], page: 0, size: 15, totalItems: 1, totalPages: 1 };
}

test("edits a volume record and locks its period", async ({ page }) => {
  let quantity = 12.5;
  let locked = false;
  const period = () => ({ id: "period-1", periodCode: "2026-08", startDate: "2026-08-01", endDate: "2026-08-31", status: locked ? "LOCKED" : "OPEN", volumeCount: 1, lockedBy: null, lockedByName: null, lockedAt: null, createdAt: "2026-08-01T00:00:00Z", updatedAt: "2026-08-01T00:00:00Z" });
  const volume = () => ({ id: "volume-1", recordNo: "VOL-2026-0001", periodCode: "2026-08", contractId: "contract-1", contractNo: "CTR-2026-0001", customerName: "Saigon Port Services", serviceCode: "STEV", serviceName: "Stevedoring", unit: "TON", quantity, note: "August handling", createdAt: "2026-08-20T00:00:00Z", createdBy: null, updatedAt: "2026-08-20T00:00:00Z" });

  await installApiMocks(page, async (request, url) => {
    if (url.pathname === "/api/v1/periods" && request.method() === "GET") return { body: envelope([period()]) };
    if (url.pathname === "/api/v1/volume-records" && request.method() === "GET") return { body: envelope({ items: [volume()], page: 0, size: 15, totalItems: 1, totalPages: 1 }) };
    if (url.pathname === "/api/v1/service-items") return { body: envelope([{ code: "STEV", name: "Stevedoring", unit: "TON", active: true }]) };
    if (url.pathname === "/api/v1/volume-records/volume-1" && request.method() === "PUT") {
      quantity = (await request.postDataJSON()).quantity;
      return { body: envelope(volume()) };
    }
    if (url.pathname === "/api/v1/periods/2026-08/lock" && request.method() === "POST") {
      locked = true;
      return { body: envelope(period()) };
    }
  });

  await page.goto("/volume-records");
  await expect(page.getByRole("link", { name: "CTR-2026-0001" })).toBeVisible();
  await page.getByRole("button", { name: "Edit" }).click();
  await page.getByRole("dialog").getByLabel("Quantity (TON) *").fill("25.125");
  const updateRequest = page.waitForRequest((request) => request.url().endsWith("/volume-records/volume-1") && request.method() === "PUT");
  await page.getByRole("button", { name: "Save changes" }).click();
  expect((await updateRequest).postDataJSON()).toMatchObject({ quantity: 25.125 });
  await expect(page.getByText(/25\.125 TON/)).toBeVisible();

  await page.getByRole("tab", { name: /Periods/ }).click();
  await page.getByRole("button", { name: "Lock period" }).click();
  await expect(page.getByRole("dialog")).toContainText("cannot be reopened");
  const lockRequest = page.waitForRequest((request) => request.url().endsWith("/periods/2026-08/lock") && request.method() === "POST");
  await page.getByRole("dialog").getByRole("button", { name: "Lock period" }).click();
  await lockRequest;
  await expect(page.getByText("Locked", { exact: true })).toBeVisible();
});

test("blocks quantities with more than three decimal places", async ({ page }) => {
  let updateCalls = 0;
  await installApiMocks(page, (request, url) => {
    if (url.pathname === "/api/v1/periods") return { body: envelope([basePeriod]) };
    if (url.pathname === "/api/v1/volume-records" && request.method() === "GET") return { body: envelope(volumePage()) };
    if (url.pathname === "/api/v1/service-items") return { body: envelope([]) };
    if (url.pathname.endsWith("/volume-records/volume-1") && request.method() === "PUT") {
      updateCalls += 1;
      return { body: envelope(baseVolume) };
    }
  });

  await page.goto("/volume-records");
  await page.getByRole("button", { name: "Edit" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Quantity (TON) *").fill("1.2345");
  await dialog.getByRole("button", { name: "Save changes" }).click();
  await expect(dialog.getByRole("alert")).toContainText("at most three decimal places");
  expect(updateCalls).toBe(0);
});

test("keeps volume records read-only without write permission", async ({ page }) => {
  await installApiMocks(page, (request, url) => {
    if (url.pathname === "/api/v1/periods") return { body: envelope([basePeriod]) };
    if (url.pathname === "/api/v1/volume-records" && request.method() === "GET") return { body: envelope(volumePage()) };
    if (url.pathname === "/api/v1/service-items") return { body: envelope([]) };
  }, { permissions: ["volume:read", "notification:read"] });

  await page.goto("/volume-records");
  await expect(page.getByText("VOL-2026-0001")).toBeVisible();
  await expect(page.getByRole("button", { name: "Edit" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "New volume record" })).toHaveCount(0);
});
