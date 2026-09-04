import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { currentUser, envelope, installApiMocks, type ApiHandler } from "./support/api";

const ADDENDUM_ID = "10000000-0000-4000-8000-000000000001";
const CONTRACT_ID = "20000000-0000-4000-8000-000000000001";
const ATTACHMENT_ID = "30000000-0000-4000-8000-000000000001";

const addendum = {
  id: ADDENDUM_ID,
  addendumNo: "ADD-2026-0001",
  contractId: CONTRACT_ID,
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

const emptyProgress = {
  documentStatus: "DRAFT",
  workflowState: "NOT_STARTED",
  instanceId: null,
  definitionVersionNo: null,
  requestedByName: null,
  startedAt: null,
  priority: null,
  currentStep: null,
  steps: [],
};

async function installAddendumMocks(
  page: Parameters<typeof installApiMocks>[0],
  handler: ApiHandler,
  userOverrides: Partial<typeof currentUser> = {},
) {
  await installApiMocks(page, async (request, url) => {
    const response = await handler(request, url);
    if (response) return response;
    if (/^\/api\/v1\/addenda\/[^/]+\/progress$/.test(url.pathname)) return { body: envelope(emptyProgress) };
    if (/^\/api\/v1\/addenda\/[^/]+\/history$/.test(url.pathname)) return { body: envelope([]) };
    if (/^\/api\/v1\/addenda\/[^/]+\/signing-request$/.test(url.pathname)) {
      return { body: envelope({ canSendForSigning: false, requestQueued: false, sessionId: null }) };
    }
    if (/^\/api\/v1\/signing-sessions\/by-document\/ADDENDUM\/[^/]+$/.test(url.pathname)) return { body: envelope([]) };
  }, userOverrides);
}

test("opens an addendum detail link and restores it after refresh and browser Back", async ({ page }) => {
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/addenda") return { body: envelope([addendum], pageMeta) };
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(addendum) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read"] });

  await page.goto("/addenda");
  await page.getByRole("link", { name: addendum.addendumNo }).click();
  await expect.poll(() => new URL(page.url()).searchParams.get("id")).toBe(ADDENDUM_ID);
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
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(addendum) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
    if (url.pathname === "/api/v1/addenda") {
      requestedChangeTypes.push(url.searchParams.get("changeType"));
      return { body: envelope([addendum], pageMeta) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read"] });

  await page.goto("/addenda?changeType=TERM_EXTENSION");
  await page.getByRole("link", { name: addendum.addendumNo }).click();
  await expect.poll(() => new URL(page.url()).searchParams.get("id")).toBe(ADDENDUM_ID);
  await expect.poll(() => new URL(page.url()).searchParams.get("changeType")).toBe("TERM_EXTENSION");

  await page.getByRole("link", { name: "Back to addenda" }).click();
  await expect.poll(() => new URL(page.url()).searchParams.get("id")).toBeNull();
  await expect.poll(() => new URL(page.url()).searchParams.get("changeType")).toBe("TERM_EXTENSION");
  await expect(page.getByLabel("Filter by change type")).toHaveValue("TERM_EXTENSION");
  await expect.poll(() => requestedChangeTypes.at(-1)).toBe("TERM_EXTENSION");
  await expect(page.getByRole("heading", { name: /Addenda/ })).toBeVisible();
});

test("shows business change labels and includes expired addenda in the status filter", async ({ page }) => {
  const requestedStatuses: Array<string | null> = [];
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/addenda") {
      requestedStatuses.push(url.searchParams.get("status"));
      return { body: envelope([{ ...addendum, changeType: "UNIT_PRICE_CHANGE", status: "EXPIRED" }], pageMeta) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto("/addenda");
  const table = page.getByRole("table");
  await expect(table.getByText("Unit price change", { exact: true })).toBeVisible();
  await expect(table.getByText("UNIT_PRICE_CHANGE", { exact: true })).toHaveCount(0);
  const statusFilter = page.getByLabel("Filter by status");
  await expect(statusFilter.getByRole("option", { name: "Expired" })).toHaveAttribute("value", "EXPIRED");
  await statusFilter.selectOption("EXPIRED");
  await expect.poll(() => requestedStatuses.at(-1)).toBe("EXPIRED");
  await expect(page).toHaveURL(/status=EXPIRED/);

  await page.getByRole("button", { name: "+ New Addendum" }).click();
  const dialog = page.getByRole("dialog", { name: "Create addendum" });
  await dialog.getByLabel("Change type *").selectOption("TERM_EXTENSION");
  await dialog.getByRole("button", { name: "Create", exact: true }).click();
  await expect(dialog.getByText("New valid-to date is required for a term extension")).toBeVisible();
  await expect(dialog.getByText("TERM_EXTENSION", { exact: true })).toHaveCount(0);
});

test("shows read-only detail access without write permission", async ({ page }) => {
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(addendum) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await expect(page.getByText("Read-only access")).toBeVisible();
  await expect(page.getByRole("heading", { name: addendum.addendumNo })).toBeVisible();
  for (const action of ["Edit", "Submit for approval", "Revise", "Cancel"]) {
    await expect(page.getByRole("button", { name: action, exact: true })).toHaveCount(0);
  }
});

test("does not request detail data without addendum read permission", async ({ page }) => {
  let detailRequests = 0;
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) detailRequests += 1;
  });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await expect(page.getByText("You do not have access to addenda.")).toBeVisible();
  expect(detailRequests).toBe(0);
});

test("read-only addendum list does not request eligible parent contracts", async ({ page }) => {
  let contractRequests = 0;
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/addenda") return { body: envelope([addendum], pageMeta) };
    if (url.pathname.startsWith("/api/v1/contracts")) contractRequests += 1;
  }, { permissions: [...currentUser.permissions, "addendum:read"] });

  await page.goto("/addenda");
  await expect(page.getByText(addendum.addendumNo)).toBeVisible();
  await expect(page.getByRole("button", { name: "+ New Addendum" })).toHaveCount(0);
  expect(contractRequests).toBe(0);
});

