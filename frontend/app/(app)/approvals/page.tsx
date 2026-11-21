"use client";
import { useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { formatDateTime, formatWon } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Approval = { id: string; request_type: string; status: string; reason: string; impact: Record<string, string | null>;
  requested_by: string; requested_by_name: string; requested_at: string; decided_by_name: string | null; decision_note: string | null };
const TYPE_KO: Record<string, string> = { SKU_MIN_PRICE_CHANGE: "최저판매가 변경" };
const STATUS: Record<string, ["warn" | "ok" | "err" | "mute", string]> = {
  PENDING: ["warn", "대기"], APPROVED: ["ok", "승인"], REJECTED: ["err", "반려"], CANCELLED: ["mute", "취소"],
};

function impactRows(a: Approval): [string, string][] {
  const i = a.impact ?? {};
  if (a.request_type === "SKU_MIN_PRICE_CHANGE") {
    return [["SKU", String(i.sku_code ?? "")], ["최저판매가", `${i.min_price_from ? formatWon(i.min_price_from) + "원" : "없음"} → ${formatWon(i.min_price_to ?? "")}원`],
      ["기준 판매가", i.base_price ? `${formatWon(i.base_price)}원` : "미설정"], ["요청자", a.requested_by_name], ["사유", a.reason]];
  }
  return [["요청자", a.requested_by_name], ["사유", a.reason]];
}

export default function ApprovalsPage() {
  const { can, me } = useSession();
  const [status, setStatus] = useState("PENDING");
  const { data, error, loading, reload } = useApi<Approval[]>(`/approvals${qs({ status })}`);
  const [target, setTarget] = useState<{ a: Approval; approve: boolean } | null>(null);
  const [msg, setMsg] = useState<{ kind: "ok" | "err"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const decide = async () => {
    if (!target) return;
    setBusy(true);
    try {
      await api(`/approvals/${target.a.id}/decision`, { method: "POST", body: { decision: target.approve ? "APPROVE" : "REJECT" } });
      setMsg({ kind: "ok", text: target.approve ? "승인해 반영했습니다." : "반려했습니다." });
    } catch (e) {
      setMsg({ kind: "err", text: e instanceof ApiError ? e.message : "처리하지 못했습니다." });
    } finally {
      setBusy(false);
      setTarget(null);
      reload();
    }
  };
  const cancel = async (a: Approval) => {
    try {
      await api(`/approvals/${a.id}/cancel`, { method: "POST", body: {} });
      reload();
    } catch (e) {
      setMsg({ kind: "err", text: e instanceof ApiError ? e.message : "취소하지 못했습니다." });
    }
  };
  return (
    <>
      <div className="page-head"><div><h1>승인</h1><p>민감한 변경은 요청자가 아닌 승인자가 확정합니다. 요청 이후 대상이 바뀌면 자동으로 취소됩니다.</p></div></div>
      {msg && <div className={`note ${msg.kind === "ok" ? "n-info" : "n-err"}`} role={msg.kind === "err" ? "alert" : "status"}>{msg.text}</div>}
      <div className="row-gap"><label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}>
        <option value="PENDING">대기</option><option value="APPROVED">승인</option><option value="REJECTED">반려</option><option value="CANCELLED">취소</option><option value="">전체</option></select></label>
        {!can("approval.decide") && <span className="ctx">내가 요청한 건만 보입니다.</span>}</div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="승인 요청" rows={data ?? []} rowKey={(r) => r.id} empty={<Empty title="해당하는 승인 요청이 없습니다." />}
          columns={[
            { key: "t", header: "종류", render: (r) => TYPE_KO[r.request_type] ?? r.request_type },
            { key: "i", header: "내용", render: (r) => impactRows(r).slice(0, 2).map(([k, v]) => `${k} ${v}`).join(" · ") },
            { key: "by", header: "요청", render: (r) => <span className="ctx">{r.requested_by_name} · {formatDateTime(r.requested_at)}</span> },
            { key: "s", header: "상태", render: (r) => <Chip kind={STATUS[r.status]?.[0] ?? "mute"}>{STATUS[r.status]?.[1] ?? r.status}</Chip> },
            { key: "a", header: "", render: (r) => r.status !== "PENDING" ? <span className="ctx">{r.decided_by_name ?? ""} {r.decision_note ?? ""}</span> :
              r.requested_by === me.member_id ? <button className="btn" type="button" onClick={() => cancel(r)}>요청 취소</button> :
                can("approval.decide") ? <span className="row-gap"><button className="btn pri" type="button" onClick={() => setTarget({ a: r, approve: true })}>승인</button>
                  <button className="btn dan" type="button" onClick={() => setTarget({ a: r, approve: false })}>반려</button></span> : null },
          ]} />
      )}
      {target && <ConfirmModal title={`${TYPE_KO[target.a.request_type] ?? "요청"}을 ${target.approve ? "승인" : "반려"}할까요?`}
        impact={impactRows(target.a)} confirmLabel={target.approve ? "승인" : "반려"} danger={!target.approve} busy={busy}
        onConfirm={decide} onClose={() => setTarget(null)} />}
    </>
  );
}
