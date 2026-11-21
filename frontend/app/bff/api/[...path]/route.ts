import { cookies } from "next/headers";
import { NextRequest, NextResponse } from "next/server";
import { BACKEND_URL, CSRF_HEADER, SESSION_COOKIE } from "@/lib/server/backend";

/**
 * 브라우저 → 백엔드 API 중계. 쿠키의 토큰을 Authorization 헤더로 바꿔 붙인다.
 * 변경 요청은 사용자 정의 헤더(x-cos-csrf)가 있어야 한다(다른 사이트의 폼 전송 차단, SameSite=Strict 와 이중 방어).
 */
const PASS_REQUEST = ["content-type", "if-match", "idempotency-key", "accept"];
const PASS_RESPONSE = ["content-type", "etag", "content-disposition", "idempotent-replayed", "retry-after"];

async function forward(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  if (path.some((p) => p === ".." || p.includes("/"))) {
    return NextResponse.json({ error: { code: "NOT_FOUND", message: "대상을 찾을 수 없습니다." } }, { status: 404 });
  }
  if (req.method !== "GET" && req.method !== "HEAD" && req.headers.get(CSRF_HEADER) !== "1") {
    return NextResponse.json({ error: { code: "CSRF_REJECTED", message: "잘못된 요청입니다." } }, { status: 403 });
  }
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  const headers = new Headers();
  for (const h of PASS_REQUEST) {
    const v = req.headers.get(h);
    if (v) headers.set(h, v);
  }
  if (token) headers.set("authorization", `Bearer ${token}`);
  const url = `${BACKEND_URL}/api/v1/${path.map(encodeURIComponent).join("/")}${req.nextUrl.search}`;
  const hasBody = !["GET", "HEAD", "DELETE"].includes(req.method);
  const res = await fetch(url, {
    method: req.method,
    headers,
    body: hasBody ? await req.arrayBuffer() : undefined,
    cache: "no-store",
    redirect: "manual",
  });
  const out = new Headers();
  for (const h of PASS_RESPONSE) {
    const v = res.headers.get(h);
    if (v) out.set(h, v);
  }
  out.set("cache-control", "no-store");
  return new NextResponse(res.status === 204 ? null : await res.arrayBuffer(), { status: res.status, headers: out });
}

export const GET = forward;
export const POST = forward;
export const PUT = forward;
export const PATCH = forward;
export const DELETE = forward;
