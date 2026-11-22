"use client";
import { FormEvent, useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { InventoryTabs } from "@/components/InventoryTabs";
import { TxnList } from "@/components/TxnList";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { SkuPicker } from "@/components/SkuPicker";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { Sku } from "@/lib/catalog";
import { formatDateTime } from "@/lib/format";
import { ago, Mismatch, Node, num, Position, QUALITY_KO, RESTRICTION_KO, SOURCE_KO, Stock, Txn } from "@/lib/inventory";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

export default function InventoryPage() {
  const { can, selected } = useSession();
  const [node, setNode] = useState("");
  const [q, setQ] = useState("");
  const [nonZero, setNonZero] = useState(true);
  const nodes = useApi<Node[]>(can("logistics_account.read") ? "/fulfillment-nodes" : null);
  const path = useMemo(() => `/inventory/positions${qs({ business_entity_id: selected, node_id: node, q, non_zero: nonZero ? "true" : undefined })}`, [selected, node, q, nonZero]);
  const { data, error, loading, reload } = useApi<Position[]>(can("inventory.read") ? path : null);
  const check = useApi<Mismatch[]>(can("inventory.read") ? "/inventory/verification" : null);
  const [open, setOpen] = useState<Position | null>(null);
  const [receiving, setReceiving] = useState(false);
  if (!can("inventory.read")) return <Forbidden />;
  const rows = data ?? [];
  const sum = (f: (p: Position) => number) => rows.reduce((a, p) => a + f(p), 0);
  return (
    <>
      <div className="page-head">
        <div><h1>통합재고</h1><p>재고 소유 사업자·거점별 수량입니다. 예약·할당·피킹은 한 번만 셉니다 — 단계가 바뀌어도 판매 가능 재고가 다시 줄지 않습니다.</p></div>
        {can("inventory.operate") && <button className="btn pri" type="button" onClick={() => setReceiving(true)}>입고 등록</button>}
      </div>
      <InventoryTabs active="positions" />
      {check.data && check.data.length > 0 && (
        <div className="note n-err" role="alert">재고 검증 불일치 {check.data.length}건 — {check.data.slice(0, 3).map((m) => m.message_ko).join(" / ")}. 해당 재고는 출고 확정 전에 확인하세요.</div>)}
      {check.data && check.data.length === 0 && <div className="note n-ok">재고 검증: 집계·버킷·확보·원장이 모두 일치합니다.</div>}
      <div className="kpis">
        <div className="kpi"><span>판매가능 실물</span><b>{num(sum((p) => p.sellable_on_hand))}</b></div>
        <div className="kpi"><span>확보(예약·할당·피킹)</span><b>{num(sum((p) => p.committed))}</b></div>
        <div className="kpi"><span>추가 판매가능(ATP)</span><b>{num(sum((p) => p.atp))}</b></div>
        <div className="kpi"><span>비가용·불용</span><b>{num(sum((p) => p.non_sellable_on_hand))}</b></div>
      </div>
      <div className="row-gap">
        <label className="fld">검색<input className="inp" value={q} onChange={(e) => setQ(e.target.value)} placeholder="SKU 코드·상품명·바코드" /></label>
        <label className="fld">출고거점<select className="inp" value={node} onChange={(e) => setNode(e.target.value)}><option value="">전체</option>
          {(nodes.data ?? []).map((n) => <option key={n.id} value={n.id}>{n.name} ({n.code})</option>)}</select></label>
        <label style={{ alignSelf: "end" }}><input type="checkbox" checked={nonZero} onChange={(e) => setNonZero(e.target.checked)} /> 수량 있는 재고만</label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="통합재고" rows={rows} rowKey={(r) => r.id} onRowClick={setOpen}
          empty={<Empty title="재고가 없습니다." hint="출고거점을 등록하고 입고를 기록하면 여기에 표시됩니다." />}
          columns={[
            { key: "s", header: "소유 사업자 · SKU", render: (r) => <><b>{r.business_entity_name}</b> · {r.sku_name ?? ""} <span className="mono">({r.sku_code})</span></> },
            { key: "n", header: "거점 · 재고 원본", render: (r) => <>{r.node_name} <span className="ctx">{SOURCE_KO[r.inventory_source]}
              {r.inventory_source === "EXTERNAL" ? ` · ${ago(r.external_as_of)}` : ""}</span>
              {r.restricted && <> <Chip kind="warn">판매 제한 · {RESTRICTION_KO[r.restriction_reason ?? ""] ?? r.restriction_reason}</Chip></>}</> },
            { key: "o", header: "정상", num: true, render: (r) => num(r.sellable_on_hand) },
            { key: "rv", header: "예약", num: true, render: (r) => num(r.reserved) },
            { key: "al", header: "할당", num: true, render: (r) => num(r.allocated) },
            { key: "pk", header: "피킹", num: true, render: (r) => num(r.picking) },
            { key: "ns", header: "비가용·불용", num: true, render: (r) => num(r.non_sellable_on_hand) },
            { key: "atp", header: "추가 판매가능", num: true, render: (r) => <>{r.atp <= 0 ? <Chip kind="err">0</Chip> : <b>{num(r.atp)}</b>}
              {r.restricted_hold > 0 && <span className="ctx"> (보류 {num(r.restricted_hold)})</span>}</> },
            { key: "u", header: "최근 변경", render: (r) => <span className="ctx">{formatDateTime(r.updated_at)}</span> },
          ]} />
      )}
      <p className="ctx">사업자 전체 판매가능 수량은 사업자 SKU 정책의 안전재고를 뺀 값으로 확보 여부를 판단합니다. 물류사 재고 기준 거점의 수량은 물류사 동기화(I4)로만 바뀝니다.</p>
      {open && <PositionDrawer p={open} onClose={() => setOpen(null)} onChanged={() => { reload(); check.reload(); }} />}
      {receiving && <ReceiptDrawer nodes={nodes.data ?? []} onClose={() => setReceiving(false)} onDone={() => { setReceiving(false); reload(); check.reload(); }} />}
    </>
  );
}

