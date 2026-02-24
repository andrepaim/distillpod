import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  retries: 1,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: "http://localhost:8124",
    browserName: "chromium",
    viewport: { width: 390, height: 844 },
    hasTouch: true,
    isMobile: true,
    screenshot: "only-on-failure",
    video: "off",
    actionTimeout: 15_000,
    navigationTimeout: 20_000,
  },
});
