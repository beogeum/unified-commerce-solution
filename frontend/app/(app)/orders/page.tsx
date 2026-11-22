"use client";
import { useEffect, useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { formatDateTime, formatWon } from "@/lib/format";
import { EVENT_KO, HOLD_KO, ORDER_STATUS, OrderDetail, OrderRow, PAYMENT_KO, RAW_RESULT_KO, SOURCE_TYPE_KO } from "@/lib/orders";
import { useSession } from "@/lib/session";

export default function OrdersPage() {
  const { can, selected } = useSession();
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const [hold, setHold] = useState("");
  const base = useMemo(() => `/orders${qs({ business_entity_id: selected, q, status, on_hold: hold ? "true" : undefined, hold_reason: hold && hold !== "ANY" ? hold : undefined, limit: "50" })}`,
    [selected, q, status, hold]);
  const [rows, setRows] = useState<OrderRow[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  const [tick, setTick] = useState(0);
  const [open, setOpen] = useState<string | null>(null);
  useEffect(() => {
    if (!can("order.read")) return;
    let alive = true;
    setLoading(true);
    setError(null);
    api<OrderRow[]>(base).then((r) => { if (alive) { setRows(r.data); setCursor(r.nextCursor); } })
      .catch((e: unknown) => alive && setError(e instanceof ApiError ? e : new ApiError(0, "UNKNOWN", "요청을 처리하지 못했습니다.")))
      .finally(() => alive && setLoading(false));
    return () => { alive = false; };
  }, [base, tick, can]);
  if (!can("order.read")) return <Forbidden />;
  const more = async () => {
    if (!cursor) return;
    const r = await api<OrderRow[]>(`${base}&cursor=${encodeURIComponent(cursor)}`);
    setRows([...rows, ...r.data]);
    setCursor(r.nextCursor);
  };
  return (
    <>
      <div className="page-head"><div><h1>주문</h1><p>판매계정에서 수집한 주문입니다. 같은 외부 주문번호라도 판매계정·판매단위가 다르면 다른 주문이고, 주문 사업자는 주문 시점의 판매계정 귀속으로 고정됩니다.
        상품이 연결되지 않았거나 금액이 맞지 않는 주문은 보류되어 출고로 넘어가지 않습니다.</p></div></div>
      <div className="row-gap">
        <label className="fld">검색<input className="inp" value={q} onChange={(e) => setQ(e.target.value)} placeholder="주문번호·외부 주문번호·상품명" /></label>
        <label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">전체</option>
          {Object.entries(ORDER_STATUS).map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label>
        <label className="fld">보류<select className="inp" value={hold} onChange={(e) => setHold(e.target.value)}><option value="">전체</option><option value="ANY">보류 중 전체</option>
          {Object.entries(HOLD_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={() => setTick(tick + 1)} /> : (
        <>
          <DataTable caption="주문" rows={rows} rowKey={(r) => r.id} onRowClick={(r) => setOpen(r.id)}
            empty={<Empty title="주문이 없습니다." hint="연동 관리에서 주문 수집을 실행하면 여기에 표시됩니다." />}
            columns={[
              { key: "no", header: "주문번호", render: (r) => <><b className="mono">{r.order_no}</b><div className="ctx mono">{r.external_order_key}</div></> },
              { key: "t", header: "주문 시각", render: (r) => <span className="ctx">{formatDateTime(r.ordered_at)}</span> },
              { key: "ch", header: "사업자 · 판매계정", render: (r) => <>{r.business_entity_name} · {r.channel_account_name}{r.source_type !== "ORDER" && <span className="ctx"> ({SOURCE_TYPE_KO[r.source_type]})</span>}</> },
              { key: "i", header: "상품", render: (r) => r.item_summary ?? "—" },
              { key: "rc", header: "수령인", render: (r) => r.recipient_name_masked ?? "—" },
              { key: "amt", header: "결제 금액", num: true, render: (r) => `${formatWon(r.total_amount)}원` },
              { key: "s", header: "상태", render: (r) => <><Chip kind={ORDER_STATUS[r.status][0]}>{ORDER_STATUS[r.status][1]}</Chip>
                {r.on_hold && <> <Chip kind="err">보류 · {r.hold_reasons.map((h) => HOLD_KO[h] ?? h).join(", ")}</Chip></>}</> },
            ]} />
          {cursor && <button className="btn" type="button" onClick={more}>더 보기</button>}
        </>
      )}
      {open && <OrderDrawer id={open} onClose={() => setOpen(null)} onChanged={() => setTick(tick + 1)} />}
    </>
  );
}

function OrderDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const [d, setD] = useState<OrderDetail | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [loadErr, setLoadErr] = useState<ApiError | null>(null);
  const [holding, setHolding] = useState(false);
  const [note, setNote] = useState("");
  const [revealing, setRevealing] = useState(false);
  const [reason, setReason] = useState("");
  const [pii, setPii] = useState<{ buyer: Record<string, string | null>; recipient: Record<string, string | null> } | null>(null);
  const [tab, setTab] = useState<"items" | "events" | "raws">("items");
  const load = () => api<OrderDetail>(`/orders/${id}`).then((r) => setD(r.data)).catch((e) => setLoadErr(e instanceof ApiError ? e : new ApiError(0, "UNKNOWN", "불러오지 못했습니다.")));
  useEffect(() => { load(); }, [id]); // eslint-disable-line react-hooks/exhaustive-deps
  const run = async (path: string, body: unknown, ifMatch?: number) => {
    setErr(null);
    try {
      const r = await api<OrderDetail>(path, { method: "POST", body, ifMatch });
      setD(r.data);
      onChanged();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "처리하지 못했습니다.");
    }
  };
  const reveal = async () => {
    setErr(null);
    try {
      const r = await api<{ buyer: Record<string, string | null>; recipient: Record<string, string | null> }>(`/orders/${id}/pii`, { method: "POST", body: { reason } });
      setPii(r.data);
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "열람하지 못했습니다.");
    }
  };
  if (loadErr) return <Drawer title="주문" onClose={onClose}><ErrorState error={loadErr} onRetry={load} /></Drawer>;
  if (!d) return <Drawer title="주문" onClose={onClose}><Loading /></Drawer>;
  const o = d.order;
  const manual = o.hold_reasons.includes("MANUAL");
  return (
    <Drawer title={`주문 ${o.order_no}`} onClose={onClose} footer={can("order.update") ? <>
      <button className="btn" type="button" onClick={() => run(`/orders/${id}/revalidate`, {})}>다시 연결·검증</button>
      {manual ? <button className="btn" type="button" onClick={() => run(`/orders/${id}/release-hold`, {}, d.version)}>직접 보류 해제</button>
        : <button className="btn dan" type="button" onClick={() => setHolding(true)}>보류</button>}</> : undefined}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {o.on_hold && <div className="note n-warn">보류 중 — {o.hold_reasons.map((h) => HOLD_KO[h] ?? h).join(", ")}{d.hold_note ? ` · ${d.hold_note}` : ""}.
        {o.hold_reasons.includes("UNMAPPED_ITEM") && " 상품 > 채널상품·연결에서 SKU 를 연결하면 자동으로 다시 검증됩니다."}</div>}
      <div className="kv"><span>외부 주문번호</span><span className="mono">{o.external_order_key}</span></div>
      <div className="kv"><span>사업자(주문 시점 귀속)</span><span>{o.business_entity_name}</span></div>
      <div className="kv"><span>판매채널 · 계정 · 판매단위</span><span>{o.sales_channel_name} · {o.channel_account_name} · {o.sales_unit_name}</span></div>
      <div className="kv"><span>주문 · 결제</span><span>{formatDateTime(o.ordered_at)} · {PAYMENT_KO[o.payment_status]}{d.paid_at ? ` (${formatDateTime(d.paid_at)})` : ""}</span></div>
      <div className="kv"><span>상태</span><span><Chip kind={ORDER_STATUS[o.status][0]}>{ORDER_STATUS[o.status][1]}</Chip> <span className="ctx">외부 {d.external_status ?? "—"}</span></span></div>
      <div className="kv"><span>금액</span><span>상품 {formatWon(d.items_amount)} − 할인 {formatWon(String(Number(d.seller_discount) + Number(d.channel_discount)))} + 배송비 {formatWon(d.shipping_fee)} = <b>{formatWon(o.total_amount)}원</b></span></div>
      <div className="kv"><span>수령인</span><span>{pii ? `${pii.recipient.name ?? ""} · ${pii.recipient.phone ?? ""} · (${pii.recipient.postal_code ?? ""}) ${pii.recipient.address1 ?? ""} ${pii.recipient.address2 ?? ""}`
        : `${o.recipient_name_masked ?? "—"} · ${d.recipient_phone_masked ?? "—"} · ${d.address_masked ?? "—"}`}
        {!pii && can("pii.read") && <> <button className="btn" type="button" onClick={() => setRevealing(true)}>원문 보기</button></>}</span></div>
      <nav className="row-gap" aria-label="주문 상세">
        {([["items", "주문상품"], ["events", "변경 이력"], ["raws", "원본 수집 이력"]] as const).map(([k, l]) =>
          <button key={k} className="btn" type="button" aria-current={tab === k ? "page" : undefined} onClick={() => setTab(k)}>{l}</button>)}
      </nav>
      {tab === "items" && <DataTable caption="주문상품" rows={d.items} rowKey={(i) => i.id} empty={<Empty title="주문상품이 없습니다." />} columns={[
        { key: "n", header: "상품", render: (i) => <>{i.product_name}{i.option_name && <span className="ctx"> · {i.option_name}</span>}<div className="ctx mono">{i.external_product_id}{i.external_option_id ? `/${i.external_option_id}` : ""} · {i.external_item_key}</div></> },
        { key: "sku", header: "SKU", render: (i) => i.mapping_status === "MAPPED" ? <span className="mono">{i.sku_code}{i.sku_qty_per_unit && i.sku_qty_per_unit > 1 ? ` ×${i.sku_qty_per_unit}` : ""}</span> : <Chip kind="err">미연결</Chip> },
        { key: "q", header: "수량(주문/취소/출고/반품)", num: true, render: (i) => `${i.original_qty} / ${i.canceled_qty} / ${i.shipped_qty} / ${i.returned_qty}` },
        { key: "a", header: "금액", num: true, render: (i) => `${formatWon(i.line_amount)}원` },
        { key: "d", header: "할인(판매자/채널)", num: true, render: (i) => `${formatWon(i.seller_discount)} / ${formatWon(i.channel_discount)}` },
      ]} />}
      {tab === "events" && <DataTable caption="변경 이력" rows={d.events} rowKey={(e) => e.id} empty={<Empty title="이력이 없습니다." />} columns={[
        { key: "t", header: "시각", render: (e) => <span className="ctx">{formatDateTime(e.occurred_at)}</span> },
        { key: "e", header: "내용", render: (e) => `${EVENT_KO[e.event_type] ?? e.event_type}${e.from_value || e.to_value ? ` (${e.from_value ?? "—"} → ${e.to_value ?? "—"})` : ""}` },
        { key: "a", header: "주체", render: (e) => e.actor_name ?? (e.actor_type === "INTEGRATION" ? "연동" : "시스템") },
      ]} />}
      {tab === "raws" && <DataTable caption="원본 수집 이력" rows={d.raws} rowKey={(r) => r.id} empty={<Empty title="원본이 없습니다." />} columns={[
        { key: "t", header: "받은 시각", render: (r) => <span className="ctx">{formatDateTime(r.received_at)}</span> },
        { key: "s", header: "원천 · 매핑 규칙", render: (r) => <span className="mono">{r.source} · {r.mapping_version}</span> },
        { key: "u", header: "외부 갱신 시각", render: (r) => <span className="ctx">{formatDateTime(r.external_updated_at)}</span> },
        { key: "r", header: "결과", render: (r) => <>{RAW_RESULT_KO[r.result] ?? r.result}{r.result_detail && <span className="ctx"> · {r.result_detail}</span>}</> },
      ]} />}
      <p className="ctx">원본은 개인정보를 가린 사본만 보입니다(원문은 암호화 보관).</p>
      {holding && <ConfirmModal title="주문 보류" danger confirmLabel="보류" onClose={() => setHolding(false)} confirmDisabled={!note.trim()}
        impact={[["주문", o.order_no], ["효과", "보류를 풀 때까지 출고 배정·확보로 넘어가지 않습니다"]]}
        onConfirm={() => { setHolding(false); run(`/orders/${id}/hold`, { note }, d.version); }}>
        <label className="fld">보류 사유(필수)<input className="inp" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
      {revealing && <ConfirmModal title="개인정보 원문 보기" confirmLabel="열람" onClose={() => setRevealing(false)} confirmDisabled={!reason.trim()}
        impact={[["대상", `${o.order_no} 구매자·수령인`], ["기록", "열람자·사유·시각이 감사로그에 남습니다"]]}
        onConfirm={() => { setRevealing(false); reveal(); }}>
        <label className="fld">열람 사유(필수)<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={200} placeholder="예: 배송지 확인 문의 CS-11" /></label>
      </ConfirmModal>}
    </Drawer>
  );
}
