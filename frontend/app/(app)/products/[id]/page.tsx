"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { FormEvent, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { ProductStatusChip } from "@/components/ProductStatus";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey } from "@/lib/api";
import { Brand, Category, MISSING_KO, PRODUCT_TAX, ProductDetail, SKU_TYPE, Sku, TYPE_DEFAULTS, Warning } from "@/lib/catalog";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

function errorText(e: unknown): string {
  if (!(e instanceof ApiError)) return "처리하지 못했습니다.";
  const missing = e.details?.missing;
  if (Array.isArray(missing)) return `${e.message} 빠진 항목: ${missing.map((m) => MISSING_KO[String(m)] ?? m).join(", ")}`;
  const opts = e.details?.missing_options;
  if (Array.isArray(opts)) return `${e.message} 빠진 옵션: ${opts.join(", ")}`;
  return e.message;
}

export default function ProductDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { can } = useSession();
  const { data, error, loading, reload } = useApi<ProductDetail>(`/products/${id}`);
  const brands = useApi<Brand[]>("/brands?status=ACTIVE");
  const categories = useApi<Category[]>("/categories");
  const [msg, setMsg] = useState<{ kind: "ok" | "err"; text: string } | null>(null);
  const [addingSku, setAddingSku] = useState(false);
  const [confirm, setConfirm] = useState<string | null>(null);
  const manage = can("catalog.manage") && data?.product.status !== "ARCHIVED";

  if (loading && !data) return <Loading />;
  if (error) return <ErrorState error={error} onRetry={reload} />;
  if (!data) return null;
  const p = data.product;

  const patch = async (body: Record<string, unknown>, okText: string) => {
    setMsg(null);
    try {
      await api(`/products/${id}`, { method: "PATCH", ifMatch: p.version, body });
      setMsg({ kind: "ok", text: okText });
      reload();
    } catch (e) {
      setMsg({ kind: "err", text: errorText(e) });
      if (e instanceof ApiError && e.code === "VERSION_CONFLICT") reload();
    }
  };

  const saveInfo = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const v = (k: string) => (String(f.get(k) ?? "").trim() || undefined);
    patch({ name: v("name"), brand_id: v("brand_id"), category_id: v("category_id"), tax_type: v("tax_type"), internal_name: v("internal_name") }, "기본 정보를 저장했습니다.");
  };

  const addValue = async (optionId: string, value: string) => {
    setMsg(null);
    try {
      await api(`/products/${id}/options/${optionId}/values`, { method: "POST", body: { value } });
      reload();
    } catch (e) {
      setMsg({ kind: "err", text: errorText(e) });
    }
  };

  const transitions: Record<string, [string, string][]> = {
    DRAFT: [["ACTIVE", "판매중으로 게시"], ["ARCHIVED", "보관"]],
    ACTIVE: [["INACTIVE", "판매중지"]],
    INACTIVE: [["ACTIVE", "판매 재개"], ["ARCHIVED", "보관"]],
    ARCHIVED: [],
  };

  return (
    <>
      <div className="page-head">
        <div>
          <div className="ctx"><Link href="/products">상품마스터</Link> › {p.product_code}</div>
          <h1 style={{ display: "flex", gap: 8, alignItems: "center" }}>{p.name} <ProductStatusChip status={p.status} /></h1>
          <p>SKU {p.active_sku_count}개 판매 가능 / 전체 {p.sku_count}개</p>
        </div>
        {can("catalog.manage") && <div className="row-gap">
          {transitions[p.status].map(([to, label]) => (
            <button key={to} className={`btn ${to === "ACTIVE" ? "pri" : ""}`} type="button"
              onClick={() => (to === "ARCHIVED" || to === "INACTIVE" ? setConfirm(to) : patch({ status: to }, `${label} 처리했습니다.`))}>{label}</button>
          ))}
        </div>}
      </div>
      {msg && <div className={`note ${msg.kind === "ok" ? "n-info" : "n-err"}`} role={msg.kind === "err" ? "alert" : "status"}>{msg.text}</div>}

      <section className="card pad" aria-labelledby="info-h">
        <h2 id="info-h" style={{ marginBottom: 12 }}>기본 정보</h2>
        <form onSubmit={saveInfo} className="kpis" style={{ alignItems: "end" }}>
          <label className="fld">상품명<input className="inp" name="name" defaultValue={p.name} disabled={!manage} maxLength={200} /></label>
          <label className="fld">내부관리명<input className="inp" name="internal_name" defaultValue={p.internal_name ?? ""} disabled={!manage} maxLength={200} /></label>
          <label className="fld">브랜드<select className="inp" name="brand_id" defaultValue={p.brand_id ?? ""} disabled={!manage}>
            <option value="">미지정</option>{(brands.data ?? []).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</select></label>
          <label className="fld">카테고리<select className="inp" name="category_id" defaultValue={p.category_id ?? ""} disabled={!manage}>
            <option value="">미지정</option>{(categories.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.path}</option>)}</select></label>
          <label className="fld">과세유형<select className="inp" name="tax_type" defaultValue={p.tax_type ?? ""} disabled={!manage}>
            <option value="">미지정</option>{Object.entries(PRODUCT_TAX).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
          {manage && <div><button className="btn" type="submit">기본 정보 저장</button></div>}
        </form>
      </section>

      <section className="card pad" aria-labelledby="opt-h" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        <h2 id="opt-h">옵션</h2>
        {data.options.length === 0 ? <p className="ctx" style={{ margin: 0 }}>옵션이 없는 단일 상품입니다.</p> : data.options.map((o) => (
          <div key={o.id} className="row-gap" style={{ alignItems: "center" }}>
            <b style={{ minWidth: 80 }}>{o.name}</b>
            {o.values.map((v) => <Chip key={v.id}>{v.value}</Chip>)}
            {manage && <AddValue onAdd={(v) => addValue(o.id, v)} label={`${o.name} 값 추가`} />}
          </div>))}
      </section>

      <section aria-labelledby="sku-h" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        <div className="page-head"><h2 id="sku-h">SKU</h2>{manage && <button className="btn pri" type="button" onClick={() => setAddingSku(true)}>SKU 추가</button>}</div>
        <DataTable caption="이 상품의 SKU" rows={data.skus} rowKey={(r) => r.id}
          empty={<Empty title="SKU 가 없습니다." hint="재고·원가는 SKU 단위로 관리합니다. 판매할 단위마다 SKU 를 추가하세요." />}
          columns={[
            { key: "code", header: "SKU 코드", render: (r) => <span className="mono">{r.sku_code}</span> },
            { key: "opt", header: "옵션", render: (r) => r.options.map((o) => `${o.option_name} ${o.value}`).join(" · ") || "—" },
            { key: "type", header: "유형", render: (r) => SKU_TYPE[r.sku_type] ?? r.sku_type },
            { key: "flags", header: "속성", render: (r) => <span className="row-gap">{r.sellable && <Chip kind="ok">판매</Chip>}{r.purchasable && <Chip>구매</Chip>}{r.inventory_managed && <Chip kind="info">재고</Chip>}{r.expiry_managed && <Chip kind="warn">유통기한</Chip>}</span> },
            { key: "bc", header: "바코드", render: (r) => <span className="mono">{r.barcode ?? "—"}</span> },
            { key: "st", header: "상태", render: (r) => <SkuStatus sku={r} canManage={manage} onChanged={reload} onError={(t) => setMsg({ kind: "err", text: t })} /> },
          ]} />
      </section>

      {addingSku && <AddSku productId={id} options={data.options} onClose={() => setAddingSku(false)}
        onCreated={(w) => { setAddingSku(false); reload(); setMsg(w.length ? { kind: "err", text: w.map((x) => x.message).join(" ") } : { kind: "ok", text: "SKU 를 추가했습니다." }); }} />}
      {confirm && <ConfirmModal title={confirm === "ARCHIVED" ? "상품을 보관할까요?" : "상품을 판매중지할까요?"} danger={confirm === "ARCHIVED"}
        confirmLabel={confirm === "ARCHIVED" ? "보관" : "판매중지"}
        impact={confirm === "ARCHIVED" ? [["되돌리기", "불가 — 보관된 상품은 수정할 수 없음"], ["SKU", `${p.sku_count}개는 그대로 남음`]]
          : [["채널 판매", "채널상품 연결(C3) 이후 판매중지 전송 대상"], ["되돌리기", "판매 재개 가능"]]}
        onConfirm={() => { const to = confirm; setConfirm(null); patch({ status: to }, "상태를 바꿨습니다."); }} onClose={() => setConfirm(null)} />}
    </>
  );
}

