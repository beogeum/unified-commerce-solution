"use client";
import { FormEvent, useMemo, useState } from "react";
import { CatalogTabs } from "@/components/CatalogTabs";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { Sku } from "@/lib/catalog";
import { formatDateTime, formatWon } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Mapping = { id: string; sku_id: string; sku_code: string; product_name: string; quantity: number; valid_from: string; valid_to: string | null;
  match_basis: string; reason: string | null; created_by_name: string | null };
type Listing = { id: string; business_entity_name: string; channel_account_id: string; channel_account_name: string; sales_unit_name: string;
  external_product_id: string; external_option_id: string; external_sku_code: string | null; title: string; option_text: string | null;
  barcode: string | null; channel_price: string | null; channel_stock: number | null; sale_status: string; status: string;
  current_mapping: Mapping | null; distinct_sku_count: number; created_at: string };
type Detail = { listing: Listing; history: Mapping[]; previous_listing_id: string | null };
type Suggestion = { sku_id: string; sku_code: string; product_name: string; basis: string; confidence: string; enabled_for_business: boolean };
type Account = { id: string; display_name: string; business_entity_name: string };
type Unit = { id: string; display_name: string; external_unit_key: string };

const BASIS_KO: Record<string, string> = { BARCODE: "바코드 일치", EXTERNAL_SKU_CODE: "외부 SKU 코드 일치", NAME: "상품명 유사", MANUAL: "직접 선택", FILE: "파일", ID_CHANGE: "외부 ID 변경 이관" };
const SALE_KO: Record<string, string> = { ON_SALE: "판매중", SUSPENDED: "판매중지", SOLD_OUT: "품절", UNKNOWN: "확인 전" };

export default function ChannelListingsPage() {
  const { can, selected } = useSession();
  const [tab, setTab] = useState("UNMAPPED");
  const [account, setAccount] = useState("");
  const [q, setQ] = useState("");
  const accounts = useApi<Account[]>(`/channel-accounts${qs({ business_entity_id: selected, limit: "200" })}`);
  const path = useMemo(() => `/channel-listings${qs({ mapping: tab, channel_account_id: account, q, business_entity_id: selected, limit: "200" })}`,
    [tab, account, q, selected]);
  const { data, error, loading, reload } = useApi<Listing[]>(path);
  const [detail, setDetail] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const tabs: [string, string][] = [["UNMAPPED", "미연결"], ["REMAPPED", "재연결 이력"], ["MAPPED", "연결됨"], ["", "전체"]];
  return (
    <>
      <div className="page-head">
        <div><h1>채널상품·연결</h1><p>판매계정·판매단위별 외부 상품·옵션을 내부 SKU 와 연결합니다. 같은 외부 번호라도 계정·판매단위가 다르면 다른 상품입니다.</p></div>
        {can("catalog.manage") && <button className="btn pri" type="button" onClick={() => setCreating(true)}>채널상품 등록</button>}
      </div>
      <CatalogTabs active="listings" />
      <div role="tablist" aria-label="연결 상태" className="row-gap">
        {tabs.map(([k, l]) => <button key={k} role="tab" aria-selected={tab === k} className="btn" type="button" onClick={() => setTab(k)}
          style={tab === k ? { borderColor: "var(--primary)", color: "var(--primary-strong)", fontWeight: 600 } : undefined}>{l}</button>)}
      </div>
      <div className="row-gap" role="search">
        <label className="fld">판매계정<select className="inp" value={account} onChange={(e) => setAccount(e.target.value)}>
          <option value="">전체</option>{(accounts.data ?? []).map((a) => <option key={a.id} value={a.id}>{a.business_entity_name} · {a.display_name}</option>)}</select></label>
        <label className="fld" style={{ minWidth: 240 }}>검색<input className="inp" value={q} onChange={(e) => setQ(e.target.value)} placeholder="상품명, 외부 상품·옵션ID, 바코드" /></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="채널상품" rows={data ?? []} rowKey={(r) => r.id} onRowClick={(r) => setDetail(r.id)}
          empty={<Empty title={tab === "UNMAPPED" ? "미연결 채널상품이 없습니다." : "조건에 맞는 채널상품이 없습니다."} />}
          columns={[
            { key: "be", header: "사업자", render: (r) => r.business_entity_name },
            { key: "acc", header: "판매계정 · 판매단위", render: (r) => <>{r.channel_account_name}<div className="ctx">{r.sales_unit_name}</div></> },
            { key: "ext", header: "외부 상품 / 옵션", render: (r) => <><b>{r.title}</b><div className="ctx mono">{r.external_product_id} / {r.external_option_id || "옵션 없음"}</div></> },
            { key: "sku", header: "내부 SKU", render: (r) => r.current_mapping ? <span className="mono">{r.current_mapping.sku_code}{r.current_mapping.quantity > 1 ? ` ×${r.current_mapping.quantity}` : ""}</span>
              : r.status === "REPLACED" ? <Chip>외부 ID 변경됨</Chip> : <Chip kind="warn">미연결</Chip> },
            { key: "price", header: "채널가 / 재고", num: true, render: (r) => `${r.channel_price ? formatWon(r.channel_price) : "—"} / ${r.channel_stock ?? "—"}` },
            { key: "sale", header: "판매상태", render: (r) => SALE_KO[r.sale_status] ?? r.sale_status },
            { key: "flag", header: "확인", render: (r) => r.distinct_sku_count > 1 ? <Chip kind="err">다른 SKU 연결 이력</Chip> : null },
          ]} />
      )}
      {detail && <ListingDrawer id={detail} onClose={() => setDetail(null)} onChanged={reload} onOpen={setDetail} />}
      {creating && <CreateListing accounts={accounts.data ?? []} onClose={() => setCreating(false)} onCreated={(id) => { setCreating(false); reload(); setDetail(id); }} />}
    </>
  );
}

