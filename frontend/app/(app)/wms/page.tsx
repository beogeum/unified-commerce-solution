"use client";
import { useApi } from "@/lib/useApi";
import { FormEvent, useEffect, useRef, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { FO_STATUS } from "@/lib/fulfillment";
import { Node } from "@/lib/inventory";
import { useSession } from "@/lib/session";
import { Packing, TASK_STATUS, Wave, WAVE_STATUS } from "@/lib/wms";

export default function WmsPage() {
  const { can } = useSession();
  const [nodes, setNodes] = useState<Node[]>([]);
  const [nodeSel, setNode] = useState("");
  const node = nodeSel || nodes[0]?.id || "";
  const [err, setErr] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  useEffect(() => {
    api<Node[]>("/fulfillment-nodes").then((r) => setNodes(r.data.filter((n) => n.provider_kind === "SELF_WMS" && n.inventory_source === "INTERNAL" && n.status === "ACTIVE")))
      .catch(() => setNodes([]));
  }, []);
  const ok = can("inventory.read") && Boolean(node);
  const wavesApi = useApi<Wave[]>(ok ? `/wms/waves${qs({ node_id: node })}` : null);
  const candApi = useApi<{ count: number }>(ok ? `/wms/candidates?node_id=${node}` : null);
  const waves = wavesApi.data ?? [];
  const count = candApi.data?.count ?? null;
  const loading = wavesApi.loading || candApi.loading;
  const error = wavesApi.error ?? candApi.error;
  const reload = () => { wavesApi.reload(); candApi.reload(); };
  if (!can("inventory.read")) return <Forbidden />;
  const create = async () => {
    setErr(null);
    try {
      const r = await api<Wave>("/wms/waves", { method: "POST", body: { node_id: node } });
      reload();
      setOpen(r.data.id);
      if (r.data.skipped.length > 0) setErr(`피킹 계획을 못 한 출고지시: ${r.data.skipped.map((x) => x.fo_no).join(", ")} (업무함 확인)`);
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "웨이브를 만들지 못했습니다.");
    }
  };
  return (
    <>
      <div className="page-head"><div><h1>WMS 작업</h1><p>자체 WMS 거점의 출고 작업입니다. 물류 접수된 출고지시를 웨이브로 묶으면 로케이션·LOT 별 피킹 목록이 만들어지고(다른 웨이브와 같은 실물을 두 번 잡지 않음),
          로케이션·상품 바코드를 스캔해 피킹한 뒤 출고지시별로 다시 스캔 검수해 포장합니다. 출고 확정은 출고 화면에서 하며, 피킹한 로케이션 재고가 그대로 빠집니다.</p></div>
        {can("inventory.operate") && <button className="btn pri" type="button" onClick={create} disabled={!node || !count}>웨이브 만들기{count ? ` (${count}건)` : ""}</button>}</div>
      {err && <div className="note n-warn" role="alert">{err}</div>}
      <div className="row-gap">
        <label className="fld">자체 WMS 거점<select className="inp" value={node} onChange={(e) => setNode(e.target.value)}>
          {nodes.map((n) => <option key={n.id} value={n.id}>{n.name} ({n.code})</option>)}</select></label>
      </div>
      {nodes.length === 0 ? <Empty title="자체 WMS 거점이 없습니다." hint="물류·3PL 화면에서 자체 WMS 거점과 로케이션을 등록하세요." />
        : loading ? <Loading /> : error ? <ErrorState error={error} onRetry={() => reload()} /> : (
          <DataTable caption="웨이브" rows={waves} rowKey={(w) => w.id} onRowClick={(w) => setOpen(w.id)}
            empty={<Empty title="웨이브가 없습니다." hint="물류 접수된 출고지시가 있으면 웨이브를 만드세요." />}
            columns={[
              { key: "no", header: "웨이브", render: (w) => <b className="mono">{w.wave_no}</b> },
              { key: "t", header: "만든 시각", render: (w) => <span className="ctx">{formatDateTime(w.created_at)}</span> },
              { key: "o", header: "출고지시", num: true, render: (w) => w.orders.length },
              { key: "p", header: "피킹(완료/전체)", num: true, render: (w) => `${w.tasks.filter((t) => t.status !== "OPEN").length} / ${w.tasks.length}` },
              { key: "s", header: "상태", render: (w) => <Chip kind={WAVE_STATUS[w.status][0]}>{WAVE_STATUS[w.status][1]}</Chip> },
            ]} />
        )}
      {open && <WaveDrawer id={open} onClose={() => setOpen(null)} onChanged={() => reload()} />}
    </>
  );
}

function WaveDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const [w, setW] = useState<Wave | null>(null);
  const [loadErr, setLoadErr] = useState<ApiError | null>(null);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [loc, setLoc] = useState("");
  const [code, setCode] = useState("");
  const [qty, setQty] = useState(1);
  const [shorting, setShorting] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [packing, setPacking] = useState<string | null>(null);
  const codeRef = useRef<HTMLInputElement>(null);
  const load = () => api<Wave>(`/wms/waves/${id}`).then((r) => setW(r.data)).catch((e) => setLoadErr(e instanceof ApiError ? e : new ApiError(0, "UNKNOWN", "불러오지 못했습니다.")));
  useEffect(() => { load(); }, [id]); // eslint-disable-line react-hooks/exhaustive-deps
  const scan = async (e: FormEvent) => {
    e.preventDefault();
    try {
      const r = await api<Wave>(`/wms/waves/${id}/picks`, { method: "POST", body: { location_code: loc || undefined, barcode: code, quantity: qty } });
      setW(r.data);
      setMsg({ ok: true, text: `${loc || "로케이션 없음"} · ${code} ×${qty} 피킹` });
      setCode("");
      setQty(1);
      onChanged();
    } catch (x) {
      setMsg({ ok: false, text: x instanceof ApiError ? x.message : "스캔을 처리하지 못했습니다." });
    }
    codeRef.current?.focus();
  };
  const doShort = async (taskId: string) => {
    try {
      const r = await api<Wave>(`/wms/pick-tasks/${taskId}/short`, { method: "POST", body: { reason } });
      setW(r.data);
      setMsg({ ok: true, text: "결품을 기록했습니다. 다른 로케이션에 재고가 있으면 피킹 목록에 추가됩니다." });
      onChanged();
    } catch (x) {
      setMsg({ ok: false, text: x instanceof ApiError ? x.message : "결품을 기록하지 못했습니다." });
    }
  };
  if (loadErr) return <Drawer title="웨이브" onClose={onClose}><ErrorState error={loadErr} onRetry={load} /></Drawer>;
  if (!w) return <Drawer title="웨이브" onClose={onClose}><Loading /></Drawer>;
  const operate = can("inventory.operate");
  return (
    <Drawer title={`웨이브 ${w.wave_no}`} onClose={onClose}>
      {msg && <div className={`note ${msg.ok ? "n-ok" : "n-err"}`} role={msg.ok ? "status" : "alert"}>{msg.text}</div>}
      <div className="kv"><span>거점 · 상태</span><span>{w.node_name} · <Chip kind={WAVE_STATUS[w.status][0]}>{WAVE_STATUS[w.status][1]}</Chip></span></div>
      {operate && w.status === "PICKING" && <form onSubmit={scan} className="row-gap" aria-label="피킹 스캔">
        <label className="fld">로케이션<input className="inp mono" value={loc} onChange={(e) => setLoc(e.target.value.toUpperCase())} placeholder="A-01" /></label>
        <label className="fld">상품 바코드·SKU<input ref={codeRef} className="inp mono" value={code} onChange={(e) => setCode(e.target.value)} /></label>
        <label className="fld">수량<input className="inp" type="number" min={1} value={qty} onChange={(e) => setQty(Number(e.target.value) || 1)} style={{ width: 80 }} /></label>
        <button className="btn pri" type="submit" disabled={!code.trim()} style={{ alignSelf: "flex-end" }}>스캔</button>
      </form>}
      <DataTable caption="피킹 목록" rows={w.tasks} rowKey={(t) => t.id} empty={<Empty title="피킹 작업이 없습니다." />} columns={[
        { key: "l", header: "로케이션", render: (t) => <b className="mono">{t.location_code ?? "—"}</b> },
        { key: "s", header: "SKU · 바코드", render: (t) => <><span className="mono">{t.sku_code}</span><div className="ctx mono">{t.barcode ?? ""}{t.lot_code ? ` · LOT ${t.lot_code}` : ""}</div></> },
        { key: "f", header: "출고지시", render: (t) => <span className="mono">{t.fo_no}</span> },
        { key: "q", header: "피킹/필요", num: true, render: (t) => `${t.picked_qty} / ${t.quantity}` },
        { key: "st", header: "상태", render: (t) => <><Chip kind={TASK_STATUS[t.status][0]}>{TASK_STATUS[t.status][1]}</Chip>{t.short_reason && <span className="ctx"> {t.short_reason}</span>}</> },
        { key: "x", header: "", render: (t) => operate && t.status === "OPEN" ? <button className="btn" type="button" onClick={() => { setReason(""); setShorting(t.id); }}>결품</button> : null },
      ]} />
      <DataTable caption="출고지시" rows={w.orders} rowKey={(o) => o.id} empty={<Empty title="출고지시가 없습니다." />} columns={[
        { key: "n", header: "출고번호", render: (o) => <span className="mono">{o.fo_no}</span> },
        { key: "s", header: "상태", render: (o) => <Chip kind={FO_STATUS[o.status][0]}>{FO_STATUS[o.status][1]}</Chip> },
        { key: "x", header: "", render: (o) => operate && o.status === "PICKING" ? <button className="btn" type="button" onClick={() => setPacking(o.id)}>검수·포장</button> : null },
      ]} />
      {shorting && <ConfirmModal title="결품 기록" danger confirmLabel="결품 기록" onClose={() => setShorting(null)} confirmDisabled={!reason.trim()}
        impact={[["효과", "남은 수량을 다른 로케이션에서 다시 계획합니다. 없으면 업무함에 올립니다"], ["재고", "이 로케이션은 실사 대상 업무로 올라갑니다"]]}
        onConfirm={() => { const t = shorting; setShorting(null); doShort(t); }}>
        <label className="fld">결품 사유(필수)<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} placeholder="예: 로케이션 비어 있음" /></label>
      </ConfirmModal>}
      {packing && <PackDrawer foId={packing} onClose={() => setPacking(null)} onDone={() => { setPacking(null); load(); onChanged(); }} />}
    </Drawer>
  );
}

