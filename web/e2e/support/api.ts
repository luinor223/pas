import type { Page, Request } from "@playwright/test";

export type MockResponse = { body: unknown; status?: number };
export type ApiHandler = (request: Request, url: URL) => MockResponse | undefined | Promise<MockResponse | undefined>;

export const currentUser = {
  id: "11111111-1111-4111-8111-111111111111",
  username: "admin",
  fullName: "System Administrator",
  department: "IT",
  roles: ["SYSTEM_ADMIN"],
  permissions: [
    "approval:act", "pricelist:read", "pricelist:write", "volume:read", "volume:write",
    "volume:lock_period", "volume:edit_locked", "audit:view_all", "notification:read",
    "contract:read", "customer:read",
  ],
};

export function envelope<T>(data: T, meta?: Record<string, unknown>) {
  return meta ? { data, meta } : { data };
}

export async function installApiMocks(
  page: Page,
  featureHandler: ApiHandler,
  userOverrides: Partial<typeof currentUser> = {},
) {
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());

    let response: MockResponse | undefined;
    if (url.pathname === "/api/v1/auth/me") {
      response = { body: envelope({ ...currentUser, ...userOverrides }) };
    } else if (url.pathname === "/api/v1/notifications" && url.searchParams.get("size") === "1") {
      response = { body: envelope({ items: [], total: 0, unreadCount: 1, counts: { all: 1, unread: 1 } }) };
    } else {
      response = await featureHandler(request, url);
    }

    if (!response) {
      response = request.method() === "GET"
        ? { status: 404, body: envelope({ message: `No E2E mock for ${url.pathname}${url.search}` }) }
        : { status: 404, body: envelope({ message: `No E2E mock for ${request.method()} ${url.pathname}` }) };
    }

    await route.fulfill({
      status: response.status ?? 200,
      contentType: "application/json",
      body: JSON.stringify(response.body),
    });
  });
}
