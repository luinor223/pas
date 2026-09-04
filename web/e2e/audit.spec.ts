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
  await expect(page).toHaveURL(/sourceService=contract-service/);

  await page.getByRole("button", { name: /View details for/i }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("Audit details");
  await expect(dialog).toContainText("Nguyen An");
  await expect(dialog).toContainText("Saigon Port Services");

  await page.reload();
  await expect(page.getByLabel("Filter by module")).toHaveValue("contract-service");
});

test("offers billing activities as exact audit filters", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/audit-records") {
      return { body: envelope([], { page: 0, size: 15, totalElements: 0, totalPages: 0 }) };
    }
  });

  await page.goto("/audit-log");
  const filteredRequest = page.waitForRequest((request) => request.url().includes("action=statement.cancelled"));
  await page.getByLabel("Filter by activity").selectOption({ label: "Payment statement cancelled" });

  await filteredRequest;
  await expect(page).toHaveURL(/action=statement.cancelled/);
});

test("opens the document behind a workflow audit record", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/audit-records") {
      return { body: envelope([{
        id: "audit-workflow-1", sourceService: "workflow-service", entityType: "WORKFLOW_STEP",
        entityId: "step-1", entityNo: "CTR-2026-0042", action: "workflow.step_approved",
        actorId: "user-1", actorName: "Nguyen An", actorDepartment: "IT",
        beforeStatus: null, afterStatus: null,
        changes: { documentType: "CONTRACT", documentId: "70000000-0000-4000-8000-000000000042", instanceId: "instance-1", stepOrder: 1 },
        note: null, ipAddress: null, occurredAt: "2026-09-03T10:30:00Z",
      }], { page: 0, size: 15, totalElements: 1, totalPages: 1 }) };
    }
  });

  await page.goto("/audit-log");
  await page.getByRole("button", { name: /View details for/i }).click();
  await page.getByRole("link", { name: "Open record" }).click();

  await expect(page).toHaveURL(/\/contracts\?id=70000000-0000-4000-8000-000000000042/);
});

test("recovers when an audit page no longer exists", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/audit-records") {
      return { body: envelope([], { page: Number(url.searchParams.get("page") ?? 0), size: 15, totalElements: 1, totalPages: 1 }) };
    }
  });

  await page.goto("/audit-log?page=3");

  await expect(page).toHaveURL(/\/audit-log$/);
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
