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

  await page.getByRole("button", { name: "Process guide" }).click();
  const guide = page.getByRole("dialog", { name: "Business record process guide" });
  await expect(guide).toContainText("Complete the records in this order");
  const flow = guide.getByRole("list", { name: "Complete business record flow" });
  await expect(flow.getByRole("listitem")).toHaveCount(8);
  await expect(flow.getByText(/Customers.*Sales/)).toBeVisible();
  await expect(flow.getByText(/Contracts.*Sales, Legal & Director/)).toBeVisible();
  await expect(flow.getByText(/Price Lists.*Sales & approvers/)).toBeVisible();
  await expect(flow.getByText(/Volume Records.*Operations/)).toBeVisible();
  await expect(flow.getByText(/Payment Statements.*Accounting/)).toBeVisible();
  await guide.getByRole("button", { name: "Close" }).click();

  await page.getByRole("button", { name: "+ New Statement" }).click();
  const dialog = page.getByRole("dialog", { name: "Calculate payment statement" });
  await expect(dialog.getByText(/Process guide in the bottom-left corner/)).toBeVisible();

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
