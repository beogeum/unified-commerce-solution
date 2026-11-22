"use client";
import { useEffect, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { Carrier, CHANNEL_DISPATCH, DISPATCH_MODE_KO, FO_EVENT_KO, FO_STATUS, FulfillmentOrder, localNow, Shipment, SHIP_STATUS } from "@/lib/fulfillment";
import { RES_STATE } from "@/lib/inventory";
import { useSession } from "@/lib/session";

export function FulfillmentDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const [f, setF] = useState<FulfillmentOrder | null>(null);
  const [loadErr, setLoadErr] = useState<ApiError | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [accepting, setAccepting] = useState(false);
  const [ref, setRef] = useState("");
  const [shipments, setShipments] = useState<Shipment[]>([]);
  const [shipping, setShipping] = useState(false);
  const loadShipments = () => api<Shipment[]>(`/shipments?fulfillment_order_id=${id}`).then((r) => setShipments(r.data)).catch(() => setShipments([]));
  const load = () => api<FulfillmentOrder>(`/fulfillment-orders/${id}`).then((r) => setF(r.data))
    .catch((e) => setLoadErr(e instanceof ApiError ? e : new ApiError(0, "UNKNOWN", "불러오지 못했습니다.")));
  useEffect(() => { load(); loadShipments(); }, [id]); // eslint-disable-line react-hooks/exhaustive-deps
  const run = async (path: string, body: unknown) => {
    setErr(null);
    try {
      const r = await api<FulfillmentOrder>(path, { method: "POST", body });
      setF(r.data);
      loadShipments();
      onChanged();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "처리하지 못했습니다.");
    }
  };
  if (loadErr) return <Drawer title="출고지시" onClose={onClose}><ErrorState error={loadErr} onRetry={load} /></Drawer>;
  if (!f) return <Drawer title="출고지시" onClose={onClose}><Loading /></Drawer>;
  const operate = can("order.update") && can("inventory.operate");
  const dispatched = ["REQUESTED", "ACCEPTED", "PICKING", "PACKING", "CANCEL_REQUESTED"].includes(f.status);
  const closed = ["SHIPPED", "CANCELLED"].includes(f.status);
  return (
    <Drawer title={`출고지시 ${f.fo_no}`} onClose={onClose} footer={operate && !closed ? <>
      {["ALLOCATED", "FAILED"].includes(f.status) && f.dispatch_mode !== "MANUAL" && <button className="btn" type="button" onClick={() => run(`/fulfillment-orders/${id}/dispatch`, {})}>다시 전송</button>}
      {["ALLOCATED", "REQUESTED", "FAILED"].includes(f.status) && <button className="btn" type="button" onClick={() => setAccepting(true)}>수동 접수 기록</button>}
      <button className="btn pri" type="button" onClick={() => setShipping(true)}>출고 확정·송장 입력</button>
      <button className="btn dan" type="button" onClick={() => { setConfirmed(false); setCancelling(true); }}>{f.status === "CANCEL_REQUESTED" ? "취소 확정" : "출고 취소"}</button></> : undefined}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {f.status === "CANCEL_REQUESTED" && <div className="note n-warn">물류사에 이미 전달된 출고입니다. 물류사에서 출고 중지(피킹분 원위치)를 확인한 뒤 취소를 확정하세요. 확정 전까지 재고 확보는 유지됩니다.</div>}
      {f.dispatch_mode === "MANUAL" && f.status === "ALLOCATED" && <div className="note n-warn">이 거점의 물류사 연결에서 출고지시 전송 기능이 확인되지 않았습니다. 물류사 화면·파일로 전달한 뒤 물류사 출고번호를 접수 기록하세요.</div>}
      {f.last_error_message && f.status !== "ACCEPTED" && <div className="note n-err">{f.last_error_message}</div>}
      <div className="kv"><span>주문</span><span className="mono">{f.order_no}</span></div>
      <div className="kv"><span>사업자 · 출고거점</span><span>{f.business_entity_name} · {f.node_name}</span></div>
      <div className="kv"><span>상태</span><span><Chip kind={FO_STATUS[f.status][0]}>{FO_STATUS[f.status][1]}</Chip></span></div>
      <div className="kv"><span>전달 방식</span><span>{f.dispatch_mode ? DISPATCH_MODE_KO[f.dispatch_mode] : "—"}{f.attempts > 0 && <span className="ctx"> · 전송 시도 {f.attempts}회</span>}</span></div>
      <div className="kv"><span>물류사 출고번호</span><span className="mono">{f.external_ref ?? "—"}</span></div>
      <div className="kv"><span>전송 멱등 키</span><span className="mono">{f.dispatch_key}</span></div>
      <div className="kv"><span>접수 시각</span><span>{formatDateTime(f.accepted_at)}</span></div>
      <DataTable caption="출고 품목" rows={f.lines} rowKey={(l) => String(l.line_no)} empty={<Empty title="품목이 없습니다." />} columns={[
        { key: "s", header: "SKU", render: (l) => <span className="mono">{l.sku_code}</span> },
        { key: "q", header: "수량", num: true, render: (l) => l.quantity },
        { key: "r", header: "재고 확보", render: (l) => l.reservation_state ? <Chip kind={RES_STATE[l.reservation_state]?.[0] ?? "mute"}>{RES_STATE[l.reservation_state]?.[1] ?? l.reservation_state}</Chip> : "—" },
      ]} />
      <DataTable caption="송장" rows={shipments} rowKey={(s) => s.id} empty={<Empty title="송장이 없습니다." hint="출고되면 물류사 연동·송장 파일·직접 입력으로 송장이 등록됩니다." />} columns={[
        { key: "n", header: "박스", num: true, render: (s) => s.parcel_no },
        { key: "t", header: "택배사 · 송장번호", render: (s) => <>{s.carrier_name} <span className="mono">{s.tracking_no}</span></> },
        { key: "s", header: "배송", render: (s) => <Chip kind={SHIP_STATUS[s.status][0]}>{SHIP_STATUS[s.status][1]}</Chip> },
        { key: "c", header: "채널 발송처리", render: (s) => <Chip kind={CHANNEL_DISPATCH[s.channel_dispatch_status][0]}>{CHANNEL_DISPATCH[s.channel_dispatch_status][1]}</Chip> },
      ]} />
      <DataTable caption="출고 이력" rows={f.events} rowKey={(e) => `${e.occurred_at}:${e.event_type}`} empty={<Empty title="이력이 없습니다." />} columns={[
        { key: "t", header: "시각", render: (e) => <span className="ctx">{formatDateTime(e.occurred_at)}</span> },
        { key: "e", header: "내용", render: (e) => `${FO_EVENT_KO[e.event_type] ?? e.event_type}${e.to_status && e.from_status !== e.to_status ? ` (${e.from_status ? FO_STATUS[e.from_status]?.[1] ?? e.from_status : "—"} → ${FO_STATUS[e.to_status]?.[1] ?? e.to_status})` : ""}` },
        { key: "a", header: "주체", render: (e) => e.actor_type === "SYSTEM" ? "시스템 자동 처리" : e.actor_type === "INTEGRATION" ? "연동" : "사용자" },
      ]} />
      {cancelling && <ConfirmModal title={f.status === "CANCEL_REQUESTED" ? "출고 취소 확정" : "출고 취소"} danger confirmLabel={dispatched && !confirmed ? "취소 요청" : "취소 확정"}
        onClose={() => setCancelling(false)} confirmDisabled={!reason.trim()}
        impact={[["출고지시", f.fo_no], ["재고", dispatched && !confirmed ? "물류사 확인 전까지 확보 유지" : "확보 해제 → 판매 가능 재고로 복귀"],
          ["주문", "다른 진행 중 출고가 없으면 배정 대기로 돌아갑니다"]]}
        onConfirm={() => { setCancelling(false); run(`/fulfillment-orders/${id}/cancel`, { reason, pick_return_confirmed: dispatched ? confirmed : undefined }); }}>
        <label className="fld">취소 사유(필수)<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
        {dispatched && <label className="fld" style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
          <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />물류사에서 출고 중지·피킹분 원위치를 확인했습니다</label>}
      </ConfirmModal>}
      {accepting && <ConfirmModal title="수동 접수 기록" confirmLabel="기록" onClose={() => setAccepting(false)} confirmDisabled={!ref.trim()}
        impact={[["출고지시", f.fo_no], ["효과", "물류사 접수로 기록하고 업무함의 전달 업무를 닫습니다"]]}
        onConfirm={() => { setAccepting(false); run(`/fulfillment-orders/${id}/manual-accept`, { external_ref: ref, accepted_at: new Date().toISOString() }); }}>
        <label className="fld">물류사 출고번호(필수)<input className="inp" value={ref} onChange={(e) => setRef(e.target.value)} maxLength={200} /></label>
      </ConfirmModal>}
      {shipping && <ShipModal fo={f} onClose={() => setShipping(false)} onDone={(x) => { setShipping(false); setF(x); loadShipments(); onChanged(); }} />}
    </Drawer>
  );
}

