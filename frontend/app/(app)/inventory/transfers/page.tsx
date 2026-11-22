"use client";
import { useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { InventoryTabs } from "@/components/InventoryTabs";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { SkuPicker } from "@/components/SkuPicker";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { Sku } from "@/lib/catalog";
import { formatDateTime } from "@/lib/format";
import { Node, num, QUALITY_KO, Transfer, TRANSFER_STATUS } from "@/lib/inventory";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

export default function TransfersPage() {
  const { can, selected } = useSession();
  const [status, setStatus] = useState("");
  const path = useMemo(() => `/inventory/transfers${qs({ business_entity_id: selected, status })}`, [selected, status]);
  const { data, error, loading, reload } = useApi<Transfer[]>(can("inventory.read") ? path : null);
  const [creating, setCreating] = useState(false);
  const [receiving, setReceiving] = useState<Transfer | null>(null);
  if (!can("inventory.read")) return <Forbidden />;
  return (
    <>
      <div className="page-head">
        <div><h1>재고 이동</h1><p>같은 소유 사업자 안에서 거점 간 재고를 옮깁니다. 이동 중 수량은 이동 기록에 남아 재고 자산에 계속 포함되고, 덜 받은 수량은 이동 차이 업무로 남습니다.
          사업자 간 이전은 이동이 아니라 내부 매출·매입 거래로 처리합니다.</p></div>
        {can("inventory.operate") && <button className="btn pri" type="button" onClick={() => setCreating(true)}>재고 이동</button>}
      </div>
      <InventoryTabs active="transfers" />
      <div className="row-gap">
        <label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">전체</option>
          {Object.entries(TRANSFER_STATUS).map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="재고 이동" rows={data ?? []} rowKey={(r) => r.id}
          empty={<Empty title="재고 이동이 없습니다." />}
          columns={[
            { key: "no", header: "이동번호", render: (r) => <b className="mono">{r.transfer_no}</b> },
            { key: "be", header: "사업자", render: (r) => r.business_entity_name },
            { key: "ft", header: "출발 → 도착", render: (r) => `${r.from_node_name} → ${r.to_node_name}` },
            { key: "l", header: "SKU · 수량", render: (r) => r.lines.map((l) => `${l.sku_code} ${num(l.quantity)}${l.received_qty != null && l.received_qty !== l.quantity ? `(받음 ${num(l.received_qty)})` : ""}`).join(", ") },
            { key: "t", header: "출고", render: (r) => <span className="ctx">{formatDateTime(r.shipped_at)}</span> },
            { key: "s", header: "상태", render: (r) => <Chip kind={TRANSFER_STATUS[r.status][0]}>{TRANSFER_STATUS[r.status][1]}</Chip> },
            { key: "a", header: "처리", render: (r) => r.status === "IN_TRANSIT" && can("inventory.operate") ? <button className="btn" type="button" onClick={() => setReceiving(r)}>입고 처리</button> : null },
          ]} />
      )}
      {creating && <CreateTransfer onClose={() => setCreating(false)} onDone={() => { setCreating(false); reload(); }} />}
      {receiving && <ReceiveModal t={receiving} onClose={() => setReceiving(null)} onDone={() => { setReceiving(null); reload(); }} />}
    </>
  );
}

