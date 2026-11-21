import { expect, test } from "@playwright/test";
import { bff, expectOnHome, fakeBrn, login, totp } from "./helpers";

/**
 * M1 완료 게이트 E2E(docs/10 F7): 관리자 2단계 인증 등록 → 사업자 등록 → 제한 사용자 범위 설정 →
 * 제한 사용자의 화면·검색·직접 조회·내보내기가 같은 범위로 일관되게 제한되는지.
 */
const ADMIN = { email: process.env.E2E_ADMIN_EMAIL ?? "admin@e2e.test", password: process.env.E2E_ADMIN_PASSWORD ?? "" };
const RESTRICTED = { email: `md-${Date.now()}@e2e.test`, password: "E2e-Md-pass-4826" };
const RUN = Date.now().toString(36);
const NAMES = { a: `A 코스메틱 ${RUN}`, b: `B 리빙 ${RUN}` };

let secret = "";
let ids: { a?: string; b?: string } = {};

test.describe.serial("Foundation", () => {
  test("관리자는 2단계 인증을 등록해야 시작할 수 있다", async ({ page }) => {
    await login(page, ADMIN.email, ADMIN.password);
    await expect(page.getByRole("heading", { name: "2단계 인증 등록" })).toBeVisible();
    secret = (await page.getByTestId("totp-secret").innerText()).replace(/\s/g, "");
    expect(secret).toMatch(/^[A-Z2-7]{32}$/);

    await page.getByLabel("앱에 표시된 6자리 코드").fill("000000");
    await page.getByRole("button", { name: "등록 완료" }).click();
    await expect(page.locator("main").getByRole("alert")).toContainText("인증 코드가 맞지 않습니다");

    await page.getByLabel("앱에 표시된 6자리 코드").fill(totp(secret));
    await page.getByRole("button", { name: "등록 완료" }).click();
    await expect(page.getByTestId("recovery-codes").locator("span")).toHaveCount(10);
    await page.getByRole("button", { name: /보관했습니다/ }).click();
    await expectOnHome(page, "최고관리자");
  });

  test("관리자: 사업자 등록(검증 오류 표시 포함)", async ({ page }) => {
    await login(page, ADMIN.email, ADMIN.password);
    await page.getByLabel("인증 코드").fill(totp(secret, 1));
    await page.getByRole("button", { name: "확인" }).click();
    await expectOnHome(page, "최고관리자");

    await page.getByRole("link", { name: "사업자 관리" }).click();
    for (const [key, name] of Object.entries(NAMES)) {
      await page.getByRole("button", { name: "사업자 등록" }).click();
      const dlg = page.getByRole("dialog", { name: "사업자 등록" });
      await dlg.getByLabel("표시 이름").fill(name);
      await dlg.getByLabel("법인명·상호").fill(`${name} 주식회사`);
      await dlg.getByLabel("사업자등록번호").fill(key === "a" ? "123-45-67890" : fakeBrn());
      await page.getByRole("button", { name: "등록", exact: true }).click();
      if (key === "a") {
        await expect(dlg.getByRole("alert")).toBeVisible(); // 검증번호가 맞지 않는 번호
        await dlg.getByLabel("사업자등록번호").fill(fakeBrn());
        await page.getByRole("button", { name: "등록", exact: true }).click();
      }
      await expect(page.getByRole("dialog")).toHaveCount(0);
      await expect(page.getByRole("cell", { name: new RegExp(name) })).toBeVisible();
    }
    const list = await (await bff(page.request).get(`/business-entities?q=${encodeURIComponent(RUN)}`)).json();
    ids = Object.fromEntries(list.data.map((e: { id: string; display_name: string }) => [e.display_name === NAMES.a ? "a" : "b", e.id]));
    expect(ids.a && ids.b).toBeTruthy();

    // 제한 사용자 생성(상품/MD: 내보내기 권한 있음, 2단계 인증 필수 역할 아님)
    const res = await bff(page.request).post("/users", { email: RESTRICTED.email, display_name: "한상품", initial_password: RESTRICTED.password, role_codes: ["MD"] },
      { "idempotency-key": `e2e-${RUN}` });
    expect(res.status()).toBe(201);

    // 사용자·권한 화면에서 조회·내보내기 범위를 A 사업자로 제한
    await page.getByRole("link", { name: "사용자·권한" }).click();
    await page.getByRole("cell", { name: /한상품/ }).click();
    const drawer = page.getByRole("dialog", { name: "한상품" });
    for (const legend of ["조회", "내보내기"]) {
      const fs = drawer.locator("fieldset", { has: page.locator("legend", { hasText: legend }) });
      await fs.getByLabel("선택한 사업자만").check();
      await fs.getByLabel(NAMES.a).check();
    }
    await drawer.getByRole("button", { name: "접근 범위 저장" }).click();
    await expect(drawer.getByRole("status")).toContainText("접근 범위를 저장했습니다");
  });

  test("제한 사용자: 화면·직접 조회·검색·내보내기가 모두 A 사업자로 제한된다", async ({ page }) => {
    await login(page, RESTRICTED.email, RESTRICTED.password);
    await expectOnHome(page, "한상품");

    await page.getByRole("link", { name: "사업자 관리" }).click();
    await expect(page.getByRole("cell", { name: new RegExp(NAMES.a) })).toBeVisible();
    await expect(page.getByRole("cell", { name: new RegExp(NAMES.b) })).toHaveCount(0);
    await expect(page.getByLabel("사업자 필터").locator("option")).toHaveText([/전체 사업자/, NAMES.a]);
    await expect(page.getByRole("button", { name: "사업자 등록" })).toHaveCount(0); // 관리 권한 없음

    const api = bff(page.request);
    expect((await api.get(`/business-entities/${ids.b}`)).status()).toBe(404); // 범위 밖은 존재 여부도 숨김
    const filtered = await api.get(`/business-entities?business_entity_id=${ids.b}`);
    expect(filtered.status()).toBe(403);
    expect((await filtered.json()).error.code).toBe("ACCESS_SCOPE_DENIED");
    const search = await (await api.get(`/search?q=${encodeURIComponent(RUN)}`)).json();
    const hits = JSON.stringify(search.data);
    expect(hits).toContain(NAMES.a);
    expect(hits).not.toContain(NAMES.b);

    await page.getByRole("button", { name: "내보내기" }).click();
    const link = page.getByTestId("export-download");
    await expect(link).toBeVisible({ timeout: 30_000 });
    const href = await link.getAttribute("href");
    const csv = await (await page.request.get(href!)).text();
    expect(csv).toContain(NAMES.a);
    expect(csv).not.toContain(NAMES.b);

    // 변경 요청은 CSRF 헤더 없이는 거부
    const noCsrf = await page.request.post("/bff/api/business-entities", { data: {} });
    expect(noCsrf.status()).toBe(403);
  });

  test("로그아웃하면 보호된 화면에 들어갈 수 없다", async ({ page }) => {
    await login(page, RESTRICTED.email, RESTRICTED.password);
    await expectOnHome(page, "한상품");
    await page.getByRole("button", { name: "로그아웃" }).click();
    await page.waitForURL(/\/login/);
    await page.goto("/settings/business-entities");
    await expect(page).toHaveURL(/\/login/);
    const r = await page.request.get("/bff/api/me");
    expect(r.status()).toBe(401);
  });
});
