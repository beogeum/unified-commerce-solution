"use client";
import { useState } from "react";
import { DataTable } from "@/components/DataTable";
import { InventoryTabs } from "@/components/InventoryTabs";
import { Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey } from "@/lib/api";
import { formatDateTime, formatWon } from "@/lib/format";
import { CountPolicy, Node } from "@/lib/inventory";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type AdjPolicy = { business_entity_id: string; approval_threshold: string; configured: boolean; updated_at: string | null; version: number };
const ON_EXCEED: Record<string, string> = { RECOUNT_REQUIRED: "재실사 필수", RECOUNT_RECOMMENDED: "재실사 권장" };

export default function InventoryPolicyPage() {
  const { can, entities } = useSession();
  const [be, setBe] = useState("");
  const owner = be || entities[0]?.id || "";
  const policies = useApi<CountPolicy[]>(owner && can("inventory.read") ? `/inventory/count-policies?business_entity_id=${owner}` : null);
  const adj = useApi<AdjPolicy>(owner && can("inventory.read") ? `/inventory/adjustment-policies/${owner}` : null);
  const [editing, setEditing] = useState<Partial<CountPolicy> | null>(null);
  const [threshold, setThreshold] = useState("");
  const [msg, setMsg] = useState<{ kind: "ok" | "err"; text: string } | null>(null);
  if (!can("inventory.read")) return <Forbidden />;
  const base = (policies.data ?? []).find((p) => !p.node_id);
  const saveThreshold = async () => {
    setMsg(null);
    try {
      await api(`/inventory/adjustment-policies/${owner}`, { method: "PUT", ifMatch: adj.data?.configured ? adj.data.version : undefined, body: { approval_threshold: threshold } });
      setMsg({ kind: "ok", text: "조정 승인 기준을 저장했습니다." });
      adj.reload();
    } catch (x) {
      setMsg({ kind: "err", text: x instanceof ApiError ? x.message : "저장하지 못했습니다." });
    }
  };
  return (
    <>
      <div className="page-head"><div><h1>재고정책</h1><p>재실사 기준과 재고 조정 승인 기준을 사업자별로 정합니다. 더 좁은 범위의 설정이 우선합니다: Zone → 거점 → 사업자 기본값.
        기준을 바꿔도 이미 만든 실사에는 생성 당시 버전이 그대로 적용됩니다.</p></div>
        {can("inventory.adjust") && <button className="btn pri" type="button" onClick={() => setEditing(base ? { ...base, node_id: null } : { node_id: null })}>{base ? "기본값 편집" : "사업자 기본값 만들기"}</button>}
      </div>
      <InventoryTabs active="policy" />
      <label className="fld">사업자<select className="inp" aria-label="정책 사업자" value={owner} onChange={(e) => setBe(e.target.value)}>
        {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
      {msg && <div className={`note ${msg.kind === "ok" ? "n-ok" : "n-err"}`} role={msg.kind === "err" ? "alert" : undefined}>{msg.text}</div>}
      <h2 style={{ fontSize: 16 }}>재실사 기준</h2>
      {!base && !policies.loading && <div className="note n-warn">사업자 기본값이 없습니다. 기준이 없는 범위는 실사를 만들 수 없습니다(고정 기본값 없음).</div>}
      {policies.loading ? <Loading /> : policies.error ? <ErrorState error={policies.error} onRetry={policies.reload} /> : (
        <DataTable caption="재실사 기준" rows={policies.data ?? []} rowKey={(r) => r.id} empty={<Empty title="재실사 기준이 없습니다." />}
          columns={[
            { key: "sc", header: "거점 · 범위", render: (r) => r.node_id ? `${r.node_name}${r.zone ? ` · Zone ${r.zone}` : " · 전체"}` : "사업자 기본값" },
            { key: "q", header: "수량 차이", render: (r) => r.qty_threshold == null ? "—" : `${r.qty_threshold}개 초과` },
            { key: "r", header: "차이율", render: (r) => r.rate_threshold_pct == null ? "—" : `${r.rate_threshold_pct}% 초과` },
            { key: "c", header: "원가 영향", render: (r) => r.cost_threshold == null ? "—" : `${formatWon(r.cost_threshold)}원 이상` },
            { key: "cb", header: "결합", render: (r) => (r.combine === "ALL" ? "모두" : "하나라도") },
            { key: "e", header: "초과 시", render: (r) => ON_EXCEED[r.on_exceed] },
            { key: "a", header: "재실사 담당", render: (r) => (r.recount_assignee === "DIFFERENT_COUNTER" ? "첫 카운트 담당자 아닌 사람" : "같은 담당자도 가능") },
            { key: "v", header: "버전", render: (r) => <span className="ctx">v{r.version} · {formatDateTime(r.created_at)} · {r.created_by_name}</span> },
            { key: "x", header: "", render: (r) => can("inventory.adjust") ? <button className="btn" type="button" onClick={() => setEditing(r)}>편집</button> : null },
          ]} />
      )}
      {can("inventory.adjust") && <button className="btn" type="button" style={{ alignSelf: "flex-start" }} onClick={() => setEditing({ node_id: "" })}>거점·Zone 재정의 추가</button>}
      <h2 style={{ fontSize: 16 }}>재고 조정 승인 기준</h2>
      {adj.data && <div className="kv"><span>원가 영향 승인 기준</span><span>{formatWon(adj.data.approval_threshold)}원 이상 승인 필요 {adj.data.configured ? "" : "(기본값)"}</span></div>}
      <p className="ctx">수동 재고 조정의 원가 영향(|수량 변화| × 기준 원가)이 기준 이상이거나 기준 원가가 없으면 승인 요청이 만들어지고, 승인 전까지 재고는 바뀌지 않습니다. 기준 변경은 승인 권한자만 할 수 있습니다.</p>
      {can("approval.decide") && <div className="row-gap">
        <label className="fld">새 승인 기준(원)<input className="inp" inputMode="numeric" value={threshold} onChange={(e) => setThreshold(e.target.value.replace(/[^0-9]/g, ""))} /></label>
        <button className="btn" type="button" style={{ alignSelf: "end" }} disabled={!threshold} onClick={saveThreshold}>저장</button></div>}
      {editing && <PolicyEditor owner={owner} initial={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); policies.reload(); setMsg({ kind: "ok", text: "새 버전으로 저장했습니다." }); }} />}
    </>
  );
}

