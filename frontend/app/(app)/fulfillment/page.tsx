"use client";
import { useApi } from "@/lib/useApi";
import { useEffect, useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { FulfillmentDrawer } from "@/components/FulfillmentDrawer";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { qs } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { DISPATCH_MODE_KO, FO_STATUS, FulfillmentOrder } from "@/lib/fulfillment";
import { useSession } from "@/lib/session";

export default function FulfillmentPage() {
  const { can, selected } = useSession();
  const [status, setStatus] = useState("");
  const base = useMemo(() => `/fulfillment-orders${qs({ business_entity_id: selected, status })}`, [selected, status]);
  const [open, setOpen] = useState<string | null>(null);
  useEffect(() => {
    const o = new URLSearchParams(window.location.search).get("open");
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 서버 렌더와 첫 화면을 같게 두고, 하이드레이션 후 브라우저 값(주소창·로컬 저장소)을 한 번 읽는다
    if (o) setOpen(o);
  }, []);
  const list = useApi<FulfillmentOrder[]>(can("order.read") ? base : null);
  const rows = list.data ?? [];
  const loading = list.loading;
  const error = list.error;
  if (!can("order.read")) return <Forbidden />;
  return (
    <>
      <div className="page-head"><div><h1>출고</h1><p>주문을 한 출고거점에 배정해 만든 출고지시입니다. 주문 전체를 채울 수 있는 거점 중 우선순위가 높은 곳에 배정하고(거점 분할 출고는 하지 않음),
        배정과 동시에 재고를 확보합니다. 자체 WMS 거점은 바로 작업지시로, 3PL 거점은 출고지시 전송이 확인된 연동이 있을 때만 API 로 보내고 아니면 수동 전달 업무를 만듭니다.</p></div></div>
      <div className="row-gap">
        <label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">전체</option>
          {Object.entries(FO_STATUS).map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={() => list.reload()} /> : (
        <DataTable caption="출고지시" rows={rows} rowKey={(r) => r.id} onRowClick={(r) => setOpen(r.id)}
          empty={<Empty title="출고지시가 없습니다." hint="결제 확인·출고 구성이 확정된 주문은 자동으로 출고 배정됩니다." />}
          columns={[
            { key: "no", header: "출고번호", render: (r) => <><b className="mono">{r.fo_no}</b><div className="ctx mono">주문 {r.order_no}</div></> },
            { key: "t", header: "배정 시각", render: (r) => <span className="ctx">{formatDateTime(r.created_at)}</span> },
            { key: "be", header: "사업자", render: (r) => r.business_entity_name },
            { key: "n", header: "출고거점", render: (r) => r.node_name },
            { key: "q", header: "SKU · 수량", render: (r) => r.lines.map((l) => `${l.sku_code} ×${l.quantity}`).join(", ") },
            { key: "m", header: "전달 방식", render: (r) => r.dispatch_mode ? DISPATCH_MODE_KO[r.dispatch_mode] : "—" },
            { key: "s", header: "상태", render: (r) => <Chip kind={FO_STATUS[r.status][0]}>{FO_STATUS[r.status][1]}</Chip> },
          ]} />
      )}
      {open && <FulfillmentDrawer id={open} onClose={() => setOpen(null)} onChanged={() => list.reload()} />}
    </>
  );
}
