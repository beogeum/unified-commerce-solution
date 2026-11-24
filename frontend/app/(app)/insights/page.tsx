"use client";
import Link from "next/link";
import { useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { INSIGHT_STATUS_KO, InsightCard, METRIC_KO, RULE_KO, won } from "@/lib/finance";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

const SEV: Record<string, ["err" | "warn" | "info", string]> = { HIGH: ["err", "높음"], MEDIUM: ["warn", "보통"], LOW: ["info", "낮음"] };

export default function InsightsPage() {
  const { can, selected } = useSession();
  const allowed = can("profit.read") || can("inventory.read") || can("settlement.read");
  const [status, setStatus] = useState("OPEN");
  const [rule, setRule] = useState("");
  const list = useApi<InsightCard[]>(allowed ? `/insights${qs({ business_entity_id: selected, status, rule: rule || undefined })}` : null);
  const last = useApi<{ finished_at?: string; detected?: number; trigger?: string }>(allowed ? "/insights/last-run" : null);
  const [open, setOpen] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  if (!allowed) return <Forbidden />;
  const refresh = async () => {
    setBusy(true);
    setNotice(null);
    try {
      const r = await api<{ detected: number; opened: number; resolved: number }>("/insights/refresh", { method: "POST", body: {} });
      setNotice(`다시 분석했습니다. 감지 ${r.data.detected} · 새로 열림 ${r.data.opened} · 해결 ${r.data.resolved}`);
      list.reload();
      last.reload();
    } catch (x) {
      setNotice(x instanceof ApiError ? x.message : "분석하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <div className="page-head"><div><h1>인사이트</h1><p>규칙 기반으로 이상을 찾아 원인 후보와 근거를 보여 줍니다. 추천만 하며 아무것도 자동 실행하지 않습니다 — 필요하면 검토 업무로 만들어 담당자가 판단합니다.
          원인은 비용 비중 변화 같은 &apos;후보&apos;이며 인과로 단정하지 않고, 확정되지 않은 근거는 &apos;미확인&apos;으로 표시합니다.</p></div>
        <span className="row-gap"><span className="ctx">{last.data?.finished_at ? `마지막 분석 ${formatDateTime(last.data.finished_at)}` : "아직 분석 전"}</span>
          <button className="btn pri" type="button" disabled={busy} onClick={refresh}>{busy ? "분석 중…" : "지금 다시 분석"}</button></span></div>
      {notice && <div className="note n-info" role="status">{notice}</div>}
      <div className="row-gap">
        <label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}>
          {["OPEN", "ACTIONED", "DISMISSED", "RESOLVED"].map((s) => <option key={s} value={s}>{INSIGHT_STATUS_KO[s]}</option>)}<option value="ALL">전체</option></select></label>
        <label className="fld">유형<select className="inp" value={rule} onChange={(e) => setRule(e.target.value)}><option value="">전체</option>
          {Object.entries(RULE_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      </div>
      {list.loading ? <Loading /> : list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : (
        <DataTable caption="인사이트 카드" rows={list.data ?? []} rowKey={(c) => c.id} onRowClick={(c) => setOpen(c.id)}
          empty={<Empty title="해당하는 인사이트가 없습니다." hint="'지금 다시 분석'으로 최신 데이터를 다시 확인할 수 있습니다. 매일 아침 자동으로도 분석합니다." />} columns={[
            { key: "v", header: "중요도", render: (c) => <Chip kind={SEV[c.severity][0]}>{SEV[c.severity][1]}</Chip> },
            { key: "r", header: "유형", render: (c) => RULE_KO[c.rule] ?? c.rule },
            { key: "t", header: "내용", render: (c) => <><b>{c.title}</b><div className="ctx">{c.summary}</div></> },
            { key: "u", header: "미확인", render: (c) => (c.unconfirmed.length > 0 ? <Chip kind="warn">미확인 {c.unconfirmed.length}</Chip> : null) },
            { key: "s", header: "상태", render: (c) => INSIGHT_STATUS_KO[c.status] ?? c.status },
            { key: "d", header: "최근 감지", render: (c) => <span className="ctx">{formatDateTime(c.last_detected_at)}</span> },
          ]} />
      )}
      {open && <InsightDrawer id={open} onClose={() => setOpen(null)} onChanged={list.reload} />}
    </>
  );
}

function fmt(k: string, v: unknown) {
  if (v === null || v === undefined) return "—";
  if (typeof v === "string" && /^-?\d+(\.\d+)?$/.test(v) && /(amount|sales|contribution|value)/.test(k)) return won(v);
  return String(v);
}

function InsightDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const { data: c, error, loading, reload } = useApi<InsightCard>(`/insights/${id}`);
  const [dismissing, setDismissing] = useState(false);
  const [reason, setReason] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const act = async (path: string, body: object, done: string) => {
    setMsg(null);
    try {
      await api(`/insights/${id}/${path}`, { method: "POST", body });
      setMsg(done);
      setDismissing(false);
      reload();
      onChanged();
    } catch (x) {
      setMsg(x instanceof ApiError ? x.message : "처리하지 못했습니다.");
    }
  };
  const ev = (c?.evidence ?? {}) as Record<string, unknown>;
  const pf = ev.profit_filter as Record<string, string> | undefined;
  const inf = ev.inventory_filter as Record<string, string> | undefined;
  const sf = ev.settlement_filter as Record<string, string> | undefined;
  return (
    <Drawer title={c ? c.title : "인사이트"} onClose={onClose} footer={c && c.status === "OPEN" && can("work_item.update") ? <>
      <button className="btn" type="button" onClick={() => setDismissing(true)}>무시</button>
      <button className="btn pri" type="button" onClick={() => act("work-item", {}, "검토 업무를 만들었습니다. 업무함에서 담당자를 지정하세요.")}>검토 업무 생성</button></> : undefined}>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : c && <>
        {msg && <div className="note n-info" role="status">{msg}</div>}
        <div className="kv"><span>유형 · 사업자</span><span>{RULE_KO[c.rule]} · {c.business_entity_name}</span></div>
        <div className="kv"><span>상태</span><span>{INSIGHT_STATUS_KO[c.status]}{c.dismiss_reason ? ` (${c.dismiss_reason})` : ""}{c.work_item_id ? " · 업무함에 등록됨" : ""}</span></div>
        <div className="kv"><span>관측일 · 처음 감지</span><span>{c.observed_on} · {formatDateTime(c.first_detected_at)}</span></div>
        <p>{c.summary}</p>
        <h3>지표</h3>
        {Object.entries(c.metric).map(([k, v]) => <div className="kv" key={k}><span>{METRIC_KO[k] ?? k}</span><span>{fmt(k, v)}</span></div>)}
        {c.causes.length > 0 && <>
          <h3>원인 후보</h3>
          <DataTable caption="원인 후보" rows={c.causes} rowKey={(x) => x.code} empty={<Empty title="없음" />} columns={[
            { key: "l", header: "항목", render: (x) => x.label },
            { key: "d", header: "비중 변화", num: true, render: (x) => (x.delta_pp ? `+${x.delta_pp}%p` : "—") },
            { key: "a", header: "최근 · 직전 금액", num: true, render: (x) => (x.current_amount !== undefined ? `${won(x.current_amount)} · ${won(x.previous_amount)}` : "—") },
            { key: "c", header: "확실성", render: (x) => <span className="ctx">{x.certainty ?? (ev.certainty as string) ?? "규칙 기반 추정"}</span> },
          ]} />
        </>}
        {c.unconfirmed.length > 0 && <div className="note n-warn"><b>미확인 근거</b>{c.unconfirmed.map((u) => <div key={u}>· {u}</div>)}</div>}
        {typeof ev.suggestion === "string" && <div className="kv"><span>제안</span><span>{ev.suggestion}</span></div>}
        <h3>근거 레코드</h3>
        <div className="row-gap">
          {pf && <Link className="btn" href={`/finance/profit${qs({ group_by: "order", from: pf.from, to: pf.to })}`}>관련 손익·주문</Link>}
          {inf && <Link className="btn" href="/inventory">재고 보기</Link>}
          {sf && <Link className="btn" href="/finance/settlements">정산·입금 보기</Link>}
        </div>
      </>}
      {dismissing && <ConfirmModal title="이 인사이트를 무시할까요?" confirmLabel="무시" confirmDisabled={!reason.trim()} onClose={() => setDismissing(false)}
        onConfirm={() => act("dismiss", { reason }, "무시했습니다. 상황이 해소된 뒤 다시 감지되면 새로 엽니다.")}
        impact={[["효과", "해결될 때까지 열린 목록에 다시 나타나지 않습니다"], ["감사", "사유와 처리자가 기록됩니다"]]}>
        <label className="fld">사유<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
    </Drawer>
  );
}
