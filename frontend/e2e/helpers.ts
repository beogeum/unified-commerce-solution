import { createHmac } from "node:crypto";
import { APIRequestContext, expect, Page } from "@playwright/test";

const B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

function base32Decode(s: string): Buffer {
  let bits = 0, value = 0;
  const out: number[] = [];
  for (const ch of s.replace(/[\s=]/g, "").toUpperCase()) {
    value = (value << 5) | B32.indexOf(ch);
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

/** RFC 6238 — 테스트에서 OTP 앱 대신 코드 계산. offset 으로 다음 30초 구간 코드를 만든다(재사용 방지 회피). */
export function currentStep(): number {
  return Math.floor(Date.now() / 1000 / 30);
}

/** 재사용 방지(직전 사용 구간보다 큰 구간만 허용, 허용 창 ±1)를 지키며 다음 코드를 만든다. 필요하면 다음 구간까지 기다린다. */
export async function nextTotp(secret: string, lastStep: number): Promise<{ code: string; step: number }> {
  while (lastStep + 1 > currentStep() + 1) {
    await new Promise((r) => setTimeout(r, 1000));
  }
  const step = Math.max(currentStep(), lastStep + 1);
  return { code: totpAt(secret, step), step };
}

export function totp(secret: string, offset = 0): string {
  return totpAt(secret, currentStep() + offset);
}

export function totpAt(secret: string, step: number): string {
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(step));
  const h = createHmac("sha1", base32Decode(secret)).update(msg).digest();
  const o = h[h.length - 1] & 0x0f;
  const bin = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(bin % 1_000_000).padStart(6, "0");
}

/** 가상 사업자등록번호(검증번호 포함). 실제 번호를 쓰지 않는다(docs/09). */
export function fakeBrn(): string {
  const d = Array.from({ length: 9 }, (_, i) => (i === 0 ? 9 : Math.floor(Math.random() * 10)));
  const w = [1, 3, 7, 1, 3, 7, 1, 3, 5];
  let sum = d.reduce((a, x, i) => a + x * w[i], 0) + Math.floor((d[8] * 5) / 10);
  const check = (10 - (sum % 10)) % 10;
  const s = [...d, check].join("");
  return `${s.slice(0, 3)}-${s.slice(3, 5)}-${s.slice(5)}`;
}

export async function login(page: Page, email: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인" }).click();
}

export function bff(request: APIRequestContext) {
  const h = { "x-cos-csrf": "1", "content-type": "application/json" };
  return {
    get: (p: string) => request.get(`/bff/api${p}`),
    post: (p: string, body: unknown, extra: Record<string, string> = {}) => request.post(`/bff/api${p}`, { headers: { ...h, ...extra }, data: body }),
  };
}

export async function expectOnHome(page: Page, name: string) {
  await expect(page.getByTestId("me-name")).toHaveText(name);
}
