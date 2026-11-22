"use client";
import { FormEvent, useState } from "react";
import { Chip, StatusChip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { Node, PROVIDER_KIND_KO, SEMANTIC_KO, SOURCE_KO } from "@/lib/inventory";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Provider = { code: string; name_ko: string; kind: string };
type Shipper = { id: string; business_entity_id: string; business_entity_name: string; logistics_provider_code: string; display_name: string; status: string };
type Location = { id: string; code: string; zone: string | null; status: string };

export default function NodesPage() {
  const { can } = useSession();
  const { data, error, loading, reload } = useApi<Node[]>(can("logistics_account.read") ? "/fulfillment-nodes" : null);
  const [open, setOpen] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  if (!can("logistics_account.read")) return <Forbidden />;
  return (
    <>
      <div className="page-head">
        <div><h1>물류센터·출고거점</h1><p>자체 창고와 3PL 센터를 출고거점으로 등록합니다. 3PL 센터는 사업자별 화주계정을 연결해야 그 사업자의 재고를 둘 수 있습니다.</p></div>
        {can("logistics_account.manage") && <button className="btn pri" type="button" onClick={() => setCreating(true)}>거점 추가</button>}
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="출고거점" rows={data ?? []} rowKey={(r) => r.id} onRowClick={(r) => setOpen(r.id)}
          empty={<Empty title="출고거점이 없습니다." hint="자체 창고나 3PL 센터를 먼저 등록하세요." />}
          columns={[
            { key: "n", header: "거점", render: (r) => <><b>{r.name}</b> <span className="mono ctx">{r.code}</span></> },
            { key: "p", header: "운영", render: (r) => `${PROVIDER_KIND_KO[r.provider_kind]} · ${r.provider_name}` },
            { key: "s", header: "사용 사업자 · 화주계정", render: (r) => r.provider_kind === "SELF_WMS" ? <span className="ctx">모든 사업자(재고는 사업자별 집계)</span>
              : r.shippers.length ? r.shippers.map((s) => `${s.business_entity_name}(${s.shipper_name})`).join(", ") : <Chip kind="warn">화주계정 미연결</Chip> },
            { key: "src", header: "재고 원본", render: (r) => <>{SOURCE_KO[r.inventory_source]}{r.inventory_source === "EXTERNAL" &&
              <span className="ctx"> · {SEMANTIC_KO[r.quantity_semantic]}</span>}</> },
            { key: "c", header: "마감", render: (r) => r.cutoff_time?.slice(0, 5) ?? "—" },
            { key: "st", header: "상태", render: (r) => <StatusChip status={r.status} /> },
          ]} />
      )}
      <p className="ctx">한 거점을 여러 사업자가 써도 재고 소유권은 사업자별로 따로 집계됩니다. 거점 닫기는 남은 재고·진행 중 확보가 없을 때만 됩니다. 물류사 API 지원 기능은 연동 관리에서 확인된 것만 켭니다.</p>
      {open && <NodeDrawer id={open} onClose={() => setOpen(null)} onChanged={reload} />}
      {creating && <CreateNode onClose={() => setCreating(false)} onCreated={(id) => { setCreating(false); reload(); setOpen(id); }} />}
    </>
  );
}

function CreateNode({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const providers = useApi<Provider[]>("/logistics-providers");
  const [f, setF] = useState({ code: "", name: "", logistics_provider_code: "SELF_WMS", inventory_source: "", cutoff_time: "", address_note: "" });
  const [err, setErr] = useState<string | null>(null);
  const kind = providers.data?.find((p) => p.code === f.logistics_provider_code)?.kind;
  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setErr(null);
    try {
      const r = await api<Node>("/fulfillment-nodes", { method: "POST", body: { ...f, inventory_source: f.inventory_source || undefined,
        address_note: f.address_note || undefined } });
      onCreated(r.data.id);
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "만들지 못했습니다.");
    }
  };
  return (
    <Drawer title="출고거점 추가" onClose={onClose} footer={<><button className="btn" type="button" onClick={onClose}>취소</button>
      <button className="btn pri" type="submit" form="node-create">저장</button></>}>
      <form id="node-create" onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        <label className="fld">거점 코드<input className="inp mono" value={f.code} onChange={(e) => setF({ ...f, code: e.target.value.toUpperCase() })} placeholder="예: FASSTO-YONGIN" required /></label>
        <label className="fld">거점 이름<input className="inp" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} required maxLength={100} /></label>
        <label className="fld">물류사업자<select className="inp" value={f.logistics_provider_code} onChange={(e) => setF({ ...f, logistics_provider_code: e.target.value, inventory_source: "" })}>
          {(providers.data ?? []).map((p) => <option key={p.code} value={p.code}>{p.name_ko} · {PROVIDER_KIND_KO[p.kind]}</option>)}</select></label>
        <label className="fld">재고 원본<select className="inp" value={f.inventory_source} onChange={(e) => setF({ ...f, inventory_source: e.target.value })}>
          <option value="">기본값 — {kind === "SELF_WMS" ? "내부 원장" : "물류사 재고 기준"}</option>
          <option value="INTERNAL">내부 원장(우리가 입출고를 기록)</option><option value="EXTERNAL">물류사 재고 기준(물류사 제공 수량으로 동기화)</option></select></label>
        <label className="fld">출고 마감 시각<input className="inp" type="time" value={f.cutoff_time} onChange={(e) => setF({ ...f, cutoff_time: e.target.value })} required /></label>
        <label className="fld">주소·메모(선택)<input className="inp" value={f.address_note} onChange={(e) => setF({ ...f, address_note: e.target.value })} maxLength={300} /></label>
      </form>
    </Drawer>
  );
}

function NodeDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const node = useApi<Node>(`/fulfillment-nodes/${id}`);
  const n = node.data;
  const shippers = useApi<Shipper[]>(n && n.provider_kind !== "SELF_WMS" ? `/shipper-accounts${qs({ logistics_provider_code: n.logistics_provider_code, limit: "200" })}` : null);
  const locations = useApi<Location[]>(n && n.provider_kind === "SELF_WMS" ? `/fulfillment-nodes/${id}/locations` : null);
  const [pick, setPick] = useState("");
  const [loc, setLoc] = useState({ code: "", zone: "" });
  const [err, setErr] = useState<string | null>(null);
  const [closing, setClosing] = useState(false);
  if (node.loading && !node.data) return <Drawer title="출고거점" onClose={onClose}><Loading /></Drawer>;
  if (node.error || !n) return <Drawer title="출고거점" onClose={onClose}>{node.error && <ErrorState error={node.error} onRetry={node.reload} />}</Drawer>;
  const linked = new Set(n.shippers.map((s) => s.shipper_account_id));
  const candidates = (shippers.data ?? []).filter((s) => !linked.has(s.id) && s.status === "ACTIVE");
  const run = async (f: () => Promise<unknown>) => {
    setErr(null);
    try {
      await f();
      node.reload();
      locations.reload();
      onChanged();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "처리하지 못했습니다.");
    }
  };
  const manage = can("logistics_account.manage");
  return (
    <Drawer title={n.name} onClose={onClose} footer={manage && n.status !== "CLOSED" ? <>
      {n.status === "ACTIVE" ? <button className="btn" type="button" onClick={() => run(() => api(`/fulfillment-nodes/${id}`, { method: "PATCH", ifMatch: n.version, body: { status: "SUSPENDED" } }))}>일시 중지</button>
        : <button className="btn" type="button" onClick={() => run(() => api(`/fulfillment-nodes/${id}`, { method: "PATCH", ifMatch: n.version, body: { status: "ACTIVE" } }))}>운영 재개</button>}
      <button className="btn dan" type="button" onClick={() => setClosing(true)}>거점 닫기</button></> : undefined}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <div className="kv"><span>코드</span><span className="mono">{n.code}</span></div>
      <div className="kv"><span>물류사업자</span><span>{n.provider_name} · {PROVIDER_KIND_KO[n.provider_kind]}</span></div>
      <div className="kv"><span>재고 원본</span><span>{SOURCE_KO[n.inventory_source]}</span></div>
      <div className="kv"><span>출고 마감</span><span>{n.cutoff_time?.slice(0, 5) ?? "—"}</span></div>
      <div className="kv"><span>주소·메모</span><span>{n.address_note ?? "—"}</span></div>
      <div className="kv"><span>상태</span><span><StatusChip status={n.status} /></span></div>
      {n.inventory_source === "EXTERNAL" && <ExternalRules node={n} manage={manage} onSaved={() => { node.reload(); onChanged(); }} />}
      {n.provider_kind === "SELF_WMS" ? (
        <>
          <h3 style={{ fontSize: 14 }}>로케이션</h3>
          {(locations.data ?? []).length === 0 ? <p className="ctx">로케이션 없이 거점 단위로 관리 중입니다.</p>
            : <p className="mono">{(locations.data ?? []).map((l) => `${l.code}${l.zone ? `(${l.zone})` : ""}`).join(" · ")}</p>}
          {manage && <div className="row-gap">
            <label className="fld">로케이션 코드<input className="inp mono" value={loc.code} onChange={(e) => setLoc({ ...loc, code: e.target.value })} placeholder="A-01-01" /></label>
            <label className="fld">구역<input className="inp" value={loc.zone} onChange={(e) => setLoc({ ...loc, zone: e.target.value })} placeholder="A" /></label>
            <button className="btn" type="button" style={{ alignSelf: "end" }} disabled={!loc.code.trim()}
              onClick={() => run(async () => { await api(`/fulfillment-nodes/${id}/locations`, { method: "POST", body: { code: loc.code, zone: loc.zone || undefined } }); setLoc({ code: "", zone: "" }); })}>추가</button>
          </div>}
        </>
      ) : (
        <>
          <h3 style={{ fontSize: 14 }}>화주계정(사업자별)</h3>
          {n.shippers.length === 0 ? <div className="note n-warn">연결된 화주계정이 없습니다. 연결 전에는 어떤 사업자의 재고도 이 거점에 둘 수 없습니다.</div>
            : n.shippers.map((s) => <div className="kv" key={s.shipper_account_id}><span>{s.business_entity_name}</span><span>{s.shipper_name}</span></div>)}
          {manage && <div className="row-gap">
            <label className="fld">화주계정 연결<select className="inp" value={pick} onChange={(e) => setPick(e.target.value)}><option value="">선택</option>
              {candidates.map((s) => <option key={s.id} value={s.id}>{s.business_entity_name} · {s.display_name}</option>)}</select></label>
            <button className="btn" type="button" style={{ alignSelf: "end" }} disabled={!pick}
              onClick={() => run(async () => { await api(`/fulfillment-nodes/${id}/shippers`, { method: "POST", body: { shipper_account_id: pick } }); setPick(""); })}>연결</button>
          </div>}
          <p className="ctx">사업자당 화주계정 1개, 거점과 같은 물류사업자의 운영 중 화주계정만 연결됩니다. 로케이션은 3PL 이 관리합니다.</p>
        </>
      )}
      {closing && <ConfirmModal title="거점 닫기" danger confirmLabel="닫기" onClose={() => setClosing(false)}
        impact={[["거점", `${n.name} (${n.code})`], ["조건", "남은 재고 0, 진행 중 확보 0 일 때만 닫힙니다"], ["되돌리기", "닫은 거점은 다시 열 수 없습니다"]]}
        onConfirm={() => { setClosing(false); run(() => api(`/fulfillment-nodes/${id}`, { method: "PATCH", ifMatch: n.version, body: { status: "CLOSED" } })); }} />}
    </Drawer>
  );
}

