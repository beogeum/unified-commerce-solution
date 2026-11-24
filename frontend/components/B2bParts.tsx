"use client";
import { useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { SkuPicker } from "@/components/SkuPicker";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey } from "@/lib/api";
import { AR_TYPE, Customer, CUSTOMER_STATUS, PAY_METHOD, Price, Statement } from "@/lib/b2b";
import { won } from "@/lib/finance";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

const msg = (x: unknown, f: string) => (x instanceof ApiError ? x.message : f);
const int = (v: string) => Math.max(0, Math.round(Number(v) || 0));

export function CustomerStatus({ s }: { s: string }) {
  const v = CUSTOMER_STATUS[s] ?? ["mute", s];
  return <Chip kind={v[0]}>{v[1]}</Chip>;
}

/** 거래처 등록. 외상 한도·결제 기한은 외상 관리 권한(재무)만. */
export function NewCustomerModal({ onClose, onDone }: { onClose: () => void; onDone: (id: string) => void }) {
  const { entities, can } = useSession();
  const finance = can("receivable.manage");
  const [beSel, setBe] = useState("");
  const be = beSel || entities[0]?.id || "";
  const [f, setF] = useState({ code: "", name: "", brn: "", contact_name: "", contact_phone: "", limit: 0, terms: 30 });
  const [err, setErr] = useState<string | null>(null);
  const submit = async () => {
    try {
      const r = await api<Customer>("/b2b-customers", { method: "POST", body: { business_entity_id: be, code: f.code, name: f.name,
        business_registration_no: f.brn || undefined, contact_name: f.contact_name || undefined, contact_phone: f.contact_phone || undefined,
        ...(finance ? { credit_limit: f.limit, payment_terms_days: f.terms } : {}) } });
      onDone(r.data.id);
    } catch (x) {
      setErr(msg(x, "등록하지 못했습니다."));
    }
  };
  return (
    <ConfirmModal title="거래처 등록" confirmLabel="등록" confirmDisabled={!be || !f.code.trim() || !f.name.trim()} onClose={onClose} onConfirm={submit}
      impact={[["단위", "거래처는 사업자별입니다"], ["외상", finance ? "한도 0 이면 선결제만 받습니다" : "외상 한도는 재무 담당이 정합니다(지금은 선결제만)"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">사업자<select className="inp" value={be} onChange={(e) => setBe(e.target.value)} aria-label="거래처 사업자">
        {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
      <label className="fld">거래처 코드<input className="inp" value={f.code} onChange={(e) => setF({ ...f, code: e.target.value })} maxLength={40} /></label>
      <label className="fld">거래처명<input className="inp" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} maxLength={100} /></label>
      <label className="fld">사업자등록번호<input className="inp" value={f.brn} onChange={(e) => setF({ ...f, brn: e.target.value })} placeholder="000-00-00000" /></label>
      <label className="fld">담당자<input className="inp" value={f.contact_name} onChange={(e) => setF({ ...f, contact_name: e.target.value })} maxLength={50} /></label>
      <label className="fld">담당자 연락처<input className="inp" value={f.contact_phone} onChange={(e) => setF({ ...f, contact_phone: e.target.value })} maxLength={30} /></label>
      {finance && <>
        <label className="fld">외상 한도(원)<input className="inp" type="number" min={0} value={f.limit} onChange={(e) => setF({ ...f, limit: int(e.target.value) })} /></label>
        <label className="fld">결제 기한(출고 후 일수)<input className="inp" type="number" min={0} max={365} value={f.terms} onChange={(e) => setF({ ...f, terms: Math.min(365, int(e.target.value)) })} /></label>
      </>}
    </ConfirmModal>
  );
}

/** 거래처 상세: 기본정보·거래 보류, 단가표, 외상(잔액·연령·원장·수금·대손 요청). */
export function CustomerDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const c = useApi<Customer>(`/b2b-customers/${id}`);
  const prices = useApi<Price[]>(`/b2b-customers/${id}/prices`);
  const canAr = can("settlement.read");
  const st = useApi<Statement>(canAr ? `/b2b-customers/${id}/receivables` : null);
  const [mode, setMode] = useState<null | "edit" | "price" | "pay" | "writeoff" | "reverse">(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [edit, setEdit] = useState({ status: "ACTIVE", hold_reason: "", limit: 0, terms: 30 });
  const [price, setPrice] = useState({ sku_id: "", sku_code: "", unit_price: 0, effective_from: "" });
  const [pay, setPay] = useState({ amount: 0, received_on: "", method: "BANK_TRANSFER", reference: "" });
  const [payKey, setPayKey] = useState(newIdempotencyKey);
  const [wo, setWo] = useState({ amount: 0, reason: "" });
  const [rev, setRev] = useState({ id: "", reason: "" });
  const reload = () => { c.reload(); prices.reload(); st.reload(); onChanged(); };
  const run = async (p: Promise<unknown>, done: string) => {
    try {
      await p;
      setNotice(done);
      setMode(null);
      reload();
    } catch (x) {
      setMode(null);
      setNotice(msg(x, "처리하지 못했습니다."));
    }
  };
  const cu = c.data;
  const finance = can("receivable.manage");
  const manage = can("customer.manage");
  const s = st.data;
  return (
    <Drawer title={cu ? `거래처 ${cu.name}` : "거래처"} onClose={onClose} footer={cu ? <>
      {manage && <button className="btn" type="button" onClick={() => { setEdit({ status: cu.status, hold_reason: cu.hold_reason ?? "", limit: Number(cu.credit_limit), terms: cu.payment_terms_days }); setMode("edit"); }}>거래 상태·한도 변경</button>}
      {manage && <button className="btn" type="button" onClick={() => setMode("price")}>단가 등록</button>}
      {finance && <button className="btn pri" type="button" onClick={() => { setPayKey(newIdempotencyKey()); setPay({ amount: 0, received_on: "", method: "BANK_TRANSFER", reference: "" }); setMode("pay"); }}>수금 기록</button>}
      {finance && <button className="btn" type="button" onClick={() => setMode("writeoff")}>대손 요청</button>}
    </> : undefined}>
      {c.loading ? <Loading /> : c.error ? <ErrorState error={c.error} onRetry={c.reload} /> : cu && <>
        {notice && <div className="note n-info" role="status">{notice}</div>}
        <div className="kv"><span>상태</span><span><CustomerStatus s={cu.status} />{cu.hold_reason ? <span className="ctx"> {cu.hold_reason}</span> : null}</span></div>
        <div className="kv"><span>사업자 · 코드</span><span>{cu.business_entity_name} · <span className="mono">{cu.code}</span></span></div>
        <div className="kv"><span>사업자등록번호</span><span>{cu.business_registration_no ?? "—"}</span></div>
        <div className="kv"><span>담당자</span><span>{cu.contact_name_masked ?? "—"} · {cu.contact_phone_masked ?? "—"}</span></div>
        <div className="kv"><span>외상 한도 · 결제 기한</span><span>{won(cu.credit_limit)}원 · 출고 후 {cu.payment_terms_days}일</span></div>
        {canAr && (st.loading ? <Loading /> : st.error ? <ErrorState error={st.error} onRetry={st.reload} /> : s && <>
          <h3>외상</h3>
          {Number(s.overdue) > 0 && <div className="note n-warn">기한이 지난 미수 {won(s.overdue)}원이 있어 새 외상 주문이 막힙니다.</div>}
          <div className="kv"><span>미수 잔액 · 연체</span><span>{won(s.balance)} · {won(s.overdue)}</span></div>
          <div className="kv"><span>미출고 외상 주문 · 사용 중 · 남은 한도</span><span>{won(s.unbilled_orders)} · {won(s.exposure)} · {won(s.available)}</span></div>
          <div className="kv"><span>연령(미도래 · 1~30 · 31~60 · 61~90 · 90일 초과)</span>
            <span>{won(s.aging.not_due)} · {won(s.aging.d1to30)} · {won(s.aging.d31to60)} · {won(s.aging.d61to90)} · {won(s.aging.over90)}</span></div>
          <DataTable caption="외상 원장" rows={s.entries} rowKey={(e) => e.id} empty={<Empty title="외상 기록이 없습니다." />} columns={[
            { key: "d", header: "일자 · 기한", render: (e) => <span className="ctx">{e.occurred_on}{e.due_on ? ` · ${e.due_on}` : ""}</span> },
            { key: "t", header: "구분", render: (e) => <>{AR_TYPE[e.entry_type] ?? e.entry_type}{e.reversed ? <span className="ctx"> (취소됨)</span> : null}</> },
            { key: "o", header: "주문 · 참조", render: (e) => <span className="ctx">{e.order_no ?? ""}{e.method ? ` ${PAY_METHOD[e.method] ?? e.method}` : ""}{e.reference ? ` ${e.reference}` : ""}{e.memo ? ` · ${e.memo}` : ""}</span> },
            { key: "a", header: "금액", num: true, render: (e) => won(e.amount) },
            { key: "x", header: "", render: (e) => (finance && e.entry_type === "PAYMENT" && !e.reversed
              ? <button className="btn" type="button" onClick={() => { setRev({ id: e.id, reason: "" }); setMode("reverse"); }}>수금 취소</button> : null) },
          ]} />
        </>)}
        <h3>단가표</h3>
        {prices.loading ? <Loading /> : <DataTable caption="거래처 단가표" rows={prices.data ?? []} rowKey={(p) => p.id} empty={<Empty title="등록된 단가가 없습니다." hint="단가가 없으면 주문할 때 직접 입력합니다." />} columns={[
          { key: "k", header: "SKU", render: (p) => <span className="mono">{p.sku_code}</span> },
          { key: "u", header: "단가(부가세 포함)", num: true, render: (p) => won(p.unit_price) },
          { key: "f", header: "적용일", render: (p) => <>{p.effective_from}{p.current ? <Chip kind="ok">현재</Chip> : null}</> },
        ]} />}
      </>}
      {mode === "edit" && cu && <ConfirmModal title="거래 상태·한도 변경" confirmLabel="저장" onClose={() => setMode(null)}
        confirmDisabled={edit.status === "ON_HOLD" && !edit.hold_reason.trim()}
        onConfirm={() => run(api(`/b2b-customers/${id}`, { method: "PATCH", ifMatch: cu.version, body: { status: edit.status, hold_reason: edit.hold_reason || undefined,
          ...(finance ? { credit_limit: edit.limit, payment_terms_days: edit.terms } : {}) } }), "저장했습니다.")}
        impact={[["거래 보류", "보류 중에는 새 주문(선결제 포함)을 받지 않습니다"], ["한도", "이미 받은 주문은 그대로입니다"]]}>
        <label className="fld">상태<select className="inp" value={edit.status} onChange={(e) => setEdit({ ...edit, status: e.target.value })} aria-label="거래처 상태">
          {Object.entries(CUSTOMER_STATUS).map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label>
        {edit.status === "ON_HOLD" && <label className="fld">보류 사유<input className="inp" value={edit.hold_reason} onChange={(e) => setEdit({ ...edit, hold_reason: e.target.value })} maxLength={300} /></label>}
        {finance && <>
          <label className="fld">외상 한도(원)<input className="inp" type="number" min={0} value={edit.limit} onChange={(e) => setEdit({ ...edit, limit: int(e.target.value) })} /></label>
          <label className="fld">결제 기한(일)<input className="inp" type="number" min={0} max={365} value={edit.terms} onChange={(e) => setEdit({ ...edit, terms: Math.min(365, int(e.target.value)) })} /></label>
        </>}
      </ConfirmModal>}
      {mode === "price" && <ConfirmModal title="단가 등록" confirmLabel="등록" confirmDisabled={!price.sku_id} onClose={() => setMode(null)}
        onConfirm={() => run(api(`/b2b-customers/${id}/prices`, { method: "POST", body: { prices: [{ sku_id: price.sku_id, unit_price: price.unit_price,
          effective_from: price.effective_from || undefined }] } }), "단가를 등록했습니다.")}
        impact={[["이력", "기존 단가는 지우지 않고 적용일별로 쌓습니다"], ["효과", "적용일 이후 주문부터 기본 단가로 씁니다"]]}>
        {price.sku_id ? <div className="kv"><span>SKU</span><span className="mono">{price.sku_code}</span></div>
          : <SkuPicker label="SKU 검색" onPick={(x) => setPrice({ ...price, sku_id: x.id, sku_code: x.sku_code })} />}
        <label className="fld">단가(원, 부가세 포함)<input className="inp" type="number" min={0} value={price.unit_price} onChange={(e) => setPrice({ ...price, unit_price: int(e.target.value) })} /></label>
        <label className="fld">적용일(비우면 오늘)<input className="inp" type="date" value={price.effective_from} onChange={(e) => setPrice({ ...price, effective_from: e.target.value })} /></label>
      </ConfirmModal>}
      {mode === "pay" && <ConfirmModal title="수금 기록" confirmLabel="기록" confirmDisabled={pay.amount < 1} onClose={() => setMode(null)}
        onConfirm={() => run(api(`/b2b-customers/${id}/payments`, { method: "POST", idempotencyKey: payKey, body: { amount: pay.amount, received_on: pay.received_on || undefined,
          method: pay.method, reference: pay.reference || undefined } }), "수금을 기록했습니다.")}
        impact={[["차감", "오래된 청구부터 차감합니다"], ["정정", "잘못 기록하면 '수금 취소'로 되돌립니다(원장은 지우지 않음)"]]}>
        <label className="fld">수금액(원)<input className="inp" type="number" min={1} value={pay.amount} onChange={(e) => setPay({ ...pay, amount: int(e.target.value) })} aria-label="수금액" /></label>
        <label className="fld">입금일(비우면 오늘)<input className="inp" type="date" value={pay.received_on} onChange={(e) => setPay({ ...pay, received_on: e.target.value })} /></label>
        <label className="fld">방법<select className="inp" value={pay.method} onChange={(e) => setPay({ ...pay, method: e.target.value })} aria-label="수금 방법">
          {Object.entries(PAY_METHOD).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
        <label className="fld">참조(입금자명·어음번호)<input className="inp" value={pay.reference} onChange={(e) => setPay({ ...pay, reference: e.target.value })} maxLength={100} /></label>
      </ConfirmModal>}
      {mode === "reverse" && <ConfirmModal title="수금을 취소할까요?" confirmLabel="수금 취소" danger confirmDisabled={!rev.reason.trim()} onClose={() => setMode(null)}
        onConfirm={() => run(api(`/receivable-entries/${rev.id}/reverse`, { method: "POST", body: { reason: rev.reason } }), "수금을 취소했습니다.")}
        impact={[["효과", "같은 금액의 수금 취소를 원장에 추가합니다(원래 기록은 남음)"]]}>
        <label className="fld">사유<input className="inp" value={rev.reason} onChange={(e) => setRev({ ...rev, reason: e.target.value })} maxLength={500} /></label>
      </ConfirmModal>}
      {mode === "writeoff" && <ConfirmModal title="대손 요청" confirmLabel="승인 요청" danger confirmDisabled={wo.amount < 1 || !wo.reason.trim()} onClose={() => setMode(null)}
        onConfirm={() => run(api(`/b2b-customers/${id}/write-offs`, { method: "POST", body: wo }), "대손 승인을 요청했습니다. 다른 승인자가 승인하면 반영됩니다.")}
        impact={[["미수 잔액", s ? `${won(s.balance)}원` : "—"], ["승인", "외상 관리 권한이 있는 다른 승인자가 승인해야 합니다"], ["손익", "관리 손익에는 반영하지 않습니다(회계 대손상각은 회계 시스템)"]]}>
        <label className="fld">대손 금액(원)<input className="inp" type="number" min={1} value={wo.amount} onChange={(e) => setWo({ ...wo, amount: int(e.target.value) })} /></label>
        <label className="fld">사유<input className="inp" value={wo.reason} onChange={(e) => setWo({ ...wo, reason: e.target.value })} maxLength={500} /></label>
      </ConfirmModal>}
    </Drawer>
  );
}