function ShipModal({ fo, onClose, onDone }: { fo: FulfillmentOrder; onClose: () => void; onDone: (f: FulfillmentOrder) => void }) {
  const [carriers, setCarriers] = useState<Carrier[]>([]);
  const [at, setAt] = useState(localNow());
  const [parcels, setParcels] = useState([{ carrier_code: "CJ", tracking_no: "" }]);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { api<Carrier[]>("/carriers").then((r) => setCarriers(r.data)).catch(() => setCarriers([])); }, []);
  const set = (i: number, k: "carrier_code" | "tracking_no", v: string) => setParcels(parcels.map((p, j) => (j === i ? { ...p, [k]: v } : p)));
  const submit = async () => {
    setErr(null);
    try {
      const r = await api<FulfillmentOrder>(`/fulfillment-orders/${fo.id}/ship`, { method: "POST", body: { shipped_at: new Date(at).toISOString(), parcels } });
      onDone(r.data);
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "출고 확정하지 못했습니다.");
    }
  };
  return (
    <ConfirmModal title="출고 확정·송장 입력" confirmLabel="출고 확정" onClose={onClose} confirmDisabled={!at || parcels.some((p) => !p.tracking_no.trim())}
      impact={[["출고지시", `${fo.fo_no} · ${fo.lines.map((l) => `${l.sku_code} ×${l.quantity}`).join(", ")}`], ["재고", "실물 재고에서 차감(확보 수량만큼, 판매 가능 재고 변화 없음)"],
        ["다음", "송장 등록 → 판매채널 발송처리 → 배송 추적"]]}
      onConfirm={submit}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {fo.status === "CANCEL_REQUESTED" && <div className="note n-warn">취소 요청 중인 출고입니다. 실물이 이미 나간 경우에만 출고 확정하세요(반품 안내 업무가 생깁니다).</div>}
      <label className="fld">출고 시각<input className="inp" type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} /></label>
      {parcels.map((p, i) => (
        <div className="row-gap" key={i}>
          <label className="fld">{`택배사 ${i + 1}`}<select className="inp" value={p.carrier_code} onChange={(e) => set(i, "carrier_code", e.target.value)}>
            {carriers.map((c) => <option key={c.code} value={c.code}>{c.name_ko}</option>)}</select></label>
          <label className="fld">{`송장번호 ${i + 1}`}<input className="inp" value={p.tracking_no} onChange={(e) => set(i, "tracking_no", e.target.value)} maxLength={40} /></label>
        </div>
      ))}
      <button className="btn" type="button" onClick={() => setParcels([...parcels, { carrier_code: parcels[0].carrier_code, tracking_no: "" }])} disabled={parcels.length >= 20}>박스 추가</button>
      <p className="ctx" style={{ margin: 0 }}>출고지시 전체를 한 번에 출고 확정합니다. 품목은 첫 박스에 기록되고, 추가 박스는 송장만 남깁니다.</p>
    </ConfirmModal>
  );
}
