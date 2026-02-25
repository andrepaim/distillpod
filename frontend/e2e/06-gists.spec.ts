import { test, expect } from "@playwright/test";
import { clearGists, createGist, EPISODE_DONE_ID } from "./helpers";

// Back button in episode gist list says "Gists" — scope to main to avoid nav tab clash
const gistsBackBtn = (page: any) => page.locator("main").getByRole("button", { name: "Gists" });

test.describe("Suite 6 — Gists Page", () => {

  test("6.1 empty state — No gists yet.", async ({ page, request }) => {
    await clearGists(request);
    await page.goto("/gists");
    await page.waitForLoadState("networkidle");
    await expect(page.locator("text=No gists yet.")).toBeVisible();
  });

  test("6.2 episode row — title, gist count pill", async ({ page, request }) => {
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 364);
    await page.goto("/gists");
    await page.waitForLoadState("networkidle");

    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible();
    await expect(page.locator("span.rounded-full").first()).toBeVisible();
    await clearGists(request);
  });

  test("6.3 last gisted date visible", async ({ page, request }) => {
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 364);
    await page.goto("/gists");
    await page.waitForLoadState("networkidle");

    await expect(page.locator("text=/Last gist/i")).toBeVisible({ timeout: 5_000 });
    await clearGists(request);
  });

  test("6.5 tap episode → drill down to gist list", async ({ page, request }) => {
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 364);
    await page.goto("/gists");
    await page.waitForLoadState("networkidle");

    await page.locator(".bg-gray-900.rounded-xl").first().click();
    await page.waitForLoadState("networkidle");

    await expect(gistsBackBtn(page)).toBeVisible({ timeout: 5_000 });
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible();
    await clearGists(request);
  });

  test("6.7 back button → returns to episode list", async ({ page, request }) => {
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 364);
    await page.goto("/gists");
    await page.waitForLoadState("networkidle");

    await page.locator(".bg-gray-900.rounded-xl").first().click();
    await expect(gistsBackBtn(page)).toBeVisible({ timeout: 5_000 });
    await gistsBackBtn(page).click();

    // Back to episode list — Last gist date visible
    await expect(page.locator("text=/Last gist/i")).toBeVisible({ timeout: 5_000 });
    await clearGists(request);
  });

  test("6.8 Play button → navigates to /player/:id", async ({ page, request }) => {
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 364);
    await page.goto("/gists");
    await page.waitForLoadState("networkidle");

    await page.locator(".bg-gray-900.rounded-xl").first().click();
    await page.waitForLoadState("networkidle");

    await page.locator("button:has-text('▶ Play')").click();
    await expect(page).toHaveURL(/\/player\/.+/);
    await clearGists(request);
  });

  test("6.10 non-AI gist card → raw transcript text", async ({ page, request }) => {
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 364);
    await page.goto("/gists");
    await page.waitForLoadState("networkidle");

    await page.locator(".bg-gray-900.rounded-xl").first().click();
    await page.waitForLoadState("networkidle");

    // Non-AI gist: plain gray text (not italic/indigo)
    const textEl = page.locator("p.text-sm.leading-relaxed").first();
    await expect(textEl).toBeVisible({ timeout: 5_000 });
    await expect(textEl).not.toBeEmpty();
    await clearGists(request);
  });

  test("6.14 delete gist → card disappears", async ({ page, request }) => {
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 364);
    await createGist(request, EPISODE_DONE_ID, 200);
    await page.goto("/gists");
    await page.waitForLoadState("networkidle");

    await page.locator(".bg-gray-900.rounded-xl").first().click();
    await page.waitForLoadState("networkidle");

    const before = await page.locator(".bg-gray-900.rounded-xl").count();

    // Accept the confirm dialog automatically
    page.on("dialog", d => d.accept());
    await page.locator("button:has-text('🗑')").first().click();
    await page.waitForTimeout(600);

    const after = await page.locator(".bg-gray-900.rounded-xl").count();
    expect(after).toBeLessThan(before);
    await clearGists(request);
  });

  test("6.15 delete last gist → returns to episode list", async ({ page, request }) => {
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 364);
    await page.goto("/gists");
    await page.waitForLoadState("networkidle");

    await page.locator(".bg-gray-900.rounded-xl").first().click();
    await page.waitForLoadState("networkidle");

    page.on("dialog", d => d.accept());
    await page.locator("button:has-text('🗑')").first().click();
    await page.waitForTimeout(600);

    // After deleting the only gist, onAllDeleted fires → episode list shows "No gists yet."
    const emptyOrList = page.locator("text=No gists yet.").or(page.locator("text=/Last gist/i"));
    await expect(emptyOrList).toBeVisible({ timeout: 5_000 });
    await clearGists(request);
  });

  test("6.16 SPA reload /gists → page loads", async ({ page }) => {
    await page.goto("/gists");
    await page.reload();
    await page.waitForLoadState("networkidle");
    const body = await page.textContent("body");
    expect(body).not.toContain('"detail"');
    await expect(page.locator("text=⚗️ DistillPod")).toBeVisible();
  });

});
