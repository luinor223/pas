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
  const actionBody = (await actionRequest).postDataJSON();
  expect(actionBody).toMatchObject({ action: "APPROVE", comment: null });
  expect(actionBody.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toContainText("CTR-2026-0001 approved");
  await expect(page.getByText("No approvals match your filters")).toBeVisible();
});

test("requires and submits a rejection reason", async ({ page }) => {
  await installApiMocks(page, (request, url) => {
    if (url.pathname === "/api/v1/inbox") return { body: envelope({ items: [approvalItem], page: 0, size: 15, totalItems: 1, totalPages: 1 }) };
    if (url.pathname.endsWith("/workflow-steps/step-1/actions") && request.method() === "POST") return { body: envelope(null) };
  });

  await page.goto("/approvals");
  const rejectButton = page.getByRole("button", { name: "Reject" });
  await rejectButton.focus();
  await rejectButton.press("Enter");
  const dialog = page.getByRole("dialog", { name: "Reject request" });
  await expect(dialog.getByRole("textbox")).toBeFocused();
  const confirm = dialog.getByRole("button", { name: "Reject request" });
  await expect(confirm).toBeDisabled();
  await dialog.getByRole("textbox").fill("The commercial terms need correction.");
  const actionRequest = page.waitForRequest((request) => request.url().endsWith("/workflow-steps/step-1/actions") && request.method() === "POST");
  await confirm.click();
  expect((await actionRequest).postDataJSON()).toMatchObject({ action: "REJECT", comment: "The commercial terms need correction." });
});

test("traps dialog focus, closes with Escape, and restores the trigger", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/inbox") return { body: envelope({ items: [approvalItem], page: 0, size: 15, totalItems: 1, totalPages: 1 }) };
  });
  await page.goto("/approvals");
  const reject = page.getByRole("button", { name: "Reject" });
  await reject.focus();
  await reject.press("Enter");
  const dialog = page.getByRole("dialog", { name: "Reject request" });
  for (let i = 0; i < 5; i += 1) await page.keyboard.press("Tab");
  await expect(dialog.locator(":focus")).toHaveCount(1);
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(reject).toBeFocused();
});

test("closes a dialog when the backdrop is clicked", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/inbox") return { body: envelope({ items: [approvalItem], page: 0, size: 15, totalItems: 1, totalPages: 1 }) };
  });
  await page.goto("/approvals");
  const reject = page.getByRole("button", { name: "Reject" });
  await reject.click();
  const dialog = page.getByRole("dialog", { name: "Reject request" });
  const bounds = await dialog.boundingBox();
  expect(bounds).not.toBeNull();

  // Click beside the dialog at the same vertical position. This guards against
  // full-width layout wrappers accidentally intercepting backdrop clicks.
  await page.mouse.click(8, bounds!.y + bounds!.height / 2);
  await expect(dialog).toHaveCount(0);
});

test("opens approval actions when randomUUID is unavailable on a non-secure origin", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(Crypto.prototype, "randomUUID", { configurable: true, value: undefined });
  });
  const pageErrors: Error[] = [];
  page.on("pageerror", (error) => pageErrors.push(error));
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/inbox") return { body: envelope({ items: [approvalItem], page: 0, size: 15, totalItems: 1, totalPages: 1 }) };
    if (url.pathname.endsWith("/workflow-steps/step-1/actions")) return { body: envelope(null) };
  });

  await page.goto("/approvals");
  expect(await page.evaluate(() => typeof crypto.randomUUID)).toBe("undefined");
  await page.getByRole("button", { name: "Approve", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: /Approve CTR-2026-0001/ });
  await expect(dialog).toBeVisible();
  const requestPromise = page.waitForRequest((request) => request.url().endsWith("/workflow-steps/step-1/actions") && request.method() === "POST");
  await dialog.getByRole("button", { name: "Approve request" }).click();
  expect((await requestPromise).postDataJSON().idempotencyKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  expect(pageErrors).toEqual([]);
});

test("recovers an out-of-range inbox page", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    const requestedPage = url.searchParams.get("page");
    if (url.pathname === "/api/v1/inbox" && requestedPage === "1") return { body: envelope({ items: [], page: 1, size: 15, totalItems: 15, totalPages: 1 }) };
    if (url.pathname === "/api/v1/inbox") return { body: envelope({ items: [approvalItem], page: 0, size: 15, totalItems: 15, totalPages: 1 }) };
  });
  await page.goto("/approvals?page=1");
  await expect(page).not.toHaveURL(/page=/);
  await expect(page.getByText("CTR-2026-0001")).toBeVisible();
});

test("links every reviewable document type", async ({ page }) => {
  const items = [
    approvalItem,
    { ...approvalItem, instanceId: "instance-2", stepInstanceId: "step-2", documentTypeCode: "ADDENDUM", documentId: "addendum-1", documentNo: "ADD-001" },
    { ...approvalItem, instanceId: "instance-3", stepInstanceId: "step-3", documentTypeCode: "PAYMENT_STATEMENT", documentId: "statement-1", documentNo: "PS-001" },
  ];
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/inbox") return { body: envelope({ items, page: 0, size: 15, totalItems: 3, totalPages: 1 }) };
  });
  await page.goto("/approvals");
  await expect(page.getByRole("link", { name: "ADD-001" })).toHaveAttribute("href", /addenda\?id=addendum-1/);
  await expect(page.getByRole("link", { name: "PS-001" })).toHaveAttribute("href", /payment-statements\?id=statement-1/);
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
