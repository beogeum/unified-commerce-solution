"use client";
import { useState } from "react";
import { CustomerDrawer, CustomerStatus, NewCustomerModal } from "@/components/B2bParts";
import { DataTable } from "@/components/DataTable";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { qs } from "@/lib/api";
import { ArSummary, Customer } from "@/lib/b2b";
import { won } from "@/lib/finance";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

export default function B2bPage() {
  const { can, selected } = useSession();
  const allowed = can("order.read");
  const [tab, setTab] = useState<"customers" | "receivables">("customers");
  const [q, setQ] = useState("");
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  const [modal, setModal] = useState(false);
  const list = useApi<Customer[]>(allowed && tab === "customers" ? `/b2b-customers${qs({ business_entity_id: selected, q: q || undefined })}` : null);
  const ar = useApi<ArSummary[]>(allowed && tab === "receivables" && can("settlement.read")
    ? `/receivables${qs({ business_entity_id: selected, overdue_only: overdueOnly ? "true" : undefined })}` : null);
  if (!allowed) return <Forbidden />;
  return (
    <>
      <div className="page-head"><div><h1>B2B 거래처·외상</h1><p>사업자별 거래처와 단가표를 관리하고, 외상 주문의 미수·연체·수금을 봅니다. 외상은 출고할 때 그 출고의 매출(부가세 포함)만큼 청구되고, 반품 확정 시 차감됩니다.
        기한이 지난 미수가 있거나 한도를 넘으면 새 외상 주문을 받지 않습니다.</p></div>
        {can("customer.manage") && <button className="btn pri" type="button" onClick={() => setModal(true)}>거래처 등록</button>}</div>
      <nav className="row-gap" aria-label="B2B 메뉴">
        <button className="btn" type="button" aria-current={tab === "customers" ? "page" : undefined} onClick={() => setTab("customers")}>거래처</button>
        {can("settlement.read") && <button className="btn" type="button" aria-current={tab === "receivables" ? "page" : undefined} onClick={() => setTab("receivables")}>외상 현황</button>}
      </nav>
      {tab === "customers" && <>
        <div className="row-gap"><label className="fld">검색<input className="inp" value={q} onChange={(e) => setQ(e.target.value)} placeholder="거래처명·코드" aria-label="거래처 검색" /></label></div>
        {list.loading ? <Loading /> : list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : (
          <DataTable caption="거래처" rows={list.data ?? []} rowKey={(c) => c.id} onRowClick={(c) => setOpen(c.id)}
            empty={<Empty title="거래처가 없습니다." hint="거래처를 등록하면 B2B 주문에서 선택하고 단가표·외상을 쓸 수 있습니다." />} columns={[
              { key: "n", header: "거래처", render: (c) => <>{c.name} <span className="mono ctx">{c.code}</span></> },
              { key: "b", header: "사업자", render: (c) => c.business_entity_name },
              { key: "l", header: "외상 한도 · 기한", num: true, render: (c) => `${won(c.credit_limit)} · ${c.payment_terms_days}일` },
              { key: "p", header: "단가 SKU", num: true, render: (c) => c.price_count },
              { key: "s", header: "상태", render: (c) => <CustomerStatus s={c.status} /> },
            ]} />)}
      </>}
      {tab === "receivables" && <>
        <div className="row-gap"><label className="fld"><span><input type="checkbox" checked={overdueOnly} onChange={(e) => setOverdueOnly(e.target.checked)} /> 연체만</span></label></div>
        {ar.loading ? <Loading /> : ar.error ? <ErrorState error={ar.error} onRetry={ar.reload} /> : (
          <DataTable caption="외상 현황" rows={ar.data ?? []} rowKey={(r) => r.customer_id} onRowClick={(r) => setOpen(r.customer_id)}
            empty={<Empty title={overdueOnly ? "연체 거래처가 없습니다." : "외상 거래처가 없습니다."} />} columns={[
              { key: "n", header: "거래처", render: (r) => <>{r.name} <span className="ctx">{r.business_entity_name}</span></> },
              { key: "b", header: "미수 잔액", num: true, render: (r) => won(r.balance) },
              { key: "o", header: "연체", num: true, render: (r) => (Number(r.overdue) > 0 ? <b>{won(r.overdue)}</b> : "0") },
              { key: "e", header: "사용 중 · 한도 · 남은 한도", num: true, render: (r) => `${won(r.exposure)} · ${won(r.credit_limit)} · ${won(r.available)}` },
              { key: "d", header: "가장 오래된 기한", render: (r) => r.oldest_due_on ?? "—" },
              { key: "s", header: "상태", render: (r) => <CustomerStatus s={r.status} /> },
            ]} />)}
      </>}
      {open && <CustomerDrawer id={open} onClose={() => setOpen(null)} onChanged={() => { list.reload(); ar.reload(); }} />}
      {modal && <NewCustomerModal onClose={() => setModal(false)} onDone={(id) => { setModal(false); setTab("customers"); list.reload(); setOpen(id); }} />}
    </>
  );
}
