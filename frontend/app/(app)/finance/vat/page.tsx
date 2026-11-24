"use client";
import { kstToday } from "@/lib/format";
import { useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { NewVatBatchModal, VatBatchDrawer, VatExportButton, VatTotals } from "@/components/VatParts";
import { qs } from "@/lib/api";
import { VatBatch, VatReport, won } from "@/lib/finance";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Account = { id: string; display_name: string; business_entity_name: string };

export default function VatPage() {
  const { can, selected, entities } = useSession();
  const allowed = can("settlement.read");
  const [y] = useState(() => Number(kstToday().slice(0, 4)));
  const [tab, setTab] = useState<"batches" | "report">("batches");
  const [from, setFrom] = useState(`${y}-01-01`);
  const [to, setTo] = useState(`${y}-12-31`);
  const [beSel, setBe] = useState("");
  const be = beSel || (selected.length === 1 ? selected[0] : "") || entities[0]?.id || "";
  const [open, setOpen] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const accounts = useApi<Account[]>(allowed ? `/channel-accounts${qs({ business_entity_id: selected, limit: "200" })}` : null);
  const listPath = useMemo(() => (allowed && tab === "batches" ? `/vat/batches${qs({ business_entity_id: selected, from, to })}` : null), [allowed, tab, selected, from, to]);
  const reportPath = useMemo(() => (allowed && tab === "report" && be ? `/vat/reports${qs({ business_entity_id: be, from, to })}` : null), [allowed, tab, be, from, to]);
  const list = useApi<VatBatch[]>(listPath);
  const report = useApi<VatReport>(reportPath);
  if (!allowed) return <Forbidden />;
  return (
    <>
      <div className="page-head"><div><h1>부가세 신고자료</h1><p>판매계정별 신고자료를 표준 형식으로 모아 검증·분류하고, 주문 기준 매출·정산과 대사합니다. 사업자 A·B 자료는 절대 합치지 않으며,
        국세청 신고는 회계·세무 프로그램에서 합니다. 분류 규칙은 세무 담당 확정 전 임시 규칙(버전 기록)입니다.</p></div>
        {can("vat.manage") && <button className="btn pri" type="button" onClick={() => setCreating(true)}>신고자료 묶음 만들기</button>}</div>
      <nav className="row-gap" aria-label="부가세 메뉴">
        <button className="btn" type="button" aria-current={tab === "batches" ? "page" : undefined} onClick={() => setTab("batches")}>신고자료 묶음</button>
        <button className="btn" type="button" aria-current={tab === "report" ? "page" : undefined} onClick={() => setTab("report")}>사업자별 신고자료</button>
      </nav>
      <div className="row-gap">
        {tab === "report" && <label className="fld">사업자<select className="inp" value={be} onChange={(e) => setBe(e.target.value)} aria-label="신고 사업자">
          {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>}
        <label className="fld">기간 시작<input className="inp" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
        <label className="fld">기간 끝<input className="inp" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
      </div>
      {tab === "batches" ? (list.loading ? <Loading /> : list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : (
        <DataTable caption="신고자료 묶음" rows={list.data ?? []} rowKey={(b) => b.id} onRowClick={(b) => setOpen(b.id)}
          empty={<Empty title="신고자료 묶음이 없습니다." hint="판매계정과 신고기간을 골라 묶음을 만들고 신고자료 파일을 올리세요." />} columns={[
            { key: "a", header: "사업자 · 판매계정", render: (b) => <>{b.business_entity_name} · {b.channel_account_name}</> },
            { key: "p", header: "신고기간", render: (b) => `${b.period_from} ~ ${b.period_to}` },
            { key: "r", header: "행 · 보정 필요", num: true, render: (b) => <>{b.rows} · {b.blocking_rows > 0 ? <b>{b.blocking_rows}</b> : 0}</> },
            { key: "t", header: "합계(제외 빼고)", num: true, render: (b) => won(b.totals.filter((t) => t.classification !== "EXCLUDED").reduce((s, t) => s + Number(t.total), 0)) },
            { key: "d", header: "원장 대비 차이", num: true, render: (b) => won(b.recon.diff_vs_ledger) },
            { key: "s", header: "상태", render: (b) => (b.status === "CONFIRMED" ? <Chip kind="ok">확정</Chip> : <Chip kind="warn">작성 중</Chip>) },
          ]} />
      )) : (report.loading ? <Loading /> : report.error ? <ErrorState error={report.error} onRetry={report.reload} /> : report.data && <>
        <div className={`note ${report.data.all_confirmed ? "n-info" : "n-warn"}`}>{report.data.business_entity_name}({report.data.business_registration_no}) · {report.data.period_from} ~ {report.data.period_to} · {report.data.note}</div>
        <VatTotals totals={report.data.totals} />
        <h3>포함된 묶음</h3>
        <DataTable caption="포함된 묶음" rows={report.data.batches} rowKey={(b) => b.id} onRowClick={(b) => setOpen(b.id)} empty={<Empty title="이 기간 안에 들어오는 묶음이 없습니다." />} columns={[
          { key: "a", header: "판매계정", render: (b) => b.channel_account_name },
          { key: "p", header: "신고기간", render: (b) => `${b.period_from} ~ ${b.period_to}` },
          { key: "s", header: "상태", render: (b) => (b.status === "CONFIRMED" ? <Chip kind="ok">확정</Chip> : <Chip kind="warn">작성 중</Chip>) },
        ]} />
        {can("export.create") && <VatExportButton businessEntityId={be} from={from} to={to} />}
      </>)}
      {open && <VatBatchDrawer id={open} onClose={() => setOpen(null)} onChanged={() => { list.reload(); report.reload(); }} />}
      {creating && <NewVatBatchModal accounts={accounts.data ?? []} onClose={() => setCreating(false)} onDone={(id) => { setCreating(false); setTab("batches"); list.reload(); setOpen(id); }} />}
    </>
  );
}