function ExternalRules({ node, manage, onSaved }: { node: Node; manage: boolean; onSaved: () => void }) {
  const [f, setF] = useState({ quantity_semantic: node.quantity_semantic, quantity_semantic_evidence: node.quantity_semantic_evidence ?? "",
    restricted_sell_buffer: node.restricted_sell_buffer, snapshot_max_age_minutes: node.snapshot_max_age_minutes });
  const [err, setErr] = useState<string | null>(null);
  const [ok, setOk] = useState(false);
  const save = async () => {
    setErr(null);
    setOk(false);
    try {
      await api(`/fulfillment-nodes/${node.id}`, { method: "PATCH", ifMatch: node.version, body: { ...f,
        quantity_semantic_evidence: f.quantity_semantic === "UNKNOWN" ? undefined : f.quantity_semantic_evidence } });
      setOk(true);
      onSaved();
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "저장하지 못했습니다.");
    }
  };
  return (
    <section aria-label="외부 재고 해석" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      <h3 style={{ fontSize: 14, margin: 0 }}>외부 재고 해석</h3>
      {err && <div className="note n-err" role="alert">{err}</div>}
      {ok && <div className="note n-ok">저장했습니다. 다음 재고 자료부터 적용됩니다.</div>}
      {node.quantity_semantic === "UNKNOWN" && <div className="note n-warn">수량 의미가 확인되지 않아 이 거점의 재고는 항상 보수적으로 판매 제한됩니다.</div>}
      <label className="fld">물류사 재고 수량 의미<select className="inp" value={f.quantity_semantic} disabled={!manage}
        onChange={(e) => setF({ ...f, quantity_semantic: e.target.value })}>
        {Object.entries(SEMANTIC_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      {f.quantity_semantic !== "UNKNOWN" && <label className="fld">확인 근거(필수)<input className="inp" value={f.quantity_semantic_evidence} disabled={!manage} maxLength={500}
        onChange={(e) => setF({ ...f, quantity_semantic_evidence: e.target.value })} placeholder="물류사 API 문서 위치·테스트 계정 응답 캡처 등" /></label>}
      {node.quantity_semantic_verified_at && <p className="ctx" style={{ margin: 0 }}>확인 기록: {node.quantity_semantic_evidence}</p>}
      <div className="row-gap">
        <label className="fld">판매 제한 때 보류 수량<input className="inp" type="number" min={0} value={f.restricted_sell_buffer} disabled={!manage}
          onChange={(e) => setF({ ...f, restricted_sell_buffer: Number(e.target.value) || 0 })} /></label>
        <label className="fld">자료 유효 시간(분)<input className="inp" type="number" min={5} max={10080} value={f.snapshot_max_age_minutes} disabled={!manage}
          onChange={(e) => setF({ ...f, snapshot_max_age_minutes: Number(e.target.value) || 60 })} /></label>
      </div>
      {manage && <button className="btn" type="button" onClick={save} style={{ alignSelf: "flex-start" }}
        disabled={f.quantity_semantic !== "UNKNOWN" && !f.quantity_semantic_evidence.trim()}>해석 규칙 저장</button>}
    </section>
  );
}
