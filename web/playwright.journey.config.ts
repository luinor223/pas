import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e-journey",
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  workers: 1,
  reporter: process.env.CI ? [["html", { open: "never" }], ["list"]] : "list",
  use: {
    baseURL: process.env.PAS_E2E_BASE_URL ?? "http://127.0.0.1:18080",
    launchOptions: { slowMo: Number(process.env.PLAYWRIGHT_SLOW_MO ?? 0) },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  expect: { timeout: 15_000 },
  timeout: 180_000,
  projects: [{ name: "chromium-real-journey", use: { ...devices["Desktop Chrome"] } }],
});