function PackDrawer({ foId, onClose, onDone }: { foId: string; onClose: () => void; onDone: () => void }) {
  const [p, setP] = useState<Packing | null>(null);
  const [scans, setScans] = useState<{ barcode: string; quantity: number }[]>([]);
  const [code, setCode] = useState("");
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { api<Packing>(`/wms/fulfillment-orders/${foId}/packing`).then((r) => setP(r.data)).catch(() => setErr("불러오지 못했습니다.")); }, [foId]);
  if (!p) return <Drawer title="검수·포장" onClose={onClose}>{err ? <div className="note n-err">{err}</div> : <Loading />}</Drawer>;
  const count = (l: Packing["lines"][number]) => scans.filter((s) => s.barcode === l.barcode || s.barcode.toUpperCase() === l.sku_code.toUpperCase()).reduce((n, s) => n + s.quantity, 0);
  const submit = async () => {
    setErr(null);
    try {
      await api(`/wms/fulfillment-orders/${foId}/pack`, { method: "POST", body: { scans } });
      onDone();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "포장하지 못했습니다.");
    }
  };
  return (
    <Drawer title={`검수·포장 ${p.fo_no}`} onClose={onClose} footer={<><button className="btn" type="button" onClick={() => setScans([])}>스캔 초기화</button>
      <button className="btn pri" type="button" onClick={submit} disabled={!p.pick_complete || scans.length === 0}>포장 완료</button></>}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {!p.pick_complete && <div className="note n-warn">피킹이 끝나지 않았습니다.</div>}
      <form className="row-gap" aria-label="검수 스캔" onSubmit={(e) => { e.preventDefault(); if (code.trim()) { setScans([...scans, { barcode: code.trim(), quantity: 1 }]); setCode(""); } }}>
        <label className="fld">상품 바코드 스캔<input className="inp mono" value={code} onChange={(e) => setCode(e.target.value)} autoFocus /></label>
        <button className="btn" type="submit" style={{ alignSelf: "flex-end" }}>추가</button>
      </form>
      <DataTable caption="검수" rows={p.lines} rowKey={(l) => l.sku_id} empty={<Empty title="상품이 없습니다." />} columns={[
        { key: "s", header: "SKU · 바코드", render: (l) => <><span className="mono">{l.sku_code}</span><div className="ctx mono">{l.barcode ?? ""}</div></> },
        { key: "q", header: "필요", num: true, render: (l) => l.quantity },
        { key: "p", header: "피킹", num: true, render: (l) => l.picked },
        { key: "c", header: "검수 스캔", num: true, render: (l) => { const n = count(l); return <Chip kind={n === l.quantity ? "ok" : n > l.quantity ? "err" : "warn"}>{n}</Chip>; } },
      ]} />
      <p className="ctx" style={{ margin: 0 }}>상품을 하나씩 스캔하세요. 출고지시 수량과 정확히 같아야 포장이 완료되며, 다른 상품·수량 차이는 서버에서 차단합니다.</p>
    </Drawer>
  );
}
