"use client";
import { FormEvent, useMemo, useState } from "react";
import { CatalogTabs } from "@/components/CatalogTabs";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { SKU_TYPE, Sku } from "@/lib/catalog";
import { formatDateTime, formatWon } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Component = { sku_id: string; sku_code: string; sku_name: string | null; sku_type: string; quantity: number };
type Bom = { id: string; bom_type: string; parent_sku_id: string | null; parent_sku_code: string | null; packaging_profile_code: string | null;
  version: number; status: string; valid_from: string | null; valid_to: string | null; split_shipment_allowed: boolean; note: string | null;
  components: Component[]; published_by_name: string | null; version_lock: number };
type Cost = { lines: { sku_code: string; quantity: number; unit_cost: string | null; line_cost: string | null }[]; total_cost: string | null; missing_cost_sku_codes: string[] };

const TYPE_KO: Record<string, string> = { SALES_BUNDLE: "판매묶음(가상)", ASSEMBLY: "조립(실물세트)", PACKAGING: "포장", PROMOTION: "프로모션", MANUFACTURING: "제조" };
const PARENT_TYPE: Record<string, string> = { SALES_BUNDLE: "BUNDLE", PROMOTION: "BUNDLE", ASSEMBLY: "KIT", MANUFACTURING: "NORMAL" };
const STATUS: Record<string, ["info" | "ok" | "mute", string]> = { DRAFT: ["info", "초안"], ACTIVE: ["ok", "게시"], RETIRED: ["mute", "종료"] };
const target = (b: Bom) => b.parent_sku_code ?? `포장 프로필 ${b.packaging_profile_code}`;

export default function BomsPage() {
  const { can } = useSession();
  const [type, setType] = useState("");
  const [status, setStatus] = useState("");
  const { data, error, loading, reload } = useApi<Bom[]>(`/boms${qs({ bom_type: type, status })}`);
  const [open, setOpen] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  return (
    <>
      <div className="page-head">
        <div><h1>묶음·세트(BOM)</h1><p>가상묶음·실물세트·포장 부자재 구성을 버전으로 관리합니다. 게시한 버전은 바꿀 수 없고, 주문은 당시 버전으로 고정됩니다.</p></div>
        {can("catalog.manage") && <button className="btn pri" type="button" onClick={() => setCreating(true)}>BOM 초안 만들기</button>}
      </div>
      <CatalogTabs active="boms" />
      <div className="row-gap">
        <label className="fld">유형<select className="inp" value={type} onChange={(e) => setType(e.target.value)}><option value="">전체</option>
          {Object.entries(TYPE_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
        <label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">전체</option>
          <option value="DRAFT">초안</option><option value="ACTIVE">게시</option><option value="RETIRED">종료</option></select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="BOM" rows={data ?? []} rowKey={(r) => r.id} onRowClick={(r) => setOpen(r.id)}
          empty={<Empty title="BOM 이 없습니다." hint="가상묶음 SKU 를 만든 뒤 구성품을 넣어 게시하세요." />}
          columns={[
            { key: "t", header: "대상", render: (r) => <b className="mono">{target(r)}</b> },
            { key: "ty", header: "유형", render: (r) => TYPE_KO[r.bom_type] },
            { key: "v", header: "버전", num: true, render: (r) => `v${r.version}` },
            { key: "c", header: "구성", render: (r) => r.components.map((c) => `${c.sku_code}×${c.quantity}`).join(" + ") || "—" },
            { key: "p", header: "적용기간", render: (r) => r.valid_from ? <span className="ctx">{formatDateTime(r.valid_from)} ~ {r.valid_to ? formatDateTime(r.valid_to) : "계속"}</span> : <span className="ctx">게시 전</span> },
            { key: "s", header: "상태", render: (r) => <Chip kind={STATUS[r.status][0]}>{STATUS[r.status][1]}</Chip> },
          ]} />
      )}
      {open && <BomDrawer id={open} onClose={() => setOpen(null)} onChanged={reload} />}
      {creating && <CreateBom onClose={() => setCreating(false)} onCreated={(id) => { setCreating(false); reload(); setOpen(id); }} />}
    </>
  );
}