function PositionDrawer({ p, onClose, onChanged }: { p: Position; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const stocks = useApi<Stock[]>(`/inventory/stocks${qs({ business_entity_id: p.business_entity_id, sku_id: p.sku_id, node_id: p.node_id })}`);
  const txns = useApi<Txn[]>(`/inventory/transactions${qs({ business_entity_id: p.business_entity_id, sku_id: p.sku_id, node_id: p.node_id, limit: "20" })}`);
  const [moving, setMoving] = useState<Stock | null>(null);
  const [adjusting, setAdjusting] = useState<Stock | null>(null);
  return (
    <Drawer title={`${p.sku_code} · ${p.node_name}`} onClose={onClose}>
      <div className="kv"><span>소유 사업자</span><span>{p.business_entity_name}</span></div>
      <div className="kv"><span>판매가능 실물 / 확보 / ATP</span><span>{num(p.sellable_on_hand)} / {num(p.committed)} / <b>{num(p.atp)}</b></span></div>
      {p.inventory_source === "EXTERNAL" && <div className="kv"><span>물류사 자료 기준시각</span><span>{p.external_as_of ? `${formatDateTime(p.external_as_of)} (${ago(p.external_as_of)})` : "기준시각 없음"}</span></div>}
      {p.restricted && <div className="note n-warn">판매 제한 중 — {RESTRICTION_KO[p.restriction_reason ?? ""] ?? p.restriction_reason}. 보수적으로 계산한 수량만 판매하고{p.restricted_hold > 0 ? ` 버퍼 ${num(p.restricted_hold)}개를 보류합니다` : " 있습니다"}. 업무함의 확인 업무를 처리하세요.</div>}
      <h3 style={{ fontSize: 14 }}>재고 버킷(로케이션·LOT·품질)</h3>
      {stocks.loading ? <Loading /> : stocks.error ? <ErrorState error={stocks.error} onRetry={stocks.reload} /> : (
        <DataTable caption="재고 버킷" rows={stocks.data ?? []} rowKey={(s) => s.id} empty={<Empty title="수량이 있는 버킷이 없습니다." />}
          columns={[
            { key: "l", header: "로케이션", render: (s) => s.location_code ?? "—" },
            { key: "lot", header: "LOT · 유통기한", render: (s) => s.lot_code ? <><span className="mono">{s.lot_code}</span> <span className="ctx">{s.expires_on ?? ""}</span></> : "—" },
            { key: "q", header: "품질", render: (s) => <Chip kind={s.quality_status === "SELLABLE" ? "ok" : "warn"}>{QUALITY_KO[s.quality_status]}{s.hold_reason ? ` · ${s.hold_reason}` : ""}</Chip> },
            { key: "n", header: "수량", num: true, render: (s) => num(s.on_hand) },
            { key: "a", header: "", render: (s) => <span className="row-gap" style={{ gap: 4 }}>
              {can("inventory.operate") && (p.inventory_source === "INTERNAL" || s.quality_status !== "SELLABLE") &&
                <button className="btn" type="button" onClick={(e) => { e.stopPropagation(); setMoving(s); }}>품질 변경</button>}
              {can("inventory.adjust") && p.inventory_source === "INTERNAL" &&
                <button className="btn" type="button" onClick={(e) => { e.stopPropagation(); setAdjusting(s); }}>조정</button>}</span> },
          ]} />)}
      <h3 style={{ fontSize: 14 }}>최근 원장</h3>
      {txns.loading ? <Loading /> : txns.error ? <ErrorState error={txns.error} onRetry={txns.reload} /> : <TxnList rows={txns.data ?? []} />}
      {adjusting && <AdjustModal stock={adjusting} onClose={() => setAdjusting(null)} onDone={() => { setAdjusting(null); stocks.reload(); txns.reload(); onChanged(); }} />}
      {moving && <QualityModal stock={moving} external={p.inventory_source === "EXTERNAL"} committedGuard={p.sellable_on_hand - p.committed} onClose={() => setMoving(null)}
        onDone={() => { setMoving(null); stocks.reload(); txns.reload(); onChanged(); }} />}
    </Drawer>
  );
}

type AdjustResult = { status: string; approval_id: string | null; before: number; after: number; delta: number; cost_impact: string | null;
  approval_threshold: string; approval_reason_ko: string | null };

function AdjustModal({ stock, onClose, onDone }: { stock: Stock; onClose: () => void; onDone: () => void }) {
  const [mode, setMode] = useState("DECREASE");
  const [qty, setQty] = useState(1);
  const [reason, setReason] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [result, setResult] = useState<AdjustResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [key] = useState(newIdempotencyKey);
  const after = mode === "ABSOLUTE" ? qty : mode === "INCREASE" ? stock.on_hand + qty : stock.on_hand - qty;
  const submit = async () => {
    if (result) { onDone(); return; }
    setBusy(true);
    setErr(null);
    try {
      const r = await api<AdjustResult>("/inventory/adjustments", { method: "POST", idempotencyKey: key, body: { stock_id: stock.id, mode, quantity: qty, reason } });
      setResult(r.data);
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "조정하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <ConfirmModal title="재고 조정" confirmLabel={result ? "닫기" : "조정 요청"} busy={busy} onClose={result ? onDone : onClose} onConfirm={submit}
      confirmDisabled={!result && (!reason.trim() || after < 0 || after === stock.on_hand)}
      impact={[["대상", `${stock.sku_code} · ${QUALITY_KO[stock.quality_status]}${stock.location_code ? ` · ${stock.location_code}` : ""}${stock.lot_code ? ` · ${stock.lot_code}` : ""}`],
        ["수량", `${num(stock.on_hand)} → ${num(Math.max(0, after))}`]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {result ? (result.status === "APPLIED" ? <div className="note n-ok">반영했습니다(원가 영향 {result.cost_impact ?? "—"}원, 승인 기준 미만).</div>
        : <div className="note n-warn">승인 요청을 만들었습니다. {result.approval_reason_ko} 승인 전까지 재고는 바뀌지 않습니다.</div>) : <>
        <label className="fld">방식<select className="inp" value={mode} onChange={(e) => setMode(e.target.value)}>
          <option value="DECREASE">감소</option><option value="INCREASE">증가</option><option value="ABSOLUTE">절대수량으로 맞춤</option></select></label>
        <label className="fld">{mode === "ABSOLUTE" ? "맞출 수량" : "수량"}<input className="inp" type="number" min={0} value={qty} onChange={(e) => setQty(Math.max(0, Number(e.target.value) || 0))} /></label>
        <label className="fld">사유(필수)<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
        <p className="ctx" style={{ margin: 0 }}>원가 영향이 사업자 승인 기준 이상이거나 기준 원가가 없으면 승인 요청이 됩니다. 실사 차이는 실사 화면에서 승인하세요.</p>
      </>}
    </ConfirmModal>
  );
}

function QualityModal({ stock, external, committedGuard, onClose, onDone }: { stock: Stock; external: boolean; committedGuard: number; onClose: () => void; onDone: () => void }) {
  const [qty, setQty] = useState(1);
  const [to, setTo] = useState(stock.quality_status === "SELLABLE" || external ? (stock.quality_status === "DEFECTIVE" ? "DAMAGED" : stock.quality_status === "SELLABLE" ? "PENDING_INSPECTION" : "DEFECTIVE") : "SELLABLE");
  const [hold, setHold] = useState("");
  const [reason, setReason] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [key] = useState(newIdempotencyKey);
  const leavingSellable = stock.quality_status === "SELLABLE" && to !== "SELLABLE";
  const submit = async () => {
    setBusy(true);
    setErr(null);
    try {
      await api("/inventory/quality-changes", { method: "POST", idempotencyKey: key, body: { stock_id: stock.id, quantity: qty, to_quality_status: to,
        to_hold_reason: to === "SELLABLE" ? undefined : hold || undefined, reason } });
      onDone();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "변경하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <ConfirmModal title="재고 품질 변경" confirmLabel="변경" busy={busy} onClose={onClose} onConfirm={submit} confirmDisabled={!reason.trim() || qty < 1 || qty > stock.on_hand}
      impact={[["대상", `${stock.sku_code} · ${QUALITY_KO[stock.quality_status]} ${num(stock.on_hand)}개`],
        ["판매 가능 재고 영향", leavingSellable ? `−${num(qty)} (확보되지 않은 수량 ${num(Math.max(0, committedGuard))}개까지)` : to === "SELLABLE" ? `+${num(qty)}` : "변화 없음"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">수량<input className="inp" type="number" min={1} max={stock.on_hand} value={qty} onChange={(e) => setQty(Number(e.target.value) || 1)} /></label>
      {external && <p className="ctx" style={{ margin: 0 }}>물류사 재고 기준 거점은 비가용 안의 세부분류만 바꿀 수 있습니다(판매가능 전환은 물류사 동기화로).</p>}
      <label className="fld">변경할 품질<select className="inp" value={to} onChange={(e) => setTo(e.target.value)}>
        {Object.entries(QUALITY_KO).filter(([k]) => !external || k !== "SELLABLE").map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      {to !== "SELLABLE" && <label className="fld">보류 사유 코드(선택)<input className="inp mono" value={hold} onChange={(e) => setHold(e.target.value.toUpperCase())} placeholder="예: SUPPLIER_CLAIM" /></label>}
      <label className="fld">사유(필수)<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
    </ConfirmModal>
  );
}

function ReceiptDrawer({ nodes, onClose, onDone }: { nodes: Node[]; onClose: () => void; onDone: () => void }) {
  const { entities } = useSession();
  const [beSel, setBe] = useState("");
  const be = beSel || entities[0]?.id || "";
  const [node, setNode] = useState(nodes.find((n) => n.inventory_source === "INTERNAL" && n.status === "ACTIVE")?.id ?? "");
  const [sku, setSku] = useState<Sku | null>(null);
  const [f, setF] = useState({ quantity: 1, quality_status: "SELLABLE", lot_code: "", expires_on: "", location_code: "", reference_type: "", reference_id: "", reason: "" });
  const [err, setErr] = useState<string | null>(null);
  const [key] = useState(newIdempotencyKey);
  const set = (k: keyof typeof f, v: string | number) => setF({ ...f, [k]: v });
  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setErr(null);
    try {
      await api("/inventory/receipts", { method: "POST", idempotencyKey: key, body: { business_entity_id: be, node_id: node, sku_id: sku?.id, quantity: f.quantity,
        quality_status: f.quality_status, lot_code: f.lot_code || undefined, expires_on: f.expires_on || undefined, location_code: f.location_code || undefined,
        reference_type: f.reference_type || undefined, reference_id: f.reference_id || undefined, reason: f.reason || undefined } });
      onDone();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "입고를 기록하지 못했습니다.");
    }
  };
  const internal = nodes.filter((n) => n.inventory_source === "INTERNAL" && n.status === "ACTIVE");
  return (
    <Drawer title="입고 등록" onClose={onClose} footer={<><button className="btn" type="button" onClick={onClose}>취소</button>
      <button className="btn pri" type="submit" form="rcpt" disabled={!be || !node || !sku}>입고 기록</button></>}>
      <form id="rcpt" onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        <label className="fld">소유 사업자<select className="inp" value={be} onChange={(e) => setBe(e.target.value)}>
          {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
        <label className="fld">입고 거점<select className="inp" value={node} onChange={(e) => setNode(e.target.value)}><option value="">선택</option>
          {internal.map((n) => <option key={n.id} value={n.id}>{n.name} ({n.code})</option>)}</select></label>
        {internal.length < nodes.length && <p className="ctx" style={{ margin: 0 }}>물류사 재고가 기준인 거점은 목록에 없습니다(물류사 동기화로 반영).</p>}
        {sku ? <div className="kv"><span>SKU</span><span className="mono">{sku.sku_code} <button className="btn" type="button" onClick={() => setSku(null)}>바꾸기</button></span></div>
          : <SkuPicker label="SKU" onPick={setSku} />}
        <label className="fld">수량<input className="inp" type="number" min={1} value={f.quantity} onChange={(e) => set("quantity", Number(e.target.value) || 1)} /></label>
        <label className="fld">품질<select className="inp" value={f.quality_status} onChange={(e) => set("quality_status", e.target.value)}>
          {Object.entries(QUALITY_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
        {sku?.lot_managed && <label className="fld">LOT 번호<input className="inp mono" value={f.lot_code} onChange={(e) => set("lot_code", e.target.value)} /></label>}
        {sku?.expiry_managed && <label className="fld">유통기한<input className="inp" type="date" value={f.expires_on} onChange={(e) => set("expires_on", e.target.value)} /></label>}
        <label className="fld">로케이션(자체 WMS, 선택)<input className="inp mono" value={f.location_code} onChange={(e) => set("location_code", e.target.value)} /></label>
        <div className="row-gap">
          <label className="fld">참조 유형<input className="inp mono" value={f.reference_type} onChange={(e) => set("reference_type", e.target.value.toUpperCase())} placeholder="PO" /></label>
          <label className="fld">참조 번호<input className="inp mono" value={f.reference_id} onChange={(e) => set("reference_id", e.target.value)} /></label>
        </div>
        <label className="fld">메모<input className="inp" value={f.reason} onChange={(e) => set("reason", e.target.value)} maxLength={500} /></label>
      </form>
    </Drawer>
  );
}
