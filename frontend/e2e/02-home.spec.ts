import { test, expect } from "@playwright/test";
import { createGist, clearGists, clearPlayed, goTo, EPISODE_DONE_ID } from "./helpers";

async function waitForFeed(page: any) {
  await page.waitForLoadState("networkidle");
  // Wait for skeleton to vanish (or just wait for a card to appear)
  await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible({ timeout: 15_000 });
}

test.describe("Suite 2 — Home Page", () => {

  test("2.3 feed loads with episode cards", async ({ page }) => {
    await page.goto("/");
    await waitForFeed(page);
    // At least one episode card
    const cards = page.locator(".bg-gray-900.rounded-xl");
    await expect(cards.first()).toBeVisible();
    // Feed contains AI Daily Brief episodes
    await expect(page.locator("text=The AI Daily Brief").first()).toBeVisible();
  });

  test("2.5 gist count badge on episode with gists", async ({ page, request }) => {
    await clearGists(request);
    await createGist(request, EPISODE_DONE_ID, 364);
    await page.goto("/");
    await waitForFeed(page);
    // Click refresh to force reload from server (bypasses cache)
    await page.getByTitle("Refresh").click();
    await waitForFeed(page);
    // Badge: ✂️ 1
    await expect(page.locator("text=✂️ 1")).toBeVisible({ timeout: 10_000 });
    await clearGists(request);
  });

  test("2.7 mark as played → checkmark + dimmed", async ({ page }) => {
    await page.goto("/");
    await clearPlayed(page); // after goto so localStorage is on correct origin
    await page.reload();
    await waitForFeed(page);

    const card = page.locator(".bg-gray-900.rounded-xl").first();
    const toggle = card.locator("button[title='Mark as played'], button[title='Mark as unplayed']");
    // Start unplayed → no opacity class
    await expect(card).not.toHaveClass(/opacity-60/);
    await toggle.click();
    // Now played: opacity-60 + indigo checkmark div
    await expect(card).toHaveClass(/opacity-60/);
    await expect(card.locator("div.bg-indigo-500")).toBeVisible();
  });

  test("2.8 mark as unplayed → circle returns", async ({ page }) => {
    await page.goto("/");
    await clearPlayed(page);
    await page.reload();
    await waitForFeed(page);

    const card = page.locator(".bg-gray-900.rounded-xl").first();
    const toggle = card.locator("button[title='Mark as played'], button[title='Mark as unplayed']");
    // Mark played
    await toggle.click();
    await expect(card).toHaveClass(/opacity-60/);
    // Mark unplayed
    await toggle.click();
    await expect(card).not.toHaveClass(/opacity-60/);
    // Border circle visible
    await expect(card.locator("div.border-gray-600, div.border-2")).toBeVisible();
  });

  test("2.9 played state persists across reload", async ({ page }) => {
    await page.goto("/");
    await clearPlayed(page);
    await page.reload();
    await waitForFeed(page);

    const card = page.locator(".bg-gray-900.rounded-xl").first();
    await card.locator("button[title='Mark as played'], button[title='Mark as unplayed']").click();
    await expect(card).toHaveClass(/opacity-60/);

    // Reload page
    await page.reload();
    await waitForFeed(page);
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toHaveClass(/opacity-60/);
  });

  test("2.10 refresh button spins while loading", async ({ page }) => {
    await page.goto("/");
    await waitForFeed(page);
    const refreshBtn = page.getByTitle("Refresh");
    await refreshBtn.click();
    // Spinner (animate-spin) appears briefly
    await expect(page.locator(".animate-spin")).toBeVisible({ timeout: 5_000 });
  });

  test("2.11 tap episode → navigates to /player/:id", async ({ page }) => {
    await page.goto("/");
    await waitForFeed(page);
    // Click the clickable content area (not the played toggle button at the end)
    const card = page.locator(".bg-gray-900.rounded-xl").first();
    // The card itself has cursor-pointer and onClick — but the toggle button needs stopPropagation
    // Use the div that triggers navigation (it wraps title/image area)
    const clickArea = card.locator(".cursor-pointer").first();
    await clickArea.click();
    await expect(page).toHaveURL(/\/player\/.+/);
  });

  test("2.12 cache hit on return — no skeleton", async ({ page }) => {
    await page.goto("/");
    await waitForFeed(page);
    // Navigate away and back
    await goTo(page, "Search");
    await goTo(page, "Home");
    // Feed reappears immediately (cache hit — no skeleton)
    await expect(page.locator(".animate-pulse")).not.toBeVisible();
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible();
  });

});
