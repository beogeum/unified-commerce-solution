import { DataTable } from "@/components/DataTable";
import { Empty } from "@/components/States";
import { formatDateTime } from "@/lib/format";
import { QUALITY_KO, signed, Txn, TXN_KO } from "@/lib/inventory";

export function TxnList({ rows }: { rows: Txn[] }) {
  return (
    <DataTable caption="재고원장" rows={rows} rowKey={(t) => t.id} empty={<Empty title="원장 기록이 없습니다." />}
      columns={[
        { key: "t", header: "시각", render: (t) => <span className="ctx">{formatDateTime(t.occurred_at)}</span> },
        { key: "ty", header: "구분", render: (t) => <>{TXN_KO[t.txn_type] ?? t.txn_type}{t.reservation_state_to ? <span className="ctx"> {t.reservation_state_from ?? "—"}→{t.reservation_state_to}</span> : null}</> },
        { key: "q", header: "품질", render: (t) => t.quality_status ? QUALITY_KO[t.quality_status] : "—" },
        { key: "oh", header: "실물", num: true, render: (t) => signed(t.on_hand_delta) },
        { key: "sd", header: "판매가능", num: true, render: (t) => signed(t.sellable_delta) },
        { key: "cd", header: "확보", num: true, render: (t) => signed(t.committed_delta) },
        { key: "r", header: "참조 · 사유", render: (t) => <span className="ctx">{[t.reference_type && `${t.reference_type} ${t.reference_id ?? ""}`, t.reason].filter(Boolean).join(" · ") || "—"}</span> },
        { key: "a", header: "처리자", render: (t) => t.actor_name ?? <span className="ctx">시스템</span> },
      ]} />
  );
}
