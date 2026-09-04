import { expect, test } from "@playwright/test";
import { currentUser, envelope, installApiMocks } from "./support/api";

const contract = {
  id: "d1111111-1111-4111-8111-111111111111",
  contractNo: "CTR-2026-0001",
  customerName: "Saigon Port Services",
  status: "ACTIVE",
};

test("explains calculation prerequisites and shows a missing-period response", async ({ page }) => {
  await installApiMocks(page, async (request, url) => {
    if (url.pathname === "/api/v1/payment-statements" && request.method() === "GET") {
      return { body: envelope([], { page: 0, size: 15, totalElements: 0, totalPages: 0 }) };
    }
    if (url.pathname === "/api/v1/contracts" && request.method() === "GET") {
      return { body: envelope([contract], { page: 0, size: 10, totalElements: 1, totalPages: 1 }) };
    }
    if (url.pathname === `/api/v1/contracts/${contract.id}`) {
      return { body: envelope(contract) };
    }
    if (url.pathname === "/api/v1/payment-statements/calculate" && request.method() === "POST") {
      return {
        status: 422,
        body: {
          code: "BILLING_PERIOD_NOT_FOUND",
          message: "Billing period 2026-06 does not exist. Create it in Volume Records before calculating a statement.",
          violations: [],
        },
      };
    }
  }, { permissions: [...currentUser.permissions, "statement:read", "statement:write"] });

  await page.goto("/payment-statements");
  await page.getByRole("button", { name: "+ New Statement" }).click();
  const dialog = page.getByRole("dialog", { name: "Calculate payment statement" });

  const guide = dialog.getByRole("complementary", { name: "Preparation guide" });
  await expect(guide).toContainText("Complete these records in order before calculating");
  await expect(guide.getByText(/Contracts:.*create and approve the contract/)).toBeVisible();
  await expect(guide.getByText(/Price Lists:.*price every recorded service/)).toBeVisible();
  await expect(guide.getByText(/Volume Records:.*lock the period/)).toBeVisible();
  await expect(guide.getByText(/Payment Statements:.*return here and calculate/)).toBeVisible();

  const picker = dialog.getByRole("combobox", { name: "Contract *" });
  await picker.click();
  await dialog.getByRole("option", { name: /CTR-2026-0001/ }).first().click();
  await dialog.getByLabel("Period *").fill("2026-06");
  const calculation = page.waitForRequest((candidate) =>
    candidate.url().endsWith("/payment-statements/calculate") && candidate.method() === "POST");
  await dialog.getByRole("button", { name: "Check and calculate" }).click();
  expect((await calculation).postDataJSON()).toEqual({ contractId: contract.id, periodCode: "2026-06" });
  await expect(dialog.getByText(/Billing period 2026-06 does not exist/)).toBeVisible();
});
