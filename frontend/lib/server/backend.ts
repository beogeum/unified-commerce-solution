import "server-only";

/** 백엔드 주소·세션 쿠키. 접근 토큰은 httpOnly 쿠키에만 두고 브라우저 스크립트에는 노출하지 않는다. */
export const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";
export const SESSION_COOKIE = "cos_session";
export const CSRF_HEADER = "x-cos-csrf";

export function cookieOptions(expiresAt?: string) {
  return {
    httpOnly: true,
    sameSite: "strict" as const,
    secure: process.env.NODE_ENV === "production" && process.env.COOKIE_INSECURE !== "true",
    path: "/",
    ...(expiresAt ? { expires: new Date(expiresAt) } : {}),
  };
}
