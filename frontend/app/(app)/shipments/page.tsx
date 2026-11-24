"use client";
import { useApi } from "@/lib/useApi";
import { FormEvent, useEffect, useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { CHANNEL_DISPATCH, localNow, Shipment, SHIP_SOURCE_KO, SHIP_STATUS } from "@/lib/fulfillment";
import { useSession } from "@/lib/session";

type ImportResult = { file_id: string; row_count: number; shipped: number; duplicate: number; failed: number;
  results: { row: number; fo_no: string | null; result: string; message: string | null }[] };
const ROW_RESULT_KO: Record<string, string> = { SHIPPED: "출고 확정", DUPLICATE: "이미 반영됨", FAILED: "실패" };

export default function ShipmentsPage() {
  const { can, selected } = useSession();
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const [dispatch, setDispatch] = useState("");
  const base = useMemo(() => `/shipments${qs({ business_entity_id: selected, q, status, channel_dispatch_status: dispatch })}`, [selected, q, status, dispatch]);
  const [open, setOpen] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const list = useApi<Shipment[]>(can("order.read") ? base : null);
  const rows = list.data ?? [];
  const loading = list.loading;
  const error = list.error;
  if (!can("order.read")) return <Forbidden />;
  return (
    <>
      <div className="page-head"><div><h1>배송</h1><p>출고 확정된 송장과 배송 단계입니다. 송장 등록은 배송완료가 아니며, 배송 단계는 늦게 온 정보로 되돌아가지 않습니다.
        판매채널 발송처리는 지원 기능이 확인된 연결만 자동으로 보내고, 나머지는 판매자센터에서 처리한 뒤 완료를 기록합니다.</p></div>
        {can("order.update") && can("inventory.operate") && <button className="btn pri" type="button" onClick={() => setUploading(true)}>송장 파일 올리기</button>}</div>
      <div className="row-gap">
        <label className="fld">검색<input className="inp" value={q} onChange={(e) => setQ(e.target.value)} placeholder="송장번호·주문번호·출고번호" /></label>
        <label className="fld">배송 단계<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">전체</option>
          {Object.entries(SHIP_STATUS).map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label>
        <label className="fld">채널 발송처리<select className="inp" value={dispatch} onChange={(e) => setDispatch(e.target.value)}><option value="">전체</option>
          {Object.entries(CHANNEL_DISPATCH).map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={() => list.reload()} /> : (
        <DataTable caption="송장" rows={rows} rowKey={(r) => r.id} onRowClick={(r) => setOpen(r.id)}
          empty={<Empty title="송장이 없습니다." hint="출고 화면에서 출고 확정하거나 송장 파일을 올리면 여기에 표시됩니다." />}
          columns={[
            { key: "t", header: "택배사 · 송장번호", render: (r) => <><b className="mono">{r.tracking_no}</b><div className="ctx">{r.carrier_name}{r.parcel_no > 1 ? ` · 박스 ${r.parcel_no}` : ""}</div></> },
            { key: "o", header: "주문 · 출고번호", render: (r) => <><span className="mono">{r.order_no}</span><div className="ctx mono">{r.fo_no}</div></> },
            { key: "n", header: "사업자 · 거점", render: (r) => `${r.business_entity_name} · ${r.node_name}` },
            { key: "d", header: "출고 시각", render: (r) => <span className="ctx">{formatDateTime(r.shipped_at)}</span> },
            { key: "s", header: "배송", render: (r) => <Chip kind={SHIP_STATUS[r.status][0]}>{SHIP_STATUS[r.status][1]}</Chip> },
            { key: "c", header: "채널 발송처리", render: (r) => <Chip kind={CHANNEL_DISPATCH[r.channel_dispatch_status][0]}>{CHANNEL_DISPATCH[r.channel_dispatch_status][1]}</Chip> },
          ]} />
      )}
      {open && <ShipmentDrawer id={open} onClose={() => setOpen(null)} onChanged={() => list.reload()} />}
      {uploading && <UploadDrawer onClose={() => setUploading(false)} onDone={() => list.reload()} />}
    </>
  );
}

function ShipmentDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const [s, setS] = useState<Shipment | null>(null);
  const [loadErr, setLoadErr] = useState<ApiError | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [recording, setRecording] = useState(false);
  const [ev, setEv] = useState({ status: "IN_TRANSIT", at: localNow(), description: "" });
  const [doneOpen, setDoneOpen] = useState(false);
  const [doneNote, setDoneNote] = useState("");
  const load = () => api<Shipment>(`/shipments/${id}`).then((r) => setS(r.data)).catch((e) => setLoadErr(e instanceof ApiError ? e : new ApiError(0, "UNKNOWN", "불러오지 못했습니다.")));
  useEffect(() => { load(); }, [id]); // eslint-disable-line react-hooks/exhaustive-deps
  const post = async (path: string, body: unknown) => {
    setErr(null);
    setNote(null);
    try {
      const r = await api<Shipment | { result: string; shipment: Shipment }>(path, { method: "POST", body });
      if ("shipment" in r.data) {
        setS(r.data.shipment);
        if (r.data.result === "IGNORED_STALE") setNote("현재 배송 단계보다 이전이거나 역행하는 기록이라 상태는 바꾸지 않고 이력에만 남겼습니다.");
      } else {
        setS(r.data);
      }
      onChanged();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "처리하지 못했습니다.");
    }
  };
  if (loadErr) return <Drawer title="송장" onClose={onClose}><ErrorState error={loadErr} onRetry={load} /></Drawer>;
  if (!s) return <Drawer title="송장" onClose={onClose}><Loading /></Drawer>;
  const update = can("order.update");
  const needsChannel = ["MANUAL_REQUIRED", "FAILED"].includes(s.channel_dispatch_status);
  return (
    <Drawer title={`송장 ${s.tracking_no}`} onClose={onClose} footer={update ? <>
      {s.status !== "DELIVERED" && <button className="btn" type="button" onClick={() => { setEv({ status: "IN_TRANSIT", at: localNow(), description: "" }); setRecording(true); }}>배송 단계 기록</button>}
      {needsChannel && <button className="btn" type="button" onClick={() => post(`/shipments/${id}/channel-dispatch/retry`, {})}>채널 다시 전송</button>}
      {needsChannel && <button className="btn pri" type="button" onClick={() => setDoneOpen(true)}>채널 발송처리 완료</button>}</> : undefined}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {note && <div className="note n-warn" role="status">{note}</div>}
      {s.channel_dispatch_status === "MANUAL_REQUIRED" && <div className="note n-warn">이 판매계정 연결에서 발송처리·송장 전송 기능이 확인되지 않았습니다. 판매자센터에서 발송처리한 뒤 완료를 기록하세요.</div>}
      {s.channel_dispatch_error && s.channel_dispatch_status === "FAILED" && <div className="note n-err">{s.channel_dispatch_error}</div>}
      <div className="kv"><span>택배사 · 송장번호</span><span>{s.carrier_name} <span className="mono">{s.tracking_no}</span>{s.parcel_no > 1 && <span className="ctx"> · 박스 {s.parcel_no}</span>}</span></div>
      <div className="kv"><span>주문 · 출고번호</span><span className="mono">{s.order_no} · {s.fo_no}</span></div>
      <div className="kv"><span>사업자 · 거점</span><span>{s.business_entity_name} · {s.node_name}</span></div>
      <div className="kv"><span>출고 시각 · 등록 경로</span><span>{formatDateTime(s.shipped_at)} · {SHIP_SOURCE_KO[s.source] ?? s.source}</span></div>
      <div className="kv"><span>배송 단계</span><span><Chip kind={SHIP_STATUS[s.status][0]}>{SHIP_STATUS[s.status][1]}</Chip> <span className="ctx">{formatDateTime(s.status_at)}</span></span></div>
      <div className="kv"><span>채널 발송처리</span><span><Chip kind={CHANNEL_DISPATCH[s.channel_dispatch_status][0]}>{CHANNEL_DISPATCH[s.channel_dispatch_status][1]}</Chip>
        {s.channel_dispatched_at && <span className="ctx"> {formatDateTime(s.channel_dispatched_at)}</span>}</span></div>
      <DataTable caption="박스 품목" rows={s.items} rowKey={(i) => `${i.order_item_id}:${i.sku_id}`} empty={<Empty title="품목 기록 없음(추가 박스)" />} columns={[
        { key: "s", header: "SKU", render: (i) => <span className="mono">{i.sku_code}</span> },
        { key: "q", header: "수량", num: true, render: (i) => i.quantity },
      ]} />
      <DataTable caption="배송 이력" rows={s.events} rowKey={(e) => `${e.occurred_at}:${e.status}:${e.received_at}`} empty={<Empty title="이력이 없습니다." />} columns={[
        { key: "t", header: "발생 시각", render: (e) => <span className="ctx">{formatDateTime(e.occurred_at)}</span> },
        { key: "s", header: "단계", render: (e) => <>{SHIP_STATUS[e.status]?.[1] ?? e.status}{!e.applied && <span className="ctx"> (늦게 도착 · 미반영)</span>}</> },
        { key: "d", header: "내용", render: (e) => [e.location, e.description].filter(Boolean).join(" · ") || "—" },
        { key: "src", header: "경로", render: (e) => SHIP_SOURCE_KO[e.source] ?? e.source },
      ]} />
      {recording && <ConfirmModal title="배송 단계 기록" confirmLabel="기록" onClose={() => setRecording(false)} confirmDisabled={!ev.at}
        impact={[["송장", `${s.carrier_name} ${s.tracking_no}`], ["규칙", "현재 단계보다 이전 기록은 이력에만 남고 상태를 되돌리지 않습니다"]]}
        onConfirm={() => { setRecording(false); post(`/shipments/${id}/tracking-events`, { status: ev.status, occurred_at: new Date(ev.at).toISOString(), description: ev.description || undefined }); }}>
        <label className="fld">배송 단계<select className="inp" value={ev.status} onChange={(e) => setEv({ ...ev, status: e.target.value })}>
          {Object.entries(SHIP_STATUS).filter(([k]) => k !== "LABEL_CREATED").map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label>
        <label className="fld">발생 시각<input className="inp" type="datetime-local" value={ev.at} onChange={(e) => setEv({ ...ev, at: e.target.value })} /></label>
        <label className="fld">메모<input className="inp" value={ev.description} onChange={(e) => setEv({ ...ev, description: e.target.value })} maxLength={500} placeholder="예: 고객 수령 확인 통화" /></label>
      </ConfirmModal>}
      {doneOpen && <ConfirmModal title="채널 발송처리 완료" confirmLabel="완료 기록" onClose={() => setDoneOpen(false)}
        impact={[["송장", `${s.carrier_name} ${s.tracking_no}`], ["효과", "판매자센터에서 발송처리했음을 기록하고 업무함의 업무를 닫습니다"]]}
        onConfirm={() => { setDoneOpen(false); post(`/shipments/${id}/channel-dispatch/manual-done`, { note: doneNote || undefined }); }}>
        <label className="fld">메모<input className="inp" value={doneNote} onChange={(e) => setDoneNote(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
    </Drawer>
  );
}

function UploadDrawer({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!file) return;
    setErr(null);
    setBusy(true);
    try {
      const fd = new FormData();
      fd.append("file", file);
      const res = await fetch("/bff/api/shipments/imports", { method: "POST", body: fd, headers: { "x-cos-csrf": "1" } });
      const j = await res.json().catch(() => null);
      if (!res.ok) throw new ApiError(res.status, j?.error?.code ?? "UPLOAD_FAILED", j?.error?.message ?? "파일을 반영하지 못했습니다.");
      setResult(j.data);
      onDone();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "파일을 반영하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <Drawer title="송장 파일 올리기" onClose={onClose} footer={<><button className="btn" type="button" onClick={onClose}>닫기</button>
      <button className="btn pri" type="submit" form="ship-upload" disabled={!file || busy}>{busy ? "반영 중…" : "반영"}</button></>}>
      <form id="ship-upload" onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        <label className="fld">파일(.xlsx·.csv)<input className="inp" type="file" accept=".xlsx,.csv" onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }} /></label>
        <p className="ctx" style={{ margin: 0 }}>필수 열: 출고번호, 택배사(이름 또는 코드), 송장번호. 선택 열: 출고일시(한국 시간, 비우면 지금). 같은 출고번호의 여러 행은 박스 여러 개로 등록합니다.
          출고지시마다 따로 반영하므로 실패한 행만 고쳐 다시 올리면 됩니다(이미 반영된 행은 중복으로 처리).</p>
      </form>
      {result && <>
        <div className="note n-info" role="status">{result.row_count}행 중 출고 확정 {result.shipped} · 이미 반영 {result.duplicate} · 실패 {result.failed}</div>
        <DataTable caption="행별 결과" rows={result.results} rowKey={(r) => String(r.row)} empty={<Empty title="행이 없습니다." />} columns={[
          { key: "r", header: "행", num: true, render: (r) => r.row },
          { key: "f", header: "출고번호", render: (r) => <span className="mono">{r.fo_no ?? "—"}</span> },
          { key: "s", header: "결과", render: (r) => <Chip kind={r.result === "FAILED" ? "err" : r.result === "DUPLICATE" ? "mute" : "ok"}>{ROW_RESULT_KO[r.result] ?? r.result}</Chip> },
          { key: "m", header: "사유", render: (r) => r.message ?? "—" },
        ]} />
      </>}
    </Drawer>
  );
}
