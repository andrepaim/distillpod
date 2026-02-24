import { test, expect } from "@playwright/test";
import { clearGists, createGist, EPISODE_DONE_ID, EPISODE_PROC_ID } from "./helpers";

// Navigate to player — do NOT use networkidle (audio streaming keeps network busy forever)
async function goToPlayer(page: any, episodeId: string) {
  await page.goto(`/player/${episodeId}`);
  await page.waitForLoadState("domcontentloaded");
  // Wait for at least the transcript badge to appear (confirms API calls returned)
  await expect(
    page.locator(".bg-green-900, .bg-yellow-900, .bg-gray-800.text-gray-400")
  ).toBeVisible({ timeout: 20_000 });
}

// Toggle: unique overflow-hidden class on the pill button
const aiToggle = (page: any) => page.locator("button.overflow-hidden");

test.describe("Suite 5 — Player", () => {

  test("5.1 episode title appears in header", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    // Episode title is loaded via getEpisode() API
    await expect(page.locator("h1")).toBeVisible({ timeout: 15_000 });
    await expect(page.locator("h1")).not.toBeEmpty();
  });

  test("5.2 transcript badge while processing", async ({ page }) => {
    await goToPlayer(page, EPISODE_PROC_ID);
    // Yellow = processing/transcribing
    await expect(page.locator(".bg-yellow-900")).toBeVisible({ timeout: 20_000 });
  });

  test("5.3 transcript badge when done → green", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await expect(page.locator(".bg-green-900")).toBeVisible({ timeout: 20_000 });
  });

  test("5.4 audio element in DOM after startPlay", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    // audioReady=true → <audio> renders with className="hidden" (display:none)
    // Use toBeAttached() — hidden elements ARE attached to DOM
    await expect(page.locator("audio")).toBeAttached({ timeout: 20_000 });
    await expect(page.locator("audio")).toHaveAttribute("src", /\/player\/audio\//);
  });

  test("5.8 gist button disabled while transcript not ready", async ({ page }) => {
    await goToPlayer(page, EPISODE_PROC_ID);
    const gistBtn = page.locator("button:has-text('Waiting for transcript')");
    await expect(gistBtn).toBeVisible({ timeout: 20_000 });
    await expect(gistBtn).toBeDisabled();
  });

  test("5.9 gist button enabled when transcript done", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    // Either "Gist + summarise" or "Gist this moment"
    const gistBtn = page.locator("button:has-text('Gist')").last();
    await expect(gistBtn).toBeEnabled({ timeout: 20_000 });
  });

  test("5.10 AI toggle visible", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await expect(page.locator("text=✨ AI summary")).toBeVisible({ timeout: 20_000 });
    await expect(aiToggle(page)).toBeVisible();
  });

  test("5.11 AI toggle default ON", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await expect(aiToggle(page)).toHaveClass(/bg-indigo-600/, { timeout: 20_000 });
  });

  test("5.12 toggle OFF → gray, button text → Gist this moment", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await expect(aiToggle(page)).toBeVisible({ timeout: 20_000 });
    await aiToggle(page).click();
    await expect(aiToggle(page)).toHaveClass(/bg-gray-700/);
    await expect(page.locator("button:has-text('Gist this moment')")).toBeVisible();
  });

  test("5.13 toggle ON → ~30s hint appears", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await expect(aiToggle(page)).toBeVisible({ timeout: 20_000 });
    await aiToggle(page).click();                 // off
    await aiToggle(page).click();                 // back on
    await expect(aiToggle(page)).toHaveClass(/bg-indigo-600/);
    await expect(page.locator("text=~30s")).toBeVisible();
  });

  test("5.14 toggle pill has overflow-hidden", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await expect(aiToggle(page)).toHaveClass(/overflow-hidden/);
  });

  test("5.15 gist without AI → card with transcript text", async ({ page, request }) => {
    await clearGists(request);
    await goToPlayer(page, EPISODE_DONE_ID);

    await expect(aiToggle(page)).toBeVisible({ timeout: 20_000 });
    await aiToggle(page).click(); // AI off

    const gistBtn = page.locator("button:has-text('Gist this moment')");
    await expect(gistBtn).toBeEnabled({ timeout: 20_000 });
    await gistBtn.click();

    // Non-AI gist card shows plain gray text
    const textEl = page.locator("p.text-sm.leading-relaxed.text-gray-100");
    await expect(textEl.first()).toBeVisible({ timeout: 5_000 });
    await expect(textEl.first()).not.toBeEmpty();
    await clearGists(request);
  });

  test("5.17 gist flash — button turns green briefly", async ({ page, request }) => {
    await clearGists(request);
    await goToPlayer(page, EPISODE_DONE_ID);

    await expect(aiToggle(page)).toBeVisible({ timeout: 20_000 });
    await aiToggle(page).click(); // AI off for speed

    const gistBtn = page.locator("button:has-text('Gist this moment')");
    await expect(gistBtn).toBeEnabled({ timeout: 20_000 });
    await gistBtn.click();

    await expect(page.locator("button.bg-green-600")).toBeVisible({ timeout: 3_000 });
    await clearGists(request);
  });

  test("5.18 copy button shows ✓ Copied then reverts", async ({ page, request, context }) => {
    // Grant clipboard-write permission so navigator.clipboard.writeText() succeeds
    await context.grantPermissions(["clipboard-write", "clipboard-read"]);
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 364);
    await goToPlayer(page, EPISODE_DONE_ID);

    const copyBtn = page.locator("button:has-text('📋 Copy')").first();
    await expect(copyBtn).toBeVisible({ timeout: 15_000 });
    await copyBtn.click();
    await expect(page.locator("button:has-text('✓ Copied')")).toBeVisible({ timeout: 2_000 });
    await expect(page.locator("button:has-text('✓ Copied')")).not.toBeVisible({ timeout: 3_000 });
    await clearGists(request);
  });

  test("5.20 multiple gists all rendered", async ({ page, request }) => {
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 100);
    await createGist(request, EPISODE_DONE_ID, 200);
    await createGist(request, EPISODE_DONE_ID, 300);
    await goToPlayer(page, EPISODE_DONE_ID);

    // Gist cards use rounded-xl; player wrapper uses rounded-2xl — so exactly 3
    await expect(page.locator(".bg-gray-900.rounded-xl")).toHaveCount(3, { timeout: 15_000 });
    await clearGists(request);
  });

  test("5.21 gist count header updates", async ({ page, request }) => {
    await clearGists(request);
    await goToPlayer(page, EPISODE_DONE_ID);

    await expect(aiToggle(page)).toBeVisible({ timeout: 20_000 });
    await aiToggle(page).click();

    const gistBtn = page.locator("button:has-text('Gist this moment')");
    await expect(gistBtn).toBeEnabled({ timeout: 20_000 });
    await gistBtn.click();
    await page.waitForTimeout(500);

    await expect(page.locator("text=Gists (1)")).toBeVisible({ timeout: 5_000 });
    await clearGists(request);
  });

  test("5.22 SPA reload /player/:id → app shell loads", async ({ page }) => {
    await page.goto(`/player/${EPISODE_DONE_ID}`);
    await page.waitForLoadState("domcontentloaded");
    await page.reload();
    await page.waitForLoadState("domcontentloaded");
    const body = await page.textContent("body");
    expect(body).not.toContain('"detail":"Not Found"');
    await expect(page.locator("text=🎧 PodGist")).toBeVisible();
  });

  test("5.23 navigate from Gists → player shows ▶ From indicator", async ({ page, request }) => {
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 364);

    await page.goto("/gists");
    await page.waitForLoadState("domcontentloaded");
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible({ timeout: 10_000 });

    await page.locator(".bg-gray-900.rounded-xl").first().click();
    await page.waitForTimeout(300);

    // Timestamp/play button: indigo-400, inside gist card (not the back button which is also indigo)
    const tsBtn = page.locator(".bg-gray-900.rounded-xl button.text-indigo-400").first();
    await expect(tsBtn).toBeVisible({ timeout: 10_000 });
    await tsBtn.click();

    await expect(page).toHaveURL(/\/player\/.+/);
    // seekTo passed in state → ▶ From indicator visible
    await expect(page.locator("text=/▶ From/")).toBeVisible({ timeout: 15_000 });
    await clearGists(request);
  });

});