function ListingDrawer({ id, onClose, onChanged, onOpen }: { id: string; onClose: () => void; onChanged: () => void; onOpen: (id: string) => void }) {
  const { can } = useSession();
  const { data, error, loading, reload } = useApi<Detail>(`/channel-listings/${id}`);
  const suggestions = useApi<Suggestion[]>(`/channel-listings/${id}/suggestions`);
  const [skuQ, setSkuQ] = useState("");
  const skuResults = useApi<Sku[]>(skuQ.trim().length >= 2 ? `/skus${qs({ q: skuQ, status: "ACTIVE", limit: "10" })}` : null);
  const [qty, setQty] = useState(1);
  const [msg, setMsg] = useState<{ kind: "ok" | "err" | "warn"; text: string } | null>(null);
  const [confirm, setConfirm] = useState<{ skuId: string; skuCode: string; previous: string[] } | null>(null);
  const [reason, setReason] = useState("");
  const [idChange, setIdChange] = useState(false);
  const manage = can("catalog.manage") && data?.listing.status === "ACTIVE";

  const doMap = async (skuId: string, skuCode: string, basis: string, confirmRemap = false) => {
    setMsg(null);
    try {
      const r = await api<{ warnings: { message: string }[] }>(`/channel-listings/${id}/mapping`, { method: "POST",
        body: { sku_id: skuId, quantity: qty, match_basis: basis === "ID_CHANGE" ? "MANUAL" : basis, confirm_remap: confirmRemap, reason: reason || undefined } });
      setConfirm(null);
      setMsg(r.data.warnings.length ? { kind: "warn", text: `${skuCode}에 연결했습니다. ${r.data.warnings.map((w) => w.message).join(" ")}` }
        : { kind: "ok", text: `${skuCode}에 연결했습니다.` });
      reload();
      onChanged();
    } catch (e) {
      if (e instanceof ApiError && e.code === "MAPPING_REMAP_CONFIRMATION_REQUIRED") {
        setConfirm({ skuId, skuCode, previous: (e.details?.previous_sku_codes as string[]) ?? [] });
      } else {
        setMsg({ kind: "err", text: e instanceof ApiError ? e.message : "연결하지 못했습니다." });
      }
    }
  };
  const [unmapping, setUnmapping] = useState(false);
  const unmap = async () => {
    try {
      await api(`/channel-listings/${id}/mapping${qs({ reason })}`, { method: "DELETE" });
      setUnmapping(false);
      setReason("");
      reload();
      onChanged();
    } catch (e) {
      setMsg({ kind: "err", text: e instanceof ApiError ? e.message : "해제하지 못했습니다." });
    }
  };
  const submitIdChange = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    try {
      const r = await api<Detail>(`/channel-listings/${id}/external-id-change`, { method: "POST", body: {
        external_product_id: f.get("p"), external_option_id: f.get("o"), reason: f.get("reason") } });
      onChanged();
      onOpen(r.data.listing.id);
    } catch (x) {
      setMsg({ kind: "err", text: x instanceof ApiError ? x.message : "변경하지 못했습니다." });
    }
  };

  if (loading && !data) return <Drawer title="채널상품" onClose={onClose}><Loading /></Drawer>;
  if (error) return <Drawer title="채널상품" onClose={onClose}><ErrorState error={error} onRetry={reload} /></Drawer>;
  if (!data) return null;
  const l = data.listing;
  return (
    <Drawer title={l.title} onClose={onClose}>
      {msg && <div className={`note ${msg.kind === "ok" ? "n-info" : msg.kind === "warn" ? "n-warn" : "n-err"}`} role={msg.kind === "err" ? "alert" : "status"}>{msg.text}</div>}
      <div className="kv"><span>판매계정 · 판매단위</span><span>{l.channel_account_name} · {l.sales_unit_name}</span></div>
      <div className="kv"><span>외부 상품 / 옵션</span><span className="mono">{l.external_product_id} / {l.external_option_id || "옵션 없음"}</span></div>
      {l.option_text && <div className="kv"><span>옵션명</span><span>{l.option_text}</span></div>}
      <div className="kv"><span>바코드 · 외부 SKU</span><span className="mono">{l.barcode ?? "—"} · {l.external_sku_code ?? "—"}</span></div>
      {l.status === "REPLACED" && <div className="note n-warn">외부 ID 가 바뀌어 새 채널상품으로 이어졌습니다. 이 채널상품은 이력 보관용입니다.</div>}
      {data.previous_listing_id && <button className="btn" type="button" onClick={() => onOpen(data.previous_listing_id!)}>이전 외부 ID 채널상품 보기</button>}

      <h3 style={{ margin: "8px 0 0", fontSize: 14 }}>현재 연결</h3>
      {l.current_mapping ? <div className="row-gap" style={{ alignItems: "center" }}>
        <b className="mono">{l.current_mapping.sku_code}{l.current_mapping.quantity > 1 ? ` ×${l.current_mapping.quantity}` : ""}</b>
        <span className="ctx">{BASIS_KO[l.current_mapping.match_basis]} · {formatDateTime(l.current_mapping.valid_from)}부터</span>
        {manage && <button className="btn" type="button" onClick={() => setUnmapping(true)}>연결 해제</button>}
      </div> : <p className="ctx" style={{ margin: 0 }}>연결된 SKU 가 없습니다. 이 상품의 주문은 '상품 미연결'로 보류됩니다.</p>}

      {manage && <>
        <h3 style={{ margin: "8px 0 0", fontSize: 14 }}>연결할 SKU</h3>
        <label className="fld">채널상품 1개당 SKU 수량<input className="inp" type="number" min={1} max={1000} value={qty} onChange={(e) => setQty(Number(e.target.value) || 1)} style={{ width: 100 }} /></label>
        {suggestions.loading ? <Loading label="제안을 찾는 중입니다…" /> : (suggestions.data ?? []).length === 0 ? <p className="ctx" style={{ margin: 0 }}>자동 제안이 없습니다. 아래에서 검색하세요.</p> :
          (suggestions.data ?? []).map((s) => (
            <div className="kv" key={s.sku_id}>
              <span><b className="mono">{s.sku_code}</b> {s.product_name} <Chip kind={s.confidence === "HIGH" ? "ok" : "mute"}>{BASIS_KO[s.basis]}</Chip>
                {!s.enabled_for_business && <Chip kind="warn">사업자 판매 설정 없음</Chip>}</span>
              <button className="btn" type="button" onClick={() => doMap(s.sku_id, s.sku_code, s.basis)} aria-label={`${s.sku_code} 연결`}>연결</button>
            </div>))}
        <label className="fld">SKU 검색<input className="inp" value={skuQ} onChange={(e) => setSkuQ(e.target.value)} placeholder="SKU 코드, 상품명(2자 이상)" /></label>
        {(skuResults.data ?? []).map((s) => (
          <div className="kv" key={s.id}><span><b className="mono">{s.sku_code}</b> {s.product_name}</span>
            <button className="btn" type="button" onClick={() => doMap(s.id, s.sku_code, "MANUAL")} aria-label={`${s.sku_code} 연결`}>연결</button></div>))}
      </>}

      <h3 style={{ margin: "8px 0 0", fontSize: 14 }}>연결 이력</h3>
      {data.history.length === 0 ? <p className="ctx" style={{ margin: 0 }}>이력이 없습니다.</p> : data.history.map((m) => (
        <div className="kv" key={m.id}><span className="mono">{m.sku_code}{m.quantity > 1 ? ` ×${m.quantity}` : ""}</span>
          <span className="ctx">{formatDateTime(m.valid_from)} ~ {m.valid_to ? formatDateTime(m.valid_to) : "현재"} · {BASIS_KO[m.match_basis] ?? m.match_basis}{m.reason ? ` · ${m.reason}` : ""}</span></div>))}

      {manage && (idChange ? (
        <form onSubmit={submitIdChange} className="card pad" aria-label="외부 ID 변경" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          <b>채널이 상품·옵션 ID 를 바꿨나요?</b>
          <label className="fld">새 외부 상품ID<input className="inp mono" name="p" defaultValue={l.external_product_id} required /></label>
          <label className="fld">새 외부 옵션ID<input className="inp mono" name="o" defaultValue={l.external_option_id} /></label>
          <label className="fld">사유<input className="inp" name="reason" required maxLength={500} /></label>
          <div className="row-gap"><button className="btn" type="button" onClick={() => setIdChange(false)}>취소</button><button className="btn pri" type="submit">새 ID 로 이어서 연결</button></div>
        </form>) : <button className="btn" type="button" onClick={() => setIdChange(true)}>외부 ID 변경 기록</button>)}

      {confirm && <ConfirmModal title={`${confirm.skuCode}로 다시 연결할까요?`} confirmLabel="다시 연결" confirmDisabled={!reason.trim()}
        impact={[["이전에 연결됐던 SKU", confirm.previous.join(", ")], ["이전 주문", "당시 SKU 그대로 유지"]]}
        onConfirm={() => doMap(confirm.skuId, confirm.skuCode, "MANUAL", true)} onClose={() => setConfirm(null)}>
        <label className="fld">다시 연결하는 이유<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
      {unmapping && <ConfirmModal title="연결을 해제할까요?" danger confirmLabel="연결 해제" confirmDisabled={!reason.trim()}
        impact={[["SKU", l.current_mapping?.sku_code ?? ""], ["이후 주문", "'상품 미연결'로 보류"], ["이력", "기존 연결 기간은 보존"]]}
        onConfirm={unmap} onClose={() => setUnmapping(false)}>
        <label className="fld">해제하는 이유<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
    </Drawer>
  );
}

function CreateListing({ accounts, onClose, onCreated }: { accounts: Account[]; onClose: () => void; onCreated: (id: string) => void }) {
  const [account, setAccount] = useState(accounts[0]?.id ?? "");
  const units = useApi<Unit[]>(account ? `/channel-accounts/${account}/sales-units` : null);
  const [key] = useState(newIdempotencyKey);
  const [err, setErr] = useState<string | null>(null);
  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const v = (k: string) => (String(f.get(k) ?? "").trim() || undefined);
    setErr(null);
    try {
      const r = await api<Detail>("/channel-listings", { method: "POST", idempotencyKey: key, body: {
        sales_unit_id: v("sales_unit_id"), external_product_id: v("p"), external_option_id: v("o") ?? "", title: v("title"), option_text: v("option_text"),
        barcode: v("barcode"), external_sku_code: v("external_sku_code"), channel_price: v("price")?.replace(/,/g, ""), channel_stock: v("stock") } });
      onCreated(r.data.listing.id);
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "등록하지 못했습니다.");
    }
  };
  return (
    <Drawer title="채널상품 등록" onClose={onClose} footer={<><button className="btn" type="button" onClick={onClose}>취소</button>
      <button className="btn pri" type="submit" form="listing-create">등록</button></>}>
      <form id="listing-create" onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        <label className="fld">판매계정<select className="inp" value={account} onChange={(e) => setAccount(e.target.value)}>
          {accounts.map((a) => <option key={a.id} value={a.id}>{a.business_entity_name} · {a.display_name}</option>)}</select></label>
        <label className="fld">판매단위<select className="inp" name="sales_unit_id" required>
          {(units.data ?? []).map((u) => <option key={u.id} value={u.id}>{u.display_name} ({u.external_unit_key})</option>)}</select></label>
        <label className="fld">외부 상품ID<input className="inp mono" name="p" required maxLength={100} /></label>
        <label className="fld">외부 옵션ID(없으면 비움)<input className="inp mono" name="o" maxLength={100} /></label>
        <label className="fld">채널 상품명<input className="inp" name="title" required maxLength={300} /></label>
        <label className="fld">옵션명<input className="inp" name="option_text" maxLength={300} /></label>
        <label className="fld">바코드<input className="inp mono" name="barcode" maxLength={40} /></label>
        <label className="fld">외부 SKU 코드(판매자 관리코드)<input className="inp mono" name="external_sku_code" maxLength={100} /></label>
        <label className="fld">채널가(원)<input className="inp" name="price" inputMode="numeric" /></label>
        <label className="fld">채널 재고<input className="inp" name="stock" type="number" min={0} /></label>
        <p className="ctx" style={{ margin: 0 }}>채널 API 로 상품을 가져오는 기능은 판매계정의 '상품 조회' 지원 기능이 확인된 뒤 켜집니다. 지금은 직접 등록하거나 일괄 파일(C6)로 등록합니다.</p>
      </form>
    </Drawer>
  );
}
