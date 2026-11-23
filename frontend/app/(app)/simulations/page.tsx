"use client";
import { useState } from "react";
import { DataTable } from "@/components/DataTable";
import { SkuPicker } from "@/components/SkuPicker";
import { Empty, Forbidden } from "@/components/States";
import { api, ApiError } from "@/lib/api";
import { CATEGORY_KO, won } from "@/lib/finance";
import { useSession } from "@/lib/session";

type Side = { net_sales: string; other_revenue: string; costs: Record<string, string>; total_cost: string; contribution: string; margin_pct: string | null; units: string };
type Result = { from: string; to: string; base: Side; simulated: Side; contribution_delta: string; volume_factor: string; breakeven_volume_change_pct: string | null;
  assumptions: string[]; unconfirmed: string[] };

export default function SimulationsPage() {
  const { can, entities } = useSession();
  const [beSel, setBe] = useState("");
  const be = beSel || entities[0]?.id || "";
  const [sku, setSku] = useState<{ id: string; code: string } | null>(null);
  const [f, setF] = useState({ price_change_pct: 0, elasticity: -1.5, extra_demand_pct: 0, commission_rate_pp: 0, free_shipping: false, shipping_cost_change_pct: 0,
    stop_gifts: false, gift_cost_change_pct: 0 });
  const [res, setRes] = useState<Result | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  if (!can("profit.read")) return <Forbidden />;
  const body = () => ({ business_entity_id: be, sku_id: sku?.id, ...f });
  const run = async () => {
    setErr(null);
    setNotice(null);
    try {
      setRes((await api<Result>("/simulations/profit", { method: "POST", body: body() })).data);
    } catch (x) {
      setRes(null);
      setErr(x instanceof ApiError ? x.message : "계산하지 못했습니다.");
    }
  };
  const save = async () => {
    try {
      await api("/simulations/profit/work-item", { method: "POST", body: body() });
      setNotice("가정과 결과를 검토 업무로 남겼습니다. 업무함에서 결정하세요.");
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "저장하지 못했습니다.");
    }
  };
  const num = (k: keyof typeof f, label: string, step = 1) => (
    <label className="fld">{label}<input className="inp" type="number" step={step} value={f[k] as number} onChange={(e) => setF({ ...f, [k]: Number(e.target.value) || 0 })} /></label>);
  const cats = res ? Array.from(new Set([...Object.keys(res.base.costs), ...Object.keys(res.simulated.costs)])) : [];
  const rows = res ? [
    { k: "순매출", b: res.base.net_sales, s: res.simulated.net_sales },
    { k: "배송비·반품비 수익", b: res.base.other_revenue, s: res.simulated.other_revenue },
    ...cats.map((c) => ({ k: CATEGORY_KO[c] ?? c, b: res.base.costs[c] ?? "0", s: res.simulated.costs[c] ?? "0" })),
    { k: "공헌이익", b: res.base.contribution, s: res.simulated.contribution },
    { k: "이익률(%)", b: res.base.margin_pct ?? "—", s: res.simulated.margin_pct ?? "—" },
    { k: "판매 수량", b: res.base.units, s: res.simulated.units },
  ] : [];
  return (
    <>
      <div className="page-head"><div><h1>손익 시뮬레이션</h1><p>최근 손익 원장을 기준으로 가격·수수료·무료배송·배송 단가·사은품을 바꿨을 때 공헌이익이 어떻게 달라지는지 추정합니다. 아무것도 바뀌지 않으며,
        인과 효과가 아닌 단순 추정입니다. 결과는 검토 업무로 남겨 사람이 결정합니다.</p></div></div>
      <div className="row-gap">
        <label className="fld">사업자<select className="inp" value={be} onChange={(e) => setBe(e.target.value)} aria-label="시뮬레이션 사업자">
          {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
        {sku ? <span className="row-gap"><span className="mono">{sku.code}</span><button className="btn" type="button" onClick={() => setSku(null)}>SKU 전체로</button></span>
          : <SkuPicker label="SKU 범위(선택)" onPick={(s) => setSku({ id: s.id, code: s.sku_code })} />}
      </div>
      <div className="row-gap">
        {num("price_change_pct", "가격 변경(%)")}{num("elasticity", "수요 탄력성", 0.1)}{num("extra_demand_pct", "추가 수요(%)")}
        {num("commission_rate_pp", "수수료율 변경(%p)", 0.1)}{num("shipping_cost_change_pct", "배송 단가 변경(%)")}{num("gift_cost_change_pct", "사은품 원가 변경(%)")}
        <label><input type="checkbox" checked={f.free_shipping} onChange={(e) => setF({ ...f, free_shipping: e.target.checked })} /> 무료배송</label>
        <label><input type="checkbox" checked={f.stop_gifts} onChange={(e) => setF({ ...f, stop_gifts: e.target.checked })} /> 사은품 중단</label>
        <button className="btn pri" type="button" onClick={run} disabled={!be}>계산</button>
      </div>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {notice && <div className="note n-info" role="status">{notice}</div>}
      {res ? <>
        <div className="note n-info">기준 {res.from} ~ {res.to} · 판매량 ×{res.volume_factor} · 공헌이익 변화 <b>{won(res.contribution_delta)}원</b>
          {res.breakeven_volume_change_pct !== null && ` · 지금 공헌이익을 유지하려면 판매량 ${res.breakeven_volume_change_pct}% 변화 필요`}</div>
        <DataTable caption="시뮬레이션 결과" rows={rows} rowKey={(r) => r.k} empty={<Empty title="결과가 없습니다." />} columns={[
          { key: "k", header: "항목", render: (r) => r.k },
          { key: "b", header: "현재", num: true, render: (r) => (/^-?\d+$/.test(r.b) ? won(r.b) : r.b) },
          { key: "s", header: "시뮬레이션", num: true, render: (r) => (/^-?\d+$/.test(r.s) ? won(r.s) : r.s) },
        ]} />
        {res.unconfirmed.length > 0 && <div className="note n-warn"><b>미확인</b>{res.unconfirmed.map((u) => <div key={u}>· {u}</div>)}</div>}
        <div className="ctx">{res.assumptions.map((a) => <div key={a}>· {a}</div>)}</div>
        {can("work_item.update") && <button className="btn" type="button" onClick={save}>검토 업무로 남기기</button>}
      </> : <Empty title="가정을 입력하고 계산을 누르세요." hint="탄력성 −1.5 는 가격 10% 인하 시 판매량 15% 증가를 뜻합니다." />}
    </>
  );
}
