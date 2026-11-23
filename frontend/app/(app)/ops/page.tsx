"use client";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Check = { code: string; level: "CRITICAL" | "WARN"; message: string; value: number };
type Conn = { id: string; display_name: string; connector_code: string; mock: boolean; status: string; business_entity_name: string | null; last_success_at: string | null;
  last_error_at: string | null; last_error_code: string | null; consecutive_failures: number; credential_expires_at: string | null; flags: string[] };
type Status = { overall: "OK" | "WARN" | "CRITICAL"; checked_at: string; checks: Check[]; outbox: Record<string, number>; jobs: Record<string, number>;
  failing_job_types: { job_type: string; failed: number; last_failed_at: string | null }[]; work_items: Record<string, number>; connections: Conn[];
  order_freshness: { channel_account_id: string; channel_account_name: string; business_entity_name: string; last_order_at: string | null }[]; security: Record<string, number> };
const OVERALL: Record<string, ["ok" | "warn" | "err", string]> = { OK: ["ok", "정상"], WARN: ["warn", "주의"], CRITICAL: ["err", "위험"] };
const FLAG_KO: Record<string, string> = { AUTH_EXPIRED: "인증 만료", CREDENTIAL_EXPIRING: "인증 만료 임박", FAILING: "연속 실패", STALE: "동기화 지연" };
const mins = (s: number) => (s >= 60 ? `${Math.floor(s / 60)}분` : `${s}초`);

export default function OpsPage() {
  const { can } = useSession();
  const { data: s, error, loading, reload } = useApi<Status>(can("audit.read") ? "/ops/status" : null);
  if (!can("audit.read")) return <Forbidden />;
  return (
    <>
      <div className="page-head"><div><h1>운영 현황</h1><p>내부 이벤트·작업 적체와 실패, 연동 인증·동기화 상태, 업무함 적체, 주문 수집 최신성, 최근 보안 이벤트를 한곳에서 봅니다. 장애 대응 절차는 운영 런북(docs/16)을 따릅니다.</p></div>
        <button className="btn" type="button" onClick={reload}>새로 고침</button></div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : s && <>
        <div className="row-gap"><Chip kind={OVERALL[s.overall][0]}>전체 {OVERALL[s.overall][1]}</Chip><span className="ctx">확인 {formatDateTime(s.checked_at)}</span></div>
        {s.checks.length === 0 ? <div className="note n-ok">확인할 문제가 없습니다.</div> : s.checks.map((c) => (
          <div key={c.code} className={`note ${c.level === "CRITICAL" ? "n-err" : "n-warn"}`} role={c.level === "CRITICAL" ? "alert" : undefined}>{c.message} ({c.value})</div>))}
        <div className="row-gap" role="group" aria-label="처리 현황">
          <Chip kind="info">이벤트 대기 {s.outbox.pending} · 가장 오래 {mins(s.outbox.oldest_pending_seconds)}</Chip><Chip kind={s.outbox.failed > 0 ? "err" : "mute"}>이벤트 실패 {s.outbox.failed}</Chip>
          <Chip kind="info">작업 대기 {s.jobs.waiting} · 실행 {s.jobs.running}</Chip><Chip kind={s.jobs.stuck > 0 ? "warn" : "mute"}>멈춘 작업 {s.jobs.stuck}</Chip>
          <Chip kind={s.jobs.failed_24h > 0 ? "warn" : "mute"}>24시간 실패 {s.jobs.failed_24h}</Chip><Chip kind={s.jobs.review_required > 0 ? "warn" : "mute"}>확인 필요 작업 {s.jobs.review_required}</Chip>
        </div>
        <div className="row-gap" role="group" aria-label="업무·보안">
          <Chip kind="info">열린 업무 {s.work_items.open}</Chip><Chip kind={s.work_items.critical > 0 ? "err" : "mute"}>긴급 {s.work_items.critical}</Chip><Chip kind="mute">높음 {s.work_items.high}</Chip>
          <Chip kind={s.work_items.overdue > 0 ? "warn" : "mute"}>기한 초과 {s.work_items.overdue}</Chip><Chip kind="mute">미배정 {s.work_items.unassigned}</Chip>
          <Chip kind="mute">권한 거부(24시간) {s.security.permission_denied_24h}</Chip><Chip kind="mute">개인정보 원문 열람 {s.security.pii_revealed_24h}</Chip><Chip kind="mute">내보내기 {s.security.exports_24h}</Chip>
        </div>
        <h3>연동</h3>
        <DataTable caption="연동 상태" rows={s.connections} rowKey={(c) => c.id} empty={<Empty title="연결된 연동이 없습니다." />} columns={[
          { key: "n", header: "연동", render: (c) => <>{c.display_name}{c.mock && <span className="ctx"> (모의)</span>}<div className="ctx">{c.business_entity_name ?? ""} · {c.connector_code}</div></> },
          { key: "s", header: "상태", render: (c) => <>{c.status} {c.flags.map((f) => <Chip key={f} kind={f === "AUTH_EXPIRED" ? "err" : "warn"}>{FLAG_KO[f] ?? f}</Chip>)}</> },
          { key: "l", header: "마지막 성공", render: (c) => <span className="ctx">{c.last_success_at ? formatDateTime(c.last_success_at) : "없음"}</span> },
          { key: "e", header: "마지막 오류", render: (c) => <span className="ctx">{c.last_error_code ? `${c.last_error_code} · ${c.last_error_at ? formatDateTime(c.last_error_at) : ""} · 연속 ${c.consecutive_failures}` : "—"}</span> },
          { key: "x", header: "인증 만료", render: (c) => <span className="ctx">{c.credential_expires_at ? formatDateTime(c.credential_expires_at) : "—"}</span> },
        ]} />
        <h3>주문 수집 최신성</h3>
        <DataTable caption="판매계정별 마지막 주문" rows={s.order_freshness} rowKey={(r) => r.channel_account_id} empty={<Empty title="판매계정이 없습니다." />} columns={[
          { key: "a", header: "판매계정", render: (r) => `${r.business_entity_name} · ${r.channel_account_name}` },
          { key: "t", header: "마지막 주문 수신", render: (r) => (r.last_order_at ? formatDateTime(r.last_order_at) : "없음") },
        ]} />
        {s.failing_job_types.length > 0 && <>
          <h3>최근 7일 실패 작업</h3>
          <DataTable caption="실패 작업 유형" rows={s.failing_job_types} rowKey={(j) => j.job_type} empty={<Empty title="없음" />} columns={[
            { key: "t", header: "작업 유형", render: (j) => <span className="mono">{j.job_type}</span> },
            { key: "n", header: "실패·확인 필요", num: true, render: (j) => j.failed },
            { key: "l", header: "마지막", render: (j) => <span className="ctx">{j.last_failed_at ? formatDateTime(j.last_failed_at) : "—"}</span> },
          ]} />
        </>}
      </>}
    </>
  );
}
