"use client";
import { useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { InventoryTabs } from "@/components/InventoryTabs";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { formatDateTime, formatWon } from "@/lib/format";
import { num } from "@/lib/inventory";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Run = { id: string; trigger_type: string; requested_by_name: string | null; started_at: string; finished_at: string | null; checked_positions: number;
  found_issues: number; new_issues: number; resolved_issues: number };
type Issue = { id: string; check_code: string; check_name_ko: string; severity: string; business_entity_id: string; business_entity_name: string;
  node_id: string | null; node_name: string | null; sku_id: string | null; sku_code: string | null; ref_type: string | null; ref_id: string | null;
  expected: number | null; actual: number | null; cost_impact: string | null; message_ko: string; evidence: Record<string, unknown>; status: string;
  first_detected_at: string; last_detected_at: string; detection_count: number; acknowledged_by_name: string | null; acknowledge_reason: string | null;
  resolved_at: string | null };
type Summary = { open_high: number; open_medium: number; open_low: number; acknowledged: number; last_run: Run | null };

const SEV: Record<string, ["err" | "warn" | "info", string]> = { HIGH: ["err", "높음"], MEDIUM: ["warn", "보통"], LOW: ["info", "낮음"] };
const STATUS: Record<string, ["warn" | "info" | "ok", string]> = { OPEN: ["warn", "열림"], ACKNOWLEDGED: ["info", "확인함"], RESOLVED: ["ok", "해결"] };
const TRIGGER: Record<string, string> = { MANUAL: "수동", SCHEDULED: "정기(매일 03:17)", RECHECK: "재검증" };

export default function ChecksPage() {
  const { can, selected } = useSession();
  const [status, setStatus] = useState("ACTIVE");
  const [severity, setSeverity] = useState("");
  const path = useMemo(() => `/inventory/checks/issues${qs({ business_entity_id: selected, status, severity })}`, [selected, status, severity]);
  const issues = useApi<Issue[]>(can("inventory.read") ? path : null);
  const summary = useApi<Summary>(can("inventory.read") ? "/inventory/checks/summary" : null);
  const [open, setOpen] = useState<Issue | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ kind: "ok" | "err"; text: string } | null>(null);
  if (!can("inventory.read")) return <Forbidden />;
  const runNow = async () => {
    setBusy(true);
    setMsg(null);
    try {
      const r = await api<Run>("/inventory/checks/runs", { method: "POST", body: {} });
      setMsg({ kind: "ok", text: `검사를 마쳤습니다: 이상 ${r.data.found_issues}건(새로 ${r.data.new_issues}건, 해결 ${r.data.resolved_issues}건).` });
      issues.reload();
      summary.reload();
    } catch (x) {
      setMsg({ kind: "err", text: x instanceof ApiError ? x.message : "검사하지 못했습니다." });
    } finally {
      setBusy(false);
    }
  };
  const s = summary.data;
  return (
    <>
      <div className="page-head">
        <div><h1>재고 검증센터</h1><p>원장·버킷·집계·확보가 서로 맞는지, 풀리지 않은 확보·장기 이동·실사 방치·외부 재고 판매 제한이 있는지 매일 검사합니다.
          검사는 아무것도 고치지 않습니다 — 근거를 확인한 뒤 실사·승인된 조정으로 바로잡고 재검증하세요.</p></div>
        {can("inventory.operate") && <button className="btn pri" type="button" onClick={runNow} disabled={busy}>{busy ? "검사 중…" : "지금 검사"}</button>}
      </div>
      <InventoryTabs active="checks" />
      {msg && <div className={`note ${msg.kind === "ok" ? "n-ok" : "n-err"}`} role={msg.kind === "err" ? "alert" : "status"}>{msg.text}</div>}
      {s && <div className="kpis">
        <div className="kpi"><span>높음(열림)</span><b>{num(s.open_high)}</b></div>
        <div className="kpi"><span>보통(열림)</span><b>{num(s.open_medium)}</b></div>
        <div className="kpi"><span>낮음(열림)</span><b>{num(s.open_low)}</b></div>
        <div className="kpi"><span>확인함</span><b>{num(s.acknowledged)}</b></div>
        <div className="kpi"><span>마지막 검사</span><b style={{ fontSize: 14 }}>{s.last_run ? `${formatDateTime(s.last_run.started_at)} · ${TRIGGER[s.last_run.trigger_type]}` : "아직 없음"}</b></div>
      </div>}
      <div className="row-gap">
        <label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="ACTIVE">열림·확인함</option><option value="OPEN">열림</option><option value="ACKNOWLEDGED">확인함</option><option value="RESOLVED">해결</option><option value="ALL">전체</option></select></label>
        <label className="fld">심각도<select className="inp" value={severity} onChange={(e) => setSeverity(e.target.value)}><option value="">전체</option>
          {Object.entries(SEV).map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label>
      </div>
      {issues.loading ? <Loading /> : issues.error ? <ErrorState error={issues.error} onRetry={issues.reload} /> : (
        <DataTable caption="검증 이슈" rows={issues.data ?? []} rowKey={(r) => r.id} onRowClick={setOpen}
          empty={<Empty title="이상이 없습니다." hint="검사에서 찾은 이상이 여기에 표시됩니다." />}
          columns={[
            { key: "sev", header: "심각도", render: (r) => <Chip kind={SEV[r.severity][0]}>{SEV[r.severity][1]}</Chip> },
            { key: "c", header: "유형", render: (r) => r.check_name_ko },
            { key: "t", header: "사업자 · 거점 · SKU", render: (r) => [r.business_entity_name, r.node_name, r.sku_code].filter(Boolean).join(" · ") },
            { key: "ea", header: "기대 / 실제", num: true, render: (r) => r.expected == null && r.actual == null ? "—" : `${r.expected ?? "—"} / ${r.actual ?? "—"}` },
            { key: "cost", header: "원가 영향", num: true, render: (r) => r.cost_impact == null ? "—" : `${formatWon(r.cost_impact)}원` },
            { key: "d", header: "최근 발견", render: (r) => <span className="ctx">{formatDateTime(r.last_detected_at)} · {r.detection_count}회</span> },
            { key: "s", header: "상태", render: (r) => <Chip kind={STATUS[r.status][0]}>{STATUS[r.status][1]}</Chip> },
          ]} />
      )}
      {open && <IssueDrawer issue={open} onClose={() => setOpen(null)} onChanged={() => { issues.reload(); summary.reload(); }} />}
    </>
  );
}

function IssueDrawer({ issue, onClose, onChanged }: { issue: Issue; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const [cur, setCur] = useState(issue);
  const [ack, setAck] = useState(false);
  const [reason, setReason] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const run = async (path: string, body: unknown) => {
    setBusy(true);
    setErr(null);
    try {
      const r = await api<Issue>(path, { method: "POST", body });
      setCur(r.data);
      onChanged();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "처리하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };
  const ledger = (cur.evidence?.recent_ledger as { id: string; txn_type: string; on_hand_delta: number; committed_delta: number; occurred_at: string; reference: string | null }[] | undefined) ?? [];
  return (
    <Drawer title={cur.check_name_ko} onClose={onClose} footer={can("inventory.operate") && cur.status !== "RESOLVED" ? <>
      {cur.status === "OPEN" && <button className="btn" type="button" onClick={() => setAck(true)}>확인 처리</button>}
      <button className="btn pri" type="button" disabled={busy} onClick={() => run(`/inventory/checks/issues/${cur.id}/recheck`, {})}>{busy ? "재검증 중…" : "재검증"}</button></> : undefined}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {cur.status === "RESOLVED" && <div className="note n-ok">재검증에서 더 이상 발견되지 않아 해결되었습니다({formatDateTime(cur.resolved_at)}).</div>}
      <div className="kv"><span>심각도 · 상태</span><span><Chip kind={SEV[cur.severity][0]}>{SEV[cur.severity][1]}</Chip> <Chip kind={STATUS[cur.status][0]}>{STATUS[cur.status][1]}</Chip></span></div>
      <div className="kv"><span>내용</span><span>{cur.message_ko}</span></div>
      <div className="kv"><span>사업자 · 거점 · SKU</span><span>{[cur.business_entity_name, cur.node_name, cur.sku_code].filter(Boolean).join(" · ")}</span></div>
      {(cur.expected != null || cur.actual != null) && <div className="kv"><span>기대 / 실제</span><span>{cur.expected ?? "—"} / {cur.actual ?? "—"}</span></div>}
      <div className="kv"><span>원가 영향</span><span>{cur.cost_impact == null ? "—" : `${formatWon(cur.cost_impact)}원`}</span></div>
      <div className="kv"><span>대상</span><span className="mono">{cur.ref_type ?? "—"} {cur.ref_id ?? ""}</span></div>
      <div className="kv"><span>처음·최근 발견</span><span>{formatDateTime(cur.first_detected_at)} · {formatDateTime(cur.last_detected_at)} ({cur.detection_count}회)</span></div>
      {cur.acknowledge_reason && <div className="kv"><span>확인</span><span>{cur.acknowledged_by_name} · {cur.acknowledge_reason}</span></div>}
      {ledger.length > 0 && <>
        <h3 style={{ fontSize: 14 }}>근거 — 최근 원장</h3>
        <DataTable caption="근거 원장" rows={ledger} rowKey={(l) => l.id} empty={<Empty title="원장 기록이 없습니다." />} columns={[
          { key: "t", header: "시각", render: (l) => <span className="ctx">{formatDateTime(l.occurred_at)}</span> },
          { key: "ty", header: "구분", render: (l) => l.txn_type },
          { key: "oh", header: "실물", num: true, render: (l) => String(l.on_hand_delta) },
          { key: "cd", header: "확보", num: true, render: (l) => String(l.committed_delta) },
          { key: "r", header: "참조", render: (l) => <span className="ctx">{l.reference ?? "—"}</span> },
        ]} />
      </>}
      <p className="ctx">확인 처리는 &apos;원인을 알고 조치 중&apos;이라는 기록일 뿐 이상을 없애지 않습니다. 원인이 사라지면 다음 검사에서 자동으로 해결됩니다.</p>
      {ack && <ConfirmModal title="이슈 확인 처리" confirmLabel="확인 처리" onClose={() => setAck(false)} confirmDisabled={!reason.trim()}
        impact={[["이슈", cur.check_name_ko], ["효과", "열림 목록·업무함 집계에서 제외(원인이 사라지면 자동 해결)"]]}
        onConfirm={() => { setAck(false); run(`/inventory/checks/issues/${cur.id}/acknowledge`, { reason }); }}>
        <label className="fld">사유(필수)<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
    </Drawer>
  );
}
