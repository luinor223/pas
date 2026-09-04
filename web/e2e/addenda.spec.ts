import { expect, test } from "@playwright/test";
import { currentUser, envelope, installApiMocks } from "./support/api";

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

test("opens an addendum detail link and restores it after refresh and browser Back", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
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
  await installApiMocks(page, (_request, url) => {
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

test("shows read-only detail access without write permission", async ({ page }) => {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(addendum) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await expect(page.getByText("Read-only access")).toBeVisible();
  await expect(page.getByRole("heading", { name: addendum.addendumNo })).toBeVisible();
});

test("does not request detail data without addendum read permission", async ({ page }) => {
  let detailRequests = 0;
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) detailRequests += 1;
  });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await expect(page.getByText("You do not have access to addenda.")).toBeVisible();
  expect(detailRequests).toBe(0);
});

test("shows a not-found state for a missing addendum", async ({ page }) => {
  const missingId = "00000000-0000-4000-8000-000000000099";
  const requestedPaths: string[] = [];
  await installApiMocks(page, (_request, url) => {
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
  await installApiMocks(page, async (request, url) => {
    if (url.pathname === "/api/v1/addenda" && request.method() === "GET") {
      return { body: envelope([], { page: 0, size: 15, totalElements: 0, totalPages: 0 }) };
    }
    if (url.pathname === "/api/v1/contracts") {
      return { body: envelope([{ id: CONTRACT_ID, contractNo: "CTR-2026-0001", status: "APPROVED" }], { page: 0, size: 100, totalElements: 1, totalPages: 1 }) };
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
  await expect(dialog.locator("select").first()).toHaveValue(CONTRACT_ID);
  await expect(dialog.locator("select").nth(1)).toHaveValue("TERM_EXTENSION");
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

test("blocks draft submission until an attachment exists", async ({ page }) => {
  let submitRequests = 0;
  await installApiMocks(page, (request, url) => {
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

  await installApiMocks(page, async (request, url) => {
    const method = request.method();
    if (url.pathname === "/api/v1/addenda" && method === "GET") {
      return { body: envelope([], { page: 0, size: 15, totalElements: 0, totalPages: 0 }) };
    }
    if (url.pathname === "/api/v1/contracts") {
      return { body: envelope([{ id: CONTRACT_ID, contractNo: "CTR-2026-0001", status: "APPROVED" }], { page: 0, size: 100, totalElements: 1, totalPages: 1 }) };
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
  await dialog.locator("select").first().selectOption(CONTRACT_ID);
  await dialog.getByRole("button", { name: "Create", exact: true }).click();

  await expect(page.getByRole("heading", { name: created.addendumNo })).toBeVisible();
  await page.getByLabel("Choose attachment file").setInputFiles({
    name: attachment.fileName,
    mimeType: attachment.contentType,
    buffer: Buffer.from("pdf contents"),
  });
  await page.getByRole("button", { name: "Upload", exact: true }).click();
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

test("disables submission immediately after deleting the final attachment", async ({ page }) => {
  const attachment = {
    id: ATTACHMENT_ID, ownerType: "ADDENDUM", ownerId: ADDENDUM_ID,
    fileName: "only-copy.pdf", contentType: "application/pdf", sizeBytes: 1024,
    uploadedAt: "2026-09-04T08:00:00Z",
  };
  let deleted = false;
  let submitRequests = 0;
  await installApiMocks(page, async (request, url) => {
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}`) return { body: envelope(addendum) };
    if (url.pathname === "/api/v1/attachments" && request.method() === "GET") {
      if (deleted) await new Promise((resolve) => setTimeout(resolve, 500));
      return { body: envelope(deleted ? [] : [attachment]) };
    }
    if (url.pathname === `/api/v1/attachments/${ATTACHMENT_ID}` && request.method() === "DELETE") {
      deleted = true;
      return { body: envelope(null) };
    }
    if (url.pathname === `/api/v1/addenda/${ADDENDUM_ID}/submit` && request.method() === "POST") {
      submitRequests += 1;
      return { body: envelope({ status: "SUBMITTED", dispatchPending: true }) };
    }
  }, { permissions: [...currentUser.permissions, "addendum:read", "addendum:write"] });

  await page.goto(`/addenda?id=${ADDENDUM_ID}`);
  await expect(page.getByRole("button", { name: "Submit for approval" })).toBeEnabled();
  await page.getByRole("button", { name: "Delete", exact: true }).click();
  await page.getByRole("dialog", { name: "Delete this attachment?" }).getByRole("button", { name: "Delete file" }).click();

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

  await installApiMocks(page, async (request, url) => {
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
  const upload = page.getByRole("button", { name: "Upload", exact: true });
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
  await installApiMocks(page, (_request, url) => {
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
