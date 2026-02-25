import { test, expect } from "@playwright/test";
import { clearGists, createGist, clearProgress, seedProgress, EPISODE_DONE_ID, EPISODE_PROC_ID } from "./helpers";

// Navigate to player — do NOT use networkidle (audio streaming keeps network busy forever)
async function goToPlayer(page: any, episodeId: string) {
  await page.goto(`/player/${episodeId}`);
  await page.waitForLoadState("domcontentloaded");
  // Wait for at least the transcript badge to appear (confirms API calls returned)
  await expect(
    page.locator(".bg-green-900, .bg-yellow-900, .bg-gray-800.text-gray-400")
  ).toBeVisible({ timeout: 20_000 });
}

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

  test("5.10 gist button text is 'Gist + summarise'", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await expect(page.locator("button:has-text('Gist + summarise')").last()).toBeVisible({ timeout: 20_000 });
  });

  test("5.17 gist flash — button turns green briefly", async ({ page, request }) => {
    await clearGists(request);
    await goToPlayer(page, EPISODE_DONE_ID);

    const gistBtn = page.locator("button:has-text('Gist + summarise')").last();
    await expect(gistBtn).toBeEnabled({ timeout: 20_000 });
    await gistBtn.click();

    // AI summary takes ~30s; flash appears after completion
    await expect(page.locator("button.bg-green-600")).toBeVisible({ timeout: 60_000 });
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

    const gistBtn = page.locator("button:has-text('Gist + summarise')").last();
    await expect(gistBtn).toBeEnabled({ timeout: 20_000 });
    await gistBtn.click();
    // AI summary takes ~30s; just confirm the gist card appears (count in header)
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible({ timeout: 60_000 });
    await expect(page.locator("text=/Gists \\(\\d+\\)/")).toBeVisible({ timeout: 5_000 });
    await clearGists(request);
  });

  test("5.24 no saved progress → no resume indicator", async ({ page }) => {
    await page.goto(`/player/${EPISODE_DONE_ID}`);
    await clearProgress(page);
    await page.reload();
    await page.waitForLoadState("domcontentloaded");
    await expect(page.locator("text=/Resuming from/")).not.toBeVisible({ timeout: 15_000 });
  });

  test("5.25 saved progress → shows ⏩ Resuming from indicator", async ({ page }) => {
    await page.goto(`/player/${EPISODE_DONE_ID}`);
    await seedProgress(page, EPISODE_DONE_ID, 364); // 6:04 into episode
    await page.reload();
    await page.waitForLoadState("domcontentloaded");
    await expect(page.locator("text=/⏩ Resuming from/")).toBeVisible({ timeout: 20_000 });
    await clearProgress(page);
  });

  test("5.26 progress is saved to localStorage during playback", async ({ page }) => {
    test.setTimeout(90_000); // audio must play 10s then wait up to 5s for throttled save
    await page.goto(`/player/${EPISODE_DONE_ID}`);
    await clearProgress(page);
    // Wait for audio element to attach
    await expect(page.locator("audio")).toBeAttached({ timeout: 20_000 });
    // Wait for first throttled progress save (currentTime > 10s + up to 5s throttle window)
    await page.waitForFunction(
      () => Object.keys(JSON.parse(localStorage.getItem("distillpod:progress") || "{}")).length > 0,
      undefined,                           // no script arg
      { timeout: 60_000, polling: 1_000 }, // now correctly passed as options
    );
    const saved = await page.evaluate(() =>
      JSON.parse(localStorage.getItem("distillpod:progress") || "{}")
    );
    expect(Object.keys(saved).length).toBeGreaterThan(0);
    await clearProgress(page);
  });

  test("5.22 SPA reload /player/:id → app shell loads", async ({ page }) => {
    await page.goto(`/player/${EPISODE_DONE_ID}`);
    await page.waitForLoadState("domcontentloaded");
    await page.reload();
    await page.waitForLoadState("domcontentloaded");
    const body = await page.textContent("body");
    expect(body).not.toContain('"detail":"Not Found"');
    await expect(page.locator("text=⚗️ DistillPod")).toBeVisible();
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
