"use client";
import { useApi } from "@/lib/useApi";
import { useMemo, useState } from "react";
import { DataTable } from "@/components/DataTable";
import { EntriesDrawer } from "@/components/FinanceEntries";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { qs } from "@/lib/api";
import { CATEGORY_KO, COST_ORDER, GROUP_KO, Profit, ProfitRow, won } from "@/lib/finance";
import { useSession } from "@/lib/session";

const today = () => new Date(Date.now() + 9 * 3600_000).toISOString().slice(0, 10);
const monthStart = () => today().slice(0, 8) + "01";

export default function ProfitPage() {
  const { can, selected } = useSession();
  const [from, setFrom] = useState(monthStart());
  const [to, setTo] = useState(today());
  const [group, setGroup] = useState("business_entity");
  const url = useMemo(() => `/finance/profit${qs({ business_entity_id: selected, from, to, group_by: group })}`, [selected, from, to, group]);
  const [drill, setDrill] = useState<ProfitRow | null>(null);
  const list = useApi<Profit>(can("profit.read") ? url : null);
  const data = list.data;
  const loading = list.loading;
  const error = list.error;
  if (!can("profit.read")) return <Forbidden />;
  const rows = data ? [...data.rows, data.total] : [];
  const usedCosts = COST_ORDER.filter((c) => rows.some((r) => Number(r.costs[c] ?? 0) !== 0));
  const hasFixed = rows.some((r) => Number(r.fixed_total ?? 0) !== 0);
  return (
    <>
      <div className="page-head"><div><h1>손익</h1><p>관리 공헌이익입니다(부가세 제외 공급가). 순매출 − 상품 원가 − 수수료·물류·사은품·반품 관련 비용 − 재고 손실 = 공헌이익. 여러 사업자·계정의 이익률은 이익 합계 ÷ 순매출 합계입니다.</p></div></div>
      {data && <div className="note n-info">{data.basis_note}</div>}
      <div className="row-gap">
        <label className="fld">시작일<input className="inp" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
        <label className="fld">종료일<input className="inp" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
        <label className="fld">묶음<select className="inp" value={group} onChange={(e) => setGroup(e.target.value)}>
          {Object.entries(GROUP_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={() => list.reload()} /> : (
        <DataTable caption="손익" rows={rows} rowKey={(r) => r.key} onRowClick={(r) => r.key !== "TOTAL" && setDrill(r)}
          empty={<Empty title="기간 안의 손익 원장이 없습니다." hint="출고 확정되면 매출·원가가 자동으로 기록됩니다." />}
          columns={[
            { key: "l", header: GROUP_KO[group], render: (r) => r.key === "TOTAL" ? <b>합계</b> : r.label },
            { key: "n", header: "순매출", num: true, render: (r) => won(r.net_sales) },
            { key: "o", header: "배송비·반품비 수익", num: true, render: (r) => won(r.other_revenue) },
            ...usedCosts.map((c) => ({ key: c, header: CATEGORY_KO[c], num: true, render: (r: ProfitRow) => won(r.costs[c] ?? 0) })),
            { key: "c", header: "공헌이익", num: true, render: (r) => <b>{won(r.contribution)}</b> },
            { key: "m", header: "이익률", num: true, render: (r) => (r.margin_pct === null ? "—" : `${r.margin_pct}%`) },
            ...(hasFixed ? [{ key: "fx", header: "고정비(배부 포함)", num: true, render: (r: ProfitRow) => won(r.fixed_total) },
              { key: "op", header: "추정 영업이익", num: true, render: (r: ProfitRow) => <span title="공헌이익 − 고정비. 고정비 배부 기준에 따라 달라지는 추정치입니다.">{won(r.operating_profit_estimate)}</span> }] : []),
            { key: "e", header: "예상 비용", num: true, render: (r) => <span className="ctx">{won(r.estimated_cost)}{r.missing_cost_lines > 0 ? ` · 원가 미등록 ${r.missing_cost_lines}` : ""}</span> },
          ]} />
      )}
      {data?.elimination && <div className="note n-info" role="note"><b>운영그룹 합계 내부거래 제거</b> · 내부 매출 −{won(data.elimination.internal_sales)}원 · 매출원가 조정 {won(data.elimination.cogs_adjustment)}원
        · 받은 사업자 보유 재고의 미실현 내부 이익 {won(data.elimination.unrealized_at_end)}원. {data.elimination.note}</div>}
      {drill && <EntriesDrawer title={`${GROUP_KO[group]} ${drill.label} 원장`} filter={group === "order" && drill.key !== "-" ? { order_id: drill.key }
        : group === "business_entity" ? { business_entity_id: drill.key } : { business_entity_id: selected ?? undefined }} from={from} to={to} onClose={() => setDrill(null)} />}
    </>
  );
}
