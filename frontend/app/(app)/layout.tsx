import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { AppShell } from "@/components/AppShell";
import { BACKEND_URL, SESSION_COOKIE } from "@/lib/server/backend";
import type { Me } from "@/lib/session";

export const dynamic = "force-dynamic";

/** 로그인한 사용자만. 세션이 없거나 만료되면 로그인, 2단계 인증 미완료면 로그인의 인증 단계로 보낸다. */
export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!token) redirect("/login");
  const res = await fetch(`${BACKEND_URL}/api/v1/me`, { headers: { authorization: `Bearer ${token}` }, cache: "no-store" });
  if (res.status === 401) redirect("/login?expired=1");
  const mfa = await fetch(`${BACKEND_URL}/api/v1/auth/mfa`, { headers: { authorization: `Bearer ${token}` }, cache: "no-store" })
    .then((r) => r.json()).catch(() => null);
  const status = mfa?.data?.mfa_status;
  if (status === "ENROLLMENT_REQUIRED" || status === "VERIFICATION_REQUIRED") redirect("/login?mfa=1");
  if (!res.ok) redirect("/login?expired=1");
  const me = (await res.json()).data as Me;
  return <AppShell me={me}>{children}</AppShell>;
}
