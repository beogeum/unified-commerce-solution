"use client";
import { useEffect, useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { InventoryTabs } from "@/components/InventoryTabs";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { SkuPicker } from "@/components/SkuPicker";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { Sku } from "@/lib/catalog";
import { formatDateTime, formatWon } from "@/lib/format";
import { COUNT_STATUS, CountDetail, CountLine, LINE_STATUS, Node, num, QUALITY_KO, SCOPE_KO, StockCount } from "@/lib/inventory";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

export default function CountsPage() {
  const { can, selected } = useSession();
  const [status, setStatus] = useState("");
  const path = useMemo(() => `/inventory/stock-counts${qs({ business_entity_id: selected, status })}`, [selected, status]);
  const { data, error, loading, reload } = useApi<StockCount[]>(can("inventory.read") ? path : null);
  const [open, setOpen] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  if (!can("inventory.read")) return <Forbidden />;
  return (
    <>
      <div className="page-head">
        <div><h1>재고조정·실사</h1><p>실사 범위의 재고는 기준 시점부터 카운트 제출까지 입고 적치·출고 배정·피킹이 잠깁니다. 제출해도 실재고는 바뀌지 않고,
          실사를 만든 사람·카운트 담당자가 아닌 승인자가 차이를 승인해야 조정 원장으로 반영됩니다.</p></div>
        {can("inventory.operate") && <button className="btn pri" type="button" onClick={() => setCreating(true)}>실사 생성</button>}
      </div>
      <InventoryTabs active="counts" />
      <div className="row-gap">
        <label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">전체</option>
          {Object.entries(COUNT_STATUS).map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="실사" rows={data ?? []} rowKey={(r) => r.id} onRowClick={(r) => setOpen(r.id)}
          empty={<Empty title="실사가 없습니다." hint="재고정책에서 재실사 기준을 정한 뒤 실사를 만드세요." />}
          columns={[
            { key: "no", header: "실사번호", render: (r) => <b className="mono">{r.count_no}</b> },
            { key: "be", header: "사업자 · 거점", render: (r) => <>{r.business_entity_name} · {r.node_name}</> },
            { key: "sc", header: "범위", render: (r) => `${SCOPE_KO[r.scope]}${r.zone ? ` ${r.zone}` : ""}${r.sku_ids ? ` ${r.sku_ids.length}개` : ""}` },
            { key: "snap", header: "기준 스냅샷", render: (r) => <span className="ctx">{formatDateTime(r.snapshot_at)}</span> },
            { key: "m", header: "방식", render: (r) => (r.blind ? "블라인드" : "수량 표시") },
            { key: "p", header: "진척", render: (r) => `${num(r.counted_lines)} / ${num(r.line_count)}${r.round > 1 ? ` · ${r.round}회차` : ""}` },
            { key: "d", header: "차이", render: (r) => r.status === "COUNTING" ? "—" : <>{num(r.diff_lines)}건{r.recount_required_lines > 0 && <> <Chip kind="err">재실사 {r.recount_required_lines}</Chip></>}</> },
            { key: "s", header: "상태", render: (r) => <Chip kind={COUNT_STATUS[r.status][0]}>{COUNT_STATUS[r.status][1]}</Chip> },
          ]} />
      )}
      {open && <CountDrawer id={open} onClose={() => setOpen(null)} onChanged={reload} />}
      {creating && <CreateCount onClose={() => setCreating(false)} onCreated={(id) => { setCreating(false); reload(); setOpen(id); }} />}
    </>
  );
}

type Assignee = { member_id: string; display_name: string };

