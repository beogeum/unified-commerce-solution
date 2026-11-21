"use client";
import { FormEvent, useState } from "react";
import { Chip } from "@/components/Chip";
import { Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey } from "@/lib/api";
import { formatDateTime, formatWon } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

export type Policy = {
  business_entity_id: string; business_entity_name: string; sku_id: string; sku_code: string; configured: boolean;
  sell_enabled: boolean; purchase_enabled: boolean; base_price: string | null; base_price_supply: string | null; base_price_vat: string | null;
  min_price: string | null; safety_stock: number; cost_visible: boolean; current_cost: string | null; current_cost_effective_from: string | null;
  pending_min_price: { approval_id: string; requested_min_price: string; requested_by_name: string } | null; version: number;
};
type Cost = { id: string; unit_cost: string; effective_from: string; source: string; reason: string; created_by_name: string | null; current: boolean };

const won = (v: string | null) => (v === null ? "—" : `${formatWon(v)}원`);

/** 한 SKU 의 사업자별 정책(T04): 같은 SKU 라도 사업자마다 판매 여부·가격·원가가 따로다. */
export function SkuPolicyDrawer({ skuId, skuCode, onClose }: { skuId: string; skuCode: string; onClose: () => void }) {
  const { data, error, loading, reload } = useApi<Policy[]>(`/skus/${skuId}/business-policies`);
  const [editing, setEditing] = useState<Policy | null>(null);
  return (
    <Drawer title={`${skuCode} · 사업자별 정책`} onClose={onClose}>
      <p className="ctx" style={{ margin: 0 }}>가격은 부가세 포함, 원가는 공급가(부가세 제외) 기준입니다. 접근 범위 안의 사업자만 보입니다.</p>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (data ?? []).length === 0 ? <Empty title="볼 수 있는 사업자가 없습니다." /> :
        (data ?? []).map((p) => editing?.business_entity_id === p.business_entity_id ? (
          <PolicyEditor key={p.business_entity_id} policy={p} onDone={() => { setEditing(null); reload(); }} onCancel={() => setEditing(null)} />
        ) : <PolicyCard key={p.business_entity_id} policy={p} onEdit={() => setEditing(p)} />)}
    </Drawer>
  );
}

function PolicyCard({ policy: p, onEdit }: { policy: Policy; onEdit: () => void }) {
  const { can } = useSession();
  const [showCosts, setShowCosts] = useState(false);
  return (
    <section className="card pad" aria-label={`${p.business_entity_name} 정책`} style={{ display: "flex", flexDirection: "column", gap: 4 }}>
      <div className="row-gap" style={{ justifyContent: "space-between", alignItems: "center" }}>
        <b>{p.business_entity_name}</b>
        <span className="row-gap">{!p.configured ? <Chip>미설정</Chip> : p.sell_enabled ? <Chip kind="ok">판매</Chip> : <Chip kind="mute">판매 안 함</Chip>}
          {can("catalog.manage") && <button className="btn" type="button" onClick={onEdit}>편집</button>}</span>
      </div>
      <div className="kv"><span>기준 판매가</span><span>{won(p.base_price)}{p.base_price_supply && <span className="ctx"> (공급가 {formatWon(p.base_price_supply)} · 부가세 {formatWon(p.base_price_vat)})</span>}</span></div>
      <div className="kv"><span>최저판매가</span><span>{won(p.min_price)} {p.pending_min_price && <Chip kind="warn">{formatWon(p.pending_min_price.requested_min_price)}원 승인 대기</Chip>}</span></div>
      <div className="kv"><span>안전재고</span><span>{p.safety_stock}</span></div>
      <div className="kv"><span>현재 원가</span><span>{p.cost_visible ? won(p.current_cost) : <span className="ctx">원가 조회 권한 필요</span>}</span></div>
      {p.cost_visible && <button className="btn" type="button" onClick={() => setShowCosts((v) => !v)}>{showCosts ? "원가 이력 닫기" : "원가 이력"}</button>}
      {showCosts && <CostHistory policy={p} />}
    </section>
  );
}

