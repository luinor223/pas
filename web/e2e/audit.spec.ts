import { expect, test } from "@playwright/test";
import { envelope, installApiMocks } from "./support/api";

test("filters audit activity and opens readable details", async ({ page }) => {
  const record = {
    id: "audit-1", sourceService: "contract-service", entityType: "CONTRACT", entityId: "contract-1",
    entityNo: "CTR-2026-0001", action: "contract.created", actorId: "user-1", actorName: "Nguyen An",
    actorDepartment: "IT", beforeStatus: null, afterStatus: "DRAFT",
    changes: { customerName: "Saigon Port Services", serviceGroup: "STEVEDORING" }, note: null,
    ipAddress: null, occurredAt: "2026-09-03T10:30:00Z",
  };
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/audit-records") {
      return { body: envelope([record], { page: 0, size: 15, totalElements: 1, totalPages: 1 }) };
    }
  });

  await page.goto("/audit-log");
  await expect(page.getByText("CTR-2026-0001")).toBeVisible();
  const filteredRequest = page.waitForRequest((request) => request.url().includes("sourceService=contract-service"));
  await page.getByLabel("Filter by module").selectOption("contract-service");
  await filteredRequest;

  await page.getByRole("button", { name: /View details for/i }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("Audit details");
  await expect(dialog).toContainText("Nguyen An");
  await expect(dialog).toContainText("Saigon Port Services");
});

test("blocks an invalid date range without sending another query", async ({ page }) => {
  let auditCalls = 0;
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/audit-records") {
      auditCalls += 1;
      return { body: envelope([], { page: 0, size: 15, totalElements: 0, totalPages: 0 }) };
    }
  });

  await page.goto("/audit-log");
  await expect.poll(() => auditCalls).toBe(1);
  await page.getByLabel("From").fill("2026-09-10");
  await page.getByLabel("To").fill("2026-09-01");
  await expect(page.getByRole("alert")).toContainText("must be the same as or later");
  await expect(page.getByText("Correct the date range to view activity.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Refresh" })).toBeDisabled();
  const callsWhenRangeBecameInvalid = auditCalls;
  await page.waitForTimeout(400);
  expect(auditCalls).toBe(callsWhenRangeBecameInvalid);
});

test("does not query audit data without audit permission", async ({ page }) => {
  let auditCalls = 0;
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/audit-records") {
      auditCalls += 1;
      return { body: envelope([]) };
    }
  }, { permissions: ["notification:read"] });

  await page.goto("/audit-log");
  await expect(page.getByText("You do not have access to the activity history.")).toBeVisible();
  expect(auditCalls).toBe(0);
});
