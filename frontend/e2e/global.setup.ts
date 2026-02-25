import { chromium } from "@playwright/test";

const BASE = "http://localhost:8124";

/**
 * Global E2E setup: hit the test-session endpoint to get a real signed
 * session cookie, then save storage state for all tests to reuse.
 *
 * Requires the backend to be running with TEST_MODE=true.
 */
export default async function globalSetup() {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    baseURL: BASE,
    ignoreHTTPSErrors: true,
  });
  const page = await context.newPage();

  // Navigate first to establish same-origin context
  await page.goto(BASE);

  // Call the test-session endpoint — sets a real signed session cookie
  const status = await page.evaluate(async (base) => {
    const r = await fetch(`${base}/auth/test-session`, {
      method: "POST",
      credentials: "include",
    });
    return r.status;
  }, BASE);

  if (status !== 200) {
    await browser.close();
    throw new Error(
      `❌ /auth/test-session returned ${status}.\n` +
      `   Make sure the backend is running with TEST_MODE=true in .env`
    );
  }

  // Persist cookies + localStorage so every test starts authenticated
  await context.storageState({ path: "e2e/.auth/user.json" });
  await browser.close();
  console.log("✅ E2E auth setup complete — session cookie saved");
}
