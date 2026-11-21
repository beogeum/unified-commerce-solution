import { cookies } from "next/headers";
import { NextRequest, NextResponse } from "next/server";
import { BACKEND_URL, CSRF_HEADER, SESSION_COOKIE, cookieOptions } from "@/lib/server/backend";

/** 로그인: 백엔드 세션을 만들고 토큰은 httpOnly 쿠키로만 보관한다. */
export async function POST(req: NextRequest) {
  if (req.headers.get(CSRF_HEADER) !== "1") {
    return NextResponse.json({ error: { code: "CSRF_REJECTED", message: "잘못된 요청입니다." } }, { status: 403 });
  }
  const body = await req.json().catch(() => ({}));
  const res = await fetch(`${BACKEND_URL}/api/v1/auth/sessions`, {
    method: "POST",
    headers: { "content-type": "application/json", "user-agent": req.headers.get("user-agent") ?? "" },
    body: JSON.stringify({ workspace_code: body.workspace_code, email: body.email, password: body.password }),
    cache: "no-store",
  });
  const json = await res.json().catch(() => null);
  if (res.status !== 201 || !json?.data?.access_token) {
    return NextResponse.json(json ?? { error: { code: "LOGIN_FAILED", message: "로그인하지 못했습니다." } }, { status: res.status });
  }
  const { access_token, expires_at, ...rest } = json.data;
  (await cookies()).set(SESSION_COOKIE, access_token, cookieOptions(expires_at));
  return NextResponse.json({ data: { ...rest, expires_at } }, { status: 201 });
}

/** 로그아웃: 백엔드 세션 폐기 후 쿠키 삭제. */
export async function DELETE(req: NextRequest) {
  if (req.headers.get(CSRF_HEADER) !== "1") {
    return NextResponse.json({ error: { code: "CSRF_REJECTED", message: "잘못된 요청입니다." } }, { status: 403 });
  }
  const jar = await cookies();
  const token = jar.get(SESSION_COOKIE)?.value;
  if (token) {
    await fetch(`${BACKEND_URL}/api/v1/auth/sessions/current`, {
      method: "DELETE",
      headers: { authorization: `Bearer ${token}` },
      cache: "no-store",
    }).catch(() => undefined);
  }
  jar.delete(SESSION_COOKIE);
  return new NextResponse(null, { status: 204 });
}
