import { test, expect } from "@playwright/test";
import {
  clearGists, createGist, clearProgress, seedProgress,
  openFullscreenPlayer,
  EPISODE_DONE_ID, EPISODE_PROC_ID,
} from "./helpers";

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Navigate to the episode info page and wait for the title to load.
 * Does NOT click Play or open the fullscreen player.
 */
async function goToPlayer(page: any, episodeId: string) {
  await page.goto(`/player/${episodeId}`);
  await page.waitForLoadState("domcontentloaded");
  // h1 is rendered from episode metadata (API response); wait for it
  await expect(page.locator("h1")).toBeVisible({ timeout: 20_000 });
  await expect(page.locator("h1")).not.toBeEmpty();
}

// ── Tests ─────────────────────────────────────────────────────────────────────

test.describe("Suite 5 — Player", () => {

  // ── Episode info page ──────────────────────────────────────────────────────

  test("5.1 episode title appears on info page", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await expect(page.locator("h1")).toBeVisible();
    await expect(page.locator("h1")).not.toBeEmpty();
  });

  test("5.2 podcast artwork rendered in hero", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    // Hero artwork — either an <img> or the placeholder <div>, both carry w-32 h-32 rounded-2xl
    await expect(page.locator(".w-32.h-32.rounded-2xl").first()).toBeVisible({ timeout: 10_000 });
  });

  test("5.3 Play button visible on info page", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await expect(
      page.getByRole("button", { name: /^(Play|Resume|Now Playing)$/ }).first()
    ).toBeVisible({ timeout: 10_000 });
  });

  test("5.4 audio element attaches after clicking Play", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await openFullscreenPlayer(page);
    await expect(page.locator("audio")).toBeAttached({ timeout: 20_000 });
    await expect(page.locator("audio")).toHaveAttribute("src", /\/player\/audio\//);
  });

  // ── Fullscreen player ──────────────────────────────────────────────────────

  test("5.5 fullscreen player opens on Play click", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await openFullscreenPlayer(page);
    // Fullscreen player shows the episode title
    await expect(page.locator("h2")).toBeVisible({ timeout: 10_000 });
    await expect(page.locator("h2")).not.toBeEmpty();
  });

  test("5.6 transcript badge — processing episode shows Transcribing", async ({ page }) => {
    await goToPlayer(page, EPISODE_PROC_ID);
    await openFullscreenPlayer(page);
    await expect(page.getByText("Transcribing…")).toBeVisible({ timeout: 20_000 });
  });

  test("5.7 transcript badge — done episode shows Transcript ready", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await openFullscreenPlayer(page);
    await expect(page.getByText("Transcript ready")).toBeVisible({ timeout: 20_000 });
  });

  test("5.8 distill button disabled while transcript not ready", async ({ page }) => {
    await goToPlayer(page, EPISODE_PROC_ID);
    await openFullscreenPlayer(page);
    const distillBtn = page.getByRole("button", {
      name: /Waiting for transcript|Transcribing/,
    });
    await expect(distillBtn).toBeVisible({ timeout: 20_000 });
    await expect(distillBtn).toBeDisabled();
  });

  test("5.9 distill button enabled when transcript done", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await openFullscreenPlayer(page);
    const distillBtn = page.getByRole("button", { name: /Distill this moment/ });
    await expect(distillBtn).toBeEnabled({ timeout: 20_000 });
  });

  test("5.10 distill button text is 'Distill this moment'", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await openFullscreenPlayer(page);
    await expect(
      page.getByRole("button", { name: "⚗️  Distill this moment" })
    ).toBeVisible({ timeout: 20_000 });
  });

  test("5.17 distill flash — indicator appears after distilling", async ({ page, request }) => {
    await clearGists(request);
    await goToPlayer(page, EPISODE_DONE_ID);
    await openFullscreenPlayer(page);

    const distillBtn = page.getByRole("button", { name: /Distill this moment/ });
    await expect(distillBtn).toBeEnabled({ timeout: 20_000 });
    await distillBtn.click();

    // After distilling, a ⚗️ indicator with title "Distill saved" appears
    await expect(page.locator('[title="Distill saved"]')).toBeVisible({ timeout: 60_000 });
    await clearGists(request);
  });

  test("5.18 copy button shows ✓ Copied then reverts", async ({ page, request, context }) => {
    await context.grantPermissions(["clipboard-write", "clipboard-read"]);
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 364);
    await goToPlayer(page, EPISODE_DONE_ID);

    // Copy button is on the episode info page (gist card), not in the fullscreen player
    const copyBtn = page.getByRole("button", { name: "Copy" }).first();
    await expect(copyBtn).toBeVisible({ timeout: 15_000 });
    await copyBtn.click();
    await expect(page.getByRole("button", { name: "✓ Copied" })).toBeVisible({ timeout: 2_000 });
    await expect(page.getByRole("button", { name: "✓ Copied" })).not.toBeVisible({ timeout: 3_000 });
    await clearGists(request);
  });

  test("5.20 multiple gists all rendered on info page", async ({ page, request }) => {
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 100);
    await createGist(request, EPISODE_DONE_ID, 200);
    await createGist(request, EPISODE_DONE_ID, 300);
    await goToPlayer(page, EPISODE_DONE_ID);

    // Gist cards have bg-gray-900 rounded-2xl space-y-2 (unique to GistCard, not summary/chapter cards)
    await expect(page.locator(".bg-gray-900.rounded-2xl.space-y-2")).toHaveCount(3, { timeout: 15_000 });
    await clearGists(request);
  });

  test("5.21 distillations header shows count", async ({ page, request }) => {
    await clearGists(request);
    await goToPlayer(page, EPISODE_DONE_ID);

    // Open fullscreen player and distill
    await openFullscreenPlayer(page);
    const distillBtn = page.getByRole("button", { name: /Distill this moment/ });
    await expect(distillBtn).toBeEnabled({ timeout: 20_000 });
    await distillBtn.click();
    // Gist indicator confirms it was saved
    await expect(page.locator('[title="Distill saved"]')).toBeVisible({ timeout: 60_000 });

    // Close fullscreen player (swipe or click handle area)
    await page.locator('[title="Distill saved"]').press("Escape");
    // Navigate back to episode page to see updated gists
    // The fullscreen close button is the chevron handle — just navigate back and re-open
    await page.goto(`/player/${EPISODE_DONE_ID}`);
    await page.waitForLoadState("domcontentloaded");

    // Distillations header
    await expect(page.locator("text=/⚗️ Distillations \\(\\d+\\)/")).toBeVisible({ timeout: 10_000 });
    await clearGists(request);
  });

  // ── Progress / resume ──────────────────────────────────────────────────────

  test("5.24 no saved progress → Play starts from beginning", async ({ page }) => {
    await goToPlayer(page, EPISODE_DONE_ID);
    await clearProgress(page);
    await openFullscreenPlayer(page);

    // Audio currentTime should be near 0 (no saved progress)
    await expect(page.locator("audio")).toBeAttached({ timeout: 20_000 });
    const time = await page.evaluate(() => document.querySelector("audio")?.currentTime ?? -1);
    expect(time).toBeLessThan(10);
  });

  test("5.25 saved progress → Play resumes from saved position", async ({ page }) => {
    await page.goto(`/player/${EPISODE_DONE_ID}`);
    await seedProgress(page, EPISODE_DONE_ID, 364); // seed 6:04
    await page.reload();
    await page.waitForLoadState("domcontentloaded");
    await expect(page.locator("h1")).toBeVisible({ timeout: 15_000 });

    await openFullscreenPlayer(page);

    // Audio should seek to near the saved position
    await expect(page.locator("audio")).toBeAttached({ timeout: 20_000 });
    const time = await page.evaluate(() => document.querySelector("audio")?.currentTime ?? 0);
    expect(time).toBeGreaterThan(300); // > 5 min
    await clearProgress(page);
  });

  test("5.26 progress is saved to localStorage during playback", async ({ page }) => {
    test.setTimeout(90_000);
    await goToPlayer(page, EPISODE_DONE_ID);
    await clearProgress(page);
    await openFullscreenPlayer(page);

    await expect(page.locator("audio")).toBeAttached({ timeout: 20_000 });
    await page.waitForFunction(
      () => Object.keys(JSON.parse(localStorage.getItem("distillpod:progress") || "{}")).length > 0,
      undefined,
      { timeout: 60_000, polling: 1_000 },
    );
    const saved = await page.evaluate(() =>
      JSON.parse(localStorage.getItem("distillpod:progress") || "{}")
    );
    expect(Object.keys(saved).length).toBeGreaterThan(0);
    await clearProgress(page);
  });

  // ── Navigation / SPA ───────────────────────────────────────────────────────

  test("5.22 SPA reload /player/:id → app shell loads", async ({ page }) => {
    await page.goto(`/player/${EPISODE_DONE_ID}`);
    await page.waitForLoadState("domcontentloaded");
    await page.reload();
    await page.waitForLoadState("domcontentloaded");
    const body = await page.textContent("body");
    expect(body).not.toContain('"detail":"Not Found"');
    await expect(page.locator("text=⚗️ DistillPod")).toBeVisible();
  });

  test("5.23 navigate from Gists → player → fullscreen loads at correct time", async ({
    page, request,
  }) => {
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 364);

    await page.goto("/gists");
    await page.waitForLoadState("domcontentloaded");
    // Gists page cards use rounded-xl (not rounded-2xl)
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible({ timeout: 10_000 });

    // Click timestamp button inside the gist card to navigate to player with seekTo
    const tsBtn = page.locator(".bg-gray-900.rounded-xl button.text-indigo-400").first();
    await expect(tsBtn).toBeVisible({ timeout: 10_000 });
    await tsBtn.click();

    await expect(page).toHaveURL(/\/player\/.+/);
    await expect(page.locator("h1")).toBeVisible({ timeout: 15_000 });

    // Open fullscreen player — it should seek to saved position (364s)
    await openFullscreenPlayer(page);
    await expect(page.locator("audio")).toBeAttached({ timeout: 20_000 });
    const time = await page.evaluate(() => document.querySelector("audio")?.currentTime ?? 0);
    expect(time).toBeGreaterThan(300); // navigated to ~6:04

    await clearGists(request);
  });

});