function PolicyEditor({ policy: p, onDone, onCancel }: { policy: Policy; onDone: () => void; onCancel: () => void }) {
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const n = (k: string) => { const v = String(f.get(k) ?? "").replace(/,/g, "").trim(); return v === "" ? undefined : v; };
    setBusy(true);
    setErr(null);
    try {
      await api(`/business-entities/${p.business_entity_id}/sku-policies/${p.sku_id}`, { method: "PUT", ifMatch: p.configured ? p.version : undefined, body: {
        sell_enabled: f.get("sell") === "on", base_price: n("base_price"), min_price: n("min_price"), safety_stock: n("safety_stock"),
        min_price_reason: n("min_price_reason") } });
      onDone();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "저장하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="card pad" onSubmit={submit} aria-label={`${p.business_entity_name} 정책 편집`} style={{ display: "flex", flexDirection: "column", gap: 10 }}>
      <b>{p.business_entity_name}</b>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label><input type="checkbox" name="sell" defaultChecked={p.sell_enabled} /> 이 사업자가 판매</label>
      <label className="fld">기준 판매가(부가세 포함, 원)<input className="inp" name="base_price" inputMode="numeric" defaultValue={p.base_price ?? ""} /></label>
      <label className="fld">최저판매가(원) — 바꾸면 승인 후 반영<input className="inp" name="min_price" inputMode="numeric" defaultValue={p.min_price ?? ""} /></label>
      <label className="fld">최저판매가 변경 사유<input className="inp" name="min_price_reason" maxLength={500} placeholder="예: 연말 프로모션 최저가 보호" /></label>
      <label className="fld">안전재고<input className="inp" name="safety_stock" type="number" min={0} defaultValue={p.safety_stock} /></label>
      <div className="row-gap"><button className="btn" type="button" onClick={onCancel}>취소</button><button className="btn pri" type="submit" disabled={busy}>저장</button></div>
    </form>
  );
}

function CostHistory({ policy: p }: { policy: Policy }) {
  const { can } = useSession();
  const path = `/business-entities/${p.business_entity_id}/sku-costs/${p.sku_id}`;
  const { data, error, loading, reload } = useApi<Cost[]>(path);
  const [err, setErr] = useState<string | null>(null);
  const add = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setErr(null);
    try {
      await api(path, { method: "POST", idempotencyKey: newIdempotencyKey(), body: {
        unit_cost: String(f.get("unit_cost") ?? "").replace(/,/g, ""), reason: f.get("reason") } });
      e.currentTarget?.reset();
      reload();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "등록하지 못했습니다.");
    }
  };
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (data ?? []).length === 0 ? <Empty title="등록된 원가가 없습니다." hint="원가가 없으면 손익이 '계산 불가'로 표시됩니다." /> :
        (data ?? []).map((c) => <div className="kv" key={c.id}><span>{formatWon(c.unit_cost)}원 {c.current && <Chip kind="ok">현재</Chip>}</span>
          <span className="ctx">{formatDateTime(c.effective_from)}부터 · {c.reason} · {c.created_by_name ?? "시스템"}</span></div>)}
      {can("cost.manage") && <form onSubmit={add} className="row-gap" aria-label="원가 등록">
        {err && <div className="fld-err" role="alert">{err}</div>}
        <input className="inp" name="unit_cost" aria-label="새 원가(공급가, 원)" inputMode="numeric" required style={{ width: 120 }} />
        <input className="inp" name="reason" aria-label="원가 등록 사유" required maxLength={500} style={{ flex: 1 }} />
        <button className="btn" type="submit">원가 등록(지금부터 적용)</button>
      </form>}
      <p className="ctx" style={{ margin: 0 }}>원가는 수정·삭제하지 않고 새 이력으로 쌓습니다. 이미 출고된 주문의 원가는 바뀌지 않습니다.</p>
    </div>
  );
}