function PolicyEditor({ owner, initial, onClose, onSaved }: { owner: string; initial: Partial<CountPolicy>; onClose: () => void; onSaved: () => void }) {
  const nodes = useApi<Node[]>("/fulfillment-nodes");
  const internal = (nodes.data ?? []).filter((n) => n.inventory_source === "INTERNAL");
  const [f, setF] = useState({ node_id: initial.node_id ?? "", zone: initial.zone ?? "", qty: initial.qty_threshold?.toString() ?? "", rate: initial.rate_threshold_pct ?? "",
    cost: initial.cost_threshold ?? "", combine: initial.combine ?? "ANY", on_exceed: initial.on_exceed ?? "RECOUNT_REQUIRED", assignee: initial.recount_assignee ?? "DIFFERENT_COUNTER" });
  const [err, setErr] = useState<string | null>(null);
  const isBase = initial.node_id === null;
  const save = async (removed = false) => {
    setErr(null);
    try {
      await api("/inventory/count-policies", { method: "POST", idempotencyKey: newIdempotencyKey(), body: { business_entity_id: owner, node_id: isBase ? undefined : f.node_id || undefined,
        zone: isBase ? undefined : f.zone || undefined, removed, qty_threshold: f.qty === "" ? undefined : Number(f.qty), rate_threshold_pct: f.rate === "" ? undefined : f.rate,
        cost_threshold: f.cost === "" ? undefined : f.cost, combine: f.combine, on_exceed: f.on_exceed, recount_assignee: f.assignee } });
      onSaved();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "저장하지 못했습니다.");
    }
  };
  return (
    <Drawer title={isBase ? "재실사 기준 — 사업자 기본값" : "재실사 기준 — 거점·Zone 재정의"} onClose={onClose} footer={<>
      {!isBase && initial.id && <button className="btn dan" type="button" onClick={() => save(true)}>재정의 삭제(상위 기준 사용)</button>}
      <button className="btn" type="button" onClick={onClose}>취소</button><button className="btn pri" type="button" onClick={() => save()}>새 버전 저장</button></>}>
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        {!isBase && <>
          <label className="fld">거점<select className="inp" value={f.node_id} onChange={(e) => setF({ ...f, node_id: e.target.value })}><option value="">선택</option>
            {internal.map((n) => <option key={n.id} value={n.id}>{n.name}</option>)}</select></label>
          <label className="fld">Zone(비우면 거점 전체)<input className="inp" value={f.zone} onChange={(e) => setF({ ...f, zone: e.target.value })} /></label>
        </>}
        <label className="fld">수량 차이가 넘으면(개)<input className="inp" inputMode="numeric" value={f.qty} onChange={(e) => setF({ ...f, qty: e.target.value.replace(/[^0-9]/g, "") })} /></label>
        <label className="fld">차이율이 넘으면(%)<input className="inp" inputMode="decimal" value={f.rate} onChange={(e) => setF({ ...f, rate: e.target.value.replace(/[^0-9.]/g, "") })} /></label>
        <label className="fld">원가 영향이 이상이면(원)<input className="inp" inputMode="numeric" value={f.cost} onChange={(e) => setF({ ...f, cost: e.target.value.replace(/[^0-9]/g, "") })} /></label>
        <label className="fld">조건 결합<select className="inp" value={f.combine} onChange={(e) => setF({ ...f, combine: e.target.value })}>
          <option value="ANY">하나라도 해당하면</option><option value="ALL">모두 해당할 때만</option></select></label>
        <label className="fld">기준을 넘었을 때<select className="inp" value={f.on_exceed} onChange={(e) => setF({ ...f, on_exceed: e.target.value })}>
          <option value="RECOUNT_REQUIRED">승인 전에 재실사 필수 — 해당 행 승인 불가</option><option value="RECOUNT_RECOMMENDED">재실사 권장 — 사유를 남기면 승인 가능</option></select></label>
        <label className="fld">재실사 담당<select className="inp" value={f.assignee} onChange={(e) => setF({ ...f, assignee: e.target.value })}>
          <option value="DIFFERENT_COUNTER">첫 카운트 담당자가 아닌 사람</option><option value="SAME_ALLOWED">같은 담당자도 가능</option></select></label>
        <p className="ctx" style={{ margin: 0 }}>빈칸으로 두면 그 조건은 쓰지 않습니다. 조건이 하나도 없으면 저장할 수 없습니다. 원가가 등록되지 않은 SKU 는 원가 조건을 넘은 것으로 봅니다.</p>
      </div>
    </Drawer>
  );
}
