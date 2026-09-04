import { expect, test } from "@playwright/test";
import { currentUser, envelope, installApiMocks } from "./support/api";

const session = {
  id: "c1381979-8e1c-457d-8472-2cedfe73a462",
  sessionNo: "SIG-5",
  documentTypeCode: "CONTRACT",
  documentId: "d5555555-5555-4555-8555-555555555555",
  documentNo: "CTR-2026-0005",
  customerName: "Tan Cang Logistics",
  signerName: "Do Minh Khoa",
  signerEmail: "khoa.dm@tancang.vn",
  provider: "MockSign",
  providerRef: "MOCK-9553ec06",
  status: "SIGNED",
  attempts: 1,
  lastError: null,
  requestedByName: "System Administrator",
  sentAt: "2026-09-04T18:06:30Z",
  completedAt: "2026-09-04T18:06:44Z",
  createdAt: "2026-09-04T18:06:28Z",
};

test("renders signing sessions from the standard paged response", async ({ page }) => {
  await installApiMocks(page, async (request, url) => {
    if (url.pathname === "/api/v1/signing-sessions" && request.method() === "GET") {
      return { body: envelope([session], { page: 0, size: 15, totalElements: 1, totalPages: 1 }) };
    }
  }, { permissions: [...currentUser.permissions, "esign:send", "esign:cancel"] });

  await page.goto("/e-signatures");

  await expect(page.getByRole("heading", { name: "E-Signatures (1)" })).toBeVisible();
  await expect(page.getByRole("link", { name: "SIG-5" })).toBeVisible();
  await expect(page.getByText("CTR-2026-0005")).toBeVisible();
  await expect(page.getByText("Tan Cang Logistics")).toBeVisible();
  await expect(page.getByText("Do Minh Khoa")).toBeVisible();
  await expect(page.getByRole("table").getByText("Signed", { exact: true })).toBeVisible();
  await expect(page.getByText("contract, addendum, or payment statement")).toBeVisible();
});
