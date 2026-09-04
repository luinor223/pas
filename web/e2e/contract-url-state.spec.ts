import { expect, test, type Page } from "@playwright/test";
import { currentUser, envelope, installApiMocks } from "./support/api";

const CUSTOMER_ID = "40000000-0000-4000-8000-000000000001";
const CONTRACT_ID = "50000000-0000-4000-8000-000000000001";
const ADDENDUM_ID = "60000000-0000-4000-8000-000000000001";

const customer = {
  id: CUSTOMER_ID, code: "CUS-URL", name: "URL Logistics", shortName: null, taxCode: null,
  address: null, representativeName: null, representativePosition: null, segment: null,
  status: "ACTIVE", contacts: [], primaryContact: null, contractsCount: 16,
  createdAt: "2026-01-01T00:00:00Z", createdByName: "Sales User", updatedAt: "2026-01-01T00:00:00Z",
};

const contract = {
  id: CONTRACT_ID, contractNo: "CTR-URL", customerId: CUSTOMER_ID, customerName: customer.name,
  description: "URL state contract", serviceGroup: "TRANSPORTATION", value: 1_000_000,
  currency: "VND", validFrom: "2026-01-01", validTo: "2026-12-31", paymentTerm: "NET30",
  billingCycle: "MONTHLY", vatRate: 10, penaltyTerms: null, serviceClause: null, status: "ACTIVE",
  editable: false, canEdit: false, canSubmit: false, submitBlockedReason: null, canRevise: false,
  canCancel: false, canCreateAddendum: true, version: 0, createdAt: "2026-01-01T00:00:00Z",
  createdByName: "Sales User", updatedAt: "2026-01-01T00:00:00Z",
};

const addendum = {
  id: ADDENDUM_ID, addendumNo: "ADD-URL", contractId: CONTRACT_ID, contractNo: contract.contractNo,
  changeType: "TERM_EXTENSION", description: "URL state addendum", effectiveFrom: "2026-06-01",
  newValidTo: "2027-06-30", paymentTermOverride: null, status: "ACTIVE", canEdit: false,
  canSubmit: false, submitBlockedReason: null, canRevise: false, canCancel: true, services: [], version: 0,
};

const pageMeta = (page: number, cursor = "url-snapshot") => ({
  page, size: 15, totalElements: 16, totalPages: 2, cursor,
});

async function installUrlMocks(page: Page, requests: Array<{ path: string; params: URLSearchParams }>) {
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/customers") {
      requests.push({ path: "customers", params: new URLSearchParams(url.search) });
      return { body: envelope([customer], pageMeta(Number(url.searchParams.get("page") ?? 0))) };
    }
    if (url.pathname === "/api/v1/contracts") {
      requests.push({ path: "contracts", params: new URLSearchParams(url.search) });
      const size = Number(url.searchParams.get("size") ?? 15);
      return { body: envelope([contract], { ...pageMeta(Number(url.searchParams.get("page") ?? 0)), size }) };
    }
    if (url.pathname === "/api/v1/addenda") {
      requests.push({ path: "addenda", params: new URLSearchParams(url.search) });
      return { body: envelope([addendum], pageMeta(Number(url.searchParams.get("page") ?? 0))) };
    }
    if (url.pathname === `/api/v1/customers/${CUSTOMER_ID}`) return { body: envelope(customer) };
    if (url.pathname === `/api/v1/customers/${CUSTOMER_ID}/metrics`) {
      return { body: envelope({ activeContracts: 1, approvedContractValues: [{ currency: "VND", value: "1000000" }] }) };
    }
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}`) return { body: envelope(contract) };
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}/progress`) {
      return { body: envelope({ documentStatus: "ACTIVE", workflowState: "ACTIVE", steps: [] }) };
    }
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}/history`) return { body: envelope([]) };
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}/signing-request`) {
      return { body: envelope({ canSendForSigning: false, requestQueued: false, sessionId: null }) };
    }
    if (url.pathname === `/api/v1/signing-sessions/by-document/CONTRACT/${CONTRACT_ID}`) return { body: envelope([]) };
  }, { permissions: [...currentUser.permissions, "addendum:read"] });
}

