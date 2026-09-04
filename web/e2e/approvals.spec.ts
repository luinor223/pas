import { expect, test } from "@playwright/test";
import { envelope, installApiMocks } from "./support/api";

const approvalItem = {
  instanceId: "instance-1", stepInstanceId: "step-1", documentTypeCode: "CONTRACT",
  documentId: "contract-1", documentNo: "CTR-2026-0001", customerName: "Saigon Port Services",
  status: "IN_PROGRESS", priority: "HIGH", currentStepOrder: 1, currentStepName: "Manager review",
  currentStepRole: "SALES_MANAGER", stepActivatedAt: "2026-09-03T08:00:00Z",
  createdAt: "2026-09-03T07:00:00Z", requestedBy: "user-2", requestedByName: "Nguyen An",
};

test("filters approvals in the URL and confirms approval", async ({ page }) => {
  let approved = false;
  await installApiMocks(page, (request, url) => {
    if (url.pathname === "/api/v1/inbox") {
      return { body: envelope({ items: approved ? [] : [approvalItem], page: 0, size: 15, totalItems: approved ? 0 : 1, totalPages: approved ? 0 : 1 }) };
    }
    if (url.pathname === "/api/v1/workflow-steps/step-1/actions" && request.method() === "POST") {
      approved = true;
      return { body: envelope(null) };
    }
  });

  await page.goto("/approvals");
  await expect(page.getByText("CTR-2026-0001")).toBeVisible();

  const searchRequest = page.waitForRequest((request) => request.url().includes("/api/v1/inbox") && request.url().includes("q=CTR-2026"));
  await page.getByLabel("Search approvals").fill("CTR-2026");
  await searchRequest;
  await expect(page).toHaveURL(/q=CTR-2026/);

  await page.getByRole("button", { name: "Approve", exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText("Approve CTR-2026-0001?");
  const actionRequest = page.waitForRequest((request) => request.url().endsWith("/workflow-steps/step-1/actions") && request.method() === "POST");
  await page.getByRole("button", { name: "Approve request" }).click();
  expect((await actionRequest).postDataJSON()).toEqual({ action: "APPROVE", comment: null });
  await expect(page.getByText("No approvals match your filters")).toBeVisible();
});

test("requires and submits a rejection reason", async ({ page }) => {
  await installApiMocks(page, (request, url) => {
    if (url.pathname === "/api/v1/inbox") return { body: envelope({ items: [approvalItem], page: 0, size: 15, totalItems: 1, totalPages: 1 }) };
    if (url.pathname.endsWith("/workflow-steps/step-1/actions") && request.method() === "POST") return { body: envelope(null) };
  });

  await page.goto("/approvals");
  await page.getByRole("button", { name: "Reject" }).click();
  const dialog = page.getByRole("dialog");
  const confirm = dialog.getByRole("button", { name: "Reject request" });
  await expect(confirm).toBeDisabled();
  await dialog.getByRole("textbox").fill("The commercial terms need correction.");
  const actionRequest = page.waitForRequest((request) => request.url().endsWith("/workflow-steps/step-1/actions") && request.method() === "POST");
  await confirm.click();
  expect((await actionRequest).postDataJSON()).toEqual({ action: "REJECT", comment: "The commercial terms need correction." });
});

test("restores completed-tab filters and page from the URL", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/inbox") return { body: envelope({ items: [], page: 2, size: 15, totalItems: 31, totalPages: 3 }) };
  });

  const request = page.waitForRequest((request) => request.url().includes("tab=COMPLETED") && request.url().includes("priority=HIGH") && request.url().includes("page=2"));
  await page.goto("/approvals?tab=COMPLETED&priority=HIGH&page=2");
  await request;
  await expect(page.getByRole("tab", { name: /Completed/ })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByLabel("Filter by priority")).toHaveValue("HIGH");
  await expect(page.getByText("Page 3 of 3")).toBeVisible();
});

test("restores the previous approval tab with browser Back", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/inbox") {
      return { body: envelope({ items: [], page: 0, size: 15, totalItems: 0, totalPages: 0 }) };
    }
  });

  await page.goto("/approvals");
  await page.getByRole("tab", { name: "Submitted by me" }).click();
  await expect(page).toHaveURL(/tab=SUBMITTED/);
  await page.getByRole("tab", { name: "Completed" }).click();
  await expect(page).toHaveURL(/tab=COMPLETED/);
  await page.goBack();
  await expect(page.getByRole("tab", { name: "Submitted by me" })).toHaveAttribute("aria-selected", "true");
  await expect(page).toHaveURL(/tab=SUBMITTED/);
});
