"use client";
import { useApi } from "@/lib/useApi";
import { useEffect, useMemo, useState } from "react";
import { AllocationTab, LandedCostTab } from "@/components/CostExtras";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal } from "@/components/Overlay";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { BASIS_KO, CATEGORY_KO, CostRule, Entry, MANUAL_COST_CATEGORIES, METHOD_KO, RULE_CATEGORIES, won } from "@/lib/finance";
import { formatDateTime } from "@/lib/format";
import { FulfillmentOrder } from "@/lib/fulfillment";
import { useSession } from "@/lib/session";

export default function CostsPage() {
  const { can, selected, entities } = useSession();
  const [tab, setTab] = useState<"entries" | "rules" | "landed" | "alloc">("entries");
  const [category, setCategory] = useState("");
  const [adding, setAdding] = useState<null | "rule" | "cost">(null);
  const [notice, setNotice] = useState<string | null>(null);
  const url = useMemo(() => `/finance/entries${qs({ business_entity_id: selected, category, limit: "300" })}`, [selected, category]);
  const entriesApi = useApi<Entry[]>(can("cost.read") && tab === "entries" ? url : null);
  const rulesApi = useApi<CostRule[]>(can("cost.read") && tab === "rules" ? "/finance/cost-rules" : null);
  const entries = entriesApi.loading ? null : entriesApi.data;
  const rules = rulesApi.loading ? null : rulesApi.data;
  const error = tab === "entries" ? entriesApi.error : tab === "rules" ? rulesApi.error : null;
  const reload = () => { entriesApi.reload(); rulesApi.reload(); };
  if (!can("cost.read")) return <Forbidden />;
  const beName = (id: string | null) => (id ? entities.find((e) => e.id === id)?.display_name ?? "—" : "공통");
  return (
    <>
      <div className="page-head"><div><h1>비용·원가</h1><p>관리 손익 원장입니다. 출고·반품·폐기는 자동으로 기록되고, 계약요율로 만든 예상 비용은 실제 청구를 같은 범위(출고지시·주문상품)로 등록하면 대체됩니다(예상과 실제를 합산하지 않음).
        기록된 원장은 고치지 않고 반대 거래로 조정합니다.</p></div>
        {can("cost.manage") && <span className="row-gap"><button className="btn" type="button" onClick={() => setAdding("rule")}>예상 비용 규칙 추가</button>
          <button className="btn pri" type="button" onClick={() => setAdding("cost")}>실제 비용 등록</button></span>}</div>
      {notice && <div className="note n-info" role="status">{notice}</div>}
      <nav className="row-gap" aria-label="비용 메뉴">
        <button className="btn" type="button" aria-current={tab === "entries" ? "page" : undefined} onClick={() => setTab("entries")}>원장</button>
        <button className="btn" type="button" aria-current={tab === "rules" ? "page" : undefined} onClick={() => setTab("rules")}>예상 비용 규칙</button>
        <button className="btn" type="button" aria-current={tab === "landed" ? "page" : undefined} onClick={() => setTab("landed")}>입고 부대비용</button>
        <button className="btn" type="button" aria-current={tab === "alloc" ? "page" : undefined} onClick={() => setTab("alloc")}>공통비 배부</button>
      </nav>
      {tab === "entries" && <div className="row-gap"><label className="fld">항목<select className="inp" value={category} onChange={(e) => setCategory(e.target.value)}>
        <option value="">전체</option>{Object.entries(CATEGORY_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label></div>}
      {tab === "landed" ? <LandedCostTab /> : tab === "alloc" ? <AllocationTab /> : error ? <ErrorState error={error} onRetry={() => reload()} /> : tab === "entries" ? (entries === null ? <Loading /> : (
        <DataTable caption="원장" rows={entries} rowKey={(e) => e.id} empty={<Empty title="원장이 없습니다." />} columns={[
          { key: "d", header: "귀속일", render: (e) => <span className="ctx">{e.period_date}</span> },
          { key: "b", header: "사업자", render: (e) => e.business_entity_name },
          { key: "c", header: "항목", render: (e) => <>{CATEGORY_KO[e.category] ?? e.category} <span className="ctx">{BASIS_KO[e.basis]}{e.reverses_id ? " · 예상 상계" : ""}</span></> },
          { key: "r", header: "근거", render: (e) => <span className="ctx mono">{e.order_no ?? ""} {e.sku_code ?? ""} {e.source_ref ?? ""}</span> },
          { key: "q", header: "수량 · 단가", num: true, render: (e) => e.quantity === null ? "—" : `${e.quantity}${e.unit_cost ? ` × ${won(Math.round(Number(e.unit_cost)))}` : ""}` },
          { key: "a", header: "공급가", num: true, render: (e) => won(e.supply_amount) },
          { key: "m", header: "메모", render: (e) => <span className="ctx">{e.memo ?? ""}</span> },
        ]} />)) : (rules === null ? <Loading /> : (
        <DataTable caption="예상 비용 규칙" rows={rules} rowKey={(r) => r.id} empty={<Empty title="규칙이 없습니다." hint="수수료·배송비 계약요율을 등록하면 출고 때 예상 비용이 만들어집니다." />} columns={[
          { key: "c", header: "항목", render: (r) => CATEGORY_KO[r.category] },
          { key: "b", header: "적용 대상", render: (r) => `${beName(r.business_entity_id)}${r.channel_account_id ? " · 판매계정 지정" : ""}${r.node_id ? " · 거점 지정" : ""}` },
          { key: "m", header: "방식", render: (r) => `${METHOD_KO[r.method]} ${r.rate_bps !== null ? `${(r.rate_bps / 100).toFixed(2)}%` : `${won(r.amount)}원`}` },
          { key: "f", header: "반품 시 환급", render: (r) => (r.refundable_on_return ? "환급" : "환급 없음") },
          { key: "p", header: "적용 기간", render: (r) => <span className="ctx">{formatDateTime(r.effective_from)} ~ {r.effective_to ? formatDateTime(r.effective_to) : ""}</span> },
        ]} />))}
      {adding === "rule" && <RuleModal onClose={() => setAdding(null)} onDone={() => { setAdding(null); setTab("rules"); reload(); }} />}
      {adding === "cost" && <CostModal onClose={() => setAdding(null)} onDone={(msg) => { setAdding(null); setNotice(msg); setTab("entries"); reload(); }} />}
    </>
  );
}

function RuleModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const { entities } = useSession();
  const [be, setBe] = useState("");
  const [category, setCategory] = useState("SELLING_FEE");
  const [method, setMethod] = useState("RATE_OF_NET_SALES");
  const [value, setValue] = useState(0);
  const [refundable, setRefundable] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const submit = async () => {
    setErr(null);
    try {
      await api("/finance/cost-rules", { method: "POST", body: { business_entity_id: be || undefined, category, method, refundable_on_return: refundable,
        rate_bps: method === "RATE_OF_NET_SALES" ? Math.round(value * 100) : undefined, amount: method === "RATE_OF_NET_SALES" ? undefined : value } });
      onDone();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "저장하지 못했습니다.");
    }
  };
  return (
    <ConfirmModal title="예상 비용 규칙 추가" confirmLabel="저장" onClose={onClose} onConfirm={submit}
      impact={[["효과", "이후 출고·반품부터 예상 비용을 만듭니다. 같은 대상·항목의 기존 규칙은 지금 종료됩니다"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">사업자<select className="inp" value={be} onChange={(e) => setBe(e.target.value)} aria-label="규칙 사업자"><option value="">운영그룹 공통</option>
        {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
      <label className="fld">항목<select className="inp" value={category} onChange={(e) => setCategory(e.target.value)}>
        {RULE_CATEGORIES.map((k) => <option key={k} value={k}>{CATEGORY_KO[k]}</option>)}</select></label>
      <label className="fld">방식<select className="inp" value={method} onChange={(e) => setMethod(e.target.value)}>
        {Object.entries(METHOD_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      <label className="fld">{method === "RATE_OF_NET_SALES" ? "요율(%)" : "금액(원, 공급가)"}<input className="inp" type="number" min={0} step={method === "RATE_OF_NET_SALES" ? 0.01 : 1}
        value={value} onChange={(e) => setValue(Number(e.target.value) || 0)} /></label>
      <label><input type="checkbox" checked={refundable} onChange={(e) => setRefundable(e.target.checked)} /> 반품 시 환급(수수료)</label>
    </ConfirmModal>
  );
}

function CostModal({ onClose, onDone }: { onClose: () => void; onDone: (msg: string) => void }) {
  const { entities } = useSession();
  const [beSel, setBe] = useState("");
  const be = beSel || entities[0]?.id || "";
  const [category, setCategory] = useState("OUTBOUND_SHIPPING");
  const [amount, setAmount] = useState(0);
  const [fo, setFo] = useState("");
  const [ref, setRef] = useState("");
  const [fos, setFos] = useState<FulfillmentOrder[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [key] = useState(newIdempotencyKey);
  useEffect(() => { if (be) api<FulfillmentOrder[]>(`/fulfillment-orders${qs({ business_entity_id: be, status: "SHIPPED" })}`).then((r) => setFos(r.data)).catch(() => setFos([])); }, [be]);
  const submit = async () => {
    setErr(null);
    try {
      const r = await api<{ variance: string | null; review_required: boolean; replaced_estimate_ids: string[] }>("/finance/cost-entries", { method: "POST", idempotencyKey: key,
        body: { business_entity_id: be, category, basis: "ACTUAL", supply_amount: amount, scope: fo ? { fulfillment_order_id: fo } : undefined, source_ref: ref || undefined } });
      onDone(r.data.replaced_estimate_ids.length > 0 ? `예상 비용 ${r.data.replaced_estimate_ids.length}건을 실제로 대체했습니다(차이 ${won(r.data.variance)}원${r.data.review_required ? ", 검토 업무 생성" : ""}).`
        : "실제 비용을 기록했습니다.");
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "기록하지 못했습니다.");
    }
  };
  return (
    <ConfirmModal title="실제 비용 등록" confirmLabel="기록" onClose={onClose} onConfirm={submit} confirmDisabled={!be || amount === 0}
      impact={[["대체", "출고지시를 지정하면 그 출고의 같은 항목 예상 비용을 상계하고 실제만 남깁니다"], ["차이", "예상과 100원 이상 다르면 검토 업무를 만듭니다"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">사업자<select className="inp" value={be} onChange={(e) => { setBe(e.target.value); setFo(""); }} aria-label="비용 사업자">
        {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
      <label className="fld">항목<select className="inp" value={category} onChange={(e) => setCategory(e.target.value)}>
        {MANUAL_COST_CATEGORIES.map((k) => <option key={k} value={k}>{CATEGORY_KO[k]}</option>)}</select></label>
      <label className="fld">공급가(원, 부가세 제외)<input className="inp" type="number" value={amount} onChange={(e) => setAmount(Math.round(Number(e.target.value) || 0))} /></label>
      <label className="fld">대상 출고지시(선택)<select className="inp" value={fo} onChange={(e) => setFo(e.target.value)}><option value="">기간 공통 비용</option>
        {fos.map((f) => <option key={f.id} value={f.id}>{f.fo_no} · {f.node_name}</option>)}</select></label>
      <label className="fld">청구 근거<input className="inp" value={ref} onChange={(e) => setRef(e.target.value)} maxLength={200} placeholder="예: CJ 11월 청구서 3행" /></label>
    </ConfirmModal>
  );
}
