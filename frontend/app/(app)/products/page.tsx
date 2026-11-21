"use client";
import { useRouter } from "next/navigation";
import { FormEvent, useMemo, useState } from "react";
import { CatalogTabs } from "@/components/CatalogTabs";
import { DataTable } from "@/components/DataTable";
import { Drawer } from "@/components/Overlay";
import { ProductStatusChip } from "@/components/ProductStatus";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { Brand, Category, PRODUCT_TAX, Product, ProductDetail } from "@/lib/catalog";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

export default function ProductsPage() {
  const { can } = useSession();
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const [brand, setBrand] = useState("");
  const brands = useApi<Brand[]>("/brands");
  const path = useMemo(() => `/products${qs({ q, status, brand_id: brand, limit: "100" })}`, [q, status, brand]);
  const { data, error, loading, reload } = useApi<Product[]>(path);
  const [creating, setCreating] = useState(false);
  const router = useRouter();
  return (
    <>
      <div className="page-head">
        <div><h1>상품마스터</h1><p>상품·SKU 는 운영그룹 공통입니다. 사업자별 원가·판매가·재고는 사업자 SKU 정책과 재고에서 따로 관리합니다.</p></div>
        {can("catalog.manage") && <button className="btn pri" type="button" onClick={() => setCreating(true)}>상품 등록</button>}
      </div>
      <CatalogTabs active="products" />
      <div className="row-gap" role="search">
        <label className="fld" style={{ minWidth: 240 }}>검색<input className="inp" value={q} onChange={(e) => setQ(e.target.value)} placeholder="상품명, 상품코드, SKU 코드, 바코드" /></label>
        <label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">전체</option><option value="DRAFT">초안</option><option value="ACTIVE">판매중</option><option value="INACTIVE">판매중지</option><option value="ARCHIVED">보관</option></select></label>
        <label className="fld">브랜드<select className="inp" value={brand} onChange={(e) => setBrand(e.target.value)}>
          <option value="">전체</option>{(brands.data ?? []).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="상품 목록" rows={data ?? []} rowKey={(r) => r.id} onRowClick={(r) => router.push(`/products/${r.id}`)}
          empty={<Empty title={q || status || brand ? "조건에 맞는 상품이 없습니다." : "아직 등록된 상품이 없습니다."} hint="상품을 등록한 뒤 옵션과 SKU 를 추가하세요." />}
          columns={[
            { key: "name", header: "상품", render: (r) => <><b>{r.name}</b><div className="ctx mono">{r.product_code}</div></> },
            { key: "brand", header: "브랜드", render: (r) => r.brand_name ?? <span className="ctx">미지정</span> },
            { key: "cat", header: "카테고리", render: (r) => r.category_name ?? <span className="ctx">미지정</span> },
            { key: "tax", header: "과세", render: (r) => (r.tax_type ? PRODUCT_TAX[r.tax_type] : <span className="ctx">미지정</span>) },
            { key: "sku", header: "SKU", num: true, render: (r) => `${r.active_sku_count} / ${r.sku_count}` },
            { key: "status", header: "상태", render: (r) => <ProductStatusChip status={r.status} /> },
            { key: "updated", header: "수정", render: (r) => <span className="ctx">{formatDateTime(r.updated_at)}</span> },
          ]} />
      )}
      {creating && <CreateProduct brands={brands.data ?? []} onClose={() => setCreating(false)} onCreated={(id) => router.push(`/products/${id}`)} />}
    </>
  );
}

/** 옵션 입력: 한 줄에 "옵션명: 값1, 값2" */
function parseOptions(text: string) {
  return text.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => {
    const [name, rest = ""] = l.split(/[:：]/, 2);
    return { name: name.trim(), values: rest.split(",").map((v) => v.trim()).filter(Boolean) };
  });
}

function CreateProduct({ brands, onClose, onCreated }: { brands: Brand[]; onClose: () => void; onCreated: (id: string) => void }) {
  const categories = useApi<Category[]>("/categories");
  const [key] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const val = (k: string) => (String(f.get(k) ?? "").trim() || undefined);
    setBusy(true);
    setErr(null);
    try {
      const r = await api<ProductDetail>("/products", { method: "POST", idempotencyKey: key, body: {
        product_code: val("product_code"), name: val("name"), brand_id: val("brand_id"), category_id: val("category_id"),
        tax_type: val("tax_type"), options: parseOptions(String(f.get("options") ?? "")) } });
      onCreated(r.data.product.id);
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "등록하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <Drawer title="상품 등록" onClose={onClose} footer={<>
      <button className="btn" type="button" onClick={onClose}>취소</button>
      <button className="btn pri" type="submit" form="product-create" disabled={busy}>{busy ? "등록 중…" : "초안으로 등록"}</button></>}>
      <form id="product-create" onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        <label className="fld">상품코드<input className="inp mono" name="product_code" required maxLength={64} placeholder="AMP" /></label>
        <label className="fld">상품명<input className="inp" name="name" required maxLength={200} /></label>
        <label className="fld">브랜드<select className="inp" name="brand_id" defaultValue=""><option value="">나중에 지정</option>
          {brands.filter((b) => b.status === "ACTIVE").map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</select></label>
        <label className="fld">카테고리<select className="inp" name="category_id" defaultValue=""><option value="">나중에 지정</option>
          {(categories.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.path}</option>)}</select></label>
        <label className="fld">과세유형<select className="inp" name="tax_type" defaultValue=""><option value="">나중에 지정</option>
          {Object.entries(PRODUCT_TAX).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
        <label className="fld">옵션(한 줄에 하나, 예: 색상: 그레이, 베이지)<textarea className="inp" name="options" rows={3} style={{ padding: "8px 10px" }} /></label>
        <p className="ctx" style={{ margin: 0 }}>초안은 정보가 덜 채워져도 저장됩니다. 판매중으로 바꾸려면 브랜드·카테고리·과세유형과 판매 가능한 SKU 가 필요합니다. 상품코드는 등록 후 바꿀 수 없습니다.</p>
      </form>
    </Drawer>
  );
}
