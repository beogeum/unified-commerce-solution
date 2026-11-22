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

    // A 사업자의 판매계정(C3 채널상품용)
    const acc = await bff(page.request).post("/channel-accounts", { business_entity_id: ids.a, sales_channel_code: "NAVER_SMARTSTORE",
      display_name: `A 공식스토어 ${RUN}`, external_account_key: `naver-${RUN}` }, { "idempotency-key": `acc-${RUN}` });
    expect(acc.status()).toBe(201);

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

  test("상품/MD: 상품·SKU·사업자 정책·채널상품·BOM·GWP·일괄 가져오기 (M2 C1~C6)", async ({ page }) => {
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
    await drawer.getByRole("button", { name: "닫기", exact: true }).click();

    // C3: 채널상품 등록 → 외부 SKU 코드로 제안 → 연결 → 미연결 목록에서 사라짐
    await page.goto("/channel-listings");
    await page.getByRole("button", { name: "채널상품 등록" }).click();
    const reg = page.getByRole("dialog", { name: "채널상품 등록" });
    await reg.getByLabel("외부 상품ID").fill(`9921${RUN}`);
    await reg.getByLabel("외부 옵션ID(없으면 비움)").fill("2289101");
    await reg.getByLabel("채널 상품명").fill("비타 앰플 30ml");
    await reg.getByLabel(/외부 SKU 코드/).fill(`AMP-30-${RUN}`);
    await page.getByRole("button", { name: "등록", exact: true }).click();
    const ld = page.getByRole("dialog", { name: "비타 앰플 30ml" });
    await ld.getByRole("button", { name: `AMP-30-${RUN} 연결` }).first().click();
    await expect(ld.getByText(`AMP-30-${RUN}에 연결했습니다.`)).toBeVisible();
    await ld.getByRole("button", { name: "닫기" }).click();
    await expect(page.getByText("미연결 채널상품이 없습니다.")).toBeVisible();

    // C4: 가상묶음 SKU 의 판매묶음 BOM 초안 → 구성품 → 게시
    const setProduct = await (await bff(page.request).post("/products", { product_code: `SET${RUN}`, name: "앰플 듀오 세트" },
      { "idempotency-key": `setp-${RUN}` })).json();
    expect((await bff(page.request).post("/skus", { product_id: setProduct.data.product.id, sku_code: `SET-DUO-${RUN}`, sku_type: "BUNDLE" },
      { "idempotency-key": `sets-${RUN}` })).status()).toBe(201);
    await page.goto("/boms");
    await page.getByRole("button", { name: "BOM 초안 만들기" }).click();
    const bd = page.getByRole("dialog", { name: "BOM 초안 만들기" });
    await bd.getByLabel(/상위 SKU/).fill(`SET-DUO-${RUN}`);
    await bd.getByRole("button", { name: new RegExp(`SET-DUO-${RUN}`) }).click();
    for (const code of [`AMP-30-${RUN}`, `AMP-50-${RUN}`]) {
      await bd.getByLabel("구성품 추가").fill(code);
      await bd.getByRole("button", { name: new RegExp(code) }).click();
    }
    await bd.getByLabel(`AMP-50-${RUN} 수량`).fill("2");
    await page.getByRole("button", { name: "초안 저장" }).click();
    const bomDrawer = page.getByRole("dialog", { name: new RegExp(`SET-DUO-${RUN}`) });
    await bomDrawer.getByRole("button", { name: "게시" }).click();
    await expect(page.getByRole("alertdialog")).toContainText(`AMP-30-${RUN}×1 + AMP-50-${RUN}×2`);
    await page.getByRole("alertdialog").getByRole("button", { name: "게시" }).click();
    await expect(bomDrawer.getByText("게시했습니다.")).toBeVisible();
    await expect(bomDrawer.getByRole("button", { name: "종료" })).toBeVisible(); // 게시 상태에서만 보이는 동작
    await bomDrawer.getByRole("button", { name: "닫기" }).click();

    // C5: GWP 캠페인 → 진행 → 미리보기(T13: 채널이 같은 행사 사은품을 이미 주면 내부 지급 생략)
    expect((await bff(page.request).post("/skus", { product_id: setProduct.data.product.id, sku_code: `GIFT-${RUN}`, sku_type: "GIFT" },
      { "idempotency-key": `gift-${RUN}` })).status()).toBe(201);
    const kst = (ms: number) => new Date(Date.now() + ms + 9 * 3600_000).toISOString().slice(0, 16);
    await page.goto("/gwp");
    await page.getByRole("button", { name: "캠페인 만들기" }).click();
    const gf = page.getByRole("dialog", { name: "캠페인 만들기" });
    await gf.getByLabel("캠페인 이름").fill(`겨울 파우치 ${RUN}`);
    await gf.getByLabel("시작(한국 시간)").fill(kst(-3600_000));
    await gf.getByLabel("종료(한국 시간)").fill(kst(7 * 86400_000));
    await gf.getByLabel("사은품 추가").fill(`GIFT-${RUN}`);
    await gf.getByRole("button", { name: new RegExp(`GIFT-${RUN}`) }).click();
    await gf.getByLabel(/채널 행사 번호/).fill(`NAVER-${RUN}`);
    await page.getByRole("button", { name: "초안 저장" }).click();
    const cd = page.getByRole("dialog", { name: `겨울 파우치 ${RUN}` });
    await cd.getByRole("button", { name: "진행 시작" }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "확인" }).click();
    await expect(cd.getByText("진행", { exact: true })).toBeVisible();
    await cd.getByRole("button", { name: "닫기" }).click();

    await page.getByRole("button", { name: "지급 미리보기" }).click();
    const pv = page.getByRole("dialog", { name: "지급 미리보기" });
    await pv.getByLabel("주문 상품 추가").fill(`AMP-30-${RUN}`);
    await pv.getByRole("button", { name: new RegExp(`AMP-30-${RUN}`) }).click();
    await pv.getByLabel(`AMP-30-${RUN} 결제금액`).fill("33000");
    await page.getByRole("button", { name: "판정" }).click();
    await expect(pv.getByRole("region", { name: "판정 결과" })).toContainText(`겨울 파우치 ${RUN}`);
    await expect(pv.getByRole("region", { name: "판정 결과" })).toContainText("1개");
    await pv.getByLabel(/행사 번호/).fill(`NAVER-${RUN}`);
    await page.getByRole("button", { name: "판정" }).click();
    await expect(pv.getByRole("region", { name: "판정 결과" })).toContainText("채널이 이미 같은 사은품을 지급");
    await pv.getByRole("button", { name: "닫기" }).click();

    // C6: CSV 가져오기 → 미리보기(정상 1·오류 1) → 확인 후 실행 → 완료
    await page.goto("/imports");
    const csv = `\uFEFF상품코드*,상품명*,SKU 코드*,중량(g)\r\nCLF${RUN},클렌징폼,CLF-150-${RUN},150\r\nDUP${RUN},중복,AMP-30-${RUN},10\r\n`;
    await page.getByLabel("가져올 파일").setInputFiles({ name: "신규상품.csv", mimeType: "text/csv", buffer: Buffer.from(csv, "utf8") });
    const panel = page.getByRole("region", { name: /미리보기 — 신규상품.csv/ });
    await expect(panel).toContainText("정상 1행");
    await expect(panel).toContainText("오류 1행");
    await expect(panel).toContainText("SKU 코드가 이미 있습니다");
    await panel.getByRole("button", { name: "정상·경고 1행 실행" }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "1행 실행" }).click();
    await expect(panel).toContainText("완료", { timeout: 30_000 });
    await expect(panel).toContainText("성공 1 · 실패 0");
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

  test("관리자: 출고거점 → 입고 → 예약·할당·피킹·출고 확정 → 원장·검증 (M3 I1~I3, T06)", async ({ page }) => {
    await login(page, ADMIN.email, ADMIN.password);
    const next = await nextTotp(secret, lastStep);
    lastStep = next.step;
    await page.getByLabel("인증 코드").fill(next.code);
    await page.getByRole("button", { name: "확인" }).click();
    await expectOnHome(page, "최고관리자");

    await page.getByRole("link", { name: "물류·3PL" }).click();
    await page.getByRole("button", { name: "거점 추가" }).click();
    const nd = page.getByRole("dialog", { name: "출고거점 추가" });
    await nd.getByLabel("거점 코드").fill(`SELF-${RUN}`.toUpperCase());
    await nd.getByLabel("거점 이름").fill(`화성 자체창고 ${RUN}`);
    await nd.getByLabel("출고 마감 시각").fill("16:00");
    await page.getByRole("button", { name: "저장" }).click();
    const drawer = page.getByRole("dialog", { name: `화성 자체창고 ${RUN}` });
    await expect(drawer).toContainText("내부 원장");
    await drawer.getByRole("button", { name: "닫기", exact: true }).click();
    await expect(page.getByRole("row", { name: new RegExp(`화성 자체창고 ${RUN}`) })).toContainText("16:00");

    await page.getByRole("link", { name: "재고", exact: true }).click();
    await page.getByRole("button", { name: "입고 등록" }).click();
    const rd = page.getByRole("dialog", { name: "입고 등록" });
    await rd.getByLabel("소유 사업자").selectOption({ label: NAMES.a });
    await rd.getByLabel("입고 거점").selectOption({ label: `화성 자체창고 ${RUN} (${`SELF-${RUN}`.toUpperCase()})` });
    await rd.getByLabel("SKU", { exact: true }).fill(`AMP-30-${RUN}`);
    await rd.getByRole("button", { name: new RegExp(`AMP-30-${RUN}`) }).click();
    await rd.getByLabel("수량").fill("5");
    await rd.getByLabel("참조 유형").fill("PO");
    await rd.getByLabel("참조 번호").fill(`PO-${RUN}`);
    await page.getByRole("button", { name: "입고 기록" }).click();
    const posRow = page.getByRole("row", { name: new RegExp(`AMP-30-${RUN}`) });
    await expect(posRow).toContainText("5");

    // 주문 수집(M4) 전이므로 확보는 API 로 만들고, 단계 전환은 화면에서 한다
    const skus = await (await bff(page.request).get(`/skus?q=AMP-30-${RUN}`)).json();
    const nodes = await (await bff(page.request).get("/fulfillment-nodes")).json();
    const nodeId = nodes.data.find((n: { code: string }) => n.code === `SELF-${RUN}`.toUpperCase()).id;
    const res = await bff(page.request).post("/inventory/reservations", { business_entity_id: ids.a, node_id: nodeId, demand_type: "ORDER_LINE",
      demand_ref: `E2E-${RUN}`, lines: [{ sku_id: skus.data[0].id, quantity: 2 }] }, { "idempotency-key": `e2e-res-${RUN}` });
    expect(res.status()).toBe(201);

    await page.getByRole("link", { name: "예약·할당" }).click();
    const resRow = page.getByRole("row", { name: new RegExp(`E2E-${RUN}`) });
    for (const [btn, chip] of [["할당", "할당"], ["피킹 시작", "피킹"], ["출고 확정", null]] as const) {
      await resRow.getByRole("button", { name: btn }).click();
      const m = page.getByRole("alertdialog");
      await expect(m).toContainText(btn === "출고 확정" ? "ATP 변화 없음" : "판매 가능 재고(ATP) 변화 없음");
      await m.getByRole("button", { name: btn }).click();
      await expect(m).toBeHidden();
      if (chip) await expect(resRow).toContainText(chip);
    }
    await expect(resRow).toBeHidden(); // 진행 중 목록에서 빠짐
    await page.getByLabel("단계").selectOption({ label: "출고 확정" });
    await expect(resRow).toContainText("출고 확정");

    await page.getByRole("link", { name: "통합재고" }).click();
    await expect(page.getByText("재고 검증: 집계·버킷·확보·원장이 모두 일치합니다.")).toBeVisible();
    await expect(posRow.getByRole("cell").nth(2)).toHaveText("3"); // 정상 5 − 출고 2
    await expect(posRow.getByRole("cell").nth(7)).toHaveText("3"); // 추가 판매가능: 확보 단계 전환으로 다시 줄지 않음
    await page.getByRole("link", { name: "재고원장" }).click();
    for (const t of ["입고", "예약", "할당", "피킹", "출고 확정"]) {
      await expect(page.getByRole("cell", { name: new RegExp(`^${t}`) }).first()).toBeVisible();
    }
  });

  test("관리자: 3PL 거점 재고 파일 → 수량 의미 미확인 판매 제한 → 확인 후 해제 (M3 I4, T09)", async ({ page }) => {
    await login(page, ADMIN.email, ADMIN.password);
    const next = await nextTotp(secret, lastStep);
    lastStep = next.step;
    await page.getByLabel("인증 코드").fill(next.code);
    await page.getByRole("button", { name: "확인" }).click();
    await expectOnHome(page, "최고관리자");
    const sa = await bff(page.request).post("/shipper-accounts", { business_entity_id: ids.a, logistics_provider_code: "FASSTO",
      display_name: `파스토 화주 ${RUN}`, external_shipper_key: `FS-${RUN}` }, { "idempotency-key": `e2e-sa-${RUN}` });
    expect(sa.status()).toBe(201);

    const nodeName = `파스토 용인 ${RUN}`;
    await page.getByRole("link", { name: "물류·3PL" }).click();
    await page.getByRole("button", { name: "거점 추가" }).click();
    const nd = page.getByRole("dialog", { name: "출고거점 추가" });
    await nd.getByLabel("거점 코드").fill(`FASSTO-${RUN}`.toUpperCase());
    await nd.getByLabel("거점 이름").fill(nodeName);
    await nd.getByLabel("물류사업자").selectOption({ label: "파스토 · 3PL" });
    await nd.getByLabel("출고 마감 시각").fill("15:00");
    await page.getByRole("button", { name: "저장" }).click();
    const drawer = page.getByRole("dialog", { name: nodeName });
    await expect(drawer).toContainText("수량 의미가 확인되지 않아");
    await drawer.getByLabel("화주계정 연결").selectOption({ label: `${NAMES.a} · 파스토 화주 ${RUN}` });
    await drawer.getByRole("button", { name: "연결", exact: true }).click();
    await expect(drawer).toContainText(`파스토 화주 ${RUN}`);
    await drawer.getByRole("button", { name: "닫기", exact: true }).click();

    // 브라우저는 Asia/Seoul(playwright.config) — datetime-local 값도 KST 로 만든다
    const local = (d: Date) => new Date(d.getTime() + 9 * 3600000).toISOString().slice(0, 16);
    const uploadCsv = async (qty: number, minutesAgo: number) => {
      await page.getByRole("button", { name: "재고 파일 올리기" }).click();
      const up = page.getByRole("dialog", { name: "물류사 재고 파일 올리기" });
      await up.getByLabel("출고거점").selectOption({ label: `${nodeName} (${`FASSTO-${RUN}`.toUpperCase()})` });
      await up.getByLabel("물류사 자료 기준시각").fill(local(new Date(Date.now() - minutesAgo * 60000)));
      await up.getByLabel("파일(.xlsx·.csv)").setInputFiles({ name: `fassto-${qty}.csv`, mimeType: "text/csv",
        buffer: Buffer.from(`SKU 코드,수량,판매가능\r\nAMP-30-${RUN},${qty},Y\r\n`, "utf8") });
      await up.getByRole("button", { name: "반영" }).click();
      return page.getByRole("dialog", { name: "외부 재고 자료" });
    };
    await page.goto("/inventory/external");
    let detail = await uploadCsv(7, 10);
    await expect(detail).toContainText("판매 제한 · 수량 의미 미확인");
    await detail.getByRole("button", { name: "닫기", exact: true }).click();
    await page.getByRole("link", { name: "통합재고" }).click();
    await expect(page.getByRole("row", { name: new RegExp(`AMP-30-${RUN}`) }).filter({ hasText: nodeName })).toContainText("판매 제한");

    await page.getByRole("link", { name: "물류·3PL" }).click();
    await page.getByRole("row", { name: new RegExp(nodeName) }).click();
    const d2 = page.getByRole("dialog", { name: nodeName });
    await d2.getByLabel("물류사 재고 수량 의미").selectOption({ label: "가용(접수된 출고지시 제외)" });
    await d2.getByLabel("확인 근거(필수)").fill("파스토 Open API 재고 조회 응답(테스트 계정) 캡처");
    await d2.getByRole("button", { name: "해석 규칙 저장" }).click();
    await expect(d2).toContainText("저장했습니다");
    await d2.getByRole("button", { name: "닫기", exact: true }).click();

    await page.goto("/inventory/external");
    detail = await uploadCsv(6, 2);
    await expect(detail.getByRole("cell", { name: "확인" })).toBeVisible();
    await detail.getByRole("button", { name: "닫기", exact: true }).click();
    await page.getByRole("link", { name: "통합재고" }).click();
    const extRow = page.getByRole("row", { name: new RegExp(`AMP-30-${RUN}`) }).filter({ hasText: nodeName });
    await expect(extRow).not.toContainText("판매 제한");
    await expect(extRow.getByRole("cell").nth(7)).toHaveText("6");
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
