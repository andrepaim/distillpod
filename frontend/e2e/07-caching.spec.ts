import { test, expect } from "@playwright/test";
import { clearPlayed, navTab } from "./helpers";

test.describe("Suite 7 — Caching & State", () => {

  test("7.1 home feed cache hit on return", async ({ page }) => {
    await page.goto("/");
    await page.waitForLoadState("networkidle");
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible({ timeout: 15_000 });

    // Navigate away via nav tabs
    await navTab(page, "Search").click();
    await navTab(page, "Home").click();
    // Cache hit: feed should reappear instantly (no skeleton)
    await expect(page.locator(".animate-pulse")).not.toBeVisible({ timeout: 1_000 });
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible();
  });

  test("7.2 library episode cache hit on return", async ({ page }) => {
    await page.goto("/subscriptions");
    await page.waitForLoadState("networkidle");

    // Open episode list — wait for episodes to fully load (not networkidle, which is unreliable)
    await page.locator(".bg-gray-900").filter({ hasText: "Since" }).first().click();
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator(".animate-pulse")).not.toBeVisible({ timeout: 5_000 });

    const firstTitle = await page.locator(".bg-gray-900.rounded-xl .text-sm.font-medium.leading-snug").first().textContent();

    // Go back via back button (scoped to main, not nav tab)
    await page.locator("main").getByRole("button", { name: "Library" }).click();
    // Re-enter same podcast
    await page.locator(".bg-gray-900").filter({ hasText: "Since" }).first().click();

    // Cache hit: episodes should appear fast (within 500ms, no prolonged skeleton)
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible({ timeout: 2_000 });
    await expect(page.locator(".animate-pulse")).not.toBeVisible({ timeout: 1_000 });
    await expect(page.locator(`text=${firstTitle?.trim()}`)).toBeVisible();
  });

  test("7.3 played state survives page reload", async ({ page }) => {
    await page.goto("/");
    await clearPlayed(page); // MUST be after goto — localStorage scoped to origin
    await page.reload();
    await page.waitForLoadState("networkidle");
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible({ timeout: 15_000 });

    // Mark first episode as played
    const card = page.locator(".bg-gray-900.rounded-xl").first();
    await card.locator("button[title='Mark as played'], button[title='Mark as unplayed']").click();
    await expect(card).toHaveClass(/opacity-60/);

    // Hard reload — played state should persist via localStorage
    await page.reload();
    await page.waitForLoadState("networkidle");
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible({ timeout: 15_000 });
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toHaveClass(/opacity-60/);
  });

});
