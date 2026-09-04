import { defineConfig, devices } from "@playwright/test";

const authState = "test-results/.auth/real-user.json";

export default defineConfig({
  testDir: "./e2e-real",
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [["html", { open: "never" }], ["list"]] : "list",
  use: {
    baseURL: process.env.PAS_E2E_BASE_URL ?? "http://127.0.0.1:18080",
    launchOptions: { slowMo: Number(process.env.PLAYWRIGHT_SLOW_MO ?? 0) },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  expect: { timeout: 10_000 },
  timeout: 45_000,
  projects: [
    {
      name: "authenticate-real-stack",
      testMatch: /.*\.setup\.ts/,
      use: { ...devices["Desktop Chrome"] },
    },
    {
      name: "chromium-real-stack",
      testIgnore: /.*\.setup\.ts/,
      use: { ...devices["Desktop Chrome"], storageState: authState },
      dependencies: ["authenticate-real-stack"],
    },
  ],
});