test("shows the rejection reason prominently on contract details", async ({ page }) => {
  const rejectedContract = {
    ...contract,
    status: "REJECTED",
    canEdit: false,
    canSubmit: false,
    canRevise: true,
    canCancel: false,
    canCreateAddendum: false,
  };
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}`) return { body: envelope(rejectedContract) };
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}/progress`) {
      return { body: envelope({
        documentStatus: "REJECTED",
        workflowState: "REJECTED",
        currentStep: null,
        steps: [{
          stepNo: 2,
          name: "Legal review",
          approverRole: "LEGAL_MANAGER",
          status: "REJECTED",
          assigneeNames: ["Legal Reviewer"],
          action: {
            actorName: "Legal Reviewer",
            actionedAt: "2026-09-04T10:30:00Z",
            comment: "The liability clause is incomplete (CTR-04)",
            action: "REJECT",
          },
          activatedAt: "2026-09-04T10:00:00Z",
          slaHours: 24,
          overdue: false,
        }],
      }) };
    }
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}/history`) return { body: envelope([]) };
    if (url.pathname === `/api/v1/customers/${CUSTOMER_ID}`) return { body: envelope(customer) };
    if (url.pathname === "/api/v1/addenda") {
      return { body: envelope([], { page: 0, size: 15, totalElements: 0, totalPages: 0 }) };
    }
    if (url.pathname === "/api/v1/attachments") return { body: envelope([]) };
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}/signing-request`) {
      return { body: envelope({ canSendForSigning: false, requestQueued: false, sessionId: null }) };
    }
    if (url.pathname === `/api/v1/signing-sessions/by-document/CONTRACT/${CONTRACT_ID}`) {
      return { body: envelope([]) };
    }
  });

  await page.goto(`/contracts?id=${CONTRACT_ID}`);

  const reason = page.getByRole("note", { name: "Rejection reason" });
  await expect(reason).toContainText("The liability clause is incomplete");
  await expect(reason).toContainText("Legal Reviewer");
  await expect(reason).not.toContainText("CTR-04");
});

test("list filters, text search, pages, and snapshot cursors survive refresh and history", async ({ page }) => {
  const requests: Array<{ path: string; params: URLSearchParams }> = [];
  await installUrlMocks(page, requests);

  for (const config of [
    { path: "customers", label: "Search customers" },
    { path: "contracts", label: "Search contracts" },
    { path: "addenda", label: "Search addenda" },
  ]) {
    await page.goto(`/${config.path}`);
    await page.getByLabel("Filter by status").selectOption("ACTIVE");
    await page.getByRole("searchbox", { name: config.label }).fill("URL-state");
    await expect.poll(() => new URL(page.url()).searchParams.get("q")).toBe("URL-state");
    await page.getByRole("button", { name: "Next page" }).click();
    await expect.poll(() => new URL(page.url()).searchParams.get("page")).toBe("1");
    await expect.poll(() => new URL(page.url()).searchParams.get("cursor")).toBe("url-snapshot");

    await page.reload();
    await expect(page.getByLabel("Filter by status")).toHaveValue("ACTIVE");
    await expect(page.getByRole("searchbox", { name: config.label })).toHaveValue("URL-state");
    await expect(page.getByText("Page 2 of 2")).toBeVisible();
    await expect.poll(() => {
      const latest = requests.filter((request) => request.path === config.path).at(-1)?.params;
      return [latest?.get("q"), latest?.get("status"), latest?.get("page"), latest?.get("cursor")];
    }).toEqual(["URL-state", "ACTIVE", "1", "url-snapshot"]);

    await page.goBack();
    await expect.poll(() => new URL(page.url()).searchParams.get("page")).toBeNull();
    await expect(page.getByRole("searchbox", { name: config.label })).toHaveValue("URL-state");
    await page.goForward();
    await expect.poll(() => new URL(page.url()).searchParams.get("page")).toBe("1");

    const searchbox = page.getByRole("searchbox", { name: config.label });
    await searchbox.fill("URL-updated");
    await expect.poll(() => new URL(page.url()).searchParams.get("q")).toBe("URL-updated");
    await page.goBack();
    await expect.poll(() => new URL(page.url()).searchParams.get("q")).toBe("URL-state");
    await expect(searchbox).toHaveValue("URL-state");
    await page.goForward();
    await expect.poll(() => new URL(page.url()).searchParams.get("q")).toBe("URL-updated");
    await expect(searchbox).toHaveValue("URL-updated");

    await page.getByRole("button", { name: `Clear ${config.label.toLowerCase()}` }).click();
    await expect.poll(() => new URL(page.url()).searchParams.get("q")).toBeNull();
    await page.goBack();
    await expect.poll(() => new URL(page.url()).searchParams.get("q")).toBe("URL-updated");
    await expect(searchbox).toHaveValue("URL-updated");
    await page.goForward();
    await expect.poll(() => new URL(page.url()).searchParams.get("q")).toBeNull();
    await expect(searchbox).toHaveValue("");

    await searchbox.fill("flush-on-filter");
    await page.getByLabel("Filter by status").selectOption("");
    await expect.poll(() => new URL(page.url()).searchParams.get("q")).toBe("flush-on-filter");
    await expect(searchbox).toHaveValue("flush-on-filter");
    await page.getByRole("button", { name: `Clear ${config.label.toLowerCase()}` }).click();
    await expect.poll(() => new URL(page.url()).searchParams.get("q")).toBeNull();

    await page.getByRole("button", { name: "Next page" }).click();
    await expect.poll(() => new URL(page.url()).searchParams.get("page")).toBe("1");
    await searchbox.fill("must-not-commit");
    await page.goBack();
    await page.waitForTimeout(400);
    await expect.poll(() => new URL(page.url()).searchParams.get("q")).toBeNull();
    await expect(searchbox).toHaveValue("");
    await page.goForward();
    await page.waitForTimeout(400);
    await expect.poll(() => new URL(page.url()).searchParams.get("q")).toBeNull();
    await expect(searchbox).toHaveValue("");
  }
});