function SkuPicker({ label, typeFilter, onPick }: { label: string; typeFilter?: string; onPick: (s: Sku) => void }) {
  const [q, setQ] = useState("");
  const res = useApi<Sku[]>(q.trim().length >= 2 ? `/skus${qs({ q, status: "ACTIVE", sku_type: typeFilter, limit: "8" })}` : null);
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
      <label className="fld">{label}<input className="inp" value={q} onChange={(e) => setQ(e.target.value)} placeholder="SKU 코드·상품명 2자 이상" /></label>
      {(res.data ?? []).map((s) => <button key={s.id} className="btn" type="button" onClick={() => { onPick(s); setQ(""); }} style={{ justifyContent: "flex-start" }}>
        <span className="mono">{s.sku_code}</span>&nbsp;<span className="ctx">{SKU_TYPE[s.sku_type]} · {s.product_name}</span></button>)}
    </div>
  );
}

function ComponentsEditor({ rows, setRows }: { rows: { sku: Sku | Component; quantity: number }[]; setRows: (r: { sku: Sku | Component; quantity: number }[]) => void }) {
  const code = (s: Sku | Component) => s.sku_code;
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
      {rows.map((r, i) => (
        <div key={code(r.sku)} className="row-gap" style={{ alignItems: "center" }}>
          <span className="mono" style={{ flex: 1 }}>{code(r.sku)}</span>
          <input className="inp" type="number" min={1} aria-label={`${code(r.sku)} 수량`} value={r.quantity} style={{ width: 90 }}
            onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, quantity: Number(e.target.value) || 1 } : x)))} />
          <button className="btn" type="button" aria-label={`${code(r.sku)} 빼기`} onClick={() => setRows(rows.filter((_, j) => j !== i))}>빼기</button>
        </div>))}
      <SkuPicker label="구성품 추가" onPick={(s) => !rows.some((r) => code(r.sku) === s.sku_code) && setRows([...rows, { sku: s, quantity: 1 }])} />
    </div>
  );
}

const skuIdOf = (s: Sku | Component) => ("id" in s ? s.id : s.sku_id);

