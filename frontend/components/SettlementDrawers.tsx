"use client";
import { FormEvent, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { APPLIED_KO, Deposit, DepositMatch, LINE_TYPE_KO, MATCH_KO, RUN_STATUS_KO, SettlementRun, won } from "@/lib/finance";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

const msg = (x: unknown, fallback: string) => (x instanceof ApiError ? x.message : fallback);

/** 정산 상세: 항목별 합계, 주문별 대사 차이, 원본 행, 입금 연결. */
export function SettlementDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const { data, error, loading, reload } = useApi<{ run: SettlementRun; matches: DepositMatch[] }>(`/finance/settlements/${id}`);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const rematch = async () => {
    setBusy(true);
    setNotice(null);
    try {
      const r = await api<SettlementRun>(`/finance/settlements/${id}/rematch`, { method: "POST", body: {} });
      setNotice(r.data.review_lines === 0 ? "원주문을 모두 찾아 연결했습니다." : `아직 원주문을 찾지 못한 행 ${r.data.review_lines}건이 있습니다.`);
      reload();
      onChanged();
    } catch (x) {
      setNotice(msg(x, "다시 연결하지 못했습니다."));
    } finally {
      setBusy(false);
    }
  };
  const run = data?.run;
  return (
    <Drawer title={run ? `정산 ${run.settlement_key}` : "정산"} onClose={onClose}
      footer={run && can("settlement.manage") && run.lines.some((l) => l.match_level === "UNMATCHED")
        ? <button className="btn" type="button" disabled={busy} onClick={rematch}>{busy ? "연결 중…" : "원주문 다시 찾기"}</button> : undefined}>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : run && <>
        {notice && <div className="note n-info" role="status">{notice}</div>}
        <div className="kv"><span>판매계정</span><span>{run.channel_account_name} · {run.business_entity_name}</span></div>
        <div className="kv"><span>정산기간 · 지급예정일</span><span>{run.period_from ?? ""} ~ {run.period_to ?? ""} · {run.payout_date ?? "—"}</span></div>
        <div className="kv"><span>상태</span><span><Chip kind={run.status === "RECONCILED" ? "ok" : "warn"}>{RUN_STATUS_KO[run.status] ?? run.status}</Chip></span></div>
        <div className="kv"><span>지급액(행 합계)</span><span>{won(run.payout)}원{run.declared_payout !== null && Number(run.declared_payout) !== Number(run.payout)
          ? <b> · 파일 지급액 {won(run.declared_payout)}원과 다름</b> : ""}</span></div>
        <div className="kv"><span>입금 연결 · 미입금</span><span>{won(run.deposited)}원 · {won(run.remaining)}원</span></div>
        <h3>항목별 합계</h3>
        {Object.entries(run.totals).map(([k, v]) => <div className="kv" key={k}><span>{LINE_TYPE_KO[k] ?? k}</span><span>{won(v)}원</span></div>)}
        <p className="ctx">수수료·패널티는 원장에 실제 비용으로 반영(같은 주문상품의 예상 수수료 대체)하고, 판매·배송비·할인보전·환불은 대사에만 씁니다(플랫폼 물류 주문은 정산이 매출 근거).</p>
        {run.differences.length > 0 && <>
          <h3>주문별 대사 차이</h3>
          <DataTable caption="대사 차이" rows={run.differences} rowKey={(d) => d.order_id} empty={<Empty title="차이가 없습니다." />} columns={[
            { key: "o", header: "주문번호", render: (d) => <span className="mono">{d.order_no}</span> },
            { key: "s", header: "정산(판매·배송비·할인보전·환불)", num: true, render: (d) => won(d.settlement) },
            { key: "l", header: "원장 인식액(부가세 포함)", num: true, render: (d) => won(d.ledger) },
            { key: "d", header: "차이", num: true, render: (d) => <b>{won(d.difference)}</b> },
          ]} />
        </>}
        <h3>원본 행</h3>
        <DataTable caption="정산 원본 행" rows={run.lines} rowKey={(l) => l.id} empty={<Empty title="행이 없습니다." />} columns={[
          { key: "n", header: "행", num: true, render: (l) => l.line_no },
          { key: "t", header: "항목", render: (l) => LINE_TYPE_KO[l.line_type] ?? l.line_type },
          { key: "o", header: "주문", render: (l) => <span className="mono">{l.order_no ?? l.external_order_key ?? "—"}</span> },
          { key: "m", header: "연결", render: (l) => <span className="ctx">{MATCH_KO[l.match_level]}</span> },
          { key: "a", header: "금액", num: true, render: (l) => won(l.amount) },
          { key: "p", header: "반영", render: (l) => <Chip kind={l.applied === "REVIEW" ? "warn" : l.applied === "LEDGER" ? "ok" : "mute"}>{APPLIED_KO[l.applied]}</Chip> },
        ]} />
        <h3>입금 연결</h3>
        <DataTable caption="입금 연결" rows={data.matches} rowKey={(m) => m.id} empty={<Empty title="연결된 입금이 없습니다." hint="입금 탭에서 입금을 선택해 연결하세요." />} columns={[
          { key: "a", header: "금액", num: true, render: (m) => won(m.amount) },
          { key: "m", header: "방식", render: (m) => (m.method === "AUTO" ? "자동" : "직접") },
          { key: "c", header: "연결일시", render: (m) => <span className="ctx">{formatDateTime(m.created_at)}</span> },
          { key: "s", header: "상태", render: (m) => (m.cancelled_at ? <Chip>취소됨</Chip> : <Chip kind="ok">연결</Chip>) },
        ]} />
      </>}
    </Drawer>
  );
}