test("invalid contract-list query values fall back safely and an out-of-range page recovers", async ({ page }) => {
  const requests: Array<{ path: string; params: URLSearchParams }> = [];
  await installApiMocks(page, (_request, url) => {
    if (url.pathname === "/api/v1/contracts") {
      requests.push({ path: "contracts", params: new URLSearchParams(url.search) });
      const requestedPage = Number(url.searchParams.get("page") ?? 0);
      return requestedPage > 0
        ? { body: envelope([], { page: requestedPage, size: 15, totalElements: 0, totalPages: 0, cursor: "bad" }) }
        : { body: envelope([contract], { page: 0, size: 15, totalElements: 1, totalPages: 1, cursor: "fresh" }) };
    }
  });

  await page.goto("/contracts?status=UNKNOWN&serviceGroup=INVALID&validFromFrom=2026-99-99&page=-2&q=%20");
  await expect(page.getByLabel("Filter by status")).toHaveValue("");
  await expect(page.getByLabel("Filter by service group")).toHaveValue("");
  await expect(page.getByLabel("Search contracts")).toHaveValue("");
  await expect.poll(() => {
    const latest = requests.at(-1)?.params;
    return [latest?.get("status"), latest?.get("serviceGroup"), latest?.get("validFromFrom"), latest?.get("page")];
  }).toEqual([null, null, null, "0"]);

  await page.goto("/contracts?page=4&cursor=expired-snapshot");
  await expect(page).not.toHaveURL(/(?:page|cursor)=/);
  await expect(page.getByText(contract.contractNo)).toBeVisible();
  expect(requests.some(({ params }) => params.get("page") === "4" && params.get("cursor") === "expired-snapshot")).toBe(true);
  await expect.poll(() => requests.at(-1)?.params.get("page")).toBe("0");
  expect(requests.at(-1)?.params.get("cursor")).toBeNull();
});

