import { test, expect } from "@playwright/test";

// Back button in the episode list says "Library" — scope to main to avoid nav tab clash
const backBtn = (page: any) => page.locator("main").getByRole("button", { name: "Library" });

test.describe("Suite 4 — Library", () => {

  test.beforeEach(async ({ page }) => {
    await page.goto("/subscriptions");
    await page.waitForLoadState("networkidle");
  });

  test("4.2 podcast list — title, subscribe date", async ({ page }) => {
    await expect(page.locator(".bg-gray-900.rounded-xl, .bg-gray-900.hover\\:bg-gray-800").first()).toBeVisible();
    await expect(page.locator("text=The AI Daily Brief").first()).toBeVisible();
    await expect(page.locator("text=/Since/").first()).toBeVisible();
  });

  test("4.3 tap podcast → episode list", async ({ page }) => {
    await page.locator(".bg-gray-900").filter({ hasText: "Since" }).first().click();
    await expect(backBtn(page)).toBeVisible({ timeout: 10_000 });
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible({ timeout: 15_000 });
  });

  test("4.4 back button → returns to podcast list", async ({ page }) => {
    await page.locator(".bg-gray-900").filter({ hasText: "Since" }).first().click();
    await expect(backBtn(page)).toBeVisible({ timeout: 10_000 });
    await backBtn(page).click();
    await expect(page.locator("text=The AI Daily Brief").first()).toBeVisible();
    await expect(page.locator("text=/Since/").first()).toBeVisible();
  });

  test("4.5 episode list — title, status badge", async ({ page }) => {
    await page.locator(".bg-gray-900").filter({ hasText: "Since" }).first().click();
    await page.waitForLoadState("networkidle");
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible({ timeout: 15_000 });
    // At least one episode has a rounded-full badge
    await expect(page.locator("span.rounded-full").first()).toBeVisible();
  });

  test("4.6 status badge none → – gray", async ({ page }) => {
    await page.locator(".bg-gray-900").filter({ hasText: "Since" }).first().click();
    await page.waitForLoadState("networkidle");
    await expect(page.locator("span.rounded-full:has-text('–')").first()).toBeVisible({ timeout: 15_000 });
  });

  test("4.7 status badge done → green", async ({ page }) => {
    await page.locator(".bg-gray-900").filter({ hasText: "Since" }).first().click();
    await page.waitForLoadState("networkidle");
    await expect(page.locator("span.bg-green-900").first()).toBeVisible({ timeout: 15_000 });
  });

  test("4.9 tap episode → /player/:id", async ({ page }) => {
    await page.locator(".bg-gray-900").filter({ hasText: "Since" }).first().click();
    await page.waitForLoadState("networkidle");
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible({ timeout: 15_000 });
    await page.locator("div.cursor-pointer").first().click();
    await expect(page).toHaveURL(/\/player\/.+/);
  });

  test("4.10 refresh button spins", async ({ page }) => {
    await page.locator(".bg-gray-900").filter({ hasText: "Since" }).first().click();
    await page.waitForLoadState("networkidle");
    const refreshBtn = page.locator("button[title='Refresh episodes']");
    await expect(refreshBtn).toBeVisible({ timeout: 10_000 });
    await refreshBtn.click();
    await expect(refreshBtn.locator(".animate-spin")).toBeVisible({ timeout: 5_000 });
  });

  test("4.11 episode cache hit on return", async ({ page }) => {
    await page.locator(".bg-gray-900").filter({ hasText: "Since" }).first().click();
    await page.waitForLoadState("networkidle");
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible({ timeout: 15_000 });
    const firstTitle = await page.locator(".bg-gray-900.rounded-xl .text-sm.font-medium").first().textContent();

    await backBtn(page).click();
    await page.locator(".bg-gray-900").filter({ hasText: "Since" }).first().click();
    // Cache hit — no skeleton, episodes appear immediately
    await expect(page.locator(".animate-pulse")).not.toBeVisible();
    await expect(page.locator(`text=${firstTitle?.trim()}`)).toBeVisible();
  });

  test("4.12 unsubscribe shows confirm dialog", async ({ page }) => {
    await page.locator(".bg-gray-900").filter({ hasText: "Since" }).first().click();
    await page.waitForLoadState("networkidle");

    let dialogSeen = false;
    page.on("dialog", dialog => { dialogSeen = true; dialog.dismiss(); });
    await page.locator("button[title='Unsubscribe']").click();
    await page.waitForTimeout(500);
    expect(dialogSeen).toBe(true);
    // Dismissed → still on episode list
    await expect(backBtn(page)).toBeVisible();
  });

  test("4.13 unsubscribe cancel → stays on list", async ({ page }) => {
    await page.locator(".bg-gray-900").filter({ hasText: "Since" }).first().click();
    await page.waitForLoadState("networkidle");

    page.on("dialog", dialog => dialog.dismiss());
    await page.locator("button[title='Unsubscribe']").click();
    await page.waitForTimeout(500);
    await expect(backBtn(page)).toBeVisible();
    await expect(page.locator(".bg-gray-900.rounded-xl").first()).toBeVisible();
  });

});