/** 입금 상세: 자동 연결, 직접 연결(금액 나눠 연결), 연결 취소. */
export function DepositDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const { data, error, loading, reload } = useApi<Deposit>(`/finance/deposits/${id}`);
  const runs = useApi<SettlementRun[]>(data ? `/finance/settlements${qs({ business_entity_id: data.business_entity_id })}` : null);
  const [runSel, setRun] = useState("");
  const [amount, setAmount] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState<DepositMatch | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const open = (runs.data ?? []).filter((r) => Number(r.remaining) > 0);
  const runId = runSel || open[0]?.id || "";
  const done = (m: string) => { setNotice(m); reload(); runs.reload(); onChanged(); };
  const auto = async () => {
    setBusy(true);
    try {
      const r = await api<{ outcome: string; message: string }>(`/finance/deposits/${id}/auto-match`, { method: "POST", body: {} });
      done(r.data.message);
    } catch (x) {
      setNotice(msg(x, "자동 연결하지 못했습니다."));
    } finally {
      setBusy(false);
    }
  };
  const manual = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      await api(`/finance/deposits/${id}/matches`, { method: "POST", body: { run_id: runId, amount: amount ? Number(amount) : undefined } });
      setAmount("");
      done("정산에 연결했습니다.");
    } catch (x) {
      setNotice(msg(x, "연결하지 못했습니다."));
    } finally {
      setBusy(false);
    }
  };
  const cancel = async () => {
    if (!cancelling) return;
    setBusy(true);
    try {
      await api(`/finance/deposit-matches/${cancelling.id}/cancel`, { method: "POST", body: { reason } });
      setCancelling(null);
      setReason("");
      done("연결을 취소했습니다. 연결 이력은 남습니다.");
    } catch (x) {
      setNotice(msg(x, "취소하지 못했습니다."));
    } finally {
      setBusy(false);
    }
  };
  const manage = can("settlement.manage");
  return (
    <Drawer title={data ? `입금 ${data.deposited_on} · ${won(data.amount)}원` : "입금"} onClose={onClose}
      footer={data && manage && Number(data.remaining) > 0 ? <button className="btn pri" type="button" disabled={busy} onClick={auto}>자동 연결</button> : undefined}>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : data && <>
        {notice && <div className="note n-info" role="status">{notice}</div>}
        <div className="kv"><span>사업자</span><span>{data.business_entity_name}{data.bank_account_last4 ? ` · 계좌 끝 ${data.bank_account_last4}` : ""}</span></div>
        <div className="kv"><span>입금자 · 거래번호</span><span>{data.depositor ?? "—"} · {data.external_ref ?? "—"}</span></div>
        <div className="kv"><span>연결 · 미연결</span><span>{won(data.matched)}원 · {won(data.remaining)}원</span></div>
        <p className="ctx">자동 연결은 미입금액이 입금 잔액과 정확히 같은 정산 1건, 또는 합계가 정확히 같은 정산 조합이 하나뿐일 때만 연결합니다. 후보가 여럿이면 직접 연결하세요.</p>
        {manage && Number(data.remaining) > 0 && (
          <form className="row-gap" onSubmit={manual}>
            <label className="fld">정산<select className="inp" value={runId} onChange={(e) => setRun(e.target.value)} aria-label="연결할 정산">
              {open.length === 0 && <option value="">미입금 정산 없음</option>}
              {open.map((r) => <option key={r.id} value={r.id}>{r.settlement_key} · {r.channel_account_name} · 미입금 {won(r.remaining)}원</option>)}</select></label>
            <label className="fld">금액(비우면 가능한 최대)<input className="inp" type="number" min={1} step={1} value={amount} onChange={(e) => setAmount(e.target.value)} /></label>
            <button className="btn" type="submit" disabled={busy || !runId}>직접 연결</button>
          </form>
        )}
        <h3>연결 내역</h3>
        <DataTable caption="연결 내역" rows={data.matches} rowKey={(m) => m.id} empty={<Empty title="연결된 정산이 없습니다." />} columns={[
          { key: "k", header: "정산번호", render: (m) => <span className="mono">{m.settlement_key}</span> },
          { key: "a", header: "판매계정", render: (m) => m.channel_account_name },
          { key: "v", header: "금액", num: true, render: (m) => won(m.amount) },
          { key: "m", header: "방식", render: (m) => (m.method === "AUTO" ? "자동" : "직접") },
          { key: "s", header: "상태", render: (m) => (m.cancelled_at ? <Chip>취소 {formatDateTime(m.cancelled_at)}</Chip>
            : manage ? <button className="btn" type="button" onClick={() => setCancelling(m)}>연결 취소</button> : <Chip kind="ok">연결</Chip>) },
        ]} />
      </>}
      {cancelling && <ConfirmModal title="입금 연결을 취소할까요?" confirmLabel="연결 취소" danger busy={busy} confirmDisabled={!reason.trim()} onClose={() => setCancelling(null)}
        onConfirm={cancel} impact={[["정산", cancelling.settlement_key], ["금액", `${won(cancelling.amount)}원`], ["효과", "정산 미입금액과 입금 잔액이 다시 늘어납니다. 연결 이력은 남습니다"]]}>
        <label className="fld">사유<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
    </Drawer>
  );
}

