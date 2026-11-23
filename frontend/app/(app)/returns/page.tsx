"use client";
import { useEffect, useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { SkuPicker } from "@/components/SkuPicker";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { Claim, CONDITION_KO, IDENT, Receipt, RECEIPT_STATUS } from "@/lib/claims";
import { formatDateTime } from "@/lib/format";
import { Node } from "@/lib/inventory";
import { useSession } from "@/lib/session";

export default function ReturnsPage() {
  const { can, selected } = useSession();
  const [status, setStatus] = useState("");
  const [ident, setIdent] = useState("");
  const base = useMemo(() => `/returns/receipts${qs({ business_entity_id: selected, status, identification: ident })}`, [selected, status, ident]);
  const [rows, setRows] = useState<Receipt[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  const [tick, setTick] = useState(0);
  const [open, setOpen] = useState<string | null>(null);
  const [receiving, setReceiving] = useState(false);
  useEffect(() => {
    if (!can("inventory.read")) return;
    let alive = true;
    setLoading(true);
    setError(null);
    api<Receipt[]>(base).then((r) => alive && setRows(r.data))
      .catch((e: unknown) => alive && setError(e instanceof ApiError ? e : new ApiError(0, "UNKNOWN", "요청을 처리하지 못했습니다.")))
      .finally(() => alive && setLoading(false));
    return () => { alive = false; };
  }, [base, tick, can]);
  if (!can("inventory.read")) return <Forbidden />;
  return (
    <>
      <div className="page-head"><div><h1>반품 입고·검수</h1><p>회수품은 검수대기로 받고 검수 판정대로 재고에 반영합니다(정상만 판매가능). 클레임·원주문과 맞지 않는 반품은 미확정 재고로 두고 확인 업무를 만듭니다.
        물류사(3PL) 거점은 물류사 재고가 기준이라 검수 결과를 보완정보로만 기록합니다.</p></div>
        {can("inventory.operate") && <button className="btn pri" type="button" onClick={() => setReceiving(true)}>반품 입고</button>}</div>
      <div className="row-gap">
        <label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">전체</option>
          {Object.entries(RECEIPT_STATUS).map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label>
        <label className="fld">식별<select className="inp" value={ident} onChange={(e) => setIdent(e.target.value)}><option value="">전체</option>
          {Object.entries(IDENT).map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={() => setTick(tick + 1)} /> : (
        <DataTable caption="반품 입고" rows={rows} rowKey={(r) => r.id} onRowClick={(r) => setOpen(r.id)}
          empty={<Empty title="반품 입고가 없습니다." hint="회수품이 도착하면 반품 입고로 받습니다." />}
          columns={[
            { key: "no", header: "입고번호", render: (r) => <><b className="mono">{r.receipt_no}</b><div className="ctx mono">{r.claim_no ?? "클레임 없음"}{r.order_no ? ` · ${r.order_no}` : ""}</div></> },
            { key: "t", header: "입고 시각", render: (r) => <span className="ctx">{formatDateTime(r.received_at)}</span> },
            { key: "n", header: "사업자 · 거점", render: (r) => `${r.business_entity_name} · ${r.node_name}` },
            { key: "l", header: "SKU · 수량", render: (r) => r.lines.map((l) => `${l.sku_code} ×${l.quantity}`).join(", ") },
            { key: "i", header: "식별", render: (r) => <Chip kind={IDENT[r.identification][0]}>{IDENT[r.identification][1]}</Chip> },
            { key: "s", header: "검수", render: (r) => <>{r.external_inventory && r.status !== "INSPECTED" ? <Chip kind="warn">미확정(수동 검수)</Chip>
              : <Chip kind={RECEIPT_STATUS[r.status][0]}>{RECEIPT_STATUS[r.status][1]}</Chip>}</> },
          ]} />
      )}
      {open && <ReceiptDrawer id={open} onClose={() => setOpen(null)} onChanged={() => setTick(tick + 1)} />}
      {receiving && <ReceiveDrawer onClose={() => setReceiving(false)} onDone={(id) => { setReceiving(false); setTick(tick + 1); setOpen(id); }} />}
    </>
  );
}

type Line = { sku_id: string; sku_code: string; quantity: number; claim_item_id?: string };

function ReceiveDrawer({ onClose, onDone }: { onClose: () => void; onDone: (id: string) => void }) {
  const { entities } = useSession();
  const [nodes, setNodes] = useState<Node[]>([]);
  const [nodeSel, setNode] = useState("");
  const node = nodeSel || nodes[0]?.id || "";
  const [claimQ, setClaimQ] = useState("");
  const [found, setFound] = useState<Claim[]>([]);
  const [claim, setClaim] = useState<Claim | null>(null);
  const [beSel, setBe] = useState("");
  const be = beSel || entities[0]?.id || "";
  const [lines, setLines] = useState<Line[]>([]);
  const [note, setNote] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { api<Node[]>("/fulfillment-nodes").then((r) => setNodes(r.data.filter((n) => n.status === "ACTIVE"))).catch(() => setNodes([])); }, []);
  useEffect(() => {
    if (claimQ.trim().length < 3) { setFound([]); return; }
    let alive = true;
    api<Claim[]>(`/claims${qs({ q: claimQ, status: "OPEN" })}`).then((r) => alive && setFound(r.data.filter((c) => c.claim_type !== "CANCEL"))).catch(() => alive && setFound([]));
    return () => { alive = false; };
  }, [claimQ]);
  const pick = (c: Claim) => {
    setClaim(c);
    setFound([]);
    setClaimQ("");
    setLines(c.items.filter((i) => i.sku_id && i.expected_return_qty > i.received_qty)
      .map((i) => ({ sku_id: i.sku_id as string, sku_code: i.sku_code ?? "", quantity: i.expected_return_qty - i.received_qty, claim_item_id: i.id })));
  };
  const submit = async () => {
    setErr(null);
    setBusy(true);
    try {
      const r = await api<Receipt>("/returns/receipts", { method: "POST", body: { node_id: node, claim_id: claim?.id, business_entity_id: claim ? undefined : be,
        note: note || undefined, lines: lines.map((l) => ({ sku_id: l.sku_id, quantity: l.quantity, claim_item_id: l.claim_item_id })) } });
      onDone(r.data.id);
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "입고하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <Drawer title="반품 입고" onClose={onClose} footer={<><button className="btn" type="button" onClick={onClose}>취소</button>
      <button className="btn pri" type="button" onClick={submit} disabled={busy || !node || lines.length === 0 || lines.some((l) => l.quantity < 1)}>{busy ? "입고 중…" : "입고"}</button></>}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">입고 거점<select className="inp" value={node} onChange={(e) => setNode(e.target.value)}>
        {nodes.map((n) => <option key={n.id} value={n.id}>{n.name} ({n.code})</option>)}</select></label>
      {claim ? <div className="kv"><span>클레임</span><span><span className="mono">{claim.claim_no}</span> · 주문 {claim.order_no} · {claim.business_entity_name}
        <button className="btn" type="button" onClick={() => { setClaim(null); setLines([]); }}>해제</button></span></div> : <>
        <label className="fld">클레임 찾기(번호·주문번호 3자 이상)<input className="inp" value={claimQ} onChange={(e) => setClaimQ(e.target.value)} /></label>
        {found.map((c) => <button key={c.id} className="btn" type="button" onClick={() => pick(c)} style={{ justifyContent: "flex-start" }}>
          <span className="mono">{c.claim_no}</span>&nbsp;<span className="ctx">{c.order_no} · {c.items.map((i) => `${i.product_name} ×${i.quantity}`).join(", ")}</span></button>)}
        <label className="fld">원주문을 모르면 — 재고 소유 사업자<select className="inp" value={be} onChange={(e) => setBe(e.target.value)} aria-label="반품 사업자">
          {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
        <p className="ctx" style={{ margin: 0 }}>클레임 없이 받은 반품은 미확정 재고로 보관하고 확인 업무를 만듭니다(판매가능 재고로 넣지 않음).</p>
      </>}
      <SkuPicker label="받은 SKU 추가" onPick={(s) => setLines([...lines, { sku_id: s.id, sku_code: s.sku_code, quantity: 1 }])} />
      <DataTable caption="받은 상품" rows={lines} rowKey={(l) => `${l.sku_id}:${l.claim_item_id ?? ""}`} empty={<Empty title="받은 SKU 를 추가하세요." />} columns={[
        { key: "s", header: "SKU", render: (l) => <span className="mono">{l.sku_code}</span> },
        { key: "q", header: "수량", render: (l) => <input className="inp" type="number" min={1} value={l.quantity} aria-label={`${l.sku_code} 수량`}
          onChange={(e) => setLines(lines.map((x) => (x === l ? { ...x, quantity: Number(e.target.value) || 0 } : x)))} style={{ width: 80 }} /> },
        { key: "x", header: "", render: (l) => <button className="btn" type="button" onClick={() => setLines(lines.filter((x) => x !== l))}>빼기</button> },
      ]} />
      <label className="fld">메모<input className="inp" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} placeholder="예: 박스 훼손 상태로 도착" /></label>
    </Drawer>
  );
}

function ReceiptDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const [r, setR] = useState<Receipt | null>(null);
  const [loadErr, setLoadErr] = useState<ApiError | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [inspecting, setInspecting] = useState(false);
  const [decisions, setDecisions] = useState<Record<string, { condition: string; quantity: number }[]>>({});
  const load = () => api<Receipt>(`/returns/receipts/${id}`).then((x) => setR(x.data)).catch((e) => setLoadErr(e instanceof ApiError ? e : new ApiError(0, "UNKNOWN", "불러오지 못했습니다.")));
  useEffect(() => { load(); }, [id]); // eslint-disable-line react-hooks/exhaustive-deps
  if (loadErr) return <Drawer title="반품 입고" onClose={onClose}><ErrorState error={loadErr} onRetry={load} /></Drawer>;
  if (!r) return <Drawer title="반품 입고" onClose={onClose}><Loading /></Drawer>;
  const pending = r.lines.filter((l) => l.matched && l.inspected_qty < l.quantity);
  const start = () => {
    const d: Record<string, { condition: string; quantity: number }[]> = {};
    pending.forEach((l) => { d[l.id] = [{ condition: r.external_inventory ? "UNDETERMINED" : "SELLABLE", quantity: l.quantity - l.inspected_qty }]; });
    setDecisions(d);
    setInspecting(true);
  };
  const submit = async () => {
    setErr(null);
    try {
      const x = await api<Receipt>(`/returns/receipts/${id}/inspections`, { method: "POST",
        body: { lines: Object.entries(decisions).map(([line, ds]) => ({ receipt_line_id: line, decisions: ds.filter((d) => d.quantity > 0) })) } });
      setR(x.data);
      onChanged();
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : "검수를 저장하지 못했습니다.");
    }
  };
  const valid = pending.every((l) => (decisions[l.id] ?? []).reduce((s, d) => s + d.quantity, 0) === l.quantity - l.inspected_qty);
  return (
    <Drawer title={`반품 입고 ${r.receipt_no}`} onClose={onClose} footer={can("inventory.operate") && pending.length > 0 ? <button className="btn pri" type="button" onClick={start}>검수</button> : undefined}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {r.identification !== "MATCHED" && <div className="note n-err">{r.identification === "UNIDENTIFIED" ? "원주문을 알 수 없는 반품입니다." : "클레임과 맞지 않는 줄이 있습니다."} 미확정 재고로 보관했습니다. 원주문을 확인한 뒤 재고 화면에서 품질을 바꾸세요.</div>}
      {r.external_inventory && <div className="note n-warn">물류사(3PL) 재고 기준 거점입니다. 물류사 반품검수 결과 연동이 확인되지 않아 '미확정'이며, 검수 결과는 보완정보로만 기록합니다(재고는 물류사 재고 동기화로 반영).</div>}
      <div className="kv"><span>클레임 · 주문</span><span className="mono">{r.claim_no ?? "—"} · {r.order_no ?? "—"}</span></div>
      <div className="kv"><span>사업자 · 거점</span><span>{r.business_entity_name} · {r.node_name}</span></div>
      <div className="kv"><span>식별 · 검수</span><span><Chip kind={IDENT[r.identification][0]}>{IDENT[r.identification][1]}</Chip> <Chip kind={RECEIPT_STATUS[r.status][0]}>{RECEIPT_STATUS[r.status][1]}</Chip></span></div>
      <div className="kv"><span>입고 시각</span><span>{formatDateTime(r.received_at)}{r.note ? ` · ${r.note}` : ""}</span></div>
      <DataTable caption="입고 상품" rows={r.lines} rowKey={(l) => l.id} empty={<Empty title="줄이 없습니다." />} columns={[
        { key: "s", header: "SKU", render: (l) => <span className="mono">{l.sku_code}</span> },
        { key: "q", header: "수량", num: true, render: (l) => l.quantity },
        { key: "m", header: "클레임 일치", render: (l) => l.matched ? <Chip kind="ok">일치</Chip> : <Chip kind="err">불일치·미확정</Chip> },
        { key: "i", header: "검수 판정", render: (l) => l.inspections.length === 0 ? "—" : l.inspections.map((x) => `${CONDITION_KO[x.condition]} ${x.quantity}${x.applied_to_stock ? "" : "(기록만)"}`).join(", ") },
      ]} />
      {inspecting && <ConfirmModal title="반품 검수" confirmLabel="검수 저장" onClose={() => setInspecting(false)} confirmDisabled={!valid}
        impact={[["규칙", "줄마다 판정 수량의 합 = 입고 수량"], ["재고", r.external_inventory ? "기록만(물류사 재고 기준)" : "정상만 판매가능, 나머지는 비가용으로 이동"]]}
        onConfirm={() => { setInspecting(false); submit(); }}>
        {pending.map((l) => (
          <div key={l.id} style={{ display: "flex", flexDirection: "column", gap: 4 }}>
            <b className="mono">{l.sku_code} ×{l.quantity - l.inspected_qty}</b>
            {(decisions[l.id] ?? []).map((d, i) => (
              <div className="row-gap" key={i}>
                <label className="fld">{`판정 ${l.sku_code} ${i + 1}`}<select className="inp" value={d.condition} onChange={(e) => setDecisions({ ...decisions,
                  [l.id]: decisions[l.id].map((x, j) => (j === i ? { ...x, condition: e.target.value } : x)) })}>
                  {Object.entries(CONDITION_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
                <label className="fld">{`수량 ${l.sku_code} ${i + 1}`}<input className="inp" type="number" min={0} value={d.quantity} style={{ width: 80 }} onChange={(e) => setDecisions({ ...decisions,
                  [l.id]: decisions[l.id].map((x, j) => (j === i ? { ...x, quantity: Number(e.target.value) || 0 } : x)) })} /></label>
              </div>
            ))}
            <button className="btn" type="button" onClick={() => setDecisions({ ...decisions, [l.id]: [...(decisions[l.id] ?? []), { condition: "DEFECTIVE", quantity: 0 }] })}>판정 나누기</button>
          </div>
        ))}
      </ConfirmModal>}
    </Drawer>
  );
}
