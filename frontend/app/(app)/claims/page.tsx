"use client";
import { useApi } from "@/lib/useApi";
import { useEffect, useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { CHANNEL_ACTION, Claim, CLAIM_EVENT_KO, CLAIM_STATUS, CLAIM_TYPE_KO, EXECUTOR_KO, FAULT_KO, REASON_KO, REFUND_ROW, REFUND_STATUS, RESHIP_STATUS,
  RETURN_STATUS } from "@/lib/claims";
import { formatDateTime, formatWon } from "@/lib/format";
import { Carrier } from "@/lib/fulfillment";
import { useSession } from "@/lib/session";

export default function ClaimsPage() {
  const { can, selected } = useSession();
  const [q, setQ] = useState("");
  const [type, setType] = useState("");
  const [status, setStatus] = useState("OPEN");
  const base = useMemo(() => `/claims${qs({ business_entity_id: selected, q, type, status })}`, [selected, q, type, status]);
  const [open, setOpen] = useState<string | null>(null);
  useEffect(() => {
    const o = new URLSearchParams(window.location.search).get("open");
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 서버 렌더와 첫 화면을 같게 두고, 하이드레이션 후 브라우저 값(주소창·로컬 저장소)을 한 번 읽는다
    if (o) setOpen(o);
  }, []);
  const list = useApi<Claim[]>(can("order.read") ? base : null);
  const rows = list.data ?? [];
  const loading = list.loading;
  const error = list.error;
  if (!can("order.read")) return <Forbidden />;
  return (
    <>
      <div className="page-head"><div><h1>클레임</h1><p>취소·반품·교환 요청입니다. 클레임·회수·환불·재출고는 따로 진행하며, 요청만으로는 주문 수량·재고·매출이 바뀌지 않습니다.
        클레임 접수는 주문 상세에서, 회수품 입고·검수는 반품 입고 화면에서 합니다.</p></div></div>
      <div className="row-gap">
        <label className="fld">검색<input className="inp" value={q} onChange={(e) => setQ(e.target.value)} placeholder="클레임·주문·외부 번호" /></label>
        <label className="fld">유형<select className="inp" value={type} onChange={(e) => setType(e.target.value)}><option value="">전체</option>
          {Object.entries(CLAIM_TYPE_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
        <label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">전체</option><option value="OPEN">진행 중</option>
          {Object.entries(CLAIM_STATUS).map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={() => list.reload()} /> : (
        <DataTable caption="클레임" rows={rows} rowKey={(r) => r.id} onRowClick={(r) => setOpen(r.id)}
          empty={<Empty title="클레임이 없습니다." hint="주문 상세에서 취소·반품·교환을 접수하거나 판매채널 클레임 수집을 실행하세요." />}
          columns={[
            { key: "no", header: "클레임번호", render: (r) => <><b className="mono">{r.claim_no}</b><div className="ctx mono">주문 {r.order_no}{r.external_claim_key ? ` · ${r.external_claim_key}` : ""}</div></> },
            { key: "t", header: "유형 · 사유", render: (r) => <>{CLAIM_TYPE_KO[r.claim_type]} <span className="ctx">{REASON_KO[r.reason_code]}{r.source === "API" ? " · 채널 수집" : ""}</span></> },
            { key: "be", header: "사업자 · 판매계정", render: (r) => `${r.business_entity_name} · ${r.channel_account_name}` },
            { key: "i", header: "상품", render: (r) => r.items.map((i) => `${i.product_name} ×${i.quantity}`).join(", ") },
            { key: "rf", header: "환불 예정", num: true, render: (r) => `${formatWon(r.expected_refund_amount)}원` },
            { key: "s", header: "진행", render: (r) => <span className="row-gap" style={{ gap: 4 }}>
              <Chip kind={CLAIM_STATUS[r.status][0]}>{CLAIM_STATUS[r.status][1]}</Chip>
              {r.return_status !== "NOT_REQUIRED" && <Chip kind={RETURN_STATUS[r.return_status][0]}>{RETURN_STATUS[r.return_status][1]}</Chip>}
              {r.refund_status !== "NOT_REQUIRED" && <Chip kind={REFUND_STATUS[r.refund_status][0]}>{REFUND_STATUS[r.refund_status][1]}</Chip>}</span> },
          ]} />
      )}
      {open && <ClaimDrawer id={open} onClose={() => setOpen(null)} onChanged={() => list.reload()} />}
    </>
  );
}

function ClaimDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const [c, setC] = useState<Claim | null>(null);
  const [loadErr, setLoadErr] = useState<ApiError | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [modal, setModal] = useState<null | "approve" | "reject" | "withdraw" | "tracking" | "refund" | { confirm: string } | { fail: string }>(null);
  const [text, setText] = useState("");
  const [amount, setAmount] = useState(0);
  const [executor, setExecutor] = useState("CHANNEL");
  const [carriers, setCarriers] = useState<Carrier[]>([]);
  const [carrier, setCarrier] = useState("CJ");
  const [key, setKey] = useState(newIdempotencyKey);
  const load = () => api<Claim>(`/claims/${id}`).then((r) => setC(r.data)).catch((e) => setLoadErr(e instanceof ApiError ? e : new ApiError(0, "UNKNOWN", "불러오지 못했습니다.")));
  useEffect(() => { load(); api<Carrier[]>("/carriers").then((r) => setCarriers(r.data)).catch(() => setCarriers([])); }, [id]); // eslint-disable-line react-hooks/exhaustive-deps
  const run = async (path: string, body: unknown, idem?: string) => {
    setErr(null);
    try {
      const r = await api<Claim>(path, { method: "POST", body, idempotencyKey: idem });
      setC(r.data);
      onChanged();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "처리하지 못했습니다.");
    }
  };
  if (loadErr) return <Drawer title="클레임" onClose={onClose}><ErrorState error={loadErr} onRetry={load} /></Drawer>;
  if (!c) return <Drawer title="클레임" onClose={onClose}><Loading /></Drawer>;
  const update = can("order.update");
  const open = ["REQUESTED", "REVIEWING", "APPROVED", "PROCESSING"].includes(c.status);
  const undecided = ["REQUESTED", "REVIEWING"].includes(c.status);
  const approved = ["APPROVED", "PROCESSING"].includes(c.status);
  const refundRoom = Number(c.expected_refund_amount) - c.refunds.filter((r) => r.status === "REQUESTED" || r.status === "CONFIRMED")
    .reduce((s, r) => s + Number(r.status === "CONFIRMED" ? r.confirmed_amount : r.requested_amount), 0);
  const close = () => { setModal(null); setText(""); };
  return (
    <Drawer title={`${CLAIM_TYPE_KO[c.claim_type]} ${c.claim_no}`} onClose={onClose} footer={update && open ? <>
      {undecided && <button className="btn pri" type="button" onClick={() => setModal("approve")}>승인</button>}
      {undecided && <button className="btn dan" type="button" onClick={() => setModal("reject")}>거부</button>}
      {c.claim_type !== "CANCEL" && <button className="btn" type="button" onClick={() => setModal("tracking")}>회수 송장</button>}
      {approved && can("claim.refund") && refundRoom > 0 && <button className="btn" type="button" onClick={() => { setAmount(refundRoom); setKey(newIdempotencyKey()); setModal("refund"); }}>환불 요청</button>}
      {["MANUAL_REQUIRED", "FAILED"].includes(c.channel_action_status) && <button className="btn" type="button" onClick={() => run(`/claims/${id}/channel-action/manual-done`, {})}>판매자센터 처리 완료</button>}
      {!c.cancel_applied && <button className="btn" type="button" onClick={() => setModal("withdraw")}>철회</button>}</> : undefined}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {c.claim_type === "CANCEL" && approved && !c.cancel_applied && <div className="note n-warn">물류에 전달된 출고지시의 취소 확인을 기다리고 있습니다. 출고 화면에서 출고 중지를 확인하면 취소 수량이 반영되고 남은 상품은 다시 배정됩니다.</div>}
      {c.channel_action_status === "MANUAL_REQUIRED" && <div className="note n-warn">이 판매계정 연결에서 클레임 처리 기능이 확인되지 않았습니다. 판매자센터에서 같은 결정을 처리한 뒤 완료를 기록하세요.</div>}
      <div className="kv"><span>주문 · 판매계정</span><span><span className="mono">{c.order_no}</span> · {c.business_entity_name} · {c.channel_account_name}</span></div>
      <div className="kv"><span>사유 · 귀책</span><span>{REASON_KO[c.reason_code]} · {FAULT_KO[c.fault_party]}{c.reason_note ? ` · ${c.reason_note}` : ""}</span></div>
      <div className="kv"><span>진행</span><span className="row-gap" style={{ gap: 4 }}>
        <Chip kind={CLAIM_STATUS[c.status][0]}>{CLAIM_STATUS[c.status][1]}</Chip><Chip kind={RETURN_STATUS[c.return_status][0]}>{RETURN_STATUS[c.return_status][1]}</Chip>
        <Chip kind={REFUND_STATUS[c.refund_status][0]}>{REFUND_STATUS[c.refund_status][1]}</Chip><Chip kind={RESHIP_STATUS[c.reship_status][0]}>{RESHIP_STATUS[c.reship_status][1]}</Chip>
        {c.source === "API" && <Chip kind={CHANNEL_ACTION[c.channel_action_status][0]}>{CHANNEL_ACTION[c.channel_action_status][1]}</Chip>}</span></div>
      <div className="kv"><span>환불 예정</span><span>상품 {formatWon(c.item_refund_amount)} + 배송비 {formatWon(c.shipping_refund_amount)} − 반품 배송비 {formatWon(c.return_shipping_fee)}
        {Number(c.price_difference) !== 0 ? ` · 교환 차액 ${formatWon(c.price_difference)}` : ""} = <b>{formatWon(c.expected_refund_amount)}원</b> <span className="ctx">(확정 {formatWon(c.confirmed_refund_amount)}원)</span></span></div>
      {Number(c.additional_payment_amount) > 0 && <div className="kv"><span>고객 추가 결제</span><span>{formatWon(c.additional_payment_amount)}원</span></div>}
      {c.return_tracking_no && <div className="kv"><span>회수 송장</span><span className="mono">{c.return_carrier_code} {c.return_tracking_no}</span></div>}
      <DataTable caption="클레임 상품" rows={c.items} rowKey={(i) => i.id} empty={<Empty title="상품이 없습니다." />} columns={[
        { key: "p", header: "상품", render: (i) => <>{i.product_name}<div className="ctx mono">{i.sku_code ?? "—"}</div></> },
        { key: "q", header: "수량", num: true, render: (i) => i.quantity },
        { key: "r", header: "환불액", num: true, render: (i) => `${formatWon(i.refund_amount)}원` },
        { key: "x", header: c.claim_type === "CANCEL" ? "—" : "회수(기대/입고/검수)", render: (i) => c.claim_type === "CANCEL" ? "—" : `${i.expected_return_qty} / ${i.received_qty} / ${i.inspected_qty}` },
        ...(c.claim_type === "EXCHANGE" ? [{ key: "e", header: "교환품", render: (i: Claim["items"][number]) => `${i.replacement_sku_code ?? "—"} ×${i.replacement_quantity ?? 0}` }] : []),
      ]} />
      <DataTable caption="환불" rows={c.refunds} rowKey={(r) => r.id} empty={<Empty title="환불 내역이 없습니다." />} columns={[
        { key: "t", header: "요청 시각", render: (r) => <span className="ctx">{formatDateTime(r.requested_at)}</span> },
        { key: "e", header: "실행 주체", render: (r) => `${EXECUTOR_KO[r.executor]}${r.source === "API" ? " (채널 수집)" : ""}` },
        { key: "a", header: "요청 / 확정", num: true, render: (r) => `${formatWon(r.requested_amount)} / ${r.confirmed_amount ? formatWon(r.confirmed_amount) : "—"}` },
        { key: "s", header: "상태", render: (r) => <><Chip kind={REFUND_ROW[r.status][0]}>{REFUND_ROW[r.status][1]}</Chip>{r.failure_reason && <span className="ctx"> {r.failure_reason}</span>}</> },
        { key: "x", header: "", render: (r) => r.status === "REQUESTED" && can("claim.refund") ? <span className="row-gap" style={{ gap: 4 }}>
          <button className="btn" type="button" onClick={() => { setAmount(Number(r.requested_amount)); setText(""); setModal({ confirm: r.id }); }}>확정</button>
          <button className="btn" type="button" onClick={() => { setText(""); setModal({ fail: r.id }); }}>실패</button></span> : null },
      ]} />
      <DataTable caption="처리 이력" rows={c.events} rowKey={(e) => `${e.occurred_at}:${e.event_type}`} empty={<Empty title="이력이 없습니다." />} columns={[
        { key: "t", header: "시각", render: (e) => <span className="ctx">{formatDateTime(e.occurred_at)}</span> },
        { key: "e", header: "내용", render: (e) => `${CLAIM_EVENT_KO[e.event_type] ?? e.event_type}${e.to_value && !["CLAIM_CREATED"].includes(e.event_type) ? ` (${e.from_value ?? "—"} → ${e.to_value})` : ""}` },
        { key: "a", header: "주체", render: (e) => e.actor_type === "SYSTEM" ? "시스템 자동 처리" : "사용자" },
      ]} />
      {modal === "approve" && <ConfirmModal title={`${CLAIM_TYPE_KO[c.claim_type]} 승인`} confirmLabel="승인" onClose={close}
        impact={c.claim_type === "CANCEL" ? [["효과", "출고 전이면 바로 취소 수량 반영·재고 확보 해제, 물류에 전달된 출고는 출고 중지 확인 후 반영"], ["환불", `예정 ${formatWon(c.expected_refund_amount)}원(별도 확정)`]]
          : c.claim_type === "RETURN" ? [["효과", "회수품은 입고·검수 후에만 재고에 반영"], ["환불", `예정 ${formatWon(c.expected_refund_amount)}원(검수와 별개로 확정)`]]
            : [["효과", c.ship_before_return ? "교환품을 바로 출고 배정(선출고)" : "회수품이 도착하면 교환품 출고 배정"], ["매출", "교환품은 0원 주문상품(원판매 매출 중복 없음)"]]}
        onConfirm={() => { close(); run(`/claims/${id}/approve`, { note: text || undefined }); }}>
        <label className="fld">메모<input className="inp" value={text} onChange={(e) => setText(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
      {(modal === "reject" || modal === "withdraw") && <ConfirmModal title={modal === "reject" ? "클레임 거부" : "클레임 철회"} danger confirmLabel={modal === "reject" ? "거부" : "철회"}
        onClose={close} confirmDisabled={modal === "reject" && !text.trim()} impact={[["클레임", c.claim_no], ["효과", "주문 수량·재고는 바뀌지 않습니다. 환불 예정은 취소됩니다"]]}
        onConfirm={() => { const m = modal; close(); run(`/claims/${id}/${m}`, { note: text || undefined }); }}>
        <label className="fld">{modal === "reject" ? "거부 사유(필수)" : "사유"}<input className="inp" value={text} onChange={(e) => setText(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
      {modal === "tracking" && <ConfirmModal title="회수 송장 기록" confirmLabel="기록" onClose={close} confirmDisabled={!text.trim()} impact={[["클레임", c.claim_no]]}
        onConfirm={() => { close(); run(`/claims/${id}/return-shipment`, { carrier_code: carrier, tracking_no: text }); }}>
        <label className="fld">택배사<select className="inp" value={carrier} onChange={(e) => setCarrier(e.target.value)}>{carriers.map((x) => <option key={x.code} value={x.code}>{x.name_ko}</option>)}</select></label>
        <label className="fld">회수 송장번호<input className="inp" value={text} onChange={(e) => setText(e.target.value)} maxLength={40} /></label>
      </ConfirmModal>}
      {modal === "refund" && <ConfirmModal title="환불 요청" confirmLabel="환불 요청" onClose={close} confirmDisabled={amount < 1 || amount > refundRoom}
        impact={[["남은 환불 예정액", `${formatWon(String(refundRoom))}원`], ["다음", "실제 환불이 끝나면 확정 금액을 기록합니다(중복 요청 방지)"]]}
        onConfirm={() => { close(); run(`/claims/${id}/refund-requests`, { amount, executor, note: text || undefined }, key); }}>
        <label className="fld">금액(원)<input className="inp" type="number" min={1} max={refundRoom} value={amount} onChange={(e) => setAmount(Number(e.target.value) || 0)} /></label>
        <label className="fld">실행 주체<select className="inp" value={executor} onChange={(e) => setExecutor(e.target.value)}>
          {Object.entries(EXECUTOR_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
        <label className="fld">메모<input className="inp" value={text} onChange={(e) => setText(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
      {modal && typeof modal === "object" && "confirm" in modal && <ConfirmModal title="환불 확정" confirmLabel="확정" onClose={close} confirmDisabled={amount < 0}
        impact={[["확정", "환불 완료 금액과 처리 번호를 기록합니다"]]}
        onConfirm={() => { const r = modal.confirm; close(); run(`/refunds/${r}/confirm`, { confirmed_amount: amount, external_ref: text || undefined }); }}>
        <label className="fld">확정 금액(원)<input className="inp" type="number" min={0} value={amount} onChange={(e) => setAmount(Number(e.target.value) || 0)} /></label>
        <label className="fld">처리 번호(PG·채널)<input className="inp" value={text} onChange={(e) => setText(e.target.value)} maxLength={200} /></label>
      </ConfirmModal>}
      {modal && typeof modal === "object" && "fail" in modal && <ConfirmModal title="환불 실패 기록" danger confirmLabel="기록" onClose={close} confirmDisabled={!text.trim()}
        impact={[["효과", "요청을 실패로 닫고 다시 요청할 수 있게 합니다"]]}
        onConfirm={() => { const r = modal.fail; close(); run(`/refunds/${r}/fail`, { reason: text }); }}>
        <label className="fld">실패 사유(필수)<input className="inp" value={text} onChange={(e) => setText(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
    </Drawer>
  );
}
