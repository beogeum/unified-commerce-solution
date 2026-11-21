"use client";
import Link from "next/link";
import { useMemo } from "react";
import { qs } from "@/lib/api";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

function Count({ path, label, href, warn }: { path: string | null; label: string; href: string; warn?: boolean }) {
  const { data, loading, error } = useApi<unknown[]>(path);
  if (path === null) return null;
  return (
    <Link href={href} className="card kpi" style={{ textDecoration: "none", color: "inherit" }}>
      <span className="ctx">{label}</span>
      <b style={warn && (data?.length ?? 0) > 0 ? { color: "var(--err)" } : undefined}>{loading ? "…" : error ? "—" : `${data?.length ?? 0}${(data?.length ?? 0) >= 100 ? "+" : ""}`}</b>
    </Link>
  );
}

export default function HomePage() {
  const { me, can, selected } = useSession();
  const be = useMemo(() => qs({ business_entity_id: selected, limit: "100" }), [selected]);
  return (
    <>
      <div className="page-head"><div><h1>안녕하세요, {me.display_name}님</h1><p>접근 범위 안의 사업자 기준 현황입니다. 주문·재고·정산 화면은 다음 단계(M2~M5)에서 열립니다.</p></div></div>
      <section className="kpis" aria-label="요약">
        <Count label="열린 업무" href="/work-queue" warn path={can("work_item.read") ? `/work-items${qs({ status: "OPEN", business_entity_id: selected, limit: "100" })}` : null} />
        <Count label="사업자" href="/settings/business-entities" path={can("business_entity.read") ? `/business-entities${be}` : null} />
        <Count label="판매계정" href="/settings/channel-accounts" path={can("channel_account.read") ? `/channel-accounts${be}` : null} />
        <Count label="연동 연결" href="/integrations" path={can("integration.read") ? `/integrations/connections${be}` : null} />
      </section>
    </>
  );
}