test("contract and customer detail tabs and their nested pages are shareable and history-backed", async ({ page }) => {
  const requests: Array<{ path: string; params: URLSearchParams }> = [];
  await installUrlMocks(page, requests);

  await page.goto(`/contracts?id=${CONTRACT_ID}&tab=addenda&relatedPage=1&relatedCursor=contract-addenda-snapshot`);
  await expect(page.getByRole("tab", { name: "Addenda" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByText("Page 2 of 2")).toBeVisible();
  await expect.poll(() => {
    const latest = requests.filter(({ path, params }) => path === "addenda" && params.get("contractId") === CONTRACT_ID).at(-1)?.params;
    return [latest?.get("page"), latest?.get("cursor")];
  }).toEqual(["1", "contract-addenda-snapshot"]);
  await page.reload();
  await expect(page.getByRole("tab", { name: "Addenda" })).toHaveAttribute("aria-selected", "true");
  await page.getByRole("tab", { name: "Approval History" }).click();
  await expect(page).toHaveURL(/tab=approval-history/);
  await expect(page).not.toHaveURL(/relatedPage|relatedCursor/);
  await page.goBack();
  await expect(page.getByRole("tab", { name: "Addenda" })).toHaveAttribute("aria-selected", "true");
  await expect.poll(() => new URL(page.url()).searchParams.get("relatedPage")).toBe("1");

  await page.goto(`/customers?id=${CUSTOMER_ID}&tab=contracts&contractsPage=1&contractsCursor=customer-contracts-snapshot`);
  await expect(page.getByRole("tab", { name: "Contracts" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByText("Page 2 of 2")).toBeVisible();
  await expect.poll(() => {
    const latest = requests.filter(({ path, params }) => path === "contracts" && params.get("size") === "15").at(-1)?.params;
    return [latest?.get("page"), latest?.get("cursor")];
  }).toEqual(["1", "customer-contracts-snapshot"]);
  await page.getByRole("tab", { name: "Contacts" }).click();
  await expect(page).toHaveURL(/tab=contacts/);
  await expect(page).not.toHaveURL(/contractsPage|contractsCursor/);
  await page.goBack();
  await expect(page.getByRole("tab", { name: "Contracts" })).toHaveAttribute("aria-selected", "true");
  await expect.poll(() => new URL(page.url()).searchParams.get("contractsPage")).toBe("1");
});

test("nested pagination is ignored unless its owning detail tab is active", async ({ page }) => {
  const requests: Array<{ path: string; params: URLSearchParams }> = [];
  await installUrlMocks(page, requests);

  await page.goto(`/contracts?id=${CONTRACT_ID}&tab=overview&relatedPage=1&relatedCursor=wrong-tab`);
  await expect(page.getByRole("tab", { name: "Overview" })).toHaveAttribute("aria-selected", "true");
  await page.getByRole("tab", { name: "Addenda" }).click();
  await expect.poll(() => {
    const latest = requests.filter(({ path, params }) => path === "addenda" && params.get("contractId") === CONTRACT_ID).at(-1)?.params;
    return [latest?.get("page"), latest?.get("cursor")];
  }).toEqual(["0", null]);
  await expect(page).not.toHaveURL(/relatedPage|relatedCursor/);

  await page.goto(`/customers?id=${CUSTOMER_ID}&tab=contacts&contractsPage=1&contractsCursor=wrong-tab`);
  await expect(page.getByRole("tab", { name: "Contacts" })).toHaveAttribute("aria-selected", "true");
  await page.getByRole("tab", { name: "Contracts" }).click();
  await expect.poll(() => {
    const latest = requests.filter(({ path, params }) => path === "contracts" && params.get("size") === "15").at(-1)?.params;
    return [latest?.get("page"), latest?.get("cursor")];
  }).toEqual(["0", null]);
  await expect(page).not.toHaveURL(/contractsPage|contractsCursor/);
});

test("edits contract details and enables submission after the required attachment is uploaded", async ({ page }) => {
  const attachment = {
    id: "70000000-0000-4000-8000-000000000001",
    ownerType: "CONTRACT",
    ownerId: CONTRACT_ID,
    fileName: "contract.pdf",
    contentType: "application/pdf",
    sizeBytes: 12,
    uploadedAt: "2026-09-04T08:00:00Z",
  };
  let hasAttachment = false;
  let submitted = false;
  let submitRequests = 0;
  let updateBody: Record<string, unknown> | undefined;
  let savedDescription = "URL state contract";
  let savedVersion = 0;
  const draft = {
    ...contract,
    status: "DRAFT",
    editable: true,
    canEdit: true,
    canSubmit: false,
    submitBlockedReason: "Upload at least one attachment before submitting this contract for approval.",
    canCancel: true,
    canCreateAddendum: false,
  };

  await installApiMocks(page, async (_request, url) => {
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}` && _request.method() === "GET") {
      const status = submitted ? "SUBMITTED" : "DRAFT";
      return { body: envelope({
        ...draft,
        description: savedDescription,
        version: savedVersion,
        status,
        canEdit: !submitted,
        canSubmit: !submitted && hasAttachment,
        submitBlockedReason: !submitted && !hasAttachment
          ? "Upload at least one attachment before submitting this contract for approval."
          : null,
        canCancel: true,
      }) };
    }
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}` && _request.method() === "PUT") {
      updateBody = await _request.postDataJSON();
      savedDescription = String(updateBody?.description);
      savedVersion += 1;
      return { body: envelope({
        ...draft,
        ...updateBody,
        description: savedDescription,
        version: savedVersion,
      }) };
    }
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}/submit` && _request.method() === "POST") {
      submitRequests += 1;
      submitted = true;
      return { body: envelope({ status: "SUBMITTED", dispatchPending: true }) };
    }
    if (url.pathname === "/api/v1/attachments" && _request.method() === "GET") {
      return { body: envelope(hasAttachment ? [attachment] : []) };
    }
    if (url.pathname === "/api/v1/attachments" && _request.method() === "POST") {
      hasAttachment = true;
      return { status: 201, body: envelope(attachment) };
    }
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}/progress`) {
      return { body: envelope({ documentStatus: submitted ? "SUBMITTED" : "DRAFT", workflowState: "NOT_STARTED", steps: [] }) };
    }
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}/history`) return { body: envelope([]) };
    if (url.pathname === `/api/v1/contracts/${CONTRACT_ID}/signing-request`) {
      return { body: envelope({ canSendForSigning: false, requestQueued: false, sessionId: null }) };
    }
    if (url.pathname === `/api/v1/signing-sessions/by-document/CONTRACT/${CONTRACT_ID}`) return { body: envelope([]) };
    if (url.pathname === `/api/v1/customers/${CUSTOMER_ID}`) return { body: envelope(customer) };
    if (url.pathname === "/api/v1/addenda") {
      return { body: envelope([], { page: 0, size: 15, totalElements: 0, totalPages: 0 }) };
    }
  }, { permissions: [...currentUser.permissions, "contract:write"] });

  await page.goto(`/contracts?id=${CONTRACT_ID}`);
  const submit = page.getByRole("button", { name: "Submit for approval" });
  await expect(submit).toBeDisabled();
  await expect(page.getByText("Upload at least one attachment before submitting this contract for approval.")).toBeVisible();

  await page.getByRole("button", { name: "Edit", exact: true }).click();
  const editDialog = page.getByRole("dialog", { name: `Edit contract ${contract.contractNo}` });
  await expect(editDialog.getByLabel("Customer *")).toHaveValue(/CUS-URL.*URL Logistics/);
  await expect(editDialog.getByRole("listbox")).toHaveCount(0);
  await editDialog.getByLabel("Description").fill("Updated from contract details");
  await editDialog.getByRole("button", { name: "Save changes" }).click();
  await expect(editDialog).toHaveCount(0);
  await expect(page.getByText("Updated from contract details")).toBeVisible();
  expect(updateBody).toMatchObject({
    customerId: CUSTOMER_ID,
    description: "Updated from contract details",
    version: 0,
  });

  await page.getByLabel("Choose attachment file").setInputFiles({
    name: attachment.fileName,
    mimeType: attachment.contentType,
    buffer: Buffer.from("pdf contents"),
  });
  await page.getByRole("button", { name: "Upload attachment", exact: true }).click();
  await expect(submit).toBeEnabled();
  await expect(page.getByText("Upload at least one attachment before submitting this contract for approval.")).toHaveCount(0);

  await submit.click();
  await expect(page.getByText("Submitted").first()).toBeVisible();
  await expect(submit).toHaveCount(0);
  expect(submitRequests).toBe(1);
});