function CreateCount({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const { entities } = useSession();
  const nodes = useApi<Node[]>("/fulfillment-nodes");
  const people = useApi<Assignee[]>("/inventory/count-assignees");
  const internal = (nodes.data ?? []).filter((n) => n.inventory_source === "INTERNAL" && n.status === "ACTIVE");
  const [beSel, setBe] = useState("");
  const be = beSel || entities[0]?.id || "";
  const [node, setNode] = useState("");
  const [scope, setScope] = useState("ALL");
  const [zone, setZone] = useState("");
  const [skus, setSkus] = useState<Sku[]>([]);
  const [blind, setBlind] = useState(true);
  const [counters, setCounters] = useState<string[]>([]);
  const [note, setNote] = useState("");
  const [fetchedPreview, setPreview] = useState<Record<string, unknown> | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [key] = useState(newIdempotencyKey);
  const nodeId = node || internal[0]?.id || "";
  const zones = useApi<{ code: string; zone: string | null }[]>(nodeId ? `/fulfillment-nodes/${nodeId}/locations` : null);
  const zoneList = Array.from(new Set((zones.data ?? []).map((l) => l.zone).filter(Boolean))) as string[];
  const body = { business_entity_id: be, node_id: nodeId, scope, zone: scope === "ZONE" ? zone : undefined, sku_ids: scope === "SKU" ? skus.map((s) => s.id) : undefined,
    blind, counter_member_ids: counters, note: note || undefined };
  const bodyKey = JSON.stringify(body);
  const previewable = Boolean(be && nodeId && !(scope === "ZONE" && !zone) && !(scope === "SKU" && skus.length === 0));
  const preview = previewable ? fetchedPreview : null;
  useEffect(() => {
    if (!previewable) return;
    let alive = true;
    api<Record<string, unknown>>("/inventory/stock-counts/preview", { method: "POST", body: JSON.parse(bodyKey) })
      .then((r) => alive && setPreview(r.data)).catch(() => alive && setPreview(null));
    return () => { alive = false; };
  }, [bodyKey, previewable]);
  const submit = async () => {
    setErr(null);
    try {
      const r = await api<CountDetail>("/inventory/stock-counts", { method: "POST", idempotencyKey: key, body });
      onCreated(r.data.count.id);
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "실사를 만들지 못했습니다.");
    }
  };
  const pol = preview?.policy as { version: number; qty_threshold: number | null; rate_threshold_pct: string | null; cost_threshold: string | null;
    combine: string; on_exceed: string; node_name: string | null; zone: string | null } | null | undefined;
  return (
    <Drawer title="실사 생성" onClose={onClose} footer={<><button className="btn" type="button" onClick={onClose}>취소</button>
      <button className="btn pri" type="button" onClick={submit} disabled={!be || !nodeId || counters.length === 0 || Boolean(preview?.policy_missing)}>실사 생성</button></>}>
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        <label className="fld">사업자(재고 소유)<select className="inp" value={be} onChange={(e) => setBe(e.target.value)}>
          {entities.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
        <label className="fld">출고거점<select className="inp" value={nodeId} onChange={(e) => setNode(e.target.value)}>
          {internal.map((n) => <option key={n.id} value={n.id}>{n.name}</option>)}</select></label>
        <p className="ctx" style={{ margin: 0 }}>물류사 재고 기준 거점은 3PL 이 실사하고 결과를 외부 재고 동기화로 받아 대조합니다.</p>
        <label className="fld">범위<select className="inp" value={scope} onChange={(e) => setScope(e.target.value)}>
          <option value="ALL">거점 전체</option><option value="ZONE">Zone 선택</option><option value="SKU">SKU 선택(순환 실사)</option></select></label>
        {scope === "ZONE" && <label className="fld">Zone<select className="inp" value={zone} onChange={(e) => setZone(e.target.value)}><option value="">선택</option>
          {zoneList.map((z) => <option key={z} value={z}>{z}</option>)}</select></label>}
        {scope === "SKU" && <>
          {skus.length > 0 && <p className="mono" style={{ margin: 0 }}>{skus.map((s) => s.sku_code).join(", ")}</p>}
          <SkuPicker label="실사할 SKU 추가" onPick={(s) => !skus.some((x) => x.id === s.id) && setSkus([...skus, s])} />
        </>}
        <label className="fld">카운트 방식<select className="inp" value={blind ? "B" : "V"} onChange={(e) => setBlind(e.target.value === "B")}>
          <option value="B">블라인드 — 작업자에게 시스템 수량을 보여 주지 않음(기본)</option><option value="V">수량 표시 — 승인 시 사유 필요</option></select></label>
        <fieldset style={{ border: "1px solid var(--border)", borderRadius: 8, padding: 8 }}>
          <legend>카운트 담당</legend>
          {(people.data ?? []).map((p) => <label key={p.member_id} style={{ display: "block" }}><input type="checkbox" checked={counters.includes(p.member_id)}
            onChange={(e) => setCounters(e.target.checked ? [...counters, p.member_id] : counters.filter((x) => x !== p.member_id))} /> {p.display_name}</label>)}
        </fieldset>
        <label className="fld">메모(선택)<input className="inp" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} /></label>
        {preview && (preview.policy_missing ? <div className="note n-err" role="alert">이 범위에 적용할 재실사 기준이 없습니다. 재고정책에서 먼저 정하세요.</div> : (
          <div className="note n-info">
            <b>영향 미리보기</b>: 버킷 {num(Number(preview.buckets))}개 · 로케이션 {num(Number(preview.locations))}개 잠금 · 이 범위의 진행 중 확보 {num(Number(preview.active_reservations))}건(잠긴 동안 출고 배정·피킹 불가)<br />
            재실사 기준 v{pol?.version} ({pol?.zone ? `Zone ${pol.zone}` : pol?.node_name ?? "사업자 기본값"}): {[pol?.qty_threshold != null && `수량 차이 ${pol.qty_threshold}개 초과`,
              pol?.rate_threshold_pct != null && `차이율 ${pol.rate_threshold_pct}% 초과`, pol?.cost_threshold != null && `원가 영향 ${formatWon(pol.cost_threshold)}원 이상`]
              .filter(Boolean).join(pol?.combine === "ALL" ? " 그리고 " : " 또는 ")} → {pol?.on_exceed === "RECOUNT_REQUIRED" ? "승인 전 재실사 필수" : "재실사 권장"}.
            생성 시점의 정책 버전이 이 실사에 고정됩니다.
          </div>))}
        <p className="ctx" style={{ margin: 0 }}>이 실사를 만든 사람과 카운트 담당자는 차이를 승인할 수 없습니다.</p>
      </div>
    </Drawer>
  );
}

function CountDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can, me } = useSession();
  const d = useApi<CountDetail>(`/inventory/stock-counts/${id}`);
  const [qty, setQty] = useState<Record<string, string>>({});
  const [picked, setPicked] = useState<string[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);
  const [deciding, setDeciding] = useState<boolean | null>(null);
  const [reason, setReason] = useState("");
  const [cancelling, setCancelling] = useState(false);
  const [cancelReason, setCancelReason] = useState("");
  if (d.loading && !d.data) return <Drawer title="실사" onClose={onClose}><Loading /></Drawer>;
  if (d.error || !d.data) return <Drawer title="실사" onClose={onClose}>{d.error && <ErrorState error={d.error} onRetry={d.reload} />}</Drawer>;
  const c = d.data.count;
  const lines = d.data.lines;
  const isCounter = c.counters.some((x) => x.member_id === me.member_id);
  const isRequester = c.requested_by === me.member_id;
  const canDecide = can("inventory.adjust") && !isCounter && !isRequester;
  const run = async (path: string, body: unknown, msg: string) => {
    setErr(null);
    setOk(null);
    try {
      await api(path, { method: "POST", idempotencyKey: newIdempotencyKey(), body });
      setOk(msg);
      setPicked([]);
      d.reload();
      onChanged();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "처리하지 못했습니다.");
    }
  };
  const open = (l: CountLine) => l.status === "COUNTING" || l.status === "RECOUNT";
  const saveCounts = () => run(`/inventory/stock-counts/${id}/entries`, { entries: lines.filter((l) => open(l) && qty[l.id] !== undefined && qty[l.id] !== "")
    .map((l) => ({ line_id: l.id, counted_qty: Number(qty[l.id]) })) }, "카운트를 저장했습니다.");
  const pending = lines.filter((l) => l.status === "PENDING_APPROVAL" || l.status === "RECOUNT_REQUIRED");
  return (
    <Drawer title={`실사 ${c.count_no}`} onClose={onClose} footer={<>
      {c.status === "COUNTING" && (isCounter || isRequester) && <button className="btn pri" type="button" onClick={() => run(`/inventory/stock-counts/${id}/submit`, {}, "제출했습니다. 실재고는 승인 전까지 바뀌지 않습니다.")}>카운트 제출</button>}
      {(c.status === "COUNTING" || c.status === "REVIEW") && (isRequester || can("inventory.adjust")) &&
        <button className="btn dan" type="button" onClick={() => setCancelling(true)}>실사 취소</button>}
    </>}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {ok && <div className="note n-ok">{ok}</div>}
      <div className="kv"><span>사업자 · 거점</span><span>{c.business_entity_name} · {c.node_name}</span></div>
      <div className="kv"><span>범위 · 방식</span><span>{SCOPE_KO[c.scope]}{c.zone ? ` ${c.zone}` : ""} · {c.blind ? "블라인드" : "수량 표시"}</span></div>
      <div className="kv"><span>기준 스냅샷</span><span>{formatDateTime(c.snapshot_at)}{c.round > 1 ? ` · ${c.round}회차(재실사)` : ""}</span></div>
      <div className="kv"><span>재실사 기준</span><span>v{c.policy_version} (생성 시점 고정)</span></div>
      <div className="kv"><span>요청 · 카운트 담당</span><span>{c.requested_by_name} · {c.counters.map((x) => x.name).join(", ")}</span></div>
      <div className="kv"><span>상태</span><span><Chip kind={COUNT_STATUS[c.status][0]}>{COUNT_STATUS[c.status][1]}</Chip>{c.status === "COUNTING" && <span className="ctx"> 범위 잠금 중</span>}</span></div>
      {c.status === "REVIEW" && !canDecide && can("inventory.adjust") && <div className="note n-info">이 실사를 만들었거나 카운트를 맡아 차이를 승인할 수 없습니다.</div>}
      <DataTable caption="실사 행" rows={lines} rowKey={(l) => l.id} empty={<Empty title="범위에 재고가 없습니다." />}
        columns={[
          ...(c.status === "REVIEW" ? [{ key: "pick", header: "선택", render: (l: CountLine) => pending.includes(l) ?
            <input type="checkbox" aria-label={`${l.sku_code} 선택`} checked={picked.includes(l.id)} onChange={(e) => setPicked(e.target.checked ? [...picked, l.id] : picked.filter((x) => x !== l.id))} /> : null }] : []),
          { key: "s", header: "SKU · LOT", render: (l: CountLine) => <><span className="mono">{l.sku_code}</span>{l.lot_code && <span className="ctx"> · {l.lot_code}</span>}{l.found_in_count && <> <Chip kind="info">발견</Chip></>}</> },
          { key: "loc", header: "로케이션", render: (l: CountLine) => l.location_code ?? "—" },
          { key: "q", header: "품질", render: (l: CountLine) => QUALITY_KO[l.quality_status] },
          { key: "sys", header: "시스템", num: true, render: (l: CountLine) => l.system_qty == null ? <span className="ctx">비공개</span> : num(l.system_qty) },
          { key: "cnt", header: "실사", num: true, render: (l: CountLine) => c.status === "COUNTING" && open(l) && isCounter
            ? <input className="inp" type="number" min={0} aria-label={`${l.sku_code} ${l.location_code ?? ""} 실사 수량`} style={{ width: 90 }}
                value={qty[l.id] ?? (l.counted_qty ?? "")} onChange={(e) => setQty({ ...qty, [l.id]: e.target.value })} />
            : l.counted_qty == null ? "—" : num(l.counted_qty) },
          { key: "diff", header: "차이", num: true, render: (l: CountLine) => l.diff == null ? "—" : (l.diff > 0 ? `+${num(l.diff)}` : num(l.diff)) },
          { key: "cost", header: "원가 영향", num: true, render: (l: CountLine) => l.diff == null || l.diff === 0 ? "—" : l.cost_impact == null ? <span className="ctx">원가 없음</span> : `${formatWon(l.cost_impact)}원` },
          { key: "st", header: "상태", render: (l: CountLine) => <><Chip kind={LINE_STATUS[l.status][0]}>{LINE_STATUS[l.status][1]}</Chip>
            {l.recount_recommended && <span className="ctx"> 재실사 권장</span>}{l.decision_reason && <span className="ctx"> · {l.decision_reason}</span>}</> },
        ]} />
      {c.status === "COUNTING" && isCounter && <button className="btn" type="button" onClick={saveCounts}>카운트 저장</button>}
      {c.status === "REVIEW" && picked.length > 0 && <div className="row-gap">
        {(isRequester || can("inventory.adjust")) && <button className="btn" type="button" onClick={() => run(`/inventory/stock-counts/${id}/recount`, { line_ids: picked }, "재실사를 시작했습니다(범위 다시 잠금).")}>선택 재실사</button>}
        {canDecide && <><button className="btn pri" type="button" onClick={() => setDeciding(true)}>선택 승인</button>
          <button className="btn" type="button" onClick={() => setDeciding(false)}>선택 반려</button></>}
      </div>}
      {cancelling && <ConfirmModal title="실사 취소" danger confirmLabel="실사 취소" onClose={() => setCancelling(false)} confirmDisabled={!cancelReason.trim()}
        impact={[["실사", c.count_no], ["재고 영향", "승인하지 않은 차이는 반영하지 않습니다(이미 승인한 행은 유지)"], ["잠금", "범위 잠금이 풀립니다"]]}
        onConfirm={() => { setCancelling(false); run(`/inventory/stock-counts/${id}/cancel`, { reason: cancelReason }, "실사를 취소했습니다."); }}>
        <label className="fld">취소 사유(필수)<input className="inp" value={cancelReason} onChange={(e) => setCancelReason(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
      {deciding !== null && <ConfirmModal title={deciding ? "실사 차이 승인" : "실사 차이 반려"} confirmLabel={deciding ? "승인" : "반려"} onClose={() => setDeciding(null)}
        impact={[["행", `${picked.length}건`], ["재고 영향", deciding ? `차이 합계 ${lines.filter((l) => picked.includes(l.id)).reduce((a, l) => a + (l.diff ?? 0), 0)}개를 조정 원장으로 반영` : "재고 변화 없음"]]}
        onConfirm={() => { const ap = deciding; setDeciding(null); run(`/inventory/stock-counts/${id}/decisions`, { line_ids: picked, approve: ap, reason: reason || undefined }, ap ? "승인해 재고에 반영했습니다." : "반려했습니다."); }}>
        <label className="fld">사유{deciding ? "(재실사 권장 행·수량 표시 실사는 필수)" : "(선택)"}<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
    </Drawer>
  );
}
