"use client";
import { useMemo, useState } from "react";
import { InventoryTabs } from "@/components/InventoryTabs";
import { ErrorState, Forbidden, Loading } from "@/components/States";
import { qs } from "@/lib/api";
import { Node, Txn, TXN_KO } from "@/lib/inventory";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";
import { TxnList } from "@/components/TxnList";

export default function LedgerPage() {
  const { can, selected } = useSession();
  const [node, setNode] = useState("");
  const [type, setType] = useState("");
  const [q, setQ] = useState("");
  const nodes = useApi<Node[]>(can("logistics_account.read") ? "/fulfillment-nodes" : null);
  const base = useMemo(() => `/inventory/transactions${qs({ business_entity_id: selected, node_id: node, txn_type: type, q, limit: "50" })}`, [selected, node, type, q]);
  const list = useApi<Txn[]>(can("inventory.read") ? base : null);
  const rows = list.data ?? [];
  const loading = list.loading;
  const error = list.error;
  const cursor = list.nextCursor;
  const more = list.more;
  if (!can("inventory.read")) return <Forbidden />;
  return (
    <>
      <div className="page-head"><div><h1>재고원장</h1><p>모든 재고 변화(입고·품질 변경·예약·할당·피킹·출고·해제)가 순서대로 남습니다. 원장은 고치거나 지울 수 없고, 정정은 새 기록으로만 합니다.</p></div></div>
      <InventoryTabs active="ledger" />
      <div className="row-gap">
        <label className="fld">검색<input className="inp" value={q} onChange={(e) => setQ(e.target.value)} placeholder="SKU 코드·상품명" /></label>
        <label className="fld">출고거점<select className="inp" value={node} onChange={(e) => setNode(e.target.value)}><option value="">전체</option>
          {(nodes.data ?? []).map((n) => <option key={n.id} value={n.id}>{n.name}</option>)}</select></label>
        <label className="fld">구분<select className="inp" value={type} onChange={(e) => setType(e.target.value)}><option value="">전체</option>
          {Object.entries(TXN_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={() => list.reload()} /> : (
        <>
          <TxnList rows={rows} />
          {cursor && <button className="btn" type="button" onClick={more}>더 보기</button>}
        </>
      )}
      <p className="ctx">실물 = 버킷 수량 변화, 판매가능 = 정상 재고 변화, 확보 = 예약·할당·피킹 수량 변화. 할당·피킹은 단계만 바뀌므로 수량 변화가 0입니다.</p>
    </>
  );
}
