"use client";
import { useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal } from "@/components/Overlay";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { won } from "@/lib/finance";
import { formatDateTime } from "@/lib/format";
import { Stock } from "@/lib/inventory";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Transfer = { id: string; from_business_entity_id: string; from_name: string; to_business_entity_id: string; to_name: string; sku_code: string; node_code: string;
  quantity: number; unit_price: string; supply_amount: string; vat_amount: string; tax_type: string; unit_cost: string | null; status: string; approval_id: string | null;
  reason: string; tax_invoice_required: boolean; created_at: string; completed_at: string | null };

const STATUS: Record<string, ["ok" | "info" | "warn" | "mute", string]> = {
  PENDING: ["warn", "승인 대기"], COMPLETED: ["ok", "완료"], REJECTED: ["mute", "반려"], CANCELLED: ["mute", "취소"],
};

export default function OwnershipTransfersPage() {
  const { can, selected } = useSession();
  const allowed = can("inventory.read");
  const list = useApi<Transfer[]>(allowed ? `/inventory/ownership-transfers${qs({ business_entity_id: selected })}` : null);
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  if (!allowed) return <Forbidden />;
  return (
    <>
      <div className="page-head"><div><h1>사업자 간 재고 이전</h1><p>다른 사업자에게 재고 소유권을 넘기는 내부 매출·매입 거래입니다. 거점 이동과 달리 승인(원가 관리 권한자)이 필요하고, 실물 위치는 그대로 둔 채 소유 사업자만 바뀝니다.
        보내는 사업자에 내부 매출·매출원가가 기록되고, 운영그룹 합계 손익에서는 내부거래를 제거합니다. 세금계산서는 외부 회계 프로그램에서 발행하세요.</p></div>
        {can("inventory.adjust") && <button className="btn pri" type="button" onClick={() => setAdding(true)}>이전 요청</button>}</div>
      {notice && <div className="note n-info" role="status">{notice}</div>}
      {list.loading ? <Loading /> : list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : (
        <DataTable caption="사업자 간 이전" rows={list.data ?? []} rowKey={(t) => t.id} empty={<Empty title="이전 내역이 없습니다." />} columns={[
          { key: "d", header: "요청일시", render: (t) => <span className="ctx">{formatDateTime(t.created_at)}</span> },
          { key: "f", header: "보내는 → 받는 사업자", render: (t) => `${t.from_name} → ${t.to_name}` },
          { key: "s", header: "SKU · 거점", render: (t) => <span className="mono">{t.sku_code} · {t.node_code}</span> },
          { key: "q", header: "수량", num: true, render: (t) => t.quantity },
          { key: "p", header: "이전 단가", num: true, render: (t) => won(t.unit_price) },
          { key: "a", header: "공급가 · 부가세", num: true, render: (t) => `${won(t.supply_amount)} · ${won(t.vat_amount)}` },
          { key: "c", header: "보내는 쪽 원가", num: true, render: (t) => (t.unit_cost === null ? "—" : won(Math.round(Number(t.unit_cost) * t.quantity))) },
          { key: "st", header: "상태", render: (t) => <Chip kind={STATUS[t.status]?.[0] ?? "mute"}>{STATUS[t.status]?.[1] ?? t.status}</Chip> },
          { key: "r", header: "사유", render: (t) => <span className="ctx">{t.reason}</span> },
        ]} />
      )}
      {adding && <RequestModal onClose={() => setAdding(false)} onDone={() => { setAdding(false); setNotice("이전 승인을 요청했습니다. 원가 관리 권한이 있는 다른 승인자가 승인함에서 처리합니다."); list.reload(); }} />}
    </>
  );
}

function RequestModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const { entities } = useSession();
  const [fromSel, setFrom] = useState("");
  const from = fromSel || entities[0]?.id || "";
  const [toSel, setTo] = useState("");
  const to = toSel || entities.find((e) => e.id !== from)?.id || "";
  const stocks = useApi<Stock[]>(from ? `/inventory/stocks${qs({ business_entity_id: from })}` : null);
  const sellable = (stocks.data ?? []).filter((s) => s.quality_status === "SELLABLE" && s.on_hand > 0);
  const [stockSel, setStock] = useState("");
  const stock = stockSel || sellable[0]?.id || "";
  const [qty, setQty] = useState(1);
  const [price, setPrice] = useState(0);
  const [tax, setTax] = useState("TAXABLE");
  const [reason, setReason] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [key] = useState(newIdempotencyKey);
  const supply = qty * price;
  const vat = tax === "TAXABLE" ? Math.floor(supply / 10) : 0;
  const submit = async () => {
    setErr(null);
    setBusy(true);
    try {
      await api("/inventory/ownership-transfers", { method: "POST", idempotencyKey: key, body: { stock_id: stock, to_business_entity_id: to, quantity: qty, unit_price: price,
        tax_type: tax, reason } });
      onDone();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "요청하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <ConfirmModal title="사업자 간 재고 이전 요청" confirmLabel="승인 요청" busy={busy} confirmDisabled={!stock || !to || from === to || qty < 1 || !reason.trim()}
      onClose={onClose} onConfirm={submit} impact={[["내부 매출(공급가 · 부가세)", `${won(supply)}원 · ${won(vat)}원`],
        ["효과", "승인되면 같은 위치에서 소유 사업자만 바뀌고, 받는 사업자 재고는 이전 단가로 평가합니다. 승인 전에는 재고가 바뀌지 않습니다"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">보내는 사업자<select className="inp" value={from} onChange={(e) => { setFrom(e.target.value); setStock(""); }} aria-label="보내는 사업자">
        {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
      <label className="fld">재고(판매가능)<select className="inp" value={stock} onChange={(e) => setStock(e.target.value)} aria-label="이전 재고">
        {sellable.length === 0 && <option value="">판매가능 재고 없음</option>}
        {sellable.map((s) => <option key={s.id} value={s.id}>{s.sku_code} · {s.node_code}{s.location_code ? ` · ${s.location_code}` : ""}{s.lot_code ? ` · ${s.lot_code}` : ""} · {s.on_hand}개</option>)}</select></label>
      <label className="fld">받는 사업자<select className="inp" value={to} onChange={(e) => setTo(e.target.value)} aria-label="받는 사업자">
        {entities.filter((x) => x.id !== from).map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
      <label className="fld">수량<input className="inp" type="number" min={1} value={qty} onChange={(e) => setQty(Math.max(1, Math.round(Number(e.target.value) || 1)))} /></label>
      <label className="fld">이전 단가(원, 공급가)<input className="inp" type="number" min={0} value={price} onChange={(e) => setPrice(Math.max(0, Math.round(Number(e.target.value) || 0)))} /></label>
      <label className="fld">과세 유형<select className="inp" value={tax} onChange={(e) => setTax(e.target.value)}>
        <option value="TAXABLE">과세(10%)</option><option value="TAX_FREE">면세</option><option value="ZERO_RATED">영세율</option></select></label>
      <label className="fld">사유<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
    </ConfirmModal>
  );
}