type Account = { id: string; display_name: string; business_entity_name: string };
type ImportResult = { runs: number; new_lines: number; duplicate_lines: number; run_ids: string[] };

export function SettlementUpload({ accounts, onClose, onDone }: { accounts: Account[]; onClose: () => void; onDone: () => void }) {
  const [accSel, setAcc] = useState("");
  const acc = accSel || accounts[0]?.id || "";
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!file || !acc) return;
    setErr(null);
    setBusy(true);
    try {
      const fd = new FormData();
      fd.append("channel_account_id", acc);
      fd.append("file", file);
      const res = await fetch("/bff/api/finance/settlements/imports", { method: "POST", body: fd, headers: { "x-cos-csrf": "1" } });
      const j = await res.json().catch(() => null);
      if (!res.ok) throw new ApiError(res.status, j?.error?.code ?? "UPLOAD_FAILED", j?.error?.message ?? "파일을 반영하지 못했습니다.");
      setResult(j.data);
      onDone();
    } catch (x) {
      setErr(msg(x, "파일을 반영하지 못했습니다."));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Drawer title="정산 파일 올리기" onClose={onClose} footer={<><button className="btn" type="button" onClick={onClose}>닫기</button>
      <button className="btn pri" type="submit" form="settlement-upload" disabled={!file || !acc || busy}>{busy ? "반영 중…" : "반영"}</button></>}>
      <form id="settlement-upload" onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        <label className="fld">판매계정<select className="inp" value={acc} onChange={(e) => setAcc(e.target.value)} aria-label="정산 판매계정">
          {accounts.map((a) => <option key={a.id} value={a.id}>{a.business_entity_name} · {a.display_name}</option>)}</select></label>
        <label className="fld">파일(.xlsx·.csv)<input className="inp" type="file" accept=".xlsx,.csv" onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }} /></label>
        <p className="ctx" style={{ margin: 0 }}>표준 정산 양식. 필수 열: 정산번호, 항목(판매·배송비·판매수수료·결제수수료·할인보전·환불·패널티·조정·기타), 금액(부가세 포함, 받을 돈 +, 공제 −).
          선택 열: 정산기간시작, 정산기간종료, 지급예정일, 지급액, 주문번호, 상품주문번호, 수량. 잘못된 행이 하나라도 있으면 파일 전체를 반영하지 않습니다. 같은 행은 다시 올려도 한 번만 반영됩니다.
          채널별 원본 양식 자동 변환·API 정산 조회는 채널 권한 확인 후 지원합니다.</p>
      </form>
      {result && <div className="note n-info" role="status">정산 {result.runs}건 · 새 행 {result.new_lines} · 이미 반영된 행 {result.duplicate_lines}</div>}
    </Drawer>
  );
}

