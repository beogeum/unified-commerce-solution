"use client";
import { useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { qs } from "@/lib/api";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Issue = { code: string; severity: "HIGH" | "MEDIUM" | "LOW"; title: string; business_entity_id: string | null; business_entity_name: string | null; sku_id: string | null;
  sku_code: string | null; listing_id: string | null; detail: string | null; fix: string };
type Report = { counts: Record<string, number>; issues: Issue[] };
const SEV: Record<string, ["err" | "warn" | "info", string]> = { HIGH: ["err", "높음"], MEDIUM: ["warn", "보통"], LOW: ["info", "낮음"] };
const CODE_KO: Record<string, string> = { SKU_NO_COST: "원가 미등록", LOT_STOCK_WITHOUT_LOT: "LOT 없는 재고", INACTIVE_SKU_WITH_STOCK: "중지 SKU 재고",
  UNMAPPED_LISTING: "SKU 미연결 채널상품", SKU_NO_BARCODE: "바코드 없음", STOCK_NOT_LISTED: "채널 미연결 재고", SELLING_WITHOUT_SUPPLIER: "공급처 없음" };

export default function CatalogQualityPage() {
  const { can, selected } = useSession();
  const [code, setCode] = useState("");
  const allowed = can("catalog.read");
  const path = useMemo(() => (allowed ? `/catalog/quality${qs({ business_entity_id: selected })}` : null), [allowed, selected]);
  const { data, error, loading, reload } = useApi<Report>(path);
  if (!allowed) return <Forbidden />;
  const rows = (data?.issues ?? []).filter((i) => !code || i.code === code);
  return (
    <>
      <div className="page-head"><div><h1>상품정보 품질 점검</h1><p>원가·LOT·바코드·채널상품 연결·공급처 누락처럼 운영과 손익을 틀리게 만드는 상품 데이터를 찾아 줍니다. 점검만 하며, 고치는 것은 각 화면에서 합니다.
        원가 점검은 원가 조회 권한이 있을 때만 보입니다.</p></div></div>
      {data && <div className="row-gap" role="group" aria-label="점검 요약">
        <button className="btn" type="button" aria-pressed={!code} onClick={() => setCode("")}>전체 {data.issues.length}</button>
        {Object.entries(data.counts).filter(([, n]) => n > 0).map(([k, n]) => (
          <button key={k} className="btn" type="button" aria-pressed={code === k} onClick={() => setCode(k)}>{CODE_KO[k] ?? k} {n}</button>))}
      </div>}
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="품질 점검 결과" rows={rows} rowKey={(i) => `${i.code}-${i.business_entity_id}-${i.sku_id}-${i.listing_id}`}
          empty={<Empty title="발견된 문제가 없습니다." />} columns={[
            { key: "v", header: "중요도", render: (i) => <Chip kind={SEV[i.severity][0]}>{SEV[i.severity][1]}</Chip> },
            { key: "t", header: "문제", render: (i) => <><b>{i.title}</b>{i.detail ? <div className="ctx">{i.detail}</div> : null}</> },
            { key: "b", header: "사업자", render: (i) => i.business_entity_name ?? "공통" },
            { key: "k", header: "SKU", render: (i) => <span className="mono">{i.sku_code ?? "—"}</span> },
            { key: "f", header: "조치", render: (i) => <span className="ctx">{i.fix}</span> },
          ]} />)}
    </>
  );
}
