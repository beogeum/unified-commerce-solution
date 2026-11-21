"use client";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { CatalogTabs } from "@/components/CatalogTabs";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ExportButton } from "@/components/ExportButton";
import { Empty, ErrorState, Loading } from "@/components/States";
import { qs } from "@/lib/api";
import { SKU_TYPE, Sku } from "@/lib/catalog";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

export default function SkusPage() {
  const { can } = useSession();
  const [q, setQ] = useState("");
  const [type, setType] = useState("");
  const [status, setStatus] = useState("ACTIVE");
  const path = useMemo(() => `/skus${qs({ q, sku_type: type, status, limit: "200" })}`, [q, type, status]);
  const { data, error, loading, reload } = useApi<Sku[]>(path);
  const router = useRouter();
  return (
    <>
      <div className="page-head">
        <div><h1>SKU</h1><p>재고·원가의 기준 단위입니다. SKU 추가·변경은 상품 상세에서 합니다.</p></div>
        {can("export.create") && <ExportButton exportType="SKUS" businessEntityIds={[]} />}
      </div>
      <CatalogTabs active="skus" />
      <div className="row-gap" role="search">
        <label className="fld" style={{ minWidth: 240 }}>검색<input className="inp" value={q} onChange={(e) => setQ(e.target.value)} placeholder="SKU 코드, 이름, 바코드" /></label>
        <label className="fld">유형<select className="inp" value={type} onChange={(e) => setType(e.target.value)}>
          <option value="">전체</option>{Object.entries(SKU_TYPE).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></label>
        <label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">전체</option><option value="ACTIVE">운영</option><option value="INACTIVE">중지</option><option value="ARCHIVED">보관</option></select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="SKU 목록" rows={data ?? []} rowKey={(r) => r.id} onRowClick={(r) => router.push(`/products/${r.product_id}`)}
          empty={<Empty title="조건에 맞는 SKU 가 없습니다." />}
          columns={[
            { key: "code", header: "SKU 코드", render: (r) => <><b className="mono">{r.sku_code}</b><div className="ctx">{r.name ?? ""}</div></> },
            { key: "product", header: "상품", render: (r) => <>{r.product_name}<div className="ctx mono">{r.product_code}</div></> },
            { key: "opt", header: "옵션", render: (r) => r.options.map((o) => o.value).join(" / ") || "—" },
            { key: "type", header: "유형", render: (r) => SKU_TYPE[r.sku_type] ?? r.sku_type },
            { key: "flags", header: "속성", render: (r) => <span className="row-gap">{r.sellable && <Chip kind="ok">판매</Chip>}{r.inventory_managed && <Chip kind="info">재고</Chip>}{r.expiry_managed && <Chip kind="warn">유통기한</Chip>}</span> },
            { key: "bc", header: "바코드", render: (r) => <span className="mono">{r.barcode ?? "—"}</span> },
          ]} />
      )}
    </>
  );
}