test("addendum writers without contract read access cannot start unusable creation", async ({ page }) => {
  let contractRequests = 0;
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/addenda") return { body: envelope([addendum], pageMeta) };
    if (url.pathname.startsWith("/api/v1/contracts")) contractRequests += 1;
  }, {
    permissions: [
      ...currentUser.permissions.filter((permission) => permission !== "contract:read"),
      "addendum:read",
      "addendum:write",
    ],
  });

  await page.goto(`/addenda?contractId=${CONTRACT_ID}`);
  await expect(page.getByText(addendum.addendumNo)).toBeVisible();
  await expect(page.getByRole("button", { name: "+ New Addendum" })).toHaveCount(0);
  expect(contractRequests).toBe(0);
});

test("shows a not-found state for a missing addendum", async ({ page }) => {
  const missingId = "00000000-0000-4000-8000-000000000099";
  const requestedPaths: string[] = [];
  await installAddendumMocks(page, (_request, url) => {
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
  await installAddendumMocks(page, async (request, url) => {
    if (url.pathname === "/api/v1/addenda" && request.method() === "GET") {
      return { body: envelope([], { page: 0, size: 15, totalElements: 0, totalPages: 0 }) };
    }
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}`) {
      return { body: envelope({ id: CONTRACT_ID, contractNo: "CTR-2026-0001", customerName: "Customer", status: "APPROVED", canCreateAddendum: true }) };
    }
    if (url.pathname === "/api/v1/addenda" && request.method() === "POST") {
      submittedContractId = (await request.postDataJSON()).contractId;
      return { status: 201, body: envelope(addendum) };
    }
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}` && request.method() === "GET") {
      detailRequestPaths.push(url.pathname);
      return { body: envelope(addendum) };
    }
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?contractId=${CONTRACT_ID}&changeType=TERM_EXTENSION`);
  await page.getByRole("button", { name: "+ New Addendum" }).click();
  const dialog = page.getByRole("dialog", { name: "Create addendum" });
  await expect(dialog.getByLabel("Contract *")).toHaveAttribute("placeholder", /CTR-2026-0001 · Customer/);
  await expect(dialog.locator("select").first()).toHaveValue("TERM_EXTENSION");
  await dialog.locator('input[type="date"]').nth(1).fill("2027-09-30");
  await dialog.getByRole("button", { name: "Create", exact: true }).click();

  await expect.poll(() => new URL(page.url()).searchParams.get("id")).toBe(ADDENDUM_ID);
  await expect.poll(() => new URL(page.url()).searchParams.get("contractId")).toBe(CONTRACT_ID);
  await expect.poll(() => new URL(page.url()).searchParams.get("changeType")).toBe("TERM_EXTENSION");
  await expect(page.getByRole("heading", { name: addendum.addendumNo })).toBeVisible();
  expect(submittedContractId).toBe(CONTRACT_ID);

  await page.reload();
  await expect(page.getByRole("heading", { name: addendum.addendumNo })).toBeVisible();
  expect(detailRequestPaths).toEqual([`/api/v1/addenda/${ADDENDUM_ID}`]);

  await page.getByRole("link", { name: "Back to addenda" }).click();
  await expect.poll(() => new URL(page.url()).searchParams.get("id")).toBeNull();
  await expect.poll(() => new URL(page.url()).searchParams.get("contractId")).toBe(CONTRACT_ID);
  await expect.poll(() => new URL(page.url()).searchParams.get("changeType")).toBe("TERM_EXTENSION");
});

test("create form excludes ineligible contracts even from a deep link", async ({ page }) => {
  const draftId = "20000000-0000-4000-8000-000000000009";
  const requestedStatuses: Array<string | null> = [];
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/addenda") return { body: envelope([], pageMeta) };
    if (url.pathname === `/api/v1/contracts/${draftId}`) {
      return { body: envelope({ id: draftId, contractNo: "CTR-DRAFT", status: "DRAFT", canCreateAddendum: false }) };
    }
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}`) {
      return { body: envelope({ id: CONTRACT_ID, contractNo: "CTR-ELIGIBLE", customerName: "Eligible Customer", status: "APPROVED", canCreateAddendum: true }) };
    }
    if (url.pathname === "/api/v1/contracts") {
      const status = url.searchParams.get("status");
      requestedStatuses.push(status);
      const rows = status === "APPROVED"
        ? [{ id: CONTRACT_ID, contractNo: "CTR-ELIGIBLE", customerName: "Eligible Customer", status: "APPROVED", canCreateAddendum: true }]
        : [];
      return { body: envelope(rows, { page: 0, size: 10, totalElements: rows.length, totalPages: rows.length ? 1 : 0 }) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?contractId=${draftId}`);
  await page.getByRole("button", { name: "+ New Addendum" }).click();
  const contractPicker = page.getByRole("dialog", { name: "Create addendum" }).getByLabel("Contract *");
  await expect(contractPicker).toHaveValue("");
  await contractPicker.click();
  await expect(page.getByRole("option", { name: /CTR-ELIGIBLE/ })).toBeVisible();
  await page.getByRole("option", { name: /CTR-ELIGIBLE/ }).click();
  await expect(contractPicker).toHaveValue("CTR-ELIGIBLE · Eligible Customer");
  expect(requestedStatuses.sort()).toEqual(["ACTIVE", "APPROVED"]);
});

test("waits for deep-link eligibility before opening the create form", async ({ page }) => {
  let releaseContract!: () => void;
  const contractGate = new Promise<void>((resolve) => { releaseContract = resolve; });
  await installAddendumMocks(page, async (_request, url) => {
    if (url.pathname === "/api/v1/addenda") return { body: envelope([], pageMeta) };
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}`) {
      await contractGate;
      return { body: envelope({ id: CONTRACT_ID, contractNo: "CTR-DEEP-LINK", customerName: "Deep Link Customer", status: "ACTIVE", canCreateAddendum: true }) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?contractId=${CONTRACT_ID}`);
  const newButton = page.getByRole("button", { name: "+ New Addendum" });
  await expect(newButton).toBeDisabled();
  releaseContract();
  await expect(newButton).toBeEnabled();
  await newButton.click();
  await expect(page.getByRole("dialog", { name: "Create addendum" }).getByLabel("Contract *"))
    .toHaveAttribute("placeholder", /CTR-DEEP-LINK · Deep Link Customer/);
});

test("searches eligible contracts instead of limiting creation to the first 100", async ({ page }) => {
  const listRequests: Array<{ q: string | null; status: string | null; size: string | null }> = [];
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/addenda") return { body: envelope([], pageMeta) };
    if (url.pathname === "/api/v1/contracts") {
      const request = { q: url.searchParams.get("q"), status: url.searchParams.get("status"), size: url.searchParams.get("size") };
      listRequests.push(request);
      const rows = request.q === "CTR-150" && request.status === "APPROVED"
        ? [{ id: CONTRACT_ID, contractNo: "CTR-150", customerName: "Customer 150", status: "APPROVED", canCreateAddendum: true }]
        : [];
      return { body: envelope(rows, { page: 0, size: 10, totalElements: rows.length, totalPages: rows.length ? 1 : 0 }) };
    }
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}`) {
      return { body: envelope({ id: CONTRACT_ID, contractNo: "CTR-150", customerName: "Customer 150", status: "APPROVED", canCreateAddendum: true }) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto("/addenda");
  await page.getByRole("button", { name: "+ New Addendum" }).click();
  const picker = page.getByRole("dialog", { name: "Create addendum" }).getByLabel("Contract *");
  await picker.fill("CTR-150");
  await page.getByRole("option", { name: /CTR-150/ }).click();
  await expect(picker).toHaveValue("CTR-150 · Customer 150");
  expect(listRequests.filter(({ q }) => q === "CTR-150").map(({ status, size }) => [status, size]).sort())
    .toEqual([["ACTIVE", "10"], ["APPROVED", "10"]]);
});

test("supports keyboard addendum creation with named service controls and restores focus", async ({ page }) => {
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/addenda") {
      return { body: envelope([], { page: 0, size: 15, totalElements: 0, totalPages: 0 }) };
    }
    if (url.pathname === "/api/v1/contracts") {
      return { body: envelope([], { page: 0, size: 10, totalElements: 0, totalPages: 0 }) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto("/addenda");
  const opener = page.getByRole("button", { name: "+ New Addendum" });
  await opener.focus();
  await page.keyboard.press("Enter");

  const dialog = page.getByRole("dialog", { name: "Create addendum" });
  await dialog.getByLabel("Change type *").selectOption("ADDED_SERVICE");
  const addService = dialog.getByRole("button", { name: "+ Service" });
  await addService.focus();
  await page.keyboard.press("Enter");

  await expect(dialog.getByLabel("Service 1 code")).toBeVisible();
  await expect(dialog.getByLabel("Service 1 name")).toBeVisible();
  await expect(dialog.getByLabel("Service 1 unit")).toBeVisible();
  await expect(dialog.getByLabel("Service 1 scope")).toBeVisible();
  const removeService = dialog.getByRole("button", { name: "Remove service 1" });
  await removeService.focus();
  await page.keyboard.press("Enter");
  await expect(dialog.getByLabel("Service 1 code")).toHaveCount(0);
  await addService.focus();
  await page.keyboard.press("Enter");
  const serviceGrid = dialog.getByLabel("Service 1 code").locator("xpath=../..");
  await expect.poll(() => serviceGrid.evaluate((element) => getComputedStyle(element).gridTemplateColumns.split(" ").length)).toBe(1);

  const accessibility = await new AxeBuilder({ page }).include('[role="dialog"]').analyze();
  expect(accessibility.violations).toEqual([]);

  await dialog.getByLabel("Service 1 code").focus();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(opener).toBeFocused();
});

test("blocks draft submission until an attachment exists", async ({ page }) => {
  let submitRequests = 0;
  await installAddendumMocks(page, (request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(addendum) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/submit` && request.method() === "POST") {
      submitRequests += 1;
      return { body: envelope({ status: "SUBMITTED", dispatchPending: true }) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await expect(page.getByText("Upload at least one attachment before submitting this addendum for approval.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Submit for approval" })).toBeDisabled();
  expect(submitRequests).toBe(0);
});

test("creates an addendum, uploads an attachment, and submits it", async ({ page }) => {
  const created = { ...addendum, changeType: "UNIT_PRICE_CHANGE", newValidTo: null };
  const attachment = {
    id: ATTACHMENT_ID,
    ownerType: "ADDENDUM",
    ownerId: created.id,
    fileName: "signed-draft.pdf",
    contentType: "application/pdf",
    sizeBytes: 12,
    uploadedAt: "2026-09-04T08:00:00Z",
  };
  let attachments: typeof attachment[] = [];
  let submitted = false;
  let submitRequests = 0;
  let uploadRequest: { ownerType: string | null; ownerId: string | null; contentType: string; body: string } | undefined;

  await installAddendumMocks(page, async (request, url) => {
    const method = request.method();
    if (url.pathname === "/api/v1/addenda" && method === "GET") {
      return { body: envelope([], { page: 0, size: 15, totalElements: 0, totalPages: 0 }) };
    }
    if (url.pathname === "/api/v1/contracts") {
      return { body: envelope([{ id: CONTRACT_ID, contractNo: "CTR-2026-0001", customerName: "Customer", status: "APPROVED", canCreateAddendum: true }], { page: 0, size: 10, totalElements: 1, totalPages: 1 }) };
    }
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}`) {
      return { body: envelope({ id: CONTRACT_ID, contractNo: "CTR-2026-0001", customerName: "Customer", status: "APPROVED", canCreateAddendum: true }) };
    }
    if (url.pathname === "/api/v1/addenda" && method === "POST") return { status: 201, body: envelope(created) };
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/submit` && method === "POST") {
      submitRequests += 1;
      submitted = true;
      return { body: envelope({ status: "SUBMITTED", dispatchPending: true }) };
    }
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}` && method === "GET") {
      if (submitted) await new Promise((resolve) => setTimeout(resolve, 500));
      return { body: envelope({ ...created, status: submitted ? "SUBMITTED" : "DRAFT" }) };
    }
    if (url.pathname === "/api/v1/attachments" && method === "GET") return { body: envelope(attachments) };
    if (url.pathname === "/api/v1/attachments" && method === "POST") {
      uploadRequest = {
        ownerType: url.searchParams.get("ownerType"),
        ownerId: url.searchParams.get("ownerId"),
        contentType: request.headers()["content-type"] ?? "",
        body: request.postData() ?? "",
      };
      attachments = [attachment];
      return { status: 201, body: envelope(attachment) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto("/addenda");
  await page.getByRole("button", { name: "+ New Addendum" }).click();
  const dialog = page.getByRole("dialog", { name: "Create addendum" });
  await dialog.getByLabel("Contract *").click();
  await page.getByRole("option", { name: /CTR-2026-0001/ }).click();
  await dialog.getByRole("button", { name: "Create", exact: true }).click();

  await expect(page.getByRole("heading", { name: created.addendumNo })).toBeVisible();
  await page.getByLabel("Choose attachment file").setInputFiles({
    name: attachment.fileName,
    mimeType: attachment.contentType,
    buffer: Buffer.from("pdf contents"),
  });
  await page.getByRole("button", { name: "Upload attachment", exact: true }).click();
  await expect(page.getByText(attachment.fileName)).toBeVisible();
  await expect(page.getByRole("button", { name: "Delete", exact: true })).toBeVisible();
  expect(uploadRequest?.ownerType).toBe("ADDENDUM");
  expect(uploadRequest?.ownerId).toBe(ADDENDUM_ID);
  expect(uploadRequest?.contentType).toContain("multipart/form-data; boundary=");
  expect(uploadRequest?.body).toContain('name="file"');
  expect(uploadRequest?.body).toContain(`filename="${attachment.fileName}"`);

  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(page.getByText("Under Review").first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Submit for approval" })).toHaveCount(0);
  expect(submitRequests).toBe(1);
});

test("blocks lifecycle actions while deleting the final attachment", async ({ page }) => {
  const attachment = {
    id: ATTACHMENT_ID, ownerType: "ADDENDUM", ownerId: ADDENDUM_ID,
    fileName: "only-copy.pdf", contentType: "application/pdf", sizeBytes: 1024,
    uploadedAt: "2026-09-04T08:00:00Z",
  };
  let releaseDelete!: () => void;
  const deleteGate = new Promise<void>((resolve) => { releaseDelete = resolve; });
  let deleted = false;
  let submitRequests = 0;
  let cancelRequests = 0;
  await installAddendumMocks(page, async (request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(addendum) };
    if (url.pathname === "/api/v1/attachments" && request.method() === "GET") {
      if (deleted) await new Promise((resolve) => setTimeout(resolve, 500));
      return { body: envelope(deleted ? [] : [attachment]) };
    }
    if (url.pathname === `/api/v1/attachments/${ATTACHMENT_ID}` && request.method() === "DELETE") {
      await deleteGate;
      deleted = true;
      return { body: envelope(null) };
    }
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/submit` && request.method() === "POST") {
      submitRequests += 1;
      return { body: envelope({ status: "SUBMITTED", dispatchPending: true }) };
    }
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/cancel` && request.method() === "POST") {
      cancelRequests += 1;
      return { body: envelope({ status: "CANCELLED", detail: null }) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await expect(page.getByRole("button", { name: "Submit for approval" })).toBeEnabled();
  await page.getByRole("button", { name: "Delete", exact: true }).click();
  await page.getByRole("dialog", { name: "Delete this attachment?" }).getByRole("button", { name: "Delete file" }).click();

  const cancelWhileDeleting = page.getByRole("button", { name: "Cancel", exact: true, includeHidden: true });
  await expect(cancelWhileDeleting).toBeDisabled();
  await cancelWhileDeleting.click({ force: true });
  expect(cancelRequests).toBe(0);
  releaseDelete();
  await expect(page.getByRole("dialog", { name: "Delete this attachment?" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Submit for approval" })).toBeDisabled();
  await expect(page.getByText("Upload at least one attachment before submitting this addendum for approval.")).toBeVisible();
  expect(submitRequests).toBe(0);
});

test("blocks upload and delete while submission is in flight", async ({ page }) => {
  const attachment = {
    id: ATTACHMENT_ID, ownerType: "ADDENDUM", ownerId: ADDENDUM_ID,
    fileName: "ready.pdf", contentType: "application/pdf", sizeBytes: 1024,
    uploadedAt: "2026-09-04T08:00:00Z",
  };
  let releaseSubmit!: () => void;
  const submitGate = new Promise<void>((resolve) => { releaseSubmit = resolve; });
  let submitted = false;
  let submitRequests = 0;
  let uploadRequests = 0;
  let deleteRequests = 0;

  await installAddendumMocks(page, async (request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) {
      if (submitted) return { status: 503, body: envelope({ message: "Refresh unavailable" }) };
      return { body: envelope({ ...addendum, status: submitted ? "SUBMITTED" : "DRAFT" }) };
    }
    if (url.pathname === "/api/v1/attachments" && request.method() === "GET") {
      return { body: envelope([attachment]) };
    }
    if (url.pathname === "/api/v1/attachments" && request.method() === "POST") {
      uploadRequests += 1;
      return { status: 201, body: envelope(attachment) };
    }
    if (url.pathname === `/api/v1/attachments/${ATTACHMENT_ID}` && request.method() === "DELETE") {
      deleteRequests += 1;
      return { body: envelope(null) };
    }
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/submit` && request.method() === "POST") {
      submitRequests += 1;
      await submitGate;
      submitted = true;
      return { body: envelope({ status: "SUBMITTED", dispatchPending: true }) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  const fileInput = page.getByLabel("Choose attachment file");
  await fileInput.setInputFiles({
    name: "replacement.pdf", mimeType: "application/pdf", buffer: Buffer.from("replacement"),
  });
  const upload = page.getByRole("button", { name: "Upload attachment", exact: true });
  const remove = page.getByRole("button", { name: "Delete", exact: true });
  await expect(upload).toBeEnabled();
  await expect(remove).toBeEnabled();

  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect.poll(() => submitRequests).toBe(1);
  await expect(fileInput).toBeDisabled();
  await expect(upload).toBeDisabled();
  await expect(remove).toBeDisabled();
  await upload.click({ force: true });
  await remove.click({ force: true });
  expect(uploadRequests).toBe(0);
  expect(deleteRequests).toBe(0);

  releaseSubmit();
  await expect(page.getByText("Under Review").first()).toBeVisible();
  await expect(page.getByText("The addendum was updated, but its latest refresh failed. The confirmed update is still shown.")).toBeVisible();
});

test("keeps locked addendum attachments downloadable but not editable", async ({ page }) => {
  const approved = { ...addendum, status: "APPROVED" };
  const attachment = {
    id: ATTACHMENT_ID, ownerType: "ADDENDUM", ownerId: approved.id,
    fileName: "approved.pdf", contentType: "application/pdf", sizeBytes: 1024,
    uploadedAt: "2026-09-04T08:00:00Z",
  };
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(approved) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([attachment]) };
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  const download = page.getByRole("button", { name: "Download" });
  await expect(download).toBeVisible();
  await page.evaluate(() => {
    const trackedWindow = window as Window & { openedAttachmentUrls: string[] };
    trackedWindow.openedAttachmentUrls = [];
    window.open = ((url?: string | URL) => {
      trackedWindow.openedAttachmentUrls.push(String(url));
      return null;
    }) as typeof window.open;
  });
  await download.click();
  const openedUrls = await page.evaluate(() =>
    (window as Window & { openedAttachmentUrls: string[] }).openedAttachmentUrls,
  );
  expect(openedUrls).toEqual([`/api/v1/attachments/${ATTACHMENT_ID}`]);
  await expect(page.getByRole("button", { name: "Delete" })).toHaveCount(0);
  await expect(page.getByLabel("Choose attachment file")).toHaveCount(0);
  await expect(page.getByText("Attachments cannot be changed in this status.")).toBeVisible();
});

test("shows approval progress and status history for an addendum under review", async ({ page }) => {
  const underReview = { ...addendum, status: "UNDER_REVIEW" };
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(underReview) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/progress`) return { body: envelope({
      ...emptyProgress,
      documentStatus: "UNDER_REVIEW",
      workflowState: "IN_PROGRESS",
      instanceId: "40000000-0000-4000-8000-000000000001",
      requestedByName: "Nora Requester",
      startedAt: "2026-09-04T08:00:00Z",
      currentStep: {
        stepNo: 2, name: "Finance review", approverRole: "FINANCE_MANAGER", status: "ACTIVE",
        assigneeNames: ["Alex Approver"], action: null, activatedAt: "2026-09-04T09:00:00Z",
        slaHours: 24, overdue: false,
      },
      steps: [
        {
          stepNo: 1, name: "Commercial review", approverRole: "COMMERCIAL_MANAGER", status: "APPROVED",
          assigneeNames: ["Mina Manager"], activatedAt: "2026-09-04T08:00:00Z", slaHours: 24, overdue: false,
          action: { actorName: "Mina Manager", actionedAt: "2026-09-04T08:30:00Z", comment: "Looks good (CTR-07)", action: "APPROVED" },
        },
        {
          stepNo: 2, name: "Finance review", approverRole: "FINANCE_MANAGER", status: "ACTIVE",
          assigneeNames: ["Alex Approver"], action: null, activatedAt: "2026-09-04T09:00:00Z", slaHours: 24, overdue: false,
        },
      ],
    }) };
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/history`) return { body: envelope([{
      id: "50000000-0000-4000-8000-000000000001",
      fromStatus: "SUBMITTED", toStatus: "UNDER_REVIEW", trigger: "W",
      triggerRef: "60000000-0000-4000-8000-000000000001",
      actorId: null, actorName: "Workflow",
      note: "Contract CTR-2026-0001 uses tier A1 (CTR-05, D14d)",
      occurredAt: "2026-09-04T09:00:00Z",
    }]) };
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await expect(page.getByText("Waiting on Finance review — Assignee: Alex Approver")).toBeVisible();
  await expect(page.getByText(/Approved by Mina Manager/)).toBeVisible();
  await expect(page.getByText(/Commercial manager/)).toBeVisible();
  await expect(page.getByText(/Finance manager/)).toBeVisible();
  await expect(page.getByText("Submitted → Under review")).toBeVisible();
  await expect(page.locator("span.text-muted-foreground").filter({ hasText: /^Approval workflow$/ })).toBeVisible();
  await expect(page.getByText("Contract CTR-2026-0001 uses tier A1")).toBeVisible();
  for (const technicalValue of ["FINANCE_MANAGER", "COMMERCIAL_MANAGER", "APPROVED", "SUBMITTED → UNDER_REVIEW", "CTR-05", "D14d", "CTR-07", "60000000-0000-4000-8000-000000000001"]) {
    await expect(page.locator("body")).not.toContainText(technicalValue);
  }
  await expect(page.getByText("W", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Cancel", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Edit", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Revise", exact: true })).toHaveCount(0);
});

test("revises a rejected addendum and refreshes every detail panel", async ({ page }) => {
  let current = { ...addendum, status: "REJECTED", version: 4 };
  let progressRequests = 0;
  let historyRequests = 0;
  let attachmentRequests = 0;
  let reviseRequests = 0;
  await installAddendumMocks(page, (request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}` && request.method() === "GET") return { body: envelope(current) };
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/revise` && request.method() === "POST") {
      reviseRequests += 1;
      current = { ...current, status: "DRAFT", version: 5 };
      return { body: envelope(current) };
    }
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/progress`) {
      progressRequests += 1;
      return { body: envelope(current.status === "REJECTED" ? {
        ...emptyProgress,
        documentStatus: "REJECTED",
        workflowState: "REJECTED",
        instanceId: "40000000-0000-4000-8000-000000000002",
        steps: [{
          stepNo: 1, name: "Finance review", approverRole: "FINANCE_MANAGER", status: "REJECTED",
          assigneeNames: ["Alex Approver"], activatedAt: "2026-09-04T09:00:00Z", slaHours: 24, overdue: false,
          action: { actorName: "Alex Approver", actionedAt: "2026-09-04T09:30:00Z", comment: "Correct the term", action: "REJECTED" },
        }],
      } : { ...emptyProgress, documentStatus: current.status }) };
    }
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/history`) {
      historyRequests += 1;
      return { body: envelope(current.status === "REJECTED" ? [{
        id: "50000000-0000-4000-8000-000000000002",
        fromStatus: "UNDER_REVIEW", toStatus: "REJECTED", trigger: "W", triggerRef: null,
        actorId: null, actorName: "Alex Approver", note: "Correct the term (CTR-04)", occurredAt: "2026-09-04T09:30:00Z",
      }] : []) };
    }
    if (url.pathname === "/api/v1/attachments") {
      attachmentRequests += 1;
      return { body: envelope([]) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await expect(page.getByRole("button", { name: "Revise", exact: true })).toBeVisible();
  await expect(page.getByText(/Rejected by Alex Approver/)).toBeVisible();
  await expect(page.getByText("Under review → Rejected")).toBeVisible();
  await expect(page.getByText("CTR-04", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Revise", exact: true }).click();

  await expect(page.getByRole("button", { name: "Edit", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Submit for approval" })).toBeVisible();
  expect(reviseRequests).toBe(1);
  await expect.poll(() => progressRequests).toBeGreaterThan(1);
  await expect.poll(() => historyRequests).toBeGreaterThan(1);
  await expect.poll(() => attachmentRequests).toBeGreaterThan(1);
});

test("edits a revision-requested addendum and preserves optimistic-lock data", async ({ page }) => {
  let current = { ...addendum, status: "REVISION_REQUESTED", version: 7 };
  let updateBody: Record<string, unknown> | undefined;
  let historyRequests = 0;
  await installAddendumMocks(page, async (request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}` && request.method() === "GET") return { body: envelope(current) };
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}` && request.method() === "PUT") {
      updateBody = await request.postDataJSON();
      current = { ...current, description: String(updateBody?.description), status: "DRAFT", version: 8 };
      return { body: envelope(current) };
    }
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/history`) {
      historyRequests += 1;
      return { body: envelope([]) };
    }
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: `Edit ${addendum.addendumNo}` });
  await dialog.getByLabel("Description").fill("Updated after reviewer feedback");
  await dialog.getByLabel("New valid to *").fill("2028-09-30");
  await dialog.getByRole("button", { name: "Save changes" }).click();

  await expect(dialog).toHaveCount(0);
  await expect(page.getByText("Updated after reviewer feedback")).toBeVisible();
  expect(updateBody).toMatchObject({
    contractId: CONTRACT_ID,
    description: "Updated after reviewer feedback",
    newValidTo: "2028-09-30",
    version: 7,
  });
  await expect.poll(() => historyRequests).toBeGreaterThan(1);
});

test("gives repeated edit-service actions distinct accessible names", async ({ page }) => {
  const serviceAddendum = {
    ...addendum,
    status: "REVISION_REQUESTED",
    changeType: "ADDED_SERVICE",
    services: [
      { id: "61000000-0000-4000-8000-000000000001", serviceCode: "SEA", serviceName: "Sea freight", unit: "trip", scopeNote: null },
      { id: "61000000-0000-4000-8000-000000000002", serviceCode: "AIR", serviceName: "Air freight", unit: "kg", scopeNote: null },
    ],
  };
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(serviceAddendum) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: `Edit ${addendum.addendumNo}` });
  await expect(dialog.getByRole("button", { name: "Remove service 1" })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Remove service 2" })).toBeVisible();
  const accessibility = await new AxeBuilder({ page }).include('[role="dialog"]').analyze();
  expect(accessibility.violations).toEqual([]);
});

test("cancels a pre-submission addendum without showing submit guidance", async ({ page }) => {
  let current = { ...addendum, status: "DRAFT", version: 3 };
  let cancelBody: Record<string, unknown> | undefined;
  let historyRequests = 0;
  await installAddendumMocks(page, async (request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}` && request.method() === "GET") return { body: envelope(current) };
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/cancel` && request.method() === "POST") {
      cancelBody = await request.postDataJSON();
      current = { ...current, status: "CANCELLED", version: 4 };
      return { body: envelope({ status: "CANCELLED", detail: null }) };
    }
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/history`) {
      historyRequests += 1;
      return { body: envelope([]) };
    }
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/progress`) {
      return { body: envelope({ ...emptyProgress, documentStatus: current.status, workflowState: current.status }) };
    }
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Cancel this addendum?" });
  await dialog.getByLabel("Reason (optional)").fill("Customer withdrew the request");
  await dialog.getByRole("button", { name: "Cancel addendum" }).click();

  await expect(dialog).toHaveCount(0);
  await expect(page.getByText("Cancelled").first()).toBeVisible();
  await expect(page.getByText("This document is cancelled. No approval steps are pending.")).toBeVisible();
  await expect(page.getByText("Submit the document to begin approval.")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Cancel", exact: true })).toHaveCount(0);
  expect(cancelBody).toEqual({ reason: "Customer withdrew the request" });
  await expect.poll(() => historyRequests).toBeGreaterThan(1);
});

test("keeps a pending cancellation actionable and explains that it must be retried", async ({ page }) => {
  const underReview = { ...addendum, status: "UNDER_REVIEW" };
  await installAddendumMocks(page, (request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}` && request.method() === "GET") return { body: envelope(underReview) };
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/cancel` && request.method() === "POST") {
      return { status: 202, body: envelope({
        status: "PENDING",
        detail: "A workflow dispatch is still in flight; the addendum keeps its current status. Retry this call.",
      }) };
    }
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await page.getByRole("dialog", { name: "Cancel this addendum?" })
    .getByRole("button", { name: "Cancel addendum" }).click();

  await expect(page.getByText(/addendum keeps its current status.*Retry this call/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Cancel", exact: true })).toBeEnabled();
  await expect(page.getByText("Under Review").first()).toBeVisible();
});

test("shows a completed approval and keeps an approved addendum read-only", async ({ page }) => {
  const approved = { ...addendum, status: "APPROVED" };
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(approved) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/progress`) return { body: envelope({
      ...emptyProgress,
      documentStatus: "APPROVED",
      workflowState: "APPROVED",
      instanceId: "40000000-0000-4000-8000-000000000003",
      steps: [{
        stepNo: 1, name: "Finance review", approverRole: "FINANCE_MANAGER", status: "APPROVED",
        assigneeNames: ["Alex Approver"], activatedAt: "2026-09-04T09:00:00Z", slaHours: 24, overdue: false,
        action: { actorName: "Alex Approver", actionedAt: "2026-09-04T09:30:00Z", comment: "Approved", action: "APPROVED" },
      }],
    }) };
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/history`) return { body: envelope([{
      id: "50000000-0000-4000-8000-000000000003",
      fromStatus: "UNDER_REVIEW", toStatus: "APPROVED", trigger: "W", triggerRef: null,
      actorId: null, actorName: "Alex Approver", note: "Approved", occurredAt: "2026-09-04T09:30:00Z",
    }]) };
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await expect(page.getByText(/Approved by Alex Approver/)).toBeVisible();
  await expect(page.getByText("Under review → Approved")).toBeVisible();
  for (const action of ["Edit", "Submit for approval", "Revise", "Cancel"]) {
    await expect(page.getByRole("button", { name: action, exact: true })).toHaveCount(0);
  }
});

test("uses addendum write rather than the contract active-cancellation permission", async ({ page }) => {
  const active = { ...addendum, status: "ACTIVE" };
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(active) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read", "contract:cancel_active"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await expect(page.getByRole("button", { name: "Cancel", exact: true })).toHaveCount(0);

  await page.unroute("**/api/v1/**");
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(active) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });
  await page.reload();
  await expect(page.getByRole("button", { name: "Cancel", exact: true })).toBeVisible();
});

test("treats an expired document as having completed approval", async ({ page }) => {
  const expired = { ...addendum, status: "EXPIRED" };
  await installAddendumMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(expired) };
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/progress`) return { body: envelope({
      ...emptyProgress, documentStatus: "EXPIRED", workflowState: "EXPIRED",
    }) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await expect(page.getByText("Approval is complete. No approval step details are available.")).toBeVisible();
  await expect(page.getByText("Submit the document to begin approval.")).toHaveCount(0);
});

test("queues an approved addendum for signing without changing approval status", async ({ page }) => {
  const approved = { ...addendum, status: "APPROVED" };
  let sendRequests = 0;
  let detailRequests = 0;
  let requestQueued = false;
  let stateReads = 0;
  await installAddendumMocks(page, async (request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}` && request.method() === "GET") {
      detailRequests += 1;
      return { body: envelope(approved) };
    }
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/send-for-signing` && request.method() === "POST") {
      sendRequests += 1;
      requestQueued = true;
      return { status: 202, body: envelope({ canSendForSigning: false, requestQueued: true, sessionId: null }) };
    }
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/signing-request`) {
      stateReads += 1;
      const staleSnapshot = requestQueued;
      if (stateReads === 2) await new Promise((resolve) => setTimeout(resolve, 500));
      return { body: envelope({ canSendForSigning: !staleSnapshot, requestQueued: staleSnapshot, sessionId: null }) };
    }
    if (url.pathname === `/api/v1/signing-sessions/by-document/ADDENDUM/${ADDENDUM_ID}`) return { body: envelope([]) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write", "esign:send"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await expect(page.getByRole("button", { name: "Send for signing" })).toBeVisible();
  await expect.poll(() => stateReads, { timeout: 10_000 }).toBeGreaterThanOrEqual(2);
  await page.getByRole("button", { name: "Send for signing" }).click();
  await expect(page.getByText("Signature request queued. Signing status will appear shortly.")).toBeVisible();
  await page.waitForTimeout(600);
  await expect(page.getByText("Signature request queued. Signing status will appear shortly.")).toBeVisible();
  await expect(page.getByText("Approved").first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Send for signing" })).toHaveCount(0);
  expect(sendRequests).toBe(1);
  expect(detailRequests).toBe(1);

  await page.reload();
  await expect(page.getByText("Signature request queued. Signing status will appear shortly.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Send for signing" })).toHaveCount(0);
  expect(sendRequests).toBe(1);
});

test("does not carry queued signing state to another addendum route", async ({ page }) => {
  const secondId = "10000000-0000-4000-8000-000000000002";
  const approved = { ...addendum, status: "APPROVED" };
  const second = { ...approved, id: secondId, addendumNo: "ADD-2026-0002" };
  let firstQueued = false;
  await installAddendumMocks(page, (request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(approved) };
    if (url.pathname === `/api/v1/addenda/${secondId}`) return { body: envelope(second) };
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/send-for-signing`
        && request.method() === "POST") {
      firstQueued = true;
      return { status: 202, body: envelope({ canSendForSigning: false, requestQueued: true, sessionId: null }) };
    }
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/signing-request`) return { body: envelope({ canSendForSigning: !firstQueued, requestQueued: firstQueued, sessionId: null }) };
    if (url.pathname === `/api/v1/addenda/${secondId}/signing-request`) return { body: envelope({ canSendForSigning: true, requestQueued: false, sessionId: null }) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read", "esign:send"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await page.getByRole("button", { name: "Send for signing" }).click();
  await expect(page.getByText("Signature request queued. Signing status will appear shortly.")).toBeVisible();

  await page.evaluate((id) => {
    window.history.pushState({}, "", `/addenda?id=${id}`);
    window.dispatchEvent(new PopStateEvent("popstate"));
  }, secondId);
  await expect(page.getByRole("heading", { name: second.addendumNo })).toBeVisible();
  await expect(page.getByText("Signature request queued. Signing status will appear shortly.")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Send for signing" })).toBeVisible();
});

test("shows every addendum signing outcome to a read-only user without offering send", async ({ page }) => {
  const approved = { ...addendum, status: "APPROVED" };
  let signingStatus = "SIGNING";
  const session = {
    id: "70000000-0000-4000-8000-000000000001", sessionNo: "SIG-101",
    documentTypeCode: "ADDENDUM", documentId: ADDENDUM_ID, documentNo: approved.addendumNo,
    customerName: "ACME Logistics", signerName: "Tran Thi B", signerEmail: "signer@acme.vn",
    provider: "MOCK", providerRef: "provider-101", status: "SIGNING", attempts: 1,
    lastError: null, requestedByName: "Nguyen Thi Lan", sentAt: "2026-09-04T10:00:00Z",
    completedAt: null, createdAt: "2026-09-04T09:59:00Z",
  };
  let sendRequests = 0;
  await installAddendumMocks(page, (request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(approved) };
    if (url.pathname === `/api/v1/signing-sessions/by-document/ADDENDUM/${ADDENDUM_ID}`) return { body: envelope([{ ...session, status: signingStatus }]) };
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/send-for-signing` && request.method() === "POST") sendRequests += 1;
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await expect(page.getByText("SIG-101")).toBeVisible();
  await expect(page.getByText("Signing", { exact: true })).toBeVisible();
  await expect(page.getByText(/Tran Thi B.*signer@acme.vn/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Send for signing" })).toHaveCount(0);
  expect(sendRequests).toBe(0);

  for (const [status, label] of [["PENDING_SEND", "Queued"], ["SIGNED", "Signed"], ["FAILED", "Failed"], ["CANCELLED", "Cancelled"]]) {
    signingStatus = status;
    await page.reload();
    await expect(page.getByText(label, { exact: true })).toBeVisible();
    await expect(page.getByText("Approved", { exact: true }).first()).toBeVisible();
    if (status === "FAILED") {
      await expect(page.getByText("The signing request failed. Please retry or contact support.")).toBeVisible();
    }
  }
});

test("renders a signed contract session separately from contract approval", async ({ page }) => {
  const contract = {
    id: CONTRACT_ID, contractNo: "CTR-2026-0001", customerId: "80000000-0000-4000-8000-000000000001",
    customerName: "ACME Logistics", description: "Annual transport", serviceGroup: "TRANSPORTATION",
    value: 1000000, currency: "VND", validFrom: "2026-01-01", validTo: "2026-12-31",
    paymentTerm: "NET30", billingCycle: "MONTHLY", vatRate: 10, penaltyTerms: null,
    serviceClause: null, status: "APPROVED", editable: false, version: 2,
    createdAt: "2026-01-01T00:00:00Z", createdByName: "Nguyen Thi Lan", updatedAt: "2026-09-04T10:00:00Z",
  };
  const signed = {
    id: "70000000-0000-4000-8000-000000000002", sessionNo: "SIG-102",
    documentTypeCode: "CONTRACT", documentId: CONTRACT_ID, documentNo: contract.contractNo,
    customerName: contract.customerName, signerName: "Tran Thi B", signerEmail: "signer@acme.vn",
    provider: "MOCK", providerRef: "provider-102", status: "SIGNED", attempts: 1,
    lastError: null, requestedByName: "Nguyen Thi Lan", sentAt: "2026-09-04T10:00:00Z",
    completedAt: "2026-09-04T10:05:00Z", createdAt: "2026-09-04T09:59:00Z",
  };
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}`) return { body: envelope(contract) };
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}/progress`) return { body: envelope({ ...emptyProgress, documentStatus: "APPROVED", workflowState: "APPROVED" }) };
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}/history`) return { body: envelope([]) };
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}/signing-request`) return { body: envelope({ canSendForSigning: false, requestQueued: false, sessionId: null }) };
    if (url.pathname === "/api/v1/addenda") return { body: envelope([], { page: 0, size: 15, totalElements: 0, totalPages: 0 }) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
    if (url.pathname === `/api/v1/customers/${contract.customerId}`) return { status: 404, body: envelope({ message: "Not needed" }) };
    if (url.pathname === `/api/v1/signing-sessions/by-document/CONTRACT/${CONTRACT_ID}`) return { body: envelope([signed]) };
  });

  await page.goto(`/contracts?id=${CONTRACT_ID}`);
  await expect(page.getByRole("heading", { name: contract.contractNo })).toBeVisible();
  await expect(page.getByText("SIG-102")).toBeVisible();
  await expect(page.getByText("Signed", { exact: true })).toBeVisible();
  await expect(page.getByText("Approved", { exact: true }).first()).toBeVisible();
});
