import { defineConfig } from "@playwright/test";

// 스택 기동은 scripts/e2e.sh 가 맡는다. 시나리오는 순서가 있는 한 흐름(관리자 → 제한 사용자)이라 직렬 실행.
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  reporter: [["list"]],
  use: { baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000", trace: "off", locale: "ko-KR", timezoneId: "Asia/Seoul" },
});
