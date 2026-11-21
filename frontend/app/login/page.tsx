import { LoginFlow } from "./LoginFlow";

export const dynamic = "force-dynamic";

export default async function LoginPage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const sp = await searchParams;
  return <LoginFlow resumeMfa={sp.mfa === "1"} expired={sp.expired === "1"} defaultWorkspace={process.env.DEFAULT_WORKSPACE_CODE ?? ""} />;
}
