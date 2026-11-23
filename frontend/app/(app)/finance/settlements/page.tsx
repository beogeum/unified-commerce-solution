"use client";
import { useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { DepositDrawer, NewDepositModal, SettlementDrawer, SettlementUpload } from "@/components/SettlementDrawers";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { qs } from "@/lib/api";
import { Deposit, RUN_STATUS_KO, SettlementRun, won } from "@/lib/finance";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Account = { id: string; display_name: string; business_entity_name: string };

export default function SettlementsPage() {
  const { can, selected } = useSession();
  const [tab, setTab] = useState<"runs" | "deposits">("runs");
  const [account, setAccount] = useState("");
  const [unmatched, setUnmatched] = useState(false);
  const [openRun, setOpenRun] = useState<string | null>(null);
  const [openDeposit, setOpenDeposit] = useState<string | null>(null);
  const [modal, setModal] = useState<null | "upload" | "deposit">(null);
  const allowed = can("settlement.read");
  const accounts = useApi<Account[]>(allowed ? `/channel-accounts${qs({ business_entity_id: selected, limit: "200" })}` : null);
  const runsPath = useMemo(() => (allowed && tab === "runs" ? `/finance/settlements${qs({ business_entity_id: selected, channel_account_id: account || undefined })}` : null),
    [allowed, tab, selected, account]);
  const depositsPath = useMemo(() => (allowed && tab === "deposits" ? `/finance/deposits${qs({ business_entity_id: selected, unmatched: unmatched ? "true" : undefined })}` : null),
    [allowed, tab, selected, unmatched]);
  const runs = useApi<SettlementRun[]>(runsPath);
  const deposits = useApi<Deposit[]>(depositsPath);
  if (!allowed) return <Forbidden />;
  const manage = can("settlement.manage");
  return (
    <>
      <div className="page-head"><div><h1>정산·입금</h1><p>판매채널 정산 원본을 올려 주문·수수료를 대사하고, 계좌 입금을 정산에 연결합니다. 정산 수수료는 같은 주문상품의 예상 수수료를 실제로 대체하고,
        판매·환불 행은 손익에 다시 더하지 않고 대사에만 씁니다. 매출 ≠ 정산액 ≠ 입금액입니다.</p></div>
        {manage && <span className="row-gap">
          <button className="btn" type="button" onClick={() => setModal("deposit")}>입금 등록</button>
          <button className="btn pri" type="button" onClick={() => setModal("upload")}>정산 파일 올리기</button></span>}</div>
      <nav className="row-gap" aria-label="정산 메뉴">
        <button className="btn" type="button" aria-current={tab === "runs" ? "page" : undefined} onClick={() => setTab("runs")}>정산</button>
        <button className="btn" type="button" aria-current={tab === "deposits" ? "page" : undefined} onClick={() => setTab("deposits")}>입금</button>
      </nav>
      {tab === "runs" ? <>
        <div className="row-gap"><label className="fld">판매계정<select className="inp" value={account} onChange={(e) => setAccount(e.target.value)}>
          <option value="">전체</option>{(accounts.data ?? []).map((a) => <option key={a.id} value={a.id}>{a.business_entity_name} · {a.display_name}</option>)}</select></label></div>
        {runs.loading ? <Loading /> : runs.error ? <ErrorState error={runs.error} onRetry={runs.reload} /> : (
          <DataTable caption="정산" rows={runs.data ?? []} rowKey={(r) => r.id} onRowClick={(r) => setOpenRun(r.id)}
            empty={<Empty title="가져온 정산이 없습니다." hint="채널 정산 파일(정산번호·항목·금액 열)을 올리면 주문과 대사합니다." />}
            columns={[
              { key: "k", header: "정산번호", render: (r) => <span className="mono">{r.settlement_key}</span> },
              { key: "a", header: "판매계정", render: (r) => <>{r.channel_account_name} <span className="ctx">{r.business_entity_name}</span></> },
              { key: "p", header: "정산기간", render: (r) => <span className="ctx">{r.period_from ?? ""} ~ {r.period_to ?? ""}</span> },
              { key: "d", header: "지급예정일", render: (r) => r.payout_date ?? "—" },
              { key: "o", header: "지급액", num: true, render: (r) => won(r.payout) },
              { key: "i", header: "입금 연결", num: true, render: (r) => won(r.deposited) },
              { key: "r", header: "미입금", num: true, render: (r) => (Number(r.remaining) === 0 ? "0" : <b>{won(r.remaining)}</b>) },
              { key: "s", header: "상태", render: (r) => <Chip kind={r.status === "RECONCILED" ? "ok" : r.status === "ISSUES" ? "warn" : "mute"}>{RUN_STATUS_KO[r.status] ?? r.status}</Chip> },
              { key: "v", header: "검토 · 차이", render: (r) => <span className="ctx">{r.review_lines > 0 ? `검토 ${r.review_lines}` : ""}{r.differences.length > 0 ? ` 차이 ${r.differences.length}` : ""}</span> },
            ]} />
        )}
      </> : <>
        <div className="row-gap"><label><input type="checkbox" checked={unmatched} onChange={(e) => setUnmatched(e.target.checked)} /> 연결 안 된 금액이 있는 입금만</label></div>
        {deposits.loading ? <Loading /> : deposits.error ? <ErrorState error={deposits.error} onRetry={deposits.reload} /> : (
          <DataTable caption="입금" rows={deposits.data ?? []} rowKey={(d) => d.id} onRowClick={(d) => setOpenDeposit(d.id)}
            empty={<Empty title="등록된 입금이 없습니다." hint="계좌 입금 내역을 등록하면 정산과 연결할 수 있습니다." />}
            columns={[
              { key: "d", header: "입금일", render: (d) => d.deposited_on },
              { key: "b", header: "사업자", render: (d) => <>{d.business_entity_name}{d.bank_account_last4 ? <span className="ctx"> · 계좌 끝 {d.bank_account_last4}</span> : null}</> },
              { key: "n", header: "입금자 · 거래번호", render: (d) => <span className="ctx">{d.depositor ?? ""} {d.external_ref ?? ""}</span> },
              { key: "a", header: "입금액", num: true, render: (d) => won(d.amount) },
              { key: "m", header: "정산 연결", num: true, render: (d) => won(d.matched) },
              { key: "r", header: "미연결", num: true, render: (d) => (Number(d.remaining) === 0 ? "0" : <b>{won(d.remaining)}</b>) },
              { key: "c", header: "연결 정산", render: (d) => <span className="ctx">{d.matches.filter((m) => !m.cancelled_at).map((m) => m.settlement_key).join(", ")}</span> },
            ]} />
        )}
      </>}
      {openRun && <SettlementDrawer id={openRun} onClose={() => setOpenRun(null)} onChanged={runs.reload} />}
      {openDeposit && <DepositDrawer id={openDeposit} onClose={() => setOpenDeposit(null)} onChanged={deposits.reload} />}
      {modal === "upload" && <SettlementUpload accounts={accounts.data ?? []} onClose={() => setModal(null)} onDone={() => { setTab("runs"); runs.reload(); }} />}
      {modal === "deposit" && <NewDepositModal onClose={() => setModal(null)} onDone={() => { setModal(null); setTab("deposits"); deposits.reload(); }} />}
    </>
  );
}
