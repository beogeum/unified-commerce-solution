"use client";
import { useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { PurchaseOrder, Shipment, SHIPMENT_STATUS } from "@/lib/procurement";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

const msg = (x: unknown, f: string) => (x instanceof ApiError ? x.message : f);
const int = (v: string) => Math.max(0, Math.round(Number(v) || 0));

export function ShipmentStatus({ s }: { s: string }) {
  const v = SHIPMENT_STATUS[s] ?? ["mute", s];
  return <Chip kind={v[0]}>{v[1]}</Chip>;
}

/** 발주에서 입고예정(입고 건) 등록: 품목별 이번에 올 수량, 운송사·송장. */
export function PlanShipmentModal({ po, onClose, onDone }: { po: PurchaseOrder; onClose: () => void; onDone: (s: Shipment) => void }) {
  const open = po.lines.filter((l) => l.remaining_qty > 0);
  const [qty, setQty] = useState<Record<string, number>>(() => Object.fromEntries(open.map((l) => [l.id, l.remaining_qty])));
  const [f, setF] = useState({ expected_on: po.expected_on ?? "", carrier: "", tracking_no: "" });
  const [err, setErr] = useState<string | null>(null);
  const chosen = open.filter((l) => (qty[l.id] ?? 0) > 0);
  const submit = async () => {
    try {
      const r = await api<Shipment>(`/purchase-orders/${po.id}/inbound-shipments`, { method: "POST", body: { expected_on: f.expected_on || undefined,
        carrier: f.carrier || undefined, tracking_no: f.tracking_no || undefined, lines: chosen.map((l) => ({ po_line_id: l.id, quantity: qty[l.id] })) } });
      onDone(r.data);
    } catch (x) {
      setErr(msg(x, "등록하지 못했습니다."));
    }
  };
  return (
    <ConfirmModal title={`${po.po_no} 입고예정 등록`} confirmLabel="등록" confirmDisabled={chosen.length === 0} onClose={onClose} onConfirm={submit}
      impact={[["다음 단계", "센터 도착 → 검수 → 적치 순서로 '입고 처리'에서 진행합니다"], ["재고", "적치를 마칠 때까지 판매가능 재고가 아닙니다"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {open.map((l) => (
        <label key={l.id} className="fld"><span><span className="mono">{l.sku_code}</span> <span className="ctx">잔량 {l.remaining_qty}</span></span>
          <input className="inp" type="number" min={0} max={l.remaining_qty} aria-label={`${l.sku_code} 입고예정 수량`} value={qty[l.id] ?? 0}
            onChange={(e) => setQty({ ...qty, [l.id]: int(e.target.value) })} /></label>))}
      <label className="fld">도착 예정일<input className="inp" type="date" value={f.expected_on} onChange={(e) => setF({ ...f, expected_on: e.target.value })} /></label>
      <label className="fld">운송사<input className="inp" value={f.carrier} onChange={(e) => setF({ ...f, carrier: e.target.value })} maxLength={100} /></label>
      <label className="fld">송장·화물 번호<input className="inp" value={f.tracking_no} onChange={(e) => setF({ ...f, tracking_no: e.target.value })} maxLength={100} /></label>
    </ConfirmModal>
  );
}

type Insp = { good: number; rej: number; over: number; reason: string; cost: string; lot: string; exp: string };
const EMPTY_INSP: Insp = { good: 0, rej: 0, over: 0, reason: "", cost: "", lot: "", exp: "" };

/** 입고 건 상세: 상태별 다음 단계(도착 등록 → 검수 시작 → 검수 확정 → 적치) · 취소 · 이력. */
export function ShipmentDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const { data: s, error, loading, reload } = useApi<Shipment>(`/inbound-shipments/${id}`);
  const [notice, setNotice] = useState<string | null>(null);
  const [mode, setMode] = useState<null | "arrive" | "inspect" | "putaway" | "cancel">(null);
  const [arrived, setArrived] = useState<Record<string, number>>({});
  const [insp, setInsp] = useState<Record<string, Insp>>({});
  const [put, setPut] = useState<Record<string, { qty: number; loc: string }>>({});
  const [reason, setReason] = useState("");
  const op = can("inventory.operate");
  const manage = can("purchase.manage");
  const post = (step: string, body: unknown, done: string) => async () => {
    try {
      await api(`/inbound-shipments/${id}/${step}`, { method: "POST", body, ifMatch: s?.version });
      setNotice(done);
      setMode(null);
      reload();
      onChanged();
    } catch (x) {
      setMode(null);
      setNotice(msg(x, "처리하지 못했습니다."));
    }
  };
  const openArrive = () => { setArrived(Object.fromEntries((s?.lines ?? []).map((l) => [l.id, l.expected_qty]))); setMode("arrive"); };
  const openInspect = () => { setInsp(Object.fromEntries((s?.lines ?? []).map((l) => [l.id, { ...EMPTY_INSP, good: l.arrived_qty ?? 0 }]))); setMode("inspect"); };
  const openPutaway = () => { setPut(Object.fromEntries((s?.lines ?? []).filter((l) => l.putaway_remaining > 0).map((l) => [l.id, { qty: l.putaway_remaining, loc: "" }]))); setMode("putaway"); };
  const si = (lid: string, p: Partial<Insp>) => setInsp((x) => ({ ...x, [lid]: { ...EMPTY_INSP, ...x[lid], ...p } }));
  const st = s?.status ?? "";
  const inspectOk = (s?.lines ?? []).every((l) => { const r = insp[l.id] ?? EMPTY_INSP; return r.good + r.rej + r.over === (l.arrived_qty ?? 0) && (r.rej + r.over === 0 || r.reason.trim()); });
  return (
    <Drawer title={s ? `입고 ${s.shipment_no}` : "입고"} onClose={onClose} footer={s ? <>
      {op && st === "EXPECTED" && <button className="btn pri" type="button" onClick={openArrive}>도착 등록</button>}
      {op && st === "ARRIVED" && <button className="btn pri" type="button" onClick={post("start-inspection", {}, "검수를 시작했습니다.")}>검수 시작</button>}
      {op && st === "INSPECTING" && <button className="btn pri" type="button" onClick={openInspect}>검수 결과 확정</button>}
      {op && st === "PUTAWAY" && <button className="btn pri" type="button" onClick={openPutaway}>적치</button>}
      {manage && ["EXPECTED", "ARRIVED", "INSPECTING"].includes(st) && <button className="btn" type="button" onClick={() => setMode("cancel")}>입고 건 취소</button>}
    </> : undefined}>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : s && <>
        {notice && <div className="note n-info" role="status">{notice}</div>}
        <div className="kv"><span>상태</span><span><ShipmentStatus s={s.status} />{s.cancel_reason ? <span className="ctx"> {s.cancel_reason}</span> : null}</span></div>
        <div className="kv"><span>발주 · 사업자 · 공급처</span><span><span className="mono">{s.po_no}</span> · {s.business_entity_name} · {s.supplier_name}</span></div>
        <div className="kv"><span>거점 · 도착 예정일</span><span>{s.node_name} · {s.expected_on ?? "—"}</span></div>
        <div className="kv"><span>운송사 · 송장</span><span>{s.carrier ?? "—"} · {s.tracking_no ?? "—"}</span></div>
        <div className="kv"><span>수량(예정 · 도착 · 정상 · 적치)</span><span>{s.expected_qty} · {s.arrived_qty} · {s.good_qty} · {s.putaway_qty}</span></div>
        {s.receipt_no && <div className="kv"><span>입고 확인 번호</span><span className="mono">{s.receipt_no}</span></div>}
        {s.has_discrepancy && <div className="note n-warn">예정과 도착 수량이 다르거나 초과 도착이 있습니다. 업무함의 &apos;입고 수량 차이&apos;에서 공급처와 정리하세요.</div>}
        {st === "PUTAWAY" && <div className="note n-info">검수된 정상 수량은 재고에 &apos;검수 대기(적치 대기)&apos;로 들어가 있고, 적치를 마친 수량부터 판매가능이 됩니다.</div>}
        <h3>품목</h3>
        <DataTable caption="입고 품목" rows={s.lines} rowKey={(l) => l.id} empty={<Empty title="품목이 없습니다." />} columns={[
          { key: "k", header: "SKU", render: (l) => <span className="mono">{l.sku_code}</span> },
          { key: "e", header: "예정 · 도착 · 차이", num: true, render: (l) => `${l.expected_qty} · ${l.arrived_qty ?? "—"} · ${l.difference === null ? "—" : l.difference > 0 ? `+${l.difference}` : l.difference}` },
          { key: "g", header: "정상 · 불량 · 초과", num: true, render: (l) => (l.good_qty === null ? "—" : `${l.good_qty} · ${l.rejected_qty} · ${l.over_qty}`) },
          { key: "p", header: "적치 · 남음", num: true, render: (l) => `${l.putaway_qty} · ${l.putaway_remaining}` },
          { key: "r", header: "사유", render: (l) => <span className="ctx">{l.reject_reason ?? ""}</span> },
        ]} />
        <h3>처리 이력</h3>
        <ol className="ctx">{s.events.map((e, i) => <li key={i}>{formatDateTime(e.occurred_at)} · {SHIPMENT_STATUS[e.to_status]?.[1] ?? e.to_status} · {e.actor_name ?? ""}</li>)}</ol>
      </>}
      {mode === "arrive" && s && <ConfirmModal title="센터 도착 등록" confirmLabel="도착 등록" onClose={() => setMode(null)}
        confirmDisabled={Object.values(arrived).reduce((a, b) => a + b, 0) < 1}
        onConfirm={post("arrive", { lines: s.lines.map((l) => ({ line_id: l.id, arrived_qty: arrived[l.id] ?? 0 })) }, "도착을 등록했습니다.")}
        impact={[["재고", "도착 수량은 기록만 하며 재고에는 검수 확정 후 반영됩니다"], ["안 온 품목", "0 으로 두세요(수량 차이로 표시)"]]}>
        {s.lines.map((l) => <label key={l.id} className="fld"><span><span className="mono">{l.sku_code}</span> <span className="ctx">예정 {l.expected_qty}</span></span>
          <input className="inp" type="number" min={0} aria-label={`${l.sku_code} 도착 수량`} value={arrived[l.id] ?? 0} onChange={(e) => setArrived({ ...arrived, [l.id]: int(e.target.value) })} /></label>)}
      </ConfirmModal>}
      {mode === "inspect" && s && <ConfirmModal title="검수 결과 확정" confirmLabel="검수 확정" confirmDisabled={!inspectOk} onClose={() => setMode(null)}
        onConfirm={post("inspect", { lines: s.lines.filter((l) => (l.arrived_qty ?? 0) > 0).map((l) => { const r = insp[l.id] ?? EMPTY_INSP; return { line_id: l.id,
          good_qty: r.good, rejected_qty: r.rej, over_qty: r.over, reject_reason: r.reason || undefined, unit_cost: r.cost ? Number(r.cost) : undefined,
          lot_code: r.lot || undefined, expires_on: r.exp || undefined }; }) }, "검수를 확정했습니다. 적치를 진행하세요.")}
        impact={[["정상", "재고 원장에 입고(적치 전까지 판매불가), 실매입 단가로 원가 반영"], ["불량·초과", "재고에 넣지 않고 공급처 반송 업무를 만듭니다"],
          ["규칙", "정상 + 불량 + 초과(미인수) = 도착 수량"]]}>
        {s.lines.filter((l) => (l.arrived_qty ?? 0) > 0).map((l) => { const r = insp[l.id] ?? EMPTY_INSP; return (
          <fieldset key={l.id} className="fld"><legend><span className="mono">{l.sku_code}</span> 도착 {l.arrived_qty}</legend>
            <label className="fld">정상<input className="inp" type="number" min={0} aria-label={`${l.sku_code} 정상`} value={r.good} onChange={(e) => si(l.id, { good: int(e.target.value) })} /></label>
            <label className="fld">불량(반송)<input className="inp" type="number" min={0} aria-label={`${l.sku_code} 불량`} value={r.rej} onChange={(e) => si(l.id, { rej: int(e.target.value) })} /></label>
            <label className="fld">초과 도착(미인수)<input className="inp" type="number" min={0} aria-label={`${l.sku_code} 초과`} value={r.over} onChange={(e) => si(l.id, { over: int(e.target.value) })} /></label>
            {r.rej + r.over > 0 && <label className="fld">불량·초과 사유<input className="inp" aria-label={`${l.sku_code} 사유`} value={r.reason} onChange={(e) => si(l.id, { reason: e.target.value })} maxLength={300} /></label>}
            <label className="fld">실매입 단가(비우면 발주 단가)<input className="inp" type="number" min={0} value={r.cost} onChange={(e) => si(l.id, { cost: e.target.value })} /></label>
            <label className="fld">LOT<input className="inp" value={r.lot} onChange={(e) => si(l.id, { lot: e.target.value })} /></label>
            <label className="fld">유통기한<input className="inp" type="date" value={r.exp} onChange={(e) => si(l.id, { exp: e.target.value })} /></label>
            {r.good + r.rej + r.over !== (l.arrived_qty ?? 0) && <span className="ctx" role="alert">합계 {r.good + r.rej + r.over} ≠ 도착 {l.arrived_qty}</span>}
          </fieldset>); })}
      </ConfirmModal>}
      {mode === "putaway" && s && <ConfirmModal title="적치" confirmLabel="적치 확정" onClose={() => setMode(null)}
        confirmDisabled={Object.values(put).every((p) => p.qty < 1)}
        onConfirm={post("putaway", { lines: Object.entries(put).filter(([, p]) => p.qty > 0).map(([lid, p]) => ({ line_id: lid, quantity: p.qty, location_code: p.loc || undefined })) },
          "적치를 확정했습니다.")}
        impact={[["재고", "적치한 수량이 판매가능이 됩니다(나머지는 계속 적치 대기)"], ["로케이션", "비우면 거점 기본 위치에 둡니다"]]}>
        {s.lines.filter((l) => l.putaway_remaining > 0).map((l) => <fieldset key={l.id} className="fld"><legend><span className="mono">{l.sku_code}</span> 남은 적치 {l.putaway_remaining}</legend>
          <label className="fld">적치 수량<input className="inp" type="number" min={0} max={l.putaway_remaining} aria-label={`${l.sku_code} 적치 수량`} value={put[l.id]?.qty ?? 0}
            onChange={(e) => setPut({ ...put, [l.id]: { loc: put[l.id]?.loc ?? "", qty: Math.min(l.putaway_remaining, int(e.target.value)) } })} /></label>
          <label className="fld">로케이션 코드<input className="inp" aria-label={`${l.sku_code} 로케이션`} value={put[l.id]?.loc ?? ""}
            onChange={(e) => setPut({ ...put, [l.id]: { qty: put[l.id]?.qty ?? 0, loc: e.target.value } })} /></label>
        </fieldset>)}
      </ConfirmModal>}
      {mode === "cancel" && <ConfirmModal title="입고 건을 취소할까요?" confirmLabel="입고 건 취소" danger confirmDisabled={!reason.trim()} onClose={() => setMode(null)}
        onConfirm={post("cancel", { reason }, "입고 건을 취소했습니다.")} impact={[["효과", "발주 잔량은 그대로 남아 다시 입고예정을 등록할 수 있습니다"]]}>
        <label className="fld">사유<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
    </Drawer>
  );
}
