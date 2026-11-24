"use client";
import { useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { qs } from "@/lib/api";
import { won } from "@/lib/finance";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type InvRow = { business_entity_name: string; sku_id: string; sku_code: string; sku_name: string | null; sellable_on_hand: number; non_sellable_on_hand: number;
  committed: number; atp: number; sold28d: number; avg_daily_sales: string; days_of_cover: string | null; last_sale_at: string | null; nearest_expiry: string | null;
  book_value: string | null; stockout_risk: boolean; slow_moving: boolean; expiry_risk: boolean; business_entity_id: string };
type Inv = { as_of: string; cover_days: number; slow_days: number; expiry_days: number; rows: InvRow[]; totals: Record<string, string | number> };
type SalesRow = { key: string; label: string; orders: number; original_units: number; canceled_units: number; shipped_units: number; returned_units: number;
  exchange_units: number; net_units: number; order_amount: string; cancel_rate_pct: string | null; return_rate_pct: string | null; exchange_rate_pct: string | null };
type Sales = { rows: SalesRow[]; total: SalesRow; note: string };
type LogRow = { node_id: string; node_code: string; node_name: string; fulfillment_orders: number; parcels: number; orders: number; split_orders: number;
  split_rate_pct: string | null; avg_lead_hours: string | null; sla_hours: number | null; on_time: number | null; on_time_rate_pct: string | null;
  shipping_cost: string | null; shipping_cost_per_parcel: string | null; estimated_share: string | null };
type Logi = { cost_visible: boolean; rows: LogRow[] };

const today = () => new Date(Date.now() + 9 * 3600_000).toISOString().slice(0, 10);
const pct = (v: string | null) => (v === null ? "계산불가" : `${v}%`);

export default function ReportsPage() {
  const { can, selected } = useSession();
  const [tab, setTab] = useState<"inventory" | "sales" | "logistics">(can("inventory.read") ? "inventory" : "sales");
  const [from, setFrom] = useState(today().slice(0, 8) + "01");
  const [to, setTo] = useState(today());
  const [group, setGroup] = useState("channel_account");
  const [cover, setCover] = useState(14);
  const [onlyRisk, setOnlyRisk] = useState(false);
  const invPath = useMemo(() => (tab === "inventory" && can("inventory.read") ? `/analytics/inventory${qs({ business_entity_id: selected, cover_days: String(cover) })}` : null),
    [tab, selected, cover, can]);
  const salesPath = useMemo(() => (tab === "sales" && can("order.read") ? `/analytics/sales${qs({ business_entity_id: selected, from, to, group_by: group })}` : null),
    [tab, selected, from, to, group, can]);
  const logPath = useMemo(() => (tab === "logistics" && can("order.read") ? `/analytics/logistics${qs({ business_entity_id: selected, from, to })}` : null),
    [tab, selected, from, to, can]);
  const inv = useApi<Inv>(invPath);
  const sales = useApi<Sales>(salesPath);
  const logi = useApi<Logi>(logPath);
  if (!can("order.read") && !can("inventory.read")) return <Forbidden />;
  const invRows = (inv.data?.rows ?? []).filter((r) => !onlyRisk || r.stockout_risk || r.slow_moving || r.expiry_risk);
  return (
    <>
      <div className="page-head"><div><h1>운영 리포트</h1><p>재고·판매·물류 지표를 사업자·판매계정·거점 접근 범위 안에서 계산합니다. 분모를 함께 표시하고, 계산할 수 없는 값은 &apos;계산불가&apos;로 둡니다.</p></div></div>
      <nav className="row-gap" aria-label="리포트 메뉴">
        {can("inventory.read") && <button className="btn" type="button" aria-current={tab === "inventory" ? "page" : undefined} onClick={() => setTab("inventory")}>재고</button>}
        {can("order.read") && <button className="btn" type="button" aria-current={tab === "sales" ? "page" : undefined} onClick={() => setTab("sales")}>판매</button>}
        {can("order.read") && <button className="btn" type="button" aria-current={tab === "logistics" ? "page" : undefined} onClick={() => setTab("logistics")}>물류</button>}
      </nav>
      {tab !== "inventory" && <div className="row-gap">
        <label className="fld">시작일<input className="inp" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
        <label className="fld">종료일<input className="inp" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
        {tab === "sales" && <label className="fld">묶음<select className="inp" value={group} onChange={(e) => setGroup(e.target.value)}>
          <option value="channel_account">판매계정</option><option value="business_entity">사업자</option><option value="sku">SKU</option></select></label>}
      </div>}
      {tab === "inventory" && <>
        <div className="row-gap">
          <label className="fld">품절 위험 기준(재고일수)<input className="inp" type="number" min={1} max={120} value={cover} onChange={(e) => setCover(Math.max(1, Number(e.target.value) || 14))} /></label>
          <label><input type="checkbox" checked={onlyRisk} onChange={(e) => setOnlyRisk(e.target.checked)} /> 위험 항목만</label>
        </div>
        {inv.loading ? <Loading /> : inv.error ? <ErrorState error={inv.error} onRetry={inv.reload} /> : inv.data && <>
          <div className="note n-info">기준일 {inv.data.as_of} · 장부가 합계 {won(String(inv.data.totals.book_value ?? 0))}원 · 품절 위험 {inv.data.totals.stockout_risk ?? 0} · {inv.data.slow_days}일 미판매 {inv.data.totals.slow_moving ?? 0}
            · 유통기한 {inv.data.expiry_days}일 이내 {inv.data.totals.expiry_risk ?? 0}. {String(inv.data.totals.book_value_note ?? "")}</div>
          <DataTable caption="재고 리포트" rows={invRows} rowKey={(r) => `${r.business_entity_id}-${r.sku_id}`} empty={<Empty title="재고가 없습니다." />} columns={[
            { key: "s", header: "사업자 · SKU", render: (r) => <>{r.business_entity_name} <span className="mono">{r.sku_code}</span></> },
            { key: "a", header: "판매가능 · 확보 · 가용", num: true, render: (r) => `${r.sellable_on_hand} · ${r.committed} · ${r.atp}` },
            { key: "n", header: "비가용", num: true, render: (r) => r.non_sellable_on_hand },
            { key: "d", header: "28일 판매 · 일평균", num: true, render: (r) => `${r.sold28d} · ${r.avg_daily_sales}` },
            { key: "c", header: "재고일수", num: true, render: (r) => r.days_of_cover ?? "계산불가" },
            { key: "e", header: "가까운 유통기한", render: (r) => r.nearest_expiry ?? "—" },
            { key: "b", header: "장부가", num: true, render: (r) => (r.book_value === null ? "원가 미등록" : won(r.book_value)) },
            { key: "f", header: "표시", render: (r) => <span className="row-gap">{r.stockout_risk && <Chip kind="err">품절 위험</Chip>}{r.slow_moving && <Chip kind="warn">장기 미판매</Chip>}
              {r.expiry_risk && <Chip kind="warn">유통기한 임박</Chip>}{r.last_sale_at ? <span className="ctx">마지막 출고 {formatDateTime(r.last_sale_at)}</span> : null}</span> },
          ]} />
        </>}
      </>}
      {tab === "sales" && (sales.loading ? <Loading /> : sales.error ? <ErrorState error={sales.error} onRetry={sales.reload} /> : sales.data && <>
        <div className="note n-info">{sales.data.note}</div>
        <DataTable caption="판매 KPI" rows={[...sales.data.rows, sales.data.total]} rowKey={(r) => r.key} empty={<Empty title="기간 안의 주문이 없습니다." />} columns={[
          { key: "l", header: "구분", render: (r) => (r.key === "TOTAL" ? <b>합계</b> : r.label) },
          { key: "o", header: "주문", num: true, render: (r) => r.orders },
          { key: "u", header: "주문 수량", num: true, render: (r) => r.original_units },
          { key: "c", header: "취소(율)", num: true, render: (r) => `${r.canceled_units} (${pct(r.cancel_rate_pct)})` },
          { key: "s", header: "출고", num: true, render: (r) => r.shipped_units },
          { key: "r", header: "반품(율)", num: true, render: (r) => `${r.returned_units} (${pct(r.return_rate_pct)})` },
          { key: "x", header: "교환(율)", num: true, render: (r) => `${r.exchange_units} (${pct(r.exchange_rate_pct)})` },
          { key: "n", header: "순판매수량", num: true, render: (r) => <b>{r.net_units}</b> },
          { key: "a", header: "주문금액", num: true, render: (r) => won(r.order_amount) },
        ]} />
      </>)}
      {tab === "logistics" && (logi.loading ? <Loading /> : logi.error ? <ErrorState error={logi.error} onRetry={logi.reload} /> : logi.data && <>
        {!logi.data.cost_visible && <div className="note n-info">원가·비용 조회 권한이 없어 물류비는 표시하지 않습니다.</div>}
        <DataTable caption="물류 리포트" rows={logi.data.rows} rowKey={(r) => r.node_id} empty={<Empty title="기간 안에 출고 확정된 건이 없습니다." />} columns={[
          { key: "n", header: "거점", render: (r) => <>{r.node_name} <span className="ctx mono">{r.node_code}</span></> },
          { key: "f", header: "출고지시 · 송장", num: true, render: (r) => `${r.fulfillment_orders} · ${r.parcels}` },
          { key: "s", header: "분할출고 주문(율)", num: true, render: (r) => `${r.split_orders}/${r.orders} (${pct(r.split_rate_pct)})` },
          { key: "l", header: "평균 리드타임(시간)", num: true, render: (r) => r.avg_lead_hours ?? "계산불가" },
          { key: "t", header: "SLA 준수", num: true, render: (r) => (r.sla_hours === null ? "SLA 미설정" : `${r.on_time}/${r.fulfillment_orders} (${pct(r.on_time_rate_pct)}, ${r.sla_hours}시간)`) },
          ...(logi.data.cost_visible ? [
            { key: "c", header: "출고 배송비", num: true, render: (r: LogRow) => won(r.shipping_cost) },
            { key: "p", header: "송장당", num: true, render: (r: LogRow) => won(r.shipping_cost_per_parcel) },
            { key: "e", header: "예상 비중", num: true, render: (r: LogRow) => (r.estimated_share === null ? "—" : `${r.estimated_share}%`) },
          ] : []),
        ]} />
      </>)}
    </>
  );
}
