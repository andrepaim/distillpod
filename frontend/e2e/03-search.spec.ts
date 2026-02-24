import { test, expect } from "@playwright/test";

// Search form submit button — scoped to main to avoid nav tab clash
const searchBtn = (page: any) => page.locator("main").getByRole("button", { name: "Search" });

test.describe("Suite 3 — Search", () => {

  test.beforeEach(async ({ page }) => {
    await page.goto("/search");
    await page.waitForLoadState("networkidle");
  });

  test("3.1 empty input → no crash, stays on /search", async ({ page }) => {
    // Empty q → doSearch bails out early; no error
    await searchBtn(page).click();
    await expect(page).toHaveURL("/search");
    await expect(page.locator("text=Error")).not.toBeVisible();
  });

  test("3.2 search query → shows results", async ({ page }) => {
    await page.locator("input[placeholder='Search podcasts…']").fill("artificial intelligence");
    await searchBtn(page).click();
    await page.waitForLoadState("networkidle");
    await expect(page.locator(".bg-gray-900").first()).toBeVisible({ timeout: 15_000 });
    await expect(page.locator(".font-semibold").first()).not.toBeEmpty();
  });

  test("3.3 Enter key triggers search", async ({ page }) => {
    const input = page.locator("input[placeholder='Search podcasts…']");
    await input.fill("machine learning");
    await input.press("Enter");
    await page.waitForLoadState("networkidle");
    await expect(page.locator(".bg-gray-900").first()).toBeVisible({ timeout: 15_000 });
  });

  test("3.4 loading spinner while fetching", async ({ page }) => {
    await page.locator("input[placeholder='Search podcasts…']").fill("technology");
    // Intercept slow response to see the spinner
    await searchBtn(page).click();
    // Spinner replaces "Search" text
    await expect(page.locator(".animate-spin")).toBeVisible({ timeout: 5_000 });
  });

  test("3.5 no results message", async ({ page }) => {
    await page.locator("input[placeholder='Search podcasts…']").fill("zzzzzxxxxxyyyynotareal12345");
    await searchBtn(page).click();
    await page.waitForLoadState("networkidle");
    await expect(page.getByText(/No results for/)).toBeVisible({ timeout: 15_000 });
  });

  test("3.6 subscribe button changes to ✓ Subscribed", async ({ page }) => {
    await page.locator("input[placeholder='Search podcasts…']").fill("history of rome");
    await searchBtn(page).click();
    await page.waitForLoadState("networkidle");
    await expect(page.locator(".bg-gray-900").first()).toBeVisible({ timeout: 15_000 });

    const subscribeBtns = page.locator("button:has-text('Subscribe')");
    if (await subscribeBtns.count() === 0) { test.skip(); return; }

    const btn = subscribeBtns.first();
    await btn.click();
    await expect(page.locator("button:has-text('✓ Subscribed')").first()).toBeVisible({ timeout: 10_000 });
    await expect(page.locator("button:has-text('✓ Subscribed')").first()).toHaveClass(/bg-green-900/);
  });

  test("3.7 toast appears on subscribe then disappears", async ({ page }) => {
    await page.locator("input[placeholder='Search podcasts…']").fill("true crime");
    await searchBtn(page).click();
    await page.waitForLoadState("networkidle");
    await expect(page.locator(".bg-gray-900").first()).toBeVisible({ timeout: 15_000 });

    const subscribeBtns = page.locator("button:has-text('Subscribe')");
    if (await subscribeBtns.count() === 0) { test.skip(); return; }

    await subscribeBtns.first().click();
    // Toast appears
    const toast = page.locator("div.bg-green-700");
    await expect(toast).toBeVisible({ timeout: 5_000 });
    // Toast disappears within ~3.5s
    await expect(toast).not.toBeVisible({ timeout: 4_000 });
  });

  test("3.8 already-subscribed shows ✓ Subscribed pre-marked", async ({ page }) => {
    await page.locator("input[placeholder='Search podcasts…']").fill("AI Daily Brief");
    await searchBtn(page).click();
    await page.waitForLoadState("networkidle");
    await expect(page.locator(".bg-gray-900").first()).toBeVisible({ timeout: 15_000 });
    // We're subscribed to this — at least one result should show ✓ Subscribed
    await expect(page.locator("button:has-text('✓ Subscribed')").first()).toBeVisible({ timeout: 5_000 });
  });

  test("3.9 subscribed button is disabled", async ({ page }) => {
    await page.locator("input[placeholder='Search podcasts…']").fill("AI Daily Brief");
    await searchBtn(page).click();
    await page.waitForLoadState("networkidle");
    await expect(page.locator(".bg-gray-900").first()).toBeVisible({ timeout: 15_000 });
    await expect(page.locator("button:has-text('✓ Subscribed')").first()).toBeDisabled({ timeout: 5_000 });
  });

});
