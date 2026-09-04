import { expect, type Page } from "@playwright/test";

export const TEST_PASSWORD = "MockTest12345!";

export const TEST_USERS = {
  salesManager: {
    username: "mock_test_01_sales_manager",
    password: TEST_PASSWORD,
    email: "mock-test-01-sales-manager@example.test",
    fullName: "Mock Test 01 - Sales Manager",
    departmentCode: "SALES",
    roleCodes: ["SALES_MANAGER"],
  },
  director: {
    username: "mock_test_02_director",
    password: TEST_PASSWORD,
    email: "mock-test-02-director@example.test",
    fullName: "Mock Test 02 - Director",
    departmentCode: "BOARD",
    roleCodes: ["DIRECTOR"],
  },
  operations: {
    username: "mock_test_03_operations",
    password: TEST_PASSWORD,
    email: "mock-test-03-operations@example.test",
    fullName: "Mock Test 03 - Operations Officer",
    departmentCode: "OPERATIONS",
    roleCodes: ["OPS_OFFICER"],
  },
} as const;

type ApiResult<T> = { ok: boolean; status: number; body: T };

export async function browserApi<T>(page: Page, path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  const result = await page.evaluate(async ({ apiPath, method, requestBody }) => {
    const csrf = document.cookie
      .split("; ")
      .find((part) => part.startsWith("pas_csrf="))
      ?.slice("pas_csrf=".length);
    const response = await fetch(apiPath, {
      method,
      credentials: "include",
      headers: {
        "Content-Type": "application/json",
        ...(csrf && method !== "GET" ? { "X-CSRF-Token": decodeURIComponent(csrf) } : {}),
      },
      body: requestBody === undefined ? undefined : JSON.stringify(requestBody),
    });
    const text = await response.text();
    let body: unknown = text;
    try { body = text ? JSON.parse(text) : null; } catch { /* Preserve non-JSON error text. */ }
    return { ok: response.ok, status: response.status, body };
  }, { apiPath: `/api/v1${path}`, method: options.method ?? "GET", requestBody: options.body }) as ApiResult<unknown>;

  expect(result.ok, `${options.method ?? "GET"} ${path} returned ${result.status}: ${JSON.stringify(result.body).slice(0, 800)}`).toBe(true);
  const envelope = result.body as { data?: T } | T;
  return envelope && typeof envelope === "object" && "data" in envelope
    ? (envelope as { data: T }).data
    : envelope as T;
}

export async function ensureTestUsers(page: Page) {
  const existing = await browserApi<Array<{ username: string; status: string; id: string }>>(page, "/users");
  for (const user of Object.values(TEST_USERS)) {
    const found = existing.find((item) => item.username === user.username);
    if (!found) {
      await browserApi(page, "/users", { method: "POST", body: user });
    } else if (found.status !== "ACTIVE") {
      await browserApi(page, `/users/${found.id}/enable`, { method: "POST" });
    }
  }
}

export function runMarker(sequence = 1) {
  const timestamp = new Date().toISOString().replace(/\D/g, "").slice(0, 14);
  return `Mock Test ${timestamp}${String(sequence).padStart(2, "0")}`;
}

export function uniqueFutureDate() {
  const day = 24 * 60 * 60 * 1000;
  const offset = Date.now() % 50_000;
  return new Date(Date.UTC(2100, 0, 1) + offset * day).toISOString().slice(0, 10);
}

export function monthsBetween(from: string, to: string) {
  const result: string[] = [];
  const cursor = new Date(`${from.slice(0, 7)}-01T00:00:00Z`);
  const end = new Date(`${to.slice(0, 7)}-01T00:00:00Z`);
  while (cursor <= end) {
    result.push(cursor.toISOString().slice(0, 7));
    cursor.setUTCMonth(cursor.getUTCMonth() + 1);
  }
  return result;
}
