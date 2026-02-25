import { test, expect } from "@playwright/test";
import { EPISODE_DONE_ID } from "./helpers";

test.describe("Suite 8 — SPA Routing (Reload Safety)", () => {

  const routes = [
    { path: "/",               name: "Home" },
    { path: "/search",         name: "Search" },
    { path: "/subscriptions",  name: "Library" },
    { path: `/player/${EPISODE_DONE_ID}`, name: "Player" },
    { path: "/gists",          name: "Gists" },
  ];

  for (const { path, name } of routes) {
    test(`8.x reload ${name} (${path}) → app shell, not JSON 404`, async ({ page }) => {
      await page.goto(path);
      await page.waitForLoadState("networkidle");
      await page.reload();
      await page.waitForLoadState("networkidle");

      // Must NOT see raw JSON error
      const body = await page.textContent("body");
      expect(body).not.toContain('"detail":"Not Found"');
      expect(body).not.toContain('"detail": "Not Found"');

      // Must see app shell
      await expect(page.locator("text=⚗️ DistillPod")).toBeVisible();
    });
  }

  test("8.6 unknown path → app shell (not raw JSON)", async ({ page }) => {
    await page.goto("/this-path-does-not-exist-xyz");
    await page.waitForLoadState("networkidle");
    const body = await page.textContent("body");
    expect(body).not.toContain('"detail"');
    await expect(page.locator("text=⚗️ DistillPod")).toBeVisible();
  });

});
