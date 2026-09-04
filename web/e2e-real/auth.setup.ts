import { mkdir } from "node:fs/promises";
import { dirname } from "node:path";
import { test as setup } from "@playwright/test";
import { signIn } from "./support/real-stack";

const authState = "playwright/.auth/real-user.json";

setup("signs in once through the real identity service", async ({ page }) => {
  await signIn(page);
  await mkdir(dirname(authState), { recursive: true });
  await page.context().storageState({ path: authState });
});