function AddValue({ onAdd, label }: { onAdd: (v: string) => void; label: string }) {
  const [v, setV] = useState("");
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (v.trim()) { onAdd(v.trim()); setV(""); } }} className="row-gap">
      <input className="inp" aria-label={`${label} 입력`} value={v} onChange={(e) => setV(e.target.value)} style={{ width: 120 }} maxLength={100} />
      <button className="btn" type="submit" aria-label={label}>값 추가</button>
    </form>
  );
}

function SkuStatus({ sku, canManage, onChanged, onError }: { sku: Sku; canManage: boolean; onChanged: () => void; onError: (t: string) => void }) {
  const toggle = async () => {
    try {
      await api(`/skus/${sku.id}`, { method: "PATCH", ifMatch: sku.version, body: { status: sku.status === "ACTIVE" ? "INACTIVE" : "ACTIVE" } });
      onChanged();
    } catch (e) {
      onError(errorText(e));
    }
  };
  const label = sku.status === "ACTIVE" ? "운영" : sku.status === "INACTIVE" ? "중지" : "보관";
  return (
    <span className="row-gap" style={{ alignItems: "center" }}>
      <Chip kind={sku.status === "ACTIVE" ? "ok" : "mute"}>{label}</Chip>
      {canManage && sku.status !== "ARCHIVED" && <button className="btn" type="button" onClick={toggle}>{sku.status === "ACTIVE" ? "중지" : "운영"}</button>}
    </span>
  );
}

