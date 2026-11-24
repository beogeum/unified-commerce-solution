"use client";
import { useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { PlanShipmentModal } from "@/components/InboundParts";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { SkuPicker } from "@/components/SkuPicker";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError } from "@/lib/api";
import { won } from "@/lib/finance";
import { formatDateTime } from "@/lib/format";
import { Node } from "@/lib/inventory";
import { PO_STATUS, PurchaseOrder, Supplier, SupplierSku } from "@/lib/procurement";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

const msg = (x: unknown, f: string) => (x instanceof ApiError ? x.message : f);
const EMPTY_ROW = { good: 0, rej: 0, cost: "", reason: "", lot: "", exp: "" };

export function PoStatus({ s }: { s: string }) {
  const v = PO_STATUS[s] ?? ["mute", s];
  return <Chip kind={v[0]}>{v[1]}</Chip>;
}

/** 공급처 상세: 공급 SKU(단가·MOQ·발주단위·리드타임) 등록·변경. */
export function SupplierDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const sup = useApi<Supplier>(`/suppliers/${id}`);
  const skus = useApi<SupplierSku[]>(`/suppliers/${id}/skus`);
  const [adding, setAdding] = useState(false);
  const [skuId, setSkuId] = useState("");
  const [skuCode, setSkuCode] = useState("");
  const [f, setF] = useState({ unit_cost: 0, moq: 1, order_multiple: 1, supplier_sku_code: "" });
  const [err, setErr] = useState<string | null>(null);
  const save = async () => {
    try {
      await api(`/suppliers/${id}/skus`, { method: "PUT", body: { sku_id: skuId, ...f, supplier_sku_code: f.supplier_sku_code || undefined } });
      setAdding(false);
      setSkuId("");
      skus.reload();
      onChanged();
    } catch (x) {
      setErr(msg(x, "저장하지 못했습니다."));
    }
  };
  const s = sup.data;
  return (
    <Drawer title={s ? `공급처 ${s.name}` : "공급처"} onClose={onClose}
      footer={can("purchase.manage") ? <button className="btn pri" type="button" onClick={() => { setAdding(true); setErr(null); }}>공급 SKU 추가·변경</button> : undefined}>
      {sup.loading ? <Loading /> : sup.error ? <ErrorState error={sup.error} onRetry={sup.reload} /> : s && <>
        <div className="kv"><span>사업자</span><span>{s.business_entity_name}</span></div>
        <div className="kv"><span>사업자등록번호 · 리드타임</span><span>{s.business_registration_no ?? "—"} · {s.lead_time_days}일</span></div>
        <div className="kv"><span>결제조건</span><span>{s.payment_terms ?? "—"}</span></div>
        <h3>공급 SKU</h3>
        {skus.loading ? <Loading /> : <DataTable caption="공급 SKU" rows={skus.data ?? []} rowKey={(x) => x.id} empty={<Empty title="등록된 공급 SKU 가 없습니다." />} columns={[
          { key: "k", header: "SKU", render: (x) => <><span className="mono">{x.sku_code}</span> <span className="ctx">{x.supplier_sku_code ?? ""}</span></> },
          { key: "u", header: "단가(공급가)", num: true, render: (x) => (x.unit_cost === null ? "권한 없음" : won(x.unit_cost)) },
          { key: "m", header: "MOQ · 발주단위", num: true, render: (x) => `${x.moq} · ${x.order_multiple}` },
          { key: "l", header: "리드타임", num: true, render: (x) => `${x.effective_lead_time_days}일` },
          { key: "s", header: "상태", render: (x) => (x.status === "ACTIVE" ? "사용" : "중지") },
        ]} />}
      </>}
      {adding && <ConfirmModal title="공급 SKU 추가·변경" confirmLabel="저장" confirmDisabled={!skuId} onClose={() => setAdding(false)} onConfirm={save}
        impact={[["효과", "이후 발주부터 이 단가·MOQ 를 씁니다(이미 만든 발주는 그대로)"]]}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        {skuId ? <div className="kv"><span>SKU</span><span className="mono">{skuCode}</span></div>
          : <SkuPicker label="SKU 검색" onPick={(x) => { setSkuId(x.id); setSkuCode(x.sku_code); }} />}
        <label className="fld">단가(원, 공급가)<input className="inp" type="number" min={0} value={f.unit_cost} onChange={(e) => setF({ ...f, unit_cost: Math.round(Number(e.target.value) || 0) })} /></label>
        <label className="fld">최소 발주수량(MOQ)<input className="inp" type="number" min={1} value={f.moq} onChange={(e) => setF({ ...f, moq: Math.max(1, Number(e.target.value) || 1) })} /></label>
        <label className="fld">발주단위<input className="inp" type="number" min={1} value={f.order_multiple} onChange={(e) => setF({ ...f, order_multiple: Math.max(1, Number(e.target.value) || 1) })} /></label>
        <label className="fld">공급처 품번<input className="inp" value={f.supplier_sku_code} onChange={(e) => setF({ ...f, supplier_sku_code: e.target.value })} maxLength={100} /></label>
      </ConfirmModal>}
    </Drawer>
  );
}

