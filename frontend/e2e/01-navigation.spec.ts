import { test, expect } from "@playwright/test";
import { goTo, navTab } from "./helpers";

test.describe("Suite 1 — Navigation & Shell", () => {

  test("1.1 header and 4 nav tabs visible", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByText("🎧 PodGist")).toBeVisible();
    await expect(navTab(page, "Home")).toBeVisible();
    await expect(navTab(page, "Search")).toBeVisible();
    await expect(navTab(page, "Library")).toBeVisible();
    await expect(navTab(page, "Gists")).toBeVisible();
  });

  test("1.2 Home tab active on load", async ({ page }) => {
    await page.goto("/");
    // Active tab has text-indigo-400 class
    await expect(navTab(page, "Home")).toHaveClass(/text-indigo-400/);
    // Other tabs are not active
    await expect(navTab(page, "Search")).not.toHaveClass(/text-indigo-400/);
  });

  test("1.3 tap Search → URL /search, Search active", async ({ page }) => {
    await page.goto("/");
    await goTo(page, "Search");
    await expect(page).toHaveURL("/search");
    await expect(navTab(page, "Search")).toHaveClass(/text-indigo-400/);
  });

  test("1.4 tap Library → URL /subscriptions", async ({ page }) => {
    await page.goto("/");
    await goTo(page, "Library");
    await expect(page).toHaveURL("/subscriptions");
    await expect(navTab(page, "Library")).toHaveClass(/text-indigo-400/);
  });

  test("1.5 tap Gists → URL /gists", async ({ page }) => {
    await page.goto("/");
    await goTo(page, "Gists");
    await expect(page).toHaveURL("/gists");
  });

  test("1.6 tap Home → URL /", async ({ page }) => {
    await page.goto("/search");
    await goTo(page, "Home");
    await expect(page).toHaveURL("/");
  });

  test("1.7 only one tab active at a time", async ({ page }) => {
    await page.goto("/search");
    // Search should be active, others not
    await expect(navTab(page, "Search")).toHaveClass(/text-indigo-400/);
    await expect(navTab(page, "Home")).not.toHaveClass(/text-indigo-400/);
    await expect(navTab(page, "Library")).not.toHaveClass(/text-indigo-400/);
    await expect(navTab(page, "Gists")).not.toHaveClass(/text-indigo-400/);
  });

});
