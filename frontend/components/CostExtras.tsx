"use client";
import { useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { ALLOC_METHOD_KO, Allocation, CATEGORY_KO, LANDED_METHOD_KO, LANDED_TYPE_KO, LandedCost, MANUAL_COST_CATEGORIES, won } from "@/lib/finance";
import { formatDateTime, kstToday } from "@/lib/format";
import { Txn } from "@/lib/inventory";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

const msg = (x: unknown, f: string) => (x instanceof ApiError ? x.message : f);

/** 입고 부대비용(T33): 운임·관세 등을 입고분에 배부해 취득원가로. */
export function LandedCostTab() {
  const { can, selected } = useSession();
  const list = useApi<LandedCost[]>(`/finance/landed-costs${qs({ business_entity_id: selected })}`);
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  return (
    <>
      <p className="ctx">수입 운임·관세·검사비 같은 취득부대비용은 입고분에 배부해 재고 원가에 더합니다. 이미 출고된 수량분만 매출원가로 바로 잡고, 같은 근거번호는 출고 배송비 등 비용으로 다시 기록할 수 없습니다(이중 차감 방지).</p>
      {can("cost.manage") && <div className="row-gap"><button className="btn pri" type="button" onClick={() => setAdding(true)}>입고 부대비용 반영</button></div>}
      {notice && <div className="note n-info" role="status">{notice}</div>}
      {list.loading ? <Loading /> : list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : (
        <DataTable caption="입고 부대비용" rows={list.data ?? []} rowKey={(l) => l.id} empty={<Empty title="반영한 입고 부대비용이 없습니다." />} columns={[
          { key: "d", header: "반영일시", render: (l) => <span className="ctx">{formatDateTime(l.created_at)}</span> },
          { key: "b", header: "사업자", render: (l) => l.business_entity_name },
          { key: "t", header: "종류 · 근거", render: (l) => <>{LANDED_TYPE_KO[l.cost_type]} <span className="ctx mono">{l.source_ref}</span></> },
          { key: "m", header: "배부 기준", render: (l) => LANDED_METHOD_KO[l.method] },
          { key: "s", header: "공급가", num: true, render: (l) => won(l.supply_amount) },
          { key: "c", header: "재고 원가 반영", num: true, render: (l) => won(l.capitalized) },
          { key: "e", header: "매출원가(이미 출고분)", num: true, render: (l) => won(l.expensed) },
          { key: "k", header: "SKU", render: (l) => <span className="ctx mono">{l.lines.map((x) => `${x.sku_code} ${won(x.allocated)}`).join(", ")}</span> },
        ]} />
      )}
      {adding && <LandedModal onClose={() => setAdding(false)} onDone={(m) => { setAdding(false); setNotice(m); list.reload(); }} />}
    </>
  );
}

function LandedModal({ onClose, onDone }: { onClose: () => void; onDone: (m: string) => void }) {
  const { entities } = useSession();
  const [beSel, setBe] = useState("");
  const be = beSel || entities[0]?.id || "";
  const receipts = useApi<Txn[]>(be ? `/inventory/transactions${qs({ business_entity_id: be, txn_type: "RECEIPT", limit: "50" })}` : null);
  const [picked, setPicked] = useState<string[]>([]);
  const [type, setType] = useState("FREIGHT");
  const [method, setMethod] = useState("QUANTITY");
  const [amount, setAmount] = useState(0);
  const [vat, setVat] = useState(0);
  const [ref, setRef] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const toggle = (id: string) => setPicked((p) => (p.includes(id) ? p.filter((x) => x !== id) : [...p, id]));
  const submit = async () => {
    setErr(null);
    setBusy(true);
    try {
      const r = await api<LandedCost>("/finance/landed-costs", { method: "POST", body: { business_entity_id: be, cost_type: type, method, supply_amount: amount, vat_amount: vat,
        source_ref: ref, receipts: picked.map((id) => ({ receipt_txn_id: id })) } });
      onDone(`재고 원가에 ${won(r.data.capitalized)}원, 이미 출고된 수량분 ${won(r.data.expensed)}원은 매출원가로 반영했습니다.`);
    } catch (x) {
      setErr(msg(x, "반영하지 못했습니다."));
    } finally {
      setBusy(false);
    }
  };
  return (
    <ConfirmModal title="입고 부대비용 반영" confirmLabel="반영" busy={busy} confirmDisabled={!be || amount <= 0 || !ref.trim() || picked.length === 0 || method === "MANUAL"}
      onClose={onClose} onConfirm={submit} impact={[["효과", "보유 중인 수량 비율만큼 이동평균 원가에 더하고, 이미 출고된 수량분은 매출원가로 기록합니다"],
        ["중복 방지", "같은 근거번호의 청구는 다시 반영하거나 비용으로 기록할 수 없습니다"], ["부가세", "매입세액이라 손익에 넣지 않습니다"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">사업자<select className="inp" value={be} onChange={(e) => { setBe(e.target.value); setPicked([]); }} aria-label="부대비용 사업자">
        {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
      <label className="fld">종류<select className="inp" value={type} onChange={(e) => setType(e.target.value)}>
        {Object.entries(LANDED_TYPE_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      <label className="fld">배부 기준<select className="inp" value={method} onChange={(e) => setMethod(e.target.value)}>
        <option value="QUANTITY">수량 비중</option><option value="AMOUNT">입고 금액 비중(입고 단가 필요)</option></select></label>
      <label className="fld">공급가(원)<input className="inp" type="number" min={0} value={amount} onChange={(e) => setAmount(Math.round(Number(e.target.value) || 0))} /></label>
      <label className="fld">부가세(원)<input className="inp" type="number" min={0} value={vat} onChange={(e) => setVat(Math.round(Number(e.target.value) || 0))} /></label>
      <label className="fld">근거번호(청구서·수입신고)<input className="inp" value={ref} onChange={(e) => setRef(e.target.value)} maxLength={200} /></label>
      <fieldset className="fld"><legend>배부할 입고(최근 50건)</legend>
        {receipts.loading ? <Loading /> : (receipts.data ?? []).length === 0 ? <span className="ctx">입고 내역이 없습니다.</span> : (receipts.data ?? []).map((t) => (
          <label key={t.id}><input type="checkbox" checked={picked.includes(t.id)} onChange={() => toggle(t.id)} /> {formatDateTime(t.occurred_at)} · {t.sku_code} · {t.node_code} · {t.on_hand_delta}개</label>))}
      </fieldset>
    </ConfirmModal>
  );
}

/** 공통비 배부(T34): 한 청구를 여러 사업자에 나눠 각 사업자 원장에. */
export function AllocationTab() {
  const { can, selected } = useSession();
  const list = useApi<Allocation[]>(`/finance/cost-allocations${qs({ business_entity_id: selected })}`);
  const [modal, setModal] = useState<null | { re?: Allocation }>(null);
  const [notice, setNotice] = useState<string | null>(null);
  return (
    <>
      <p className="ctx">공동 광고비·보관비처럼 여러 사업자가 함께 쓴 비용을 순매출 비중·균등·직접 비율로 나눠 각 사업자 손익에 기록합니다. 배부 합계는 원본 금액과 같고(원 미만 잔액은 가장 큰 몫에),
        재배부하면 이전 버전을 반대 거래로 상계한 뒤 새 버전을 기록합니다.</p>
      {can("cost.manage") && <div className="row-gap"><button className="btn pri" type="button" onClick={() => setModal({})}>공통비 배부</button></div>}
      {notice && <div className="note n-info" role="status">{notice}</div>}
      {list.loading ? <Loading /> : list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : (
        <DataTable caption="공통비 배부" rows={list.data ?? []} rowKey={(a) => a.id} empty={<Empty title="공통비 배부가 없습니다." />} columns={[
          { key: "n", header: "배부번호", render: (a) => <span className="mono">{a.allocation_no} v{a.version}</span> },
          { key: "s", header: "상태", render: (a) => (a.status === "ACTIVE" ? <Chip kind="ok">적용</Chip> : <Chip>이전 버전</Chip>) },
          { key: "c", header: "항목 · 귀속일", render: (a) => <>{CATEGORY_KO[a.category]} <span className="ctx">{a.period_date}</span></> },
          { key: "m", header: "기준", render: (a) => <>{ALLOC_METHOD_KO[a.method]}{a.basis_from ? <span className="ctx"> {a.basis_from}~{a.basis_to}</span> : null}</> },
          { key: "a", header: "공급가", num: true, render: (a) => won(a.supply_amount) },
          { key: "l", header: "사업자별", render: (a) => <span className="ctx">{a.lines.map((l) => `${l.business_entity_name} ${l.supply_amount === null ? "—" : won(l.supply_amount)}`).join(" · ")}</span> },
          { key: "r", header: "", render: (a) => (a.status === "ACTIVE" && can("cost.manage") ? <button className="btn" type="button" onClick={() => setModal({ re: a })}>재배부</button> : null) },
        ]} />
      )}
      {modal && <AllocationModal re={modal.re} onClose={() => setModal(null)} onDone={(m) => { setModal(null); setNotice(m); list.reload(); }} />}
    </>
  );
}

function AllocationModal({ re, onClose, onDone }: { re?: Allocation; onClose: () => void; onDone: (m: string) => void }) {
  const { entities } = useSession();
  const [category, setCategory] = useState(re?.category ?? "ADVERTISING");
  const [amount, setAmount] = useState(re ? Number(re.supply_amount) : 0);
  const [vat, setVat] = useState(re ? Number(re.vat_amount) : 0);
  const [method, setMethod] = useState(re?.method ?? "NET_SALES");
  const [period, setPeriod] = useState(() => re?.period_date ?? kstToday());
  const [ref, setRef] = useState(re?.source_ref ?? "");
  const [targets, setTargets] = useState<Record<string, string>>(() => Object.fromEntries((re?.lines ?? []).map((l) => [l.business_entity_id, "1"])));
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [key] = useState(newIdempotencyKey);
  const chosen = Object.keys(targets);
  const toggle = (id: string) => setTargets((t) => { const n = { ...t }; if (id in n) delete n[id]; else n[id] = "1"; return n; });
  const submit = async () => {
    setErr(null);
    setBusy(true);
    const body = { category, supply_amount: amount, vat_amount: vat, period_date: period, method, source_ref: ref || undefined,
      targets: chosen.map((id) => ({ business_entity_id: id, ratio: method === "MANUAL_RATIO" ? Number(targets[id]) : undefined })) };
    try {
      const r = re ? await api<Allocation>(`/finance/cost-allocations/${re.id}/reallocate`, { method: "POST", body })
        : await api<Allocation>("/finance/cost-allocations", { method: "POST", idempotencyKey: key, body });
      onDone(`${r.data.allocation_no} v${r.data.version}: ${r.data.lines.map((l) => `${l.business_entity_name} ${won(l.supply_amount)}원`).join(", ")}`);
    } catch (x) {
      setErr(msg(x, "배부하지 못했습니다."));
    } finally {
      setBusy(false);
    }
  };
  return (
    <ConfirmModal title={re ? `${re.allocation_no} 재배부` : "공통비 배부"} confirmLabel={re ? "재배부" : "배부"} busy={busy} confirmDisabled={amount <= 0 || chosen.length < 2}
      onClose={onClose} onConfirm={submit} impact={[["합계", `${won(amount)}원(부가세 ${won(vat)}원)을 ${chosen.length}개 사업자에`],
        ["효과", re ? "이전 버전 원장을 반대 거래로 상계하고 새 버전을 기록합니다" : "각 사업자 손익에 실제 비용으로 기록합니다"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {!re && <>
        <label className="fld">항목<select className="inp" value={category} onChange={(e) => setCategory(e.target.value)}>
          {MANUAL_COST_CATEGORIES.map((k) => <option key={k} value={k}>{CATEGORY_KO[k]}</option>)}</select></label>
        <label className="fld">공급가(원)<input className="inp" type="number" min={0} value={amount} onChange={(e) => setAmount(Math.round(Number(e.target.value) || 0))} /></label>
        <label className="fld">부가세(원)<input className="inp" type="number" min={0} value={vat} onChange={(e) => setVat(Math.round(Number(e.target.value) || 0))} /></label>
        <label className="fld">귀속일<input className="inp" type="date" value={period} onChange={(e) => setPeriod(e.target.value)} /></label>
        <label className="fld">근거번호<input className="inp" value={ref} onChange={(e) => setRef(e.target.value)} maxLength={200} /></label>
      </>}
      <label className="fld">배부 기준<select className="inp" value={method} onChange={(e) => setMethod(e.target.value)} aria-label="배부 기준">
        {Object.entries(ALLOC_METHOD_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      {method === "NET_SALES" && <p className="ctx" style={{ margin: 0 }}>귀속일이 속한 달의 사업자별 순매출(내부거래 제외) 비중으로 나눕니다.</p>}
      <fieldset className="fld"><legend>대상 사업자(2곳 이상)</legend>
        {entities.map((x) => (
          <span key={x.id} className="row-gap"><label><input type="checkbox" checked={x.id in targets} onChange={() => toggle(x.id)} /> {x.display_name}</label>
            {method === "MANUAL_RATIO" && x.id in targets && <input className="inp" type="number" min={0} aria-label={`${x.display_name} 비율`} value={targets[x.id]}
              onChange={(e) => setTargets((t) => ({ ...t, [x.id]: e.target.value }))} style={{ width: 90 }} />}</span>))}
      </fieldset>
    </ConfirmModal>
  );
}
