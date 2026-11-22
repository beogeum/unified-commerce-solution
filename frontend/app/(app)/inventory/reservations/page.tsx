"use client";
import { useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { InventoryTabs } from "@/components/InventoryTabs";
import { ConfirmModal } from "@/components/Overlay";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { num, Reservation, RES_STATE } from "@/lib/inventory";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Action = "allocate" | "pick" | "consume" | "release";
const NEXT: Record<string, [Action, string][]> = {
  RESERVED: [["allocate", "할당"], ["release", "해제"]],
  ALLOCATED: [["pick", "피킹 시작"], ["release", "해제"]],
  PICKING: [["consume", "출고 확정"], ["release", "작업 복귀·해제"]],
};
const IMPACT: Record<Action, string> = {
  allocate: "단계만 바뀝니다. 판매 가능 재고(ATP) 변화 없음.",
  pick: "단계만 바뀝니다. 판매 가능 재고(ATP) 변화 없음.",
  consume: "실물 재고 −수량(유통기한 빠른 LOT 부터), 확보 −수량. ATP 변화 없음.",
  release: "확보 −수량, 판매 가능 재고(ATP) +수량.",
};

export default function ReservationsPage() {
  const { can, selected } = useSession();
  const [state, setState] = useState("ACTIVE");
  const [ref, setRef] = useState("");
  const path = useMemo(() => `/inventory/reservations${qs({ business_entity_id: selected, state, demand_ref: ref })}`, [selected, state, ref]);
  const { data, error, loading, reload } = useApi<Reservation[]>(can("inventory.read") ? path : null);
  const [pending, setPending] = useState<{ r: Reservation; action: Action; label: string } | null>(null);
  if (!can("inventory.read")) return <Forbidden />;
  return (
    <>
      <div className="page-head"><div><h1>예약·할당</h1><p>주문 등 수요별 재고 확보입니다. 한 확보는 한 시점에 한 단계(예약 → 할당 → 피킹 → 출고 확정)만 가지며, 여러 SKU 를 함께 확보할 때는 전부 확보하거나 전부 실패합니다.</p></div></div>
      <InventoryTabs active="reservations" />
      <div className="row-gap">
        <label className="fld">단계<select className="inp" value={state} onChange={(e) => setState(e.target.value)}>
          <option value="ACTIVE">진행 중(예약·할당·피킹)</option><option value="">전체</option>
          {Object.entries(RES_STATE).map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label>
        <label className="fld">수요 참조<input className="inp mono" value={ref} onChange={(e) => setRef(e.target.value)} placeholder="정확히 일치" /></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="예약·할당" rows={data ?? []} rowKey={(r) => r.id}
          empty={<Empty title="확보 내역이 없습니다." hint="주문 수집(M4)이 열리면 주문상품별 확보가 여기에 쌓입니다." />}
          columns={[
            { key: "d", header: "수요", render: (r) => <><span className="ctx">{r.demand_type}</span> <span className="mono">{r.demand_ref}</span></> },
            { key: "s", header: "사업자 · SKU", render: (r) => <>{r.business_entity_name} · <span className="mono">{r.sku_code}</span></> },
            { key: "n", header: "거점", render: (r) => r.node_code },
            { key: "q", header: "수량", num: true, render: (r) => num(r.quantity) },
            { key: "st", header: "단계", render: (r) => <Chip kind={RES_STATE[r.state][0]}>{RES_STATE[r.state][1]}</Chip> },
            { key: "e", header: "만료", render: (r) => <span className="ctx">{r.expires_at ? formatDateTime(r.expires_at) : "—"}</span> },
            { key: "u", header: "변경", render: (r) => <span className="ctx">{formatDateTime(r.updated_at)}</span> },
            { key: "a", header: "처리", render: (r) => can("inventory.operate") ? <span className="row-gap" style={{ gap: 4 }}>
              {(NEXT[r.state] ?? []).map(([a, label]) => <button key={a} className={`btn ${a === "release" ? "" : "pri"}`} type="button"
                onClick={() => setPending({ r, action: a, label })}>{label}</button>)}</span> : null },
          ]} />
      )}
      {pending && <ActionModal {...pending} onClose={() => setPending(null)} onDone={() => { setPending(null); reload(); }} />}
    </>
  );
}

function ActionModal({ r, action, label, onClose, onDone }: { r: Reservation; action: Action; label: string; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [key] = useState(newIdempotencyKey);
  const needsReturn = action === "release" && r.state === "PICKING";
  const run = async () => {
    setBusy(true);
    setErr(null);
    try {
      await api(`/inventory/reservations/${r.id}/${action}`, { method: "POST", idempotencyKey: key, body: { reason: reason || undefined, pick_return_confirmed: needsReturn ? confirmed : undefined } });
      onDone();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "처리하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <ConfirmModal title={`${label} — ${r.sku_code} ${num(r.quantity)}개`} confirmLabel={label} danger={action === "release"} busy={busy} onClose={onClose} onConfirm={run}
      confirmDisabled={needsReturn && !confirmed}
      impact={[["수요", `${r.demand_type} ${r.demand_ref}`], ["현재 단계", RES_STATE[r.state][1]], ["재고 영향", IMPACT[action]]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {needsReturn && <label><input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} /> 피킹 작업이 중지되고 물건이 제자리로 돌아온 것을 확인했습니다</label>}
      <label className="fld">사유(선택)<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
    </ConfirmModal>
  );
}
