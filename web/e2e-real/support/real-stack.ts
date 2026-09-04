import { expect, type Page, type Response } from "@playwright/test";

const username = process.env.PAS_E2E_USERNAME ?? "admin";
const password = process.env.PAS_E2E_PASSWORD ?? "admin12345";

export async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByLabel("Email address").fill(username);
  await page.getByLabel("Password").fill(password);

  const loginResponse = page.waitForResponse((response) =>
    response.url().includes("/api/v1/auth/login") && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Sign in" }).click();
  await expectApiSuccess(await loginResponse, "login");
  await expect(page).not.toHaveURL(/\/login(?:\?|$)/);
  await expect(page.getByRole("button", { name: "Open account menu" })).toBeVisible();
}

export async function openFeatureAndWaitForApi({
  page,
  linkName,
  apiPath,
  method = "GET",
}: {
  page: Page;
  linkName: string;
  apiPath: string;
  method?: string;
}) {
  const apiResponse = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === apiPath && response.request().method() === method;
  });
  await page.getByRole("navigation").getByRole("link", { name: linkName, exact: true }).click();
  const response = await apiResponse;
  await expectApiSuccess(response, `${method} ${apiPath}`);
  return response;
}

export async function expectApiSuccess(response: Response, description: string) {
  const body = await response.text();
  expect(
    response.ok(),
    `${description} returned ${response.status()}: ${body.slice(0, 500)}`,
  ).toBe(true);
  expect(response.headers()["content-type"] ?? "", `${description} should return JSON`).toContain("application/json");
}
