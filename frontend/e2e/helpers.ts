import { type Page, type APIRequestContext } from "@playwright/test";

export const BASE = "http://localhost:8124";

// Live test data constants
export const AIDB_PODCAST_ID    = "1680633614";
export const LEX_PODCAST_ID     = "360084272";
export const EPISODE_DONE_ID    = "a53389ba-7294-404f-948c-f09c0b3e726d"; // transcript=done
export const EPISODE_PROC_ID    = "a47b4d01-d552-4c16-abd6-c205c641e0e0"; // transcript=processing

/** Create a gist via API, returns gist id */
export async function createGist(
  request: APIRequestContext,
  episodeId = EPISODE_DONE_ID,
  seconds = 364,
): Promise<string> {
  const r = await request.post(`${BASE}/gists/`, {
    data: { episode_id: episodeId, current_seconds: seconds },
  });
  const body = await r.json();
  return body.id;
}

/** Delete all gists via API */
export async function clearGists(request: APIRequestContext): Promise<void> {
  const r = await request.get(`${BASE}/gists/`);
  const gists = await r.json();
  await Promise.all(gists.map((g: { id: string }) =>
    request.delete(`${BASE}/gists/${g.id}`)
  ));
}

/** Navigate to a tab via bottom nav (scoped to <nav> to avoid ambiguity with page buttons) */
export async function goTo(page: Page, tab: "Home" | "Search" | "Library" | "Gists") {
  await page.locator("nav").getByRole("button", { name: tab }).click();
  await page.waitForLoadState("networkidle");
}

/** Scope to nav and get the tab button */
export function navTab(page: Page, tab: string) {
  return page.locator("nav").getByRole("button", { name: tab });
}

/** Clear localStorage played state — call AFTER page.goto() */
export async function clearPlayed(page: Page) {
  await page.evaluate(() => localStorage.removeItem("podgist:played"));
}

/** Seed saved progress in localStorage — call AFTER page.goto() */
export async function seedProgress(page: Page, episodeId: string, currentTime: number, duration = 3600) {
  await page.evaluate(
    ([key, id, t, d]) => {
      const map = JSON.parse(localStorage.getItem(key as string) || "{}");
      map[id as string] = { currentTime: t, duration: d, savedAt: Date.now() };
      localStorage.setItem(key as string, JSON.stringify(map));
    },
    ["podgist:progress", episodeId, currentTime, duration] as const,
  );
}

/** Clear all saved progress from localStorage — call AFTER page.goto() */
export async function clearProgress(page: Page) {
  await page.evaluate(() => localStorage.removeItem("podgist:progress"));
}