function CreateBom({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const [type, setType] = useState("SALES_BUNDLE");
  const [parent, setParent] = useState<Sku | null>(null);
  const [profile, setProfile] = useState("DEFAULT");
  const [rows, setRows] = useState<{ sku: Sku | Component; quantity: number }[]>([]);
  const [split, setSplit] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [key] = useState(newIdempotencyKey);
  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setErr(null);
    try {
      const r = await api<Bom>("/boms", { method: "POST", idempotencyKey: key, body: {
        bom_type: type, parent_sku_id: type === "PACKAGING" ? undefined : parent?.id, packaging_profile_code: type === "PACKAGING" ? profile : undefined,
        split_shipment_allowed: split, components: rows.map((r) => ({ sku_id: skuIdOf(r.sku), quantity: r.quantity })) } });
      onCreated(r.data.id);
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "만들지 못했습니다.");
    }
  };
  return (
    <Drawer title="BOM 초안 만들기" onClose={onClose} footer={<><button className="btn" type="button" onClick={onClose}>취소</button>
      <button className="btn pri" type="submit" form="bom-create">초안 저장</button></>}>
      <form id="bom-create" onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        <label className="fld">유형<select className="inp" value={type} onChange={(e) => { setType(e.target.value); setParent(null); }}>
          {Object.entries(TYPE_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
        {type === "PACKAGING" ? <label className="fld">포장 프로필 코드<input className="inp mono" value={profile} onChange={(e) => setProfile(e.target.value.toUpperCase())} /></label>
          : parent ? <div className="kv"><span>상위 SKU</span><span className="mono">{parent.sku_code} <button className="btn" type="button" onClick={() => setParent(null)}>바꾸기</button></span></div>
            : <SkuPicker label={`상위 SKU (${SKU_TYPE[PARENT_TYPE[type]]})`} typeFilter={PARENT_TYPE[type]} onPick={setParent} />}
        <h3 style={{ margin: 0, fontSize: 14 }}>구성품</h3>
        <ComponentsEditor rows={rows} setRows={setRows} />
        {type !== "PACKAGING" && <label><input type="checkbox" checked={split} onChange={(e) => setSplit(e.target.checked)} /> 구성품을 여러 거점에서 나눠 출고 허용(기본 불가)</label>}
        <p className="ctx" style={{ margin: 0 }}>자기 자신·순환 구성은 저장되지 않습니다. 포장 BOM 은 부자재 SKU 로만 구성합니다.</p>
      </form>
    </Drawer>
  );
}

function BomDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can, entities } = useSession();
  const { data, error, loading, reload } = useApi<Bom>(`/boms/${id}`);
  const [rows, setRows] = useState<{ sku: Sku | Component; quantity: number }[] | null>(null);
  const [msg, setMsg] = useState<{ kind: "ok" | "err"; text: string } | null>(null);
  const [confirm, setConfirm] = useState<"publish" | "retire" | null>(null);
  const [reason, setReason] = useState("");
  const [be, setBe] = useState("");
  const cost = useApi<Cost>(be && can("cost.read") ? `/boms/${id}/cost${qs({ business_entity_id: be })}` : null);
  const siblings = useApi<Bom[]>(data ? `/boms${qs({ bom_type: data.bom_type, parent_sku_id: data.parent_sku_id ?? undefined, packaging_profile_code: data.packaging_profile_code ?? undefined })}` : null);
  const current = useMemo(() => (siblings.data ?? []).find((b) => b.status === "ACTIVE" && !b.valid_to), [siblings.data]);
  const manage = can("catalog.manage");

  const act = async (fn: () => Promise<unknown>, ok: string): Promise<boolean> => {
    setMsg(null);
    try {
      await fn();
      setMsg({ kind: "ok", text: ok });
      setRows(null);
      reload();
      siblings.reload();
      onChanged();
      return true;
    } catch (e) {
      setMsg({ kind: "err", text: e instanceof ApiError ? e.message : "처리하지 못했습니다." });
      return false;
    } finally {
      setConfirm(null);
    }
  };
  if (loading && !data) return <Drawer title="BOM" onClose={onClose}><Loading /></Drawer>;
  if (error) return <Drawer title="BOM" onClose={onClose}><ErrorState error={error} onRetry={reload} /></Drawer>;
  if (!data) return null;
  const b = data;
  const editing = rows ?? b.components.map((c) => ({ sku: c, quantity: c.quantity }));
  return (
    <Drawer title={`${target(b)} · ${TYPE_KO[b.bom_type]} v${b.version}`} onClose={onClose} footer={manage ? <>
      {b.status === "DRAFT" && <button className="btn dan" type="button" onClick={() => act(() => api(`/boms/${id}`, { method: "DELETE" }), "초안을 삭제했습니다.").then((ok) => ok && onClose())}>초안 삭제</button>}
      {b.status === "DRAFT" && rows && <button className="btn" type="button" onClick={() => act(() => api(`/boms/${id}/components`, { method: "PUT", ifMatch: b.version_lock,
        body: { components: editing.map((r) => ({ sku_id: skuIdOf(r.sku), quantity: r.quantity })) } }), "구성을 저장했습니다.")}>구성 저장</button>}
      {b.status === "DRAFT" && <button className="btn pri" type="button" disabled={!!rows} onClick={() => setConfirm("publish")}>게시</button>}
      {b.status === "ACTIVE" && <button className="btn" type="button" onClick={() => setConfirm("retire")}>종료</button>}</> : undefined}>
      {msg && <div className={`note ${msg.kind === "ok" ? "n-info" : "n-err"}`} role={msg.kind === "err" ? "alert" : "status"}>{msg.text}</div>}
      <div className="kv"><span>상태</span><Chip kind={STATUS[b.status][0]}>{STATUS[b.status][1]}</Chip></div>
      <div className="kv"><span>적용기간</span><span>{b.valid_from ? `${formatDateTime(b.valid_from)} ~ ${b.valid_to ? formatDateTime(b.valid_to) : "계속"}` : "게시 전"}</span></div>
      <div className="kv"><span>분할출고</span><span>{b.split_shipment_allowed ? "허용" : "불가(한 거점에서 모두 출고)"}</span></div>
      <h3 style={{ margin: "8px 0 0", fontSize: 14 }}>구성품</h3>
      {b.status === "DRAFT" && manage ? <ComponentsEditor rows={editing} setRows={setRows} /> :
        b.components.map((c) => <div className="kv" key={c.sku_id}><span className="mono">{c.sku_code}</span><span>{SKU_TYPE[c.sku_type]} × {c.quantity}</span></div>)}
      {can("cost.read") && <>
        <h3 style={{ margin: "8px 0 0", fontSize: 14 }}>구성품 원가(사업자별)</h3>
        <select className="inp" aria-label="원가 사업자" value={be} onChange={(e) => setBe(e.target.value)}><option value="">사업자 선택</option>
          {entities.map((e) => <option key={e.id} value={e.id}>{e.display_name}</option>)}</select>
        {cost.data && <>
          {cost.data.lines.map((l) => <div className="kv" key={l.sku_code}><span className="mono">{l.sku_code} × {l.quantity}</span><span>{l.line_cost ? `${formatWon(l.line_cost)}원` : "원가 없음"}</span></div>)}
          <div className="kv"><b>합계</b><b>{cost.data.total_cost ? `${formatWon(cost.data.total_cost)}원` : `계산 불가(원가 없음: ${cost.data.missing_cost_sku_codes.join(", ")})`}</b></div>
        </>}
      </>}
      <h3 style={{ margin: "8px 0 0", fontSize: 14 }}>버전</h3>
      {(siblings.data ?? []).map((s) => <div className="kv" key={s.id}><span>v{s.version} <Chip kind={STATUS[s.status][0]}>{STATUS[s.status][1]}</Chip></span>
        <span className="ctx">{s.valid_from ? `${formatDateTime(s.valid_from)} ~ ${s.valid_to ? formatDateTime(s.valid_to) : "계속"}` : "게시 전"}</span></div>)}

      {confirm === "publish" && <ConfirmModal title={`v${b.version}을 지금부터 적용할까요?`} confirmLabel="게시"
        impact={[["구성", b.components.map((c) => `${c.sku_code}×${c.quantity}`).join(" + ")], ["이전 버전", current ? `v${current.version} 은 지금 시각에 종료` : "없음"],
          ["과거 주문", "당시 버전 그대로"], ["게시 후", "구성 변경 불가(새 버전으로)"]]}
        onConfirm={() => act(() => api(`/boms/${id}/publish`, { method: "POST", ifMatch: b.version_lock, body: {} }), "게시했습니다.")} onClose={() => setConfirm(null)} />}
      {confirm === "retire" && <ConfirmModal title={`v${b.version}을 종료할까요?`} danger confirmLabel="종료" confirmDisabled={!reason.trim()}
        impact={[["종료 시각", "지금"], ["이후 주문", "이 대상의 게시 BOM 이 없으면 묶음 전개 불가"]]}
        onConfirm={() => act(() => api(`/boms/${id}/retire`, { method: "POST", ifMatch: b.version_lock, body: { reason } }), "종료했습니다.")} onClose={() => setConfirm(null)}>
        <label className="fld">종료 사유<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
    </Drawer>
  );
}
