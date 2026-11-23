"use client";
import { useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { CATEGORY_KO, Period, won } from "@/lib/finance";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

const ym = (d: string) => d.slice(0, 7);

export default function PeriodsPage() {
  const { can, entities, selected } = useSession();
  const [beSel, setBe] = useState("");
  const be = beSel || (selected && selected.length === 1 ? selected[0] : "") || entities[0]?.id || "";
  const allowed = can("profit.read");
  const list = useApi<Period[]>(allowed && be ? `/finance/periods${qs({ business_entity_id: be, months: "12" })}` : null);
  const [open, setOpen] = useState<string | null>(null);
  if (!allowed) return <Forbidden />;
  return (
    <>
      <div className="page-head"><div><h1>월 마감</h1><p>사업자별로 지난 달을 점검하고 마감합니다. 마감하면 그 달의 원장 합계를 스냅샷으로 보존하고, 이후 들어온 환불·청구·정산 조정은 다음 열린 달에 원래 귀속월과 연결해 기록합니다.
        재개방은 다른 승인자(월 마감 권한)의 승인이 필요합니다.</p></div></div>
      <div className="row-gap"><label className="fld">사업자<select className="inp" value={be} onChange={(e) => setBe(e.target.value)} aria-label="마감 사업자">
        {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label></div>
      {!be ? <Empty title="사업자가 없습니다." /> : list.loading ? <Loading /> : list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : (
        <DataTable caption="월별 마감 상태" rows={list.data ?? []} rowKey={(p) => p.month} onRowClick={(p) => setOpen(p.month)} empty={<Empty title="표시할 달이 없습니다." />}
          columns={[
            { key: "m", header: "월", render: (p) => ym(p.month) },
            { key: "s", header: "상태", render: (p) => (p.status === "CLOSED" ? <Chip kind="ok">마감</Chip> : <Chip>열림</Chip>) },
            { key: "n", header: "순매출", num: true, render: (p) => won(p.totals.net_sales) },
            { key: "c", header: "공헌이익", num: true, render: (p) => won(p.totals.contribution) },
            { key: "w", header: "점검", render: (p) => {
              const w = p.checks.filter((c) => c.severity === "WARNING").length;
              return p.status === "CLOSED" ? <span className="ctx">{p.closed_at ? formatDateTime(p.closed_at) : ""} {p.closed_by_name ?? ""}</span>
                : w > 0 ? <Chip kind="warn">확인 {w}</Chip> : <span className="ctx">이상 없음</span>;
            } },
            { key: "a", header: "마감 후 조정", num: true, render: (p) => p.totals.posted_after_close || "" },
          ]} />
      )}
      {open && be && <PeriodDrawer be={be} month={open} onClose={() => setOpen(null)} onChanged={list.reload} />}
    </>
  );
}

function PeriodDrawer({ be, month, onClose, onChanged }: { be: string; month: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const { data: p, error, loading, reload } = useApi<Period>(`/finance/periods/preview${qs({ business_entity_id: be, month })}`);
  const [modal, setModal] = useState<null | "close" | "reopen">(null);
  const [note, setNote] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const warnings = p?.checks.filter((c) => c.severity === "WARNING") ?? [];
  const run = async (fn: () => Promise<string>) => {
    setErr(null);
    setBusy(true);
    try {
      setNotice(await fn());
      setModal(null);
      setNote("");
      reload();
      onChanged();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "처리하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };
  const close = () => run(async () => {
    await api("/finance/periods/close", { method: "POST", body: { business_entity_id: be, month, acknowledge_warnings: warnings.length > 0, note: note || undefined } });
    return `${ym(month)} 마감을 완료했습니다.`;
  });
  const reopen = () => run(async () => {
    await api("/finance/periods/reopen-requests", { method: "POST", body: { business_entity_id: be, month, reason: note } });
    return "재개방 승인을 요청했습니다. 다른 승인자가 승인함에서 처리합니다.";
  });
  const t = p?.totals;
  return (
    <Drawer title={`${ym(month)} 마감`} onClose={onClose} footer={p && can("period.close") ? (p.status === "OPEN"
      ? <button className="btn pri" type="button" disabled={!p.closable} title={p.not_closable_reason ?? undefined} onClick={() => setModal("close")}>점검 후 마감</button>
      : <button className="btn" type="button" disabled={!!p.pending_reopen_approval_id} onClick={() => setModal("reopen")}>{p.pending_reopen_approval_id ? "재개방 승인 대기 중" : "재개방 요청"}</button>) : undefined}>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : p && t && <>
        {notice && <div className="note n-info" role="status">{notice}</div>}
        {!p.closable && p.status === "OPEN" && <div className="note n-info">{p.not_closable_reason}</div>}
        <div className="kv"><span>사업자</span><span>{p.business_entity_name}</span></div>
        <div className="kv"><span>상태</span><span>{p.status === "CLOSED" ? `마감 · ${p.closed_at ? formatDateTime(p.closed_at) : ""} ${p.closed_by_name ?? ""}` : "열림"}</span></div>
        {p.status === "CLOSED" && <div className={`note ${p.snapshot_matches ? "n-info" : "n-err"}`}>{p.snapshot_matches ? "현재 원장 합계가 마감 스냅샷과 같습니다."
          : "현재 원장 합계가 마감 스냅샷과 다릅니다. 관리자에게 알리세요."}</div>}
        <h3>점검</h3>
        {p.checks.length === 0 ? <p className="ctx">확인할 항목이 없습니다.</p> : p.checks.map((c) => (
          <div className="kv" key={c.code}><span>{c.severity === "WARNING" ? <Chip kind="warn">확인</Chip> : <Chip kind="info">참고</Chip>}</span>
            <span>{c.message}{c.amount !== null ? ` (${won(c.amount)}원)` : ""}</span></div>))}
        <h3>원장 합계(공급가)</h3>
        <div className="kv"><span>순매출</span><span>{won(t.net_sales)}원</span></div>
        <div className="kv"><span>배송비·반품비 수익</span><span>{won(t.other_revenue)}원</span></div>
        <div className="kv"><span>비용 합계</span><span>{won(t.total_cost)}원</span></div>
        <div className="kv"><span>공헌이익</span><span><b>{won(t.contribution)}원</b></span></div>
        <div className="kv"><span>원장 행 · 마감 후 다음 달로 귀속된 조정</span><span>{t.entry_count} · {t.posted_after_close}</span></div>
        <DataTable caption="항목별" rows={Object.entries(t.supply_by_category)} rowKey={([k]) => k} empty={<Empty title="원장이 없습니다." />} columns={[
          { key: "c", header: "항목", render: ([k]) => CATEGORY_KO[k] ?? k },
          { key: "s", header: "공급가", num: true, render: ([, v]) => won(v) },
          { key: "v", header: "부가세", num: true, render: ([k]) => won(t.vat_by_category[k]) },
        ]} />
        {p.events.length > 0 && <>
          <h3>마감 이력</h3>
          {p.events.map((e, i) => <div className="kv" key={i}><span>{e.event_type === "CLOSED" ? "마감" : "재개방"}</span>
            <span className="ctx">{formatDateTime(e.occurred_at)} · {e.actor_name ?? ""}{e.reason ? ` · ${e.reason}` : ""}</span></div>)}
        </>}
      </>}
      {modal === "close" && p && <ConfirmModal title={`${ym(month)}을 마감할까요?`} confirmLabel={warnings.length > 0 ? "확인하고 마감" : "마감"} busy={busy}
        onClose={() => setModal(null)} onConfirm={close} impact={[["공헌이익", `${won(p.totals.contribution)}원`], ["원장 행", String(p.totals.entry_count)],
          ["확인 항목", warnings.length > 0 ? warnings.map((w) => w.message).join(" / ") : "없음"],
          ["효과", "이 달에는 원장을 더 기록할 수 없고, 이후 조정은 다음 열린 달로 귀속됩니다"]]}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        <label className="fld">메모{warnings.length > 0 ? "(확인 항목 처리 계획)" : ""}<input className="inp" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
      {modal === "reopen" && <ConfirmModal title={`${ym(month)} 재개방을 요청할까요?`} confirmLabel="승인 요청" busy={busy} confirmDisabled={!note.trim()} onClose={() => setModal(null)}
        onConfirm={reopen} impact={[["승인", "요청자가 아닌 월 마감 권한자가 승인해야 합니다"], ["효과", "승인되면 이 달에 다시 기록할 수 있고, 재마감 때 새 스냅샷이 저장됩니다. 이미 다음 달로 귀속된 조정은 옮기지 않습니다"]]}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        <label className="fld">사유<input className="inp" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
    </Drawer>
  );
}
