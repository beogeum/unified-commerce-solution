"use client";
import { FormEvent, useMemo, useState } from "react";
import { DataTable } from "@/components/DataTable";
import { Drawer } from "@/components/Overlay";
import { ExportButton } from "@/components/ExportButton";
import { StatusChip } from "@/components/Chip";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { formatDateTime, LEGAL_TYPE, TAX_TYPE } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

const ACTION_KO: Record<string, string> = { BUSINESS_ENTITY_CREATED: "등록", BUSINESS_ENTITY_UPDATED: "수정", BUSINESS_ENTITY_INACTIVE: "비활성화" };

type Entity = { id: string; display_name: string; legal_name: string; legal_type: string; business_registration_no: string;
  tax_type: string; status: string; version: number; updated_at: string };
type History = { audit_id: string; action: string; actor_label: string; occurred_at: string };

export default function BusinessEntitiesPage() {
  const { can, selected, refreshEntities } = useSession();
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const path = useMemo(() => `/business-entities${qs({ q, status, business_entity_id: selected, limit: "100" })}`, [q, status, selected]);
  const { data, error, loading, reload } = useApi<Entity[]>(path);
  const [creating, setCreating] = useState(false);
  const [detail, setDetail] = useState<Entity | null>(null);

  return (
    <>
      <div className="page-head">
        <div><h1>사업자 관리</h1><p>한 운영그룹에 여러 사업자를 둡니다. 사업자마다 재고 소유·원가·정산·손익이 따로 집계됩니다.</p></div>
        <div className="row-gap">
          {can("export.create") && <ExportButton exportType="BUSINESS_ENTITIES" businessEntityIds={selected} />}
          {can("business_entity.manage") && <button className="btn pri" type="button" onClick={() => setCreating(true)}>사업자 등록</button>}
        </div>
      </div>
      <div className="row-gap" role="search">
        <label className="fld" style={{ minWidth: 240 }}>검색<input className="inp" value={q} onChange={(e) => setQ(e.target.value)} placeholder="사업자명, 등록번호" /></label>
        <label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">전체</option><option value="ACTIVE">운영</option><option value="INACTIVE">비활성</option></select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="사업자 목록" rows={data ?? []} rowKey={(r) => r.id} onRowClick={setDetail}
          empty={<Empty title={q || status ? "조건에 맞는 사업자가 없습니다." : "아직 등록된 사업자가 없습니다."}
            hint={q || status ? "검색어나 필터를 바꿔 보세요." : "사업자를 등록하면 판매계정·화주계정을 연결할 수 있습니다."} />}
          columns={[
            { key: "name", header: "사업자", render: (r) => <><b>{r.display_name}</b><div className="ctx">{r.legal_name}</div></> },
            { key: "type", header: "구분", render: (r) => LEGAL_TYPE[r.legal_type] ?? r.legal_type },
            { key: "brn", header: "사업자등록번호", render: (r) => <span className="mono">{r.business_registration_no}</span> },
            { key: "tax", header: "과세유형", render: (r) => TAX_TYPE[r.tax_type] ?? r.tax_type },
            { key: "status", header: "상태", render: (r) => <StatusChip status={r.status} /> },
            { key: "updated", header: "수정", render: (r) => <span className="ctx">{formatDateTime(r.updated_at)}</span> },
          ]} />
      )}
      {creating && <CreateDrawer onClose={() => setCreating(false)} onCreated={() => { setCreating(false); reload(); refreshEntities(); }} />}
      {detail && <DetailDrawer entity={detail} onClose={() => setDetail(null)} />}
    </>
  );
}

function CreateDrawer({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const [key] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<{ field?: string; message: string } | null>(null);
  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const f = Object.fromEntries(new FormData(e.currentTarget).entries());
    setBusy(true);
    setErr(null);
    try {
      await api("/business-entities", { method: "POST", idempotencyKey: key, body: f });
      onCreated();
    } catch (x) {
      const a = x as ApiError;
      setErr({ field: typeof a.details?.field === "string" ? (a.details.field as string) : undefined, message: a.message });
    } finally {
      setBusy(false);
    }
  };
  const fieldErr = (name: string) => (err?.field === name ? <span className="fld-err" role="alert">{err.message}</span> : null);
  return (
    <Drawer title="사업자 등록" onClose={onClose} footer={<>
      <button className="btn" type="button" onClick={onClose}>취소</button>
      <button className="btn pri" type="submit" form="be-create" disabled={busy}>{busy ? "등록 중…" : "등록"}</button></>}>
      <form id="be-create" onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {err && !err.field && <div className="note n-err" role="alert">{err.message}</div>}
        <label className="fld">표시 이름<input className="inp" name="display_name" required maxLength={100} aria-invalid={err?.field === "display_name"} />{fieldErr("display_name")}</label>
        <label className="fld">법인명·상호<input className="inp" name="legal_name" required maxLength={200} />{fieldErr("legal_name")}</label>
        <label className="fld">구분<select className="inp" name="legal_type" defaultValue="CORPORATION"><option value="CORPORATION">법인</option><option value="INDIVIDUAL">개인</option></select></label>
        <label className="fld">사업자등록번호<input className="inp" name="business_registration_no" required placeholder="000-00-00000" aria-invalid={err?.field === "business_registration_no"} />{fieldErr("business_registration_no")}</label>
        <label className="fld">과세유형<select className="inp" name="tax_type" defaultValue="GENERAL"><option value="GENERAL">일반과세</option><option value="SIMPLIFIED">간이과세</option><option value="EXEMPT">면세</option></select></label>
        <p className="ctx" style={{ margin: 0 }}>사업자등록번호와 법인/개인 구분은 등록 후 바꿀 수 없습니다.</p>
      </form>
    </Drawer>
  );
}

function DetailDrawer({ entity, onClose }: { entity: Entity; onClose: () => void }) {
  const history = useApi<History[]>(`/business-entities/${entity.id}/history`);
  return (
    <Drawer title={entity.display_name} onClose={onClose}>
      <div className="kv"><span>법인명·상호</span><span>{entity.legal_name}</span></div>
      <div className="kv"><span>구분</span><span>{LEGAL_TYPE[entity.legal_type] ?? entity.legal_type}</span></div>
      <div className="kv"><span>사업자등록번호</span><span className="mono">{entity.business_registration_no}</span></div>
      <div className="kv"><span>과세유형</span><span>{TAX_TYPE[entity.tax_type] ?? entity.tax_type}</span></div>
      <div className="kv"><span>상태</span><StatusChip status={entity.status} /></div>
      <h3 style={{ margin: "8px 0 0", fontSize: 14 }}>변경이력</h3>
      {history.loading ? <Loading /> : history.error ? <ErrorState error={history.error} onRetry={history.reload} /> :
        (history.data ?? []).length === 0 ? <Empty title="변경이력이 없습니다." /> :
          (history.data ?? []).map((h) => <div className="kv" key={h.audit_id}><span>{ACTION_KO[h.action] ?? h.action}</span><span className="ctx">{h.actor_label} · {formatDateTime(h.occurred_at)}</span></div>)}
    </Drawer>
  );
}