function AddSku({ productId, options, onClose, onCreated }: {
  productId: string; options: ProductDetail["options"]; onClose: () => void; onCreated: (w: Warning[]) => void;
}) {
  const [key] = useState(newIdempotencyKey);
  const [type, setType] = useState("NORMAL");
  const [flags, setFlags] = useState<[boolean, boolean, boolean]>(TYPE_DEFAULTS.NORMAL);
  const [lot, setLot] = useState(false);
  const [expiry, setExpiry] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const fixedInventory = ["BUNDLE", "SERVICE", "DIGITAL", "KIT", "MATERIAL"].includes(type);

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const v = (k: string) => (String(f.get(k) ?? "").trim() || undefined);
    const num = (k: string) => (v(k) === undefined ? undefined : Number(v(k)));
    setBusy(true);
    setErr(null);
    try {
      const r = await api<Sku>("/skus", { method: "POST", idempotencyKey: key, body: {
        product_id: productId, sku_code: v("sku_code"), name: v("name"), sku_type: type, sellable: flags[0], purchasable: flags[1],
        inventory_managed: flags[2], barcode: v("barcode"), weight_g: v("weight_g"), lot_managed: lot, expiry_managed: expiry,
        min_remaining_shelf_days: expiry ? num("min_days") : undefined,
        option_value_ids: options.map((o) => String(f.get(`opt-${o.id}`) ?? "")).filter(Boolean) } });
      onCreated(r.data.warnings ?? []);
    } catch (x) {
      setErr(errorText(x));
    } finally {
      setBusy(false);
    }
  };
  const flag = (i: 0 | 1 | 2, label: string, disabled = false) => (
    <label><input type="checkbox" checked={flags[i]} disabled={disabled} onChange={(e) => setFlags((f) => { const n = [...f] as [boolean, boolean, boolean]; n[i] = e.target.checked; return n; })} /> {label}</label>
  );
  return (
    <Drawer title="SKU 추가" onClose={onClose} footer={<>
      <button className="btn" type="button" onClick={onClose}>취소</button>
      <button className="btn pri" type="submit" form="sku-create" disabled={busy}>{busy ? "추가 중…" : "추가"}</button></>}>
      <form id="sku-create" onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        <label className="fld">SKU 코드<input className="inp mono" name="sku_code" required maxLength={64} placeholder="AMP-30" /></label>
        <label className="fld">SKU 이름<input className="inp" name="name" maxLength={200} /></label>
        <label className="fld">유형<select className="inp" value={type} onChange={(e) => { setType(e.target.value); setFlags(TYPE_DEFAULTS[e.target.value]); if (!TYPE_DEFAULTS[e.target.value][2]) { setLot(false); setExpiry(false); } }}>
          {Object.entries(SKU_TYPE).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></label>
        {options.map((o) => (
          <label key={o.id} className="fld">{o.name}<select className="inp" name={`opt-${o.id}`} required defaultValue="">
            <option value="" disabled>선택</option>{o.values.map((v) => <option key={v.id} value={v.id}>{v.value}</option>)}</select></label>))}
        <fieldset className="card pad" style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          <legend className="ctx">속성(유형별 기본값, 바꿀 수 있음)</legend>
          {flag(0, "고객에게 판매", type === "MATERIAL")}
          {flag(1, "공급처에서 구매")}
          {flag(2, "재고 관리", fixedInventory)}
          <label><input type="checkbox" checked={lot} disabled={!flags[2]} onChange={(e) => { setLot(e.target.checked); if (!e.target.checked) setExpiry(false); }} /> LOT 관리</label>
          <label><input type="checkbox" checked={expiry} disabled={!lot} onChange={(e) => setExpiry(e.target.checked)} /> 유통기한 관리(선입선출 FEFO)</label>
          {expiry && <label className="fld">남은 유통기한 최소일<input className="inp" name="min_days" type="number" min={0} /></label>}
        </fieldset>
        <label className="fld">바코드·GTIN<input className="inp mono" name="barcode" maxLength={40} /></label>
        <label className="fld">중량(g)<input className="inp" name="weight_g" type="number" min={0} step="0.001" /></label>
        <p className="ctx" style={{ margin: 0 }}>SKU 코드와 유형은 등록 후 바꿀 수 없습니다. 가상묶음·서비스·디지털은 자체 재고가 없고, 실물세트·부자재는 재고관리 대상입니다.</p>
      </form>
    </Drawer>
  );
}
