"use client";
import { useState } from "react";
import { ConfirmModal } from "@/components/Overlay";
import { SkuPicker } from "@/components/SkuPicker";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { won } from "@/lib/finance";
import { useApi } from "@/lib/useApi";

type Account = { id: string; display_name: string; business_entity_id: string; business_entity_name: string };
type Cust = { id: string; name: string; code: string; credit_limit: string; status: string };
type Price = { sku_id: string; unit_price: string; current: boolean };
type Line = { sku_id: string; sku_code: string; quantity: number; unit_price: number };
export const ORDER_KIND_KO: Record<string, string> = { NORMAL: "채널 주문", MANUAL: "수동", B2B: "B2B", SAMPLE: "샘플" };

/** 수동·B2B·샘플 주문 작성(FR-ORD-04). 채널 주문과 같은 경로로 확보·출고되며 채널 발송처리는 없다. */
export function ManualOrderModal({ selected, onClose, onDone }: { selected: string[]; onClose: () => void; onDone: (orderNo: string) => void }) {
  const accounts = useApi<Account[]>(`/channel-accounts${qs({ business_entity_id: selected, limit: "200" })}`);
  const [accSel, setAcc] = useState("");
  const acc = accSel || accounts.data?.[0]?.id || "";
  const [kind, setKind] = useState("B2B");
  const [customer, setCustomer] = useState("");
  const [custId, setCustId] = useState("");
  const [terms, setTerms] = useState("CREDIT");
  const be = accounts.data?.find((a) => a.id === acc)?.business_entity_id;
  const custs = useApi<Cust[]>(kind === "B2B" && be ? `/b2b-customers${qs({ business_entity_id: [be], status: "ACTIVE" })}` : null);
  const prices = useApi<Price[]>(custId ? `/b2b-customers/${custId}/prices` : null);
  const priceOf = (sku: string) => Number(prices.data?.find((p) => p.sku_id === sku && p.current)?.unit_price ?? 0);
  const [rcp, setRcp] = useState({ name: "", phone: "", address1: "", address2: "", memo: "" });
  const [ship, setShip] = useState(0);
  const [lines, setLines] = useState<Line[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [key] = useState(newIdempotencyKey);
  const sample = kind === "SAMPLE";
  const total = lines.reduce((t, l) => t + (sample ? 0 : l.unit_price * l.quantity), 0) + (sample ? 0 : ship);
  const submit = async () => {
    setErr(null);
    setBusy(true);
    try {
      const r = await api<{ order_no: string }>("/orders/manual", { method: "POST", idempotencyKey: key, body: { channel_account_id: acc, kind,
        customer_name: custId ? undefined : customer || undefined,
        customer_id: kind === "B2B" && custId ? custId : undefined, payment_terms: kind === "B2B" && custId ? terms : undefined, shipping_fee: sample ? 0 : ship, recipient: { ...rcp, address2: rcp.address2 || undefined, memo: rcp.memo || undefined },
        lines: lines.map((l) => ({ sku_id: l.sku_id, quantity: l.quantity, unit_price: sample ? 0 : l.unit_price })) } });
      onDone(r.data.order_no);
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "주문을 만들지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };
  const ok = acc && lines.length > 0 && rcp.name.trim() && rcp.phone.trim() && rcp.address1.trim() && (kind !== "B2B" || custId || customer.trim());
  return (
    <ConfirmModal title="수동 주문 작성" confirmLabel="주문 만들기" busy={busy} confirmDisabled={!ok} onClose={onClose} onConfirm={submit}
      impact={[["결제 금액", `${won(total)}원(부가세 포함)`], ...(kind === "B2B" && custId && terms === "CREDIT" ? [["외상", "외상 한도·연체를 확인하고, 출고 시 청구합니다"] as [string, string]] : []), ["처리", "채널 주문과 같이 재고 확보·출고 배정되며 채널 발송처리는 하지 않습니다"],
        ...(sample ? [["샘플", "금액 0, 출고 원가는 판촉비로 기록됩니다"] as [string, string]] : [])]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">판매계정(주문 귀속)<select className="inp" value={acc} onChange={(e) => setAcc(e.target.value)} aria-label="수동 주문 판매계정">
        {(accounts.data ?? []).map((a) => <option key={a.id} value={a.id}>{a.business_entity_name} · {a.display_name}</option>)}</select></label>
      <label className="fld">주문 종류<select className="inp" value={kind} onChange={(e) => setKind(e.target.value)} aria-label="주문 종류">
        <option value="B2B">B2B 납품</option><option value="MANUAL">일반 수동(전화·현장)</option><option value="SAMPLE">샘플(무상)</option></select></label>
      {kind === "B2B" && <label className="fld">거래처<select className="inp" value={custId} onChange={(e) => { setCustId(e.target.value); setLines([]); }} aria-label="B2B 거래처">
        <option value="">직접 입력(거래처 미등록)</option>
        {(custs.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name} ({c.code}){Number(c.credit_limit) > 0 ? "" : " · 선결제만"}</option>)}</select></label>}
      {kind === "B2B" && !custId && <label className="fld">거래처명<input className="inp" value={customer} onChange={(e) => setCustomer(e.target.value)} maxLength={100} /></label>}
      {kind === "B2B" && custId && <label className="fld">결제 조건<select className="inp" value={terms} onChange={(e) => setTerms(e.target.value)} aria-label="결제 조건">
        <option value="CREDIT">외상(출고 후 청구)</option><option value="PREPAID">선결제</option></select></label>}
      <label className="fld">받는 분<input className="inp" value={rcp.name} onChange={(e) => setRcp({ ...rcp, name: e.target.value })} /></label>
      <label className="fld">연락처<input className="inp" value={rcp.phone} onChange={(e) => setRcp({ ...rcp, phone: e.target.value })} /></label>
      <label className="fld">주소<input className="inp" value={rcp.address1} onChange={(e) => setRcp({ ...rcp, address1: e.target.value })} /></label>
      <label className="fld">상세 주소<input className="inp" value={rcp.address2} onChange={(e) => setRcp({ ...rcp, address2: e.target.value })} /></label>
      <SkuPicker label="상품 추가(SKU 검색)" onPick={(s) => !lines.some((l) => l.sku_id === s.id) && setLines([...lines, { sku_id: s.id, sku_code: s.sku_code, quantity: 1, unit_price: custId ? priceOf(s.id) : 0 }])} />
      {lines.map((l, i) => (
        <div key={l.sku_id} className="row-gap">
          <span className="mono">{l.sku_code}</span>
          <label className="fld">수량<input className="inp" type="number" min={1} aria-label={`${l.sku_code} 수량`} value={l.quantity}
            onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, quantity: Math.max(1, Math.round(Number(e.target.value) || 1)) } : x)))} /></label>
          {!sample && <label className="fld">단가(부가세 포함)<input className="inp" type="number" min={0} aria-label={`${l.sku_code} 단가`} value={l.unit_price}
            onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, unit_price: Math.max(0, Math.round(Number(e.target.value) || 0)) } : x)))} /></label>}
          <button className="btn" type="button" onClick={() => setLines(lines.filter((_, j) => j !== i))}>빼기</button>
        </div>))}
      {!sample && <label className="fld">배송비(부가세 포함)<input className="inp" type="number" min={0} value={ship} onChange={(e) => setShip(Math.max(0, Math.round(Number(e.target.value) || 0)))} /></label>}
    </ConfirmModal>
  );
}
