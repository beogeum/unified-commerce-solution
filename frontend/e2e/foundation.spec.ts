import { expect, test } from "@playwright/test";
import { bff, currentStep, expectOnHome, fakeBrn, login, nextTotp, totp } from "./helpers";

/**
 * M1 완료 게이트 E2E(docs/10 F7): 관리자 2단계 인증 등록 → 사업자 등록 → 제한 사용자 범위 설정 →
 * 제한 사용자의 화면·검색·직접 조회·내보내기가 같은 범위로 일관되게 제한되는지.
 */
const ADMIN = { email: process.env.E2E_ADMIN_EMAIL ?? "admin@e2e.test", password: process.env.E2E_ADMIN_PASSWORD ?? "" };
const RESTRICTED = { email: `md-${Date.now()}@e2e.test`, password: "E2e-Md-pass-4826" };
const RUN = Date.now().toString(36);
const NAMES = { a: `A 코스메틱 ${RUN}`, b: `B 리빙 ${RUN}` };

let secret = "";
let lastStep = 0;
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

    lastStep = currentStep();
    await page.getByLabel("앱에 표시된 6자리 코드").fill(totp(secret));
    await page.getByRole("button", { name: "등록 완료" }).click();
    await expect(page.getByTestId("recovery-codes").locator("span")).toHaveCount(10);
    await page.getByRole("button", { name: /보관했습니다/ }).click();
    await expectOnHome(page, "최고관리자");
  });

  test("관리자: 사업자 등록(검증 오류 표시 포함)", async ({ page }) => {
    await login(page, ADMIN.email, ADMIN.password);
    const next = await nextTotp(secret, lastStep);
    lastStep = next.step;
    await page.getByLabel("인증 코드").fill(next.code);
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
    await expect(drawer.getByText("접근 범위를 저장했습니다")).toBeVisible();
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

  test("상품/MD: 상품·옵션·SKU 등록과 게시 조건(M2 C1)", async ({ page }) => {
    await login(page, RESTRICTED.email, RESTRICTED.password);
    await expectOnHome(page, "한상품");
    await page.getByRole("link", { name: "브랜드·카테고리" }).click();
    await page.getByLabel("새 브랜드 이름").fill(`루미에르 ${RUN}`);
    await page.getByRole("button", { name: "브랜드 추가" }).click();
    await expect(page.getByRole("cell", { name: `루미에르 ${RUN}` })).toBeVisible();
    await page.getByLabel("새 카테고리 이름").fill(`스킨케어 ${RUN}`);
    await page.getByRole("button", { name: "카테고리 추가" }).click();
    await expect(page.getByRole("cell", { name: `스킨케어 ${RUN}` })).toBeVisible();

    await page.getByRole("link", { name: "상품", exact: true }).click();
    await page.getByRole("button", { name: "상품 등록" }).click();
    const dlg = page.getByRole("dialog", { name: "상품 등록" });
    await dlg.getByLabel("상품코드").fill(`AMP${RUN}`);
    await dlg.getByLabel("상품명").fill("비타 앰플");
    await dlg.getByLabel("브랜드").selectOption({ label: `루미에르 ${RUN}` });
    await dlg.getByLabel("카테고리").selectOption({ label: `스킨케어 ${RUN}` });
    await dlg.getByLabel("과세유형").selectOption({ label: "과세" });
    await dlg.getByLabel(/옵션/).fill("용량: 30ml, 50ml");
    await page.getByRole("button", { name: "초안으로 등록" }).click();
    await expect(page.getByRole("heading", { name: /비타 앰플/ })).toBeVisible();

    // SKU 없이 게시 → 빠진 항목 안내
    await page.getByRole("button", { name: "판매중으로 게시" }).click();
    await expect(page.locator("main").getByRole("alert")).toContainText("판매 가능한 SKU");

    for (const [code, size] of [[`AMP-30-${RUN}`, "30ml"], [`AMP-50-${RUN}`, "50ml"], [`AMP-DUP-${RUN}`, "30ml"]] as const) {
      await page.getByRole("button", { name: "SKU 추가" }).click();
      const s = page.getByRole("dialog", { name: "SKU 추가" });
      await s.getByLabel("SKU 코드").fill(code);
      await s.getByLabel("용량").selectOption({ label: size });
      await s.getByRole("button", { name: "추가", exact: true }).click();
      if (code.startsWith("AMP-DUP")) {
        await expect(s.getByRole("alert")).toContainText("옵션 조합");
        await s.getByRole("button", { name: "닫기" }).click();
      } else {
        await expect(page.getByRole("cell", { name: code })).toBeVisible();
      }
    }
    await page.getByRole("button", { name: "판매중으로 게시" }).click();
    await expect(page.getByRole("heading", { name: /비타 앰플/ })).toContainText("판매중");
    await expect(page.getByText("판매중으로 게시 처리했습니다.")).toBeVisible();

    // SKU 목록·검색
    await page.goto("/skus");
    await page.getByLabel("검색").fill(`AMP-30-${RUN}`);
    await expect(page.getByRole("cell", { name: `AMP-30-${RUN}` })).toBeVisible();

    // C2: 사업자별 정책 — A 만 보이고, 최저판매가는 승인 대기
    await page.getByRole("cell", { name: `AMP-30-${RUN}` }).click();
    await expect(page.getByRole("heading", { name: /비타 앰플/ })).toBeVisible();
    await page.getByRole("cell", { name: `AMP-30-${RUN}` }).click();
    const drawer = page.getByRole("dialog", { name: /사업자별 정책/ });
    await expect(drawer.getByRole("region", { name: `${NAMES.a} 정책` })).toBeVisible();
    await expect(drawer.getByRole("region", { name: `${NAMES.b} 정책` })).toHaveCount(0);
    await drawer.getByRole("region", { name: `${NAMES.a} 정책` }).getByRole("button", { name: "편집" }).click();
    const form = drawer.getByRole("form", { name: `${NAMES.a} 정책 편집` });
    await form.getByLabel(/기준 판매가/).fill("33000");
    await form.getByLabel(/^최저판매가\(원\)/).fill("29700");
    await form.getByLabel("최저판매가 변경 사유").fill("최저가 보호");
    await form.getByRole("button", { name: "저장" }).click();
    const card = drawer.getByRole("region", { name: `${NAMES.a} 정책` });
    await expect(card).toContainText("33,000원");
    await expect(card).toContainText("공급가 30,000");
    await expect(card).toContainText("29,700원 승인 대기");
  });

  test("관리자: 최저판매가 승인 → 반영(C2, 자기 요청 아님)", async ({ page }) => {
    await login(page, ADMIN.email, ADMIN.password);
    const next = await nextTotp(secret, lastStep);
    lastStep = next.step;
    await page.getByLabel("인증 코드").fill(next.code);
    await page.getByRole("button", { name: "확인" }).click();
    await expectOnHome(page, "최고관리자");
    await page.getByRole("link", { name: "승인" }).click();
    const row = page.getByRole("row", { name: /최저판매가 변경/ }).filter({ hasText: `AMP-30-${RUN}` });
    await row.getByRole("button", { name: "승인" }).click();
    const modal = page.getByRole("alertdialog");
    await expect(modal).toContainText("없음 → 29,700원");
    await modal.getByRole("button", { name: "승인" }).click();
    await expect(page.getByText("승인해 반영했습니다.")).toBeVisible();
    const skus = await (await bff(page.request).get(`/skus?q=AMP-30-${RUN}`)).json();
    const policy = await (await bff(page.request).get(`/business-entities/${ids.a}/sku-policies/${skus.data[0].id}`)).json();
    expect(policy.data.min_price).toBe("29700");
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