/** 발주 작성: 공급처 → 공급 SKU 수량. MOQ·발주단위를 맞추지 않으면 예외 사유 필요. */
export function NewPoModal({ suppliers, nodes, onClose, onDone }: { suppliers: Supplier[]; nodes: Node[]; onClose: () => void; onDone: (id: string) => void }) {
  const [supSel, setSup] = useState("");
  const sup = supSel || suppliers[0]?.id || "";
  const internal = nodes.filter((n) => n.inventory_source === "INTERNAL" && n.status === "ACTIVE");
  const [nodeSel, setNode] = useState("");
  const node = nodeSel || internal[0]?.id || "";
  const [expected, setExpected] = useState("");
  const skus = useApi<SupplierSku[]>(sup ? `/suppliers/${sup}/skus` : null);
  const [qty, setQty] = useState<Record<string, number>>({});
  const [override, setOverride] = useState<Record<string, string>>({});
  const [err, setErr] = useState<string | null>(null);
  const active = (skus.data ?? []).filter((s) => s.status === "ACTIVE");
  const chosen = active.filter((s) => (qty[s.sku_id] ?? 0) > 0);
  const total = chosen.reduce((t, s) => t + (s.unit_cost === null ? 0 : Number(s.unit_cost) * qty[s.sku_id]), 0);
  const submit = async () => {
    try {
      const r = await api<PurchaseOrder>("/purchase-orders", { method: "POST", body: { supplier_id: sup, node_id: node, expected_on: expected || undefined,
        lines: chosen.map((s) => ({ sku_id: s.sku_id, quantity: qty[s.sku_id], moq_override_reason: override[s.sku_id] || undefined })) } });
      onDone(r.data.id);
    } catch (x) {
      setErr(msg(x, "만들지 못했습니다."));
    }
  };
  return (
    <ConfirmModal title="발주 작성" confirmLabel="작성" confirmDisabled={!sup || !node || chosen.length === 0} onClose={onClose} onConfirm={submit}
      impact={[["예상 공급가", `${won(total)}원(부가세 별도)`], ["다음 단계", "작성 후 승인 요청 → 다른 담당자 승인 시 발주 완료"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">공급처<select className="inp" value={sup} onChange={(e) => { setSup(e.target.value); setQty({}); }} aria-label="발주 공급처">
        {suppliers.filter((s) => s.status === "ACTIVE").map((s) => <option key={s.id} value={s.id}>{s.business_entity_name} · {s.name}</option>)}</select></label>
      <label className="fld">입고 거점<select className="inp" value={node} onChange={(e) => setNode(e.target.value)} aria-label="입고 거점">
        {internal.map((n) => <option key={n.id} value={n.id}>{n.name}</option>)}</select></label>
      <label className="fld">입고 예정일<input className="inp" type="date" value={expected} onChange={(e) => setExpected(e.target.value)} /></label>
      {active.length === 0 ? <span className="ctx">이 공급처에 등록된 공급 SKU 가 없습니다.</span> : active.map((s) => {
        const q = qty[s.sku_id] ?? 0;
        const bad = q > 0 && (q < s.moq || q % s.order_multiple !== 0);
        return (
          <div key={s.sku_id} style={{ display: "flex", flexDirection: "column", gap: 4 }}>
            <label className="fld"><span><span className="mono">{s.sku_code}</span> <span className="ctx">MOQ {s.moq} · 단위 {s.order_multiple} · {s.unit_cost === null ? "" : `${won(s.unit_cost)}원`}</span></span>
              <input className="inp" type="number" min={0} aria-label={`${s.sku_code} 수량`} value={q} onChange={(e) => setQty({ ...qty, [s.sku_id]: Math.max(0, Math.round(Number(e.target.value) || 0)) })} /></label>
            {bad && <label className="fld">MOQ·발주단위 예외 사유<input className="inp" value={override[s.sku_id] ?? ""} onChange={(e) => setOverride({ ...override, [s.sku_id]: e.target.value })} maxLength={300} /></label>}
          </div>
        );
      })}
    </ConfirmModal>
  );
}

/** 발주 상세: 승인 요청·공급처 확인·입고(정상/불량·실매입 단가)·취소·잔량 종료. */
export function PoDrawer({ id, onClose, onChanged, onShipment }: { id: string; onClose: () => void; onChanged: () => void; onShipment?: (shipmentId: string) => void }) {
  const { can } = useSession();
  const { data: po, error, loading, reload } = useApi<PurchaseOrder>(`/purchase-orders/${id}`);
  const [notice, setNotice] = useState<string | null>(null);
  const [mode, setMode] = useState<null | "receive" | "cancel" | "close" | "confirm" | "plan">(null);
  const [rows, setRows] = useState<Record<string, { good: number; rej: number; cost: string; reason: string; lot: string; exp: string }>>({});
  const [reason, setReason] = useState("");
  const [eta, setEta] = useState("");
  const [ref, setRef] = useState("");
  const run = async (p: Promise<unknown>, done: string) => {
    try {
      await p;
      setNotice(done);
      setMode(null);
      setReason("");
      reload();
      onChanged();
    } catch (x) {
      setMode(null);
      setNotice(msg(x, "처리하지 못했습니다."));
    }
  };
  const manage = can("purchase.manage");
  const st = po?.status ?? "";
  const open = ["PLACED", "SUPPLIER_CONFIRMED", "PARTIALLY_RECEIVED"].includes(st);
  const receiveBody = () => ({ lines: (po?.lines ?? []).filter((l) => (rows[l.id]?.good ?? 0) + (rows[l.id]?.rej ?? 0) > 0).map((l) => {
    const r = rows[l.id];
    return { po_line_id: l.id, good_qty: r.good, rejected_qty: r.rej, unit_cost: r.cost ? Number(r.cost) : undefined, reject_reason: r.reason || undefined,
      lot_code: r.lot || undefined, expires_on: r.exp || undefined };
  }) });
  const set = (lid: string, patch: Partial<{ good: number; rej: number; cost: string; reason: string; lot: string; exp: string }>) =>
    setRows((x) => ({ ...x, [lid]: { ...EMPTY_ROW, ...x[lid], ...patch } }));
  return (
    <Drawer title={po ? `발주 ${po.po_no}` : "발주"} onClose={onClose} footer={po ? <>
      {manage && st === "DRAFT" && <button className="btn pri" type="button" onClick={() => run(api(`/purchase-orders/${id}/submit`, { method: "POST", body: {} }), "승인을 요청했습니다.")}>승인 요청</button>}
      {manage && ["PLACED", "SUPPLIER_CONFIRMED"].includes(st) && <button className="btn" type="button" onClick={() => setMode("confirm")}>공급처 확인 기록</button>}
      {manage && open && <button className="btn pri" type="button" onClick={() => setMode("plan")}>입고예정 등록</button>}
      {can("inventory.operate") && open && <button className="btn" type="button" onClick={() => setMode("receive")}>즉시 입고</button>}
      {manage && ["DRAFT", "APPROVAL_PENDING", "PLACED", "SUPPLIER_CONFIRMED"].includes(st) && <button className="btn" type="button" onClick={() => setMode("cancel")}>발주 취소</button>}
      {manage && st === "PARTIALLY_RECEIVED" && <button className="btn" type="button" onClick={() => setMode("close")}>잔량 종료</button>}
    </> : undefined}>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : po && <>
        {notice && <div className="note n-info" role="status">{notice}</div>}
        <div className="kv"><span>상태</span><span><PoStatus s={po.status} />{po.close_reason ? <span className="ctx"> {po.close_reason}</span> : null}</span></div>
        <div className="kv"><span>사업자 · 공급처</span><span>{po.business_entity_name} · {po.supplier_name}</span></div>
        <div className="kv"><span>입고 거점 · 예정일</span><span>{po.node_name} · {po.expected_on ?? "—"}{po.supplier_ref ? ` · 공급처 주문번호 ${po.supplier_ref}` : ""}</span></div>
        <div className="kv"><span>수량(발주 · 입고 · 반송)</span><span>{po.ordered_qty} · {po.received_qty} · {po.rejected_qty}</span></div>
        {po.cost_visible && <div className="kv"><span>공급가 · 부가세 · 단가 차이</span><span>{won(po.supply_total)} · {won(po.vat_total)} · {won(po.price_variance)}</span></div>}
        <h3>품목</h3>
        <DataTable caption="발주 품목" rows={po.lines} rowKey={(l) => l.id} empty={<Empty title="품목이 없습니다." />} columns={[
          { key: "k", header: "SKU", render: (l) => <><span className="mono">{l.sku_code}</span>{l.moq_override_reason ? <div className="ctx">MOQ 예외: {l.moq_override_reason}</div> : null}</> },
          { key: "q", header: "발주 · 입고 · 반송 · 잔량", num: true, render: (l) => `${l.quantity} · ${l.received_qty} · ${l.rejected_qty} · ${l.remaining_qty}` },
          { key: "u", header: "단가", num: true, render: (l) => (l.unit_cost === null ? "권한 없음" : won(l.unit_cost)) },
          { key: "s", header: "공급가", num: true, render: (l) => (l.supply_amount === null ? "—" : won(l.supply_amount)) },
        ]} />
        <h3>입고 이력</h3>
        {po.receipts.length === 0 ? <p className="ctx">아직 입고가 없습니다.</p> : po.receipts.map((r) => (
          <div key={r.id} className="card" style={{ padding: 12 }}>
            <div className="kv"><span className="mono">{r.receipt_no}</span><span className="ctx">{formatDateTime(r.received_at)} · {r.created_by_name ?? ""}{r.note ? ` · ${r.note}` : ""}</span></div>
            {r.lines.map((x) => <div key={x.po_line_id} className="ctx">{x.sku_code}: 정상 {x.good_qty} · 반송 {x.rejected_qty}{x.reject_reason ? `(${x.reject_reason})` : ""}
              {x.unit_cost !== null && x.unit_cost !== x.po_unit_cost ? ` · 실매입 ${won(x.unit_cost)}(발주 ${won(x.po_unit_cost)})` : ""}{x.lot_code ? ` · LOT ${x.lot_code}` : ""}</div>)}
          </div>))}
      </>}
      {mode === "receive" && po && <ConfirmModal title={`${po.po_no} 즉시 입고`} confirmLabel="입고 반영" onClose={() => setMode(null)}
        onConfirm={() => run(api(`/purchase-orders/${id}/receipts`, { method: "POST", body: receiveBody() }), "입고를 반영했습니다.")}
        impact={[["정상", "재고 원장에 판매가능으로 입고(실매입 단가로 원가 반영)"], ["불량", "재고에 넣지 않고 공급처 반송 업무를 만듭니다"]]}>
        {po.lines.filter((l) => l.remaining_qty > 0).map((l) => (
          <fieldset key={l.id} className="fld"><legend><span className="mono">{l.sku_code}</span> 잔량 {l.remaining_qty}</legend>
            <label className="fld">정상 수량<input className="inp" type="number" min={0} max={l.remaining_qty} aria-label={`${l.sku_code} 정상 수량`} value={rows[l.id]?.good ?? 0}
              onChange={(e) => set(l.id, { good: Math.max(0, Math.round(Number(e.target.value) || 0)) })} /></label>
            <label className="fld">불량(반송) 수량<input className="inp" type="number" min={0} aria-label={`${l.sku_code} 불량 수량`} value={rows[l.id]?.rej ?? 0}
              onChange={(e) => set(l.id, { rej: Math.max(0, Math.round(Number(e.target.value) || 0)) })} /></label>
            {(rows[l.id]?.rej ?? 0) > 0 && <label className="fld">불량 사유<input className="inp" aria-label={`${l.sku_code} 불량 사유`} value={rows[l.id]?.reason ?? ""} onChange={(e) => set(l.id, { reason: e.target.value })} /></label>}
            {po.cost_visible && <label className="fld">실매입 단가(비우면 발주 단가 {won(l.unit_cost)})<input className="inp" type="number" min={0} value={rows[l.id]?.cost ?? ""} onChange={(e) => set(l.id, { cost: e.target.value })} /></label>}
            <label className="fld">LOT(LOT 관리 SKU)<input className="inp" value={rows[l.id]?.lot ?? ""} onChange={(e) => set(l.id, { lot: e.target.value })} /></label>
            <label className="fld">유통기한<input className="inp" type="date" value={rows[l.id]?.exp ?? ""} onChange={(e) => set(l.id, { exp: e.target.value })} /></label>
          </fieldset>))}
      </ConfirmModal>}
      {mode === "plan" && po && <PlanShipmentModal po={po} onClose={() => setMode(null)}
        onDone={(sh) => { setMode(null); setNotice(`입고 건 ${sh.shipment_no} 을 등록했습니다.`); reload(); onChanged(); onShipment?.(sh.id); }} />}
      {mode === "confirm" && <ConfirmModal title="공급처 확인 기록" confirmLabel="기록" onClose={() => setMode(null)}
        onConfirm={() => run(api(`/purchase-orders/${id}/supplier-confirm`, { method: "POST", body: { expected_on: eta || undefined, supplier_ref: ref || undefined } }), "공급처 확인을 기록했습니다.")}
        impact={[["효과", "입고 예정일을 공급처 회신으로 갱신합니다"]]}>
        <label className="fld">확정 입고 예정일<input className="inp" type="date" value={eta} onChange={(e) => setEta(e.target.value)} /></label>
        <label className="fld">공급처 주문번호<input className="inp" value={ref} onChange={(e) => setRef(e.target.value)} maxLength={100} /></label>
      </ConfirmModal>}
      {(mode === "cancel" || mode === "close") && <ConfirmModal title={mode === "cancel" ? "발주를 취소할까요?" : "남은 수량을 종료할까요?"} confirmLabel={mode === "cancel" ? "발주 취소" : "잔량 종료"} danger
        confirmDisabled={st !== "DRAFT" && !reason.trim()} onClose={() => setMode(null)}
        onConfirm={() => run(api(`/purchase-orders/${id}/${mode === "cancel" ? "cancel" : "close"}`, { method: "POST", body: { reason } }), mode === "cancel" ? "발주를 취소했습니다." : "잔량을 종료했습니다.")}
        impact={[["효과", mode === "cancel" ? "입고예정에서 빠지고 승인 대기 중이면 승인 요청도 취소됩니다" : "남은 수량은 입고예정에서 빠지고 이미 입고된 수량은 그대로입니다"]]}>
        <label className="fld">사유<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
    </Drawer>
  );
}