function CreateTransfer({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const { entities } = useSession();
  const nodes = useApi<Node[]>("/fulfillment-nodes");
  const active = (nodes.data ?? []).filter((n) => n.status === "ACTIVE");
  const [be, setBe] = useState(entities[0]?.id ?? "");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [lines, setLines] = useState<{ sku: Sku; quantity: number }[]>([]);
  const [note, setNote] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [key] = useState(newIdempotencyKey);
  const submit = async () => {
    setErr(null);
    try {
      await api("/inventory/transfers", { method: "POST", idempotencyKey: key, body: { business_entity_id: be, from_node_id: from, to_node_id: to,
        lines: lines.map((l) => ({ sku_id: l.sku.id, quantity: l.quantity })), note: note || undefined } });
      onDone();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "이동하지 못했습니다.");
    }
  };
  return (
    <Drawer title="재고 이동" onClose={onClose} footer={<><button className="btn" type="button" onClick={onClose}>취소</button>
      <button className="btn pri" type="button" onClick={submit} disabled={!be || !from || !to || from === to || lines.length === 0}>출고(이동 시작)</button></>}>
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        <label className="fld">소유 사업자<select className="inp" value={be} onChange={(e) => setBe(e.target.value)}>
          {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
        <label className="fld">출발 거점<select className="inp" value={from} onChange={(e) => setFrom(e.target.value)}><option value="">선택</option>
          {active.filter((n) => n.inventory_source === "INTERNAL").map((n) => <option key={n.id} value={n.id}>{n.name}</option>)}</select></label>
        <label className="fld">도착 거점<select className="inp" value={to} onChange={(e) => setTo(e.target.value)}><option value="">선택</option>
          {active.filter((n) => n.id !== from).map((n) => <option key={n.id} value={n.id}>{n.name}{n.inventory_source === "EXTERNAL" ? " (물류사 재고 기준)" : ""}</option>)}</select></label>
        {active.find((n) => n.id === to)?.inventory_source === "EXTERNAL" && <div className="note n-info">물류사 거점 도착분은 물류사 재고 자료(외부 재고 동기화)로 반영됩니다. 입고 처리는 수량 확인만 기록합니다.</div>}
        {lines.map((l, i) => <div key={l.sku.id} className="row-gap" style={{ alignItems: "center" }}>
          <span className="mono" style={{ flex: 1 }}>{l.sku.sku_code}</span>
          <input className="inp" type="number" min={1} aria-label={`${l.sku.sku_code} 이동 수량`} style={{ width: 90 }} value={l.quantity}
            onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, quantity: Number(e.target.value) || 1 } : x)))} />
          <button className="btn" type="button" onClick={() => setLines(lines.filter((_, j) => j !== i))}>빼기</button></div>)}
        <SkuPicker label="이동할 SKU 추가" onPick={(s) => !lines.some((l) => l.sku.id === s.id) && setLines([...lines, { sku: s, quantity: 1 }])} />
        <label className="fld">메모(선택)<input className="inp" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} /></label>
        <p className="ctx" style={{ margin: 0 }}>확보(예약·할당·피킹)되지 않은 판매가능 재고만, 유통기한 빠른 LOT 부터 보냅니다. 실사 중인 재고는 보낼 수 없습니다.</p>
      </div>
    </Drawer>
  );
}

function ReceiveModal({ t, onClose, onDone }: { t: Transfer; onClose: () => void; onDone: () => void }) {
  const [rows, setRows] = useState(t.lines.map((l) => ({ line_no: l.line_no, received_qty: l.quantity, quality_status: "SELLABLE" })));
  const [loc, setLoc] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [key] = useState(newIdempotencyKey);
  const short = t.lines.reduce((a, l, i) => a + l.quantity - rows[i].received_qty, 0);
  const submit = async () => {
    setErr(null);
    try {
      await api(`/inventory/transfers/${t.id}/receive`, { method: "POST", idempotencyKey: key, body: { lines: rows, location_code: loc || undefined } });
      onDone();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "입고 처리하지 못했습니다.");
    }
  };
  return (
    <ConfirmModal title={`이동 입고 — ${t.transfer_no}`} confirmLabel="입고 처리" onClose={onClose} onConfirm={submit}
      impact={[["도착", t.to_node_name], ["부족", short > 0 ? `${num(short)}개 — 이동 차이 업무가 생깁니다` : "없음"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {t.lines.map((l, i) => <div key={l.line_no} className="row-gap" style={{ alignItems: "center" }}>
        <span className="mono" style={{ flex: 1 }}>{l.sku_code}{l.lot_code ? ` · ${l.lot_code}` : ""} (보냄 {num(l.quantity)})</span>
        <input className="inp" type="number" min={0} max={l.quantity} aria-label={`${l.sku_code} 받은 수량`} style={{ width: 90 }} value={rows[i].received_qty}
          onChange={(e) => setRows(rows.map((r, j) => (j === i ? { ...r, received_qty: Math.min(l.quantity, Math.max(0, Number(e.target.value) || 0)) } : r)))} />
        <select className="inp" aria-label={`${l.sku_code} 받은 품질`} value={rows[i].quality_status}
          onChange={(e) => setRows(rows.map((r, j) => (j === i ? { ...r, quality_status: e.target.value } : r)))}>
          {Object.entries(QUALITY_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></div>)}
      {t.to_inventory_source === "INTERNAL" && <label className="fld">적치 로케이션(선택)<input className="inp mono" value={loc} onChange={(e) => setLoc(e.target.value)} /></label>}
    </ConfirmModal>
  );
}