export function NewDepositModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const { entities } = useSession();
  const [beSel, setBe] = useState("");
  const be = beSel || entities[0]?.id || "";
  const [date, setDate] = useState(new Date(Date.now() + 9 * 3600_000).toISOString().slice(0, 10));
  const [amount, setAmount] = useState("");
  const [depositor, setDepositor] = useState("");
  const [ref, setRef] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [key] = useState(newIdempotencyKey);
  const submit = async () => {
    setErr(null);
    setBusy(true);
    try {
      await api("/finance/deposits", { method: "POST", idempotencyKey: key, body: { business_entity_id: be, deposited_on: date, amount: Number(amount),
        depositor: depositor || undefined, external_ref: ref || undefined } });
      onDone();
    } catch (x) {
      setErr(msg(x, "등록하지 못했습니다."));
    } finally {
      setBusy(false);
    }
  };
  return (
    <ConfirmModal title="입금 등록" confirmLabel="등록" busy={busy} confirmDisabled={!be || !(Number(amount) > 0)} onClose={onClose} onConfirm={submit}
      impact={[["효과", "입금 내역은 고칠 수 없습니다. 잘못 등록했으면 정산 연결을 취소하고 메모로 남기세요"], ["손익", "입금은 현금 대사이며 매출·손익에 더하지 않습니다"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">사업자<select className="inp" value={be} onChange={(e) => setBe(e.target.value)} aria-label="입금 사업자">
        {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
      <label className="fld">입금일<input className="inp" type="date" value={date} onChange={(e) => setDate(e.target.value)} /></label>
      <label className="fld">입금액(원)<input className="inp" type="number" min={1} step={1} value={amount} onChange={(e) => setAmount(e.target.value)} /></label>
      <label className="fld">입금자명<input className="inp" value={depositor} onChange={(e) => setDepositor(e.target.value)} maxLength={100} /></label>
      <label className="fld">거래번호(중복 방지)<input className="inp" value={ref} onChange={(e) => setRef(e.target.value)} maxLength={120} /></label>
    </ConfirmModal>
  );
}
