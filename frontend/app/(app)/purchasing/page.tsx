"use client";
import { useMemo, useState } from "react";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal } from "@/components/Overlay";
import { NewPoModal, PoDrawer, PoStatus, SupplierDrawer } from "@/components/PurchaseParts";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { won } from "@/lib/finance";
import { Node } from "@/lib/inventory";
import { Inbound, PO_STATUS, PurchaseOrder, Supplier } from "@/lib/procurement";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

export default function PurchasingPage() {
  const { can, selected, entities } = useSession();
  const allowed = can("inventory.read");
  const [tab, setTab] = useState<"orders" | "suppliers" | "inbound">("orders");
  const [status, setStatus] = useState("");
  const [openPo, setOpenPo] = useState<string | null>(null);
  const [openSup, setOpenSup] = useState<string | null>(null);
  const [modal, setModal] = useState<null | "po" | "supplier">(null);
  const ordersPath = useMemo(() => (allowed && tab === "orders" ? `/purchase-orders${qs({ business_entity_id: selected, status: status || undefined })}` : null),
    [allowed, tab, selected, status]);
  const orders = useApi<PurchaseOrder[]>(ordersPath);
  const suppliers = useApi<Supplier[]>(allowed ? `/suppliers${qs({ business_entity_id: selected })}` : null);
  const inbound = useApi<Inbound[]>(allowed && tab === "inbound" ? `/purchase-orders/inbound${qs({ business_entity_id: selected })}` : null);
  const nodes = useApi<Node[]>(allowed ? "/fulfillment-nodes" : null);
  if (!allowed) return <Forbidden />;
  const manage = can("purchase.manage");
  const beName = (id: string) => entities.find((e) => e.id === id)?.display_name ?? "—";
  const nodeName = (id: string) => nodes.data?.find((n) => n.id === id)?.name ?? "—";
  return (
    <>
      <div className="page-head"><div><h1>발주·매입</h1><p>사업자별 공급처·공급 SKU 로 발주를 만들고, 승인 후 입고합니다. 정상 입고만 판매가능 재고가 되고 실매입 단가가 원가에 반영됩니다. 입고예정은 판매가능 재고로 세지 않습니다.
        운임·관세는 비용·원가 &gt; 입고 부대비용에서 발주 단위로 배부합니다.</p></div>
        {manage && <span className="row-gap"><button className="btn" type="button" onClick={() => setModal("supplier")}>공급처 등록</button>
          <button className="btn pri" type="button" onClick={() => setModal("po")}>발주 작성</button></span>}</div>
      <nav className="row-gap" aria-label="발주 메뉴">
        <button className="btn" type="button" aria-current={tab === "orders" ? "page" : undefined} onClick={() => setTab("orders")}>발주</button>
        <button className="btn" type="button" aria-current={tab === "suppliers" ? "page" : undefined} onClick={() => setTab("suppliers")}>공급처</button>
        <button className="btn" type="button" aria-current={tab === "inbound" ? "page" : undefined} onClick={() => setTab("inbound")}>입고예정</button>
      </nav>
      {tab === "orders" && <>
        <div className="row-gap"><label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">전체</option>
          {Object.entries(PO_STATUS).map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label></div>
        {orders.loading ? <Loading /> : orders.error ? <ErrorState error={orders.error} onRetry={orders.reload} /> : (
          <DataTable caption="발주" rows={orders.data ?? []} rowKey={(p) => p.id} onRowClick={(p) => setOpenPo(p.id)}
            empty={<Empty title="발주가 없습니다." hint="공급처와 공급 SKU 를 등록한 뒤 발주를 작성하세요." />} columns={[
              { key: "n", header: "발주번호", render: (p) => <span className="mono">{p.po_no}</span> },
              { key: "s", header: "사업자 · 공급처", render: (p) => `${p.business_entity_name} · ${p.supplier_name}` },
              { key: "d", header: "거점 · 예정일", render: (p) => <span className="ctx">{p.node_name} · {p.expected_on ?? "—"}</span> },
              { key: "q", header: "수량(발주 · 입고)", num: true, render: (p) => `${p.ordered_qty} · ${p.received_qty}` },
              { key: "a", header: "공급가", num: true, render: (p) => (p.supply_total === null ? "—" : won(p.supply_total)) },
              { key: "t", header: "상태", render: (p) => <PoStatus s={p.status} /> },
            ]} />)}
      </>}
      {tab === "suppliers" && (suppliers.loading ? <Loading /> : suppliers.error ? <ErrorState error={suppliers.error} onRetry={suppliers.reload} /> : (
        <DataTable caption="공급처" rows={suppliers.data ?? []} rowKey={(s) => s.id} onRowClick={(s) => setOpenSup(s.id)} empty={<Empty title="공급처가 없습니다." />} columns={[
          { key: "n", header: "공급처", render: (s) => s.name },
          { key: "b", header: "사업자", render: (s) => s.business_entity_name },
          { key: "l", header: "리드타임", num: true, render: (s) => `${s.lead_time_days}일` },
          { key: "p", header: "결제조건", render: (s) => <span className="ctx">{s.payment_terms ?? ""}</span> },
          { key: "k", header: "공급 SKU", num: true, render: (s) => s.sku_count },
          { key: "s", header: "상태", render: (s) => (s.status === "ACTIVE" ? "사용" : "중지") },
        ]} />))}
      {tab === "inbound" && (inbound.loading ? <Loading /> : inbound.error ? <ErrorState error={inbound.error} onRetry={inbound.reload} /> : (
        <DataTable caption="입고예정" rows={inbound.data ?? []} rowKey={(r) => `${r.business_entity_id}-${r.sku_id}-${r.node_id}`} empty={<Empty title="입고예정이 없습니다." />} columns={[
          { key: "b", header: "사업자", render: (r) => beName(r.business_entity_id) },
          { key: "k", header: "SKU", render: (r) => <span className="mono">{r.sku_code}</span> },
          { key: "n", header: "거점", render: (r) => nodeName(r.node_id) },
          { key: "q", header: "입고예정 수량", num: true, render: (r) => r.inbound_qty },
          { key: "e", header: "가장 빠른 예정일", render: (r) => r.earliest_expected_on ?? "—" },
        ]} />))}
      {openPo && <PoDrawer id={openPo} onClose={() => setOpenPo(null)} onChanged={orders.reload} />}
      {openSup && <SupplierDrawer id={openSup} onClose={() => setOpenSup(null)} onChanged={suppliers.reload} />}
      {modal === "po" && <NewPoModal suppliers={suppliers.data ?? []} nodes={nodes.data ?? []} onClose={() => setModal(null)}
        onDone={(id) => { setModal(null); setTab("orders"); orders.reload(); setOpenPo(id); }} />}
      {modal === "supplier" && <NewSupplierModal onClose={() => setModal(null)} onDone={(id) => { setModal(null); setTab("suppliers"); suppliers.reload(); setOpenSup(id); }} />}
    </>
  );
}

function NewSupplierModal({ onClose, onDone }: { onClose: () => void; onDone: (id: string) => void }) {
  const { entities } = useSession();
  const [beSel, setBe] = useState("");
  const be = beSel || entities[0]?.id || "";
  const [name, setName] = useState("");
  const [lead, setLead] = useState(7);
  const [terms, setTerms] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const submit = async () => {
    try {
      const r = await api<Supplier>("/suppliers", { method: "POST", body: { business_entity_id: be, name, lead_time_days: lead, payment_terms: terms || undefined } });
      onDone(r.data.id);
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "등록하지 못했습니다.");
    }
  };
  return (
    <ConfirmModal title="공급처 등록" confirmLabel="등록" confirmDisabled={!be || !name.trim()} onClose={onClose} onConfirm={submit}
      impact={[["단위", "공급처는 사업자별입니다(같은 업체라도 사업자마다 따로 등록)"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">사업자<select className="inp" value={be} onChange={(e) => setBe(e.target.value)} aria-label="공급처 사업자">
        {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
      <label className="fld">공급처 이름<input className="inp" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} /></label>
      <label className="fld">리드타임(일)<input className="inp" type="number" min={0} max={365} value={lead} onChange={(e) => setLead(Math.max(0, Number(e.target.value) || 0))} /></label>
      <label className="fld">결제조건<input className="inp" value={terms} onChange={(e) => setTerms(e.target.value)} maxLength={200} /></label>
    </ConfirmModal>
  );
}
