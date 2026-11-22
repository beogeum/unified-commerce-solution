"use client";
import { FormEvent, useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { InventoryTabs } from "@/components/InventoryTabs";
import { Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { ApiError, qs } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { LINE_ISSUE_KO, Node, num, RESTRICTION_KO, SEMANTIC_KO, Snapshot, SnapshotDetail, SNAPSHOT_STATUS } from "@/lib/inventory";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

export default function ExternalInventoryPage() {
  const { can, selected } = useSession();
  const [node, setNode] = useState("");
  const nodes = useApi<Node[]>(can("logistics_account.read") ? "/fulfillment-nodes" : null);
  const external = (nodes.data ?? []).filter((n) => n.inventory_source === "EXTERNAL");
  const path = useMemo(() => `/inventory/external-snapshots${qs({ business_entity_id: selected, node_id: node })}`, [selected, node]);
  const { data, error, loading, reload } = useApi<Snapshot[]>(can("inventory.read") ? path : null);
  const [open, setOpen] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  if (!can("inventory.read")) return <Forbidden />;
  return (
    <>
      <div className="page-head">
        <div><h1>외부 재고 동기화</h1><p>3PL·플랫폼 물류가 준 재고 자료(연동 조회·파일)를 원본 그대로 보관하고, 거점의 수량 의미와 기준시각으로 해석해 반영합니다.
          이미 물류사에 전달된 출고지시는 다시 빼지 않고, 판단할 수 없으면 보수적으로 판매를 제한합니다.</p></div>
        {can("inventory.operate") && <button className="btn pri" type="button" onClick={() => setUploading(true)} disabled={external.length === 0}>재고 파일 올리기</button>}
      </div>
      <InventoryTabs active="external" />
      {external.length === 0 && !nodes.loading && <div className="note n-info">물류사 재고 기준 거점이 없습니다. 물류·3PL 에서 3PL 거점을 등록하고 화주계정을 연결하세요.</div>}
      <div className="row-gap">
        <label className="fld">출고거점<select className="inp" value={node} onChange={(e) => setNode(e.target.value)}><option value="">전체</option>
          {external.map((n) => <option key={n.id} value={n.id}>{n.name} · {SEMANTIC_KO[n.quantity_semantic]}</option>)}</select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="외부 재고 스냅샷" rows={data ?? []} rowKey={(r) => r.id} onRowClick={(r) => setOpen(r.id)}
          empty={<Empty title="받은 외부 재고 자료가 없습니다." hint="연동 관리에서 재고 조회를 실행하거나 물류사 재고 파일을 올리세요." />}
          columns={[
            { key: "t", header: "받은 시각", render: (r) => <span className="ctx">{formatDateTime(r.created_at)}</span> },
            { key: "n", header: "거점 · 사업자", render: (r) => <>{r.node_name} · {r.business_entity_name}</> },
            { key: "src", header: "원천", render: (r) => r.source === "API" ? "연동 조회" : r.file_id ? "파일" : "직접 입력" },
            { key: "a", header: "기준시각(워터마크)", render: (r) => r.as_of ? formatDateTime(r.as_of) : <Chip kind="warn">없음</Chip> },
            { key: "sem", header: "수량 의미", render: (r) => SEMANTIC_KO[r.quantity_semantic] },
            { key: "l", header: "행", num: true, render: (r) => <>{num(r.line_count)}{r.issue_count > 0 && <> <Chip kind="warn">확인 {r.issue_count}</Chip></>}</> },
            { key: "s", header: "결과", render: (r) => <Chip kind={SNAPSHOT_STATUS[r.status][0]}>{SNAPSHOT_STATUS[r.status][1]}</Chip> },
          ]} />
      )}
      <p className="ctx">같은 자료(같은 파일·같은 연동 작업)는 한 번만 반영합니다. 마지막으로 반영한 기준시각보다 이른 자료는 보관만 하고 반영하지 않습니다.</p>
      {open && <SnapshotDrawer id={open} onClose={() => setOpen(null)} />}
      {uploading && <UploadDrawer nodes={external} onClose={() => setUploading(false)} onDone={(id) => { setUploading(false); reload(); setOpen(id); }} />}
    </>
  );
}

function SnapshotDrawer({ id, onClose }: { id: string; onClose: () => void }) {
  const d = useApi<SnapshotDetail>(`/inventory/external-snapshots/${id}`);
  const s = d.data?.snapshot;
  return (
    <Drawer title="외부 재고 자료" onClose={onClose}>
      {d.loading ? <Loading /> : d.error ? <ErrorState error={d.error} onRetry={d.reload} /> : s && d.data && (
        <>
          <div className="kv"><span>거점 · 사업자</span><span>{s.node_name} · {s.business_entity_name}</span></div>
          <div className="kv"><span>기준시각</span><span>{s.as_of ? formatDateTime(s.as_of) : "없음(판단 불가)"}</span></div>
          <div className="kv"><span>수량 의미(받을 때)</span><span>{SEMANTIC_KO[s.quantity_semantic]}</span></div>
          <div className="kv"><span>전체 자료 여부</span><span>{s.complete ? "전체(목록에 없는 SKU 는 0)" : "일부(목록에 있는 SKU 만)"}</span></div>
          <div className="kv"><span>매핑 규칙 · 원천 키</span><span className="mono">{s.mapping_version} · {s.source_key}</span></div>
          <div className="kv"><span>결과</span><span><Chip kind={SNAPSHOT_STATUS[s.status][0]}>{SNAPSHOT_STATUS[s.status][1]}</Chip></span></div>
          {s.status === "IGNORED_OUT_OF_ORDER" && <div className="note n-info">이미 반영한 자료보다 기준시각이 이르거나 같아 재고에 반영하지 않았습니다(원본만 보관).</div>}
          <h3 style={{ fontSize: 14 }}>SKU 별 판단 근거</h3>
          <DataTable caption="SKU 별 판단" rows={d.data.results} rowKey={(r) => r.sku_id} empty={<Empty title="반영한 SKU 가 없습니다." />}
            columns={[
              { key: "s", header: "SKU", render: (r) => <span className="mono">{r.sku_code}</span> },
              { key: "e", header: "외부 판매가능", num: true, render: (r) => num(r.external_sellable) },
              { key: "rf", header: "+ 반영된 확보", num: true, render: (r) => num(r.reflected_quantity) },
              { key: "sh", header: "− 이후 출고", num: true, render: (r) => num(r.shipped_after_quantity) },
              { key: "sa", header: "판매가능 전→후", num: true, render: (r) => `${num(r.sellable_before)} → ${num(r.sellable_after)}` },
              { key: "c", header: "확보", num: true, render: (r) => num(r.committed_at_apply) },
              { key: "ns", header: "비가용 외부 / 내부 전→후", render: (r) => `${num(r.external_non_sellable)} / ${num(r.non_sellable_before)} → ${num(r.non_sellable_after)}` },
              { key: "j", header: "판단", render: (r) => r.certain ? <Chip kind="ok">확인</Chip> : <Chip kind="warn">판매 제한 · {RESTRICTION_KO[r.restriction_reason ?? ""] ?? r.restriction_reason}</Chip> },
            ]} />
          <h3 style={{ fontSize: 14 }}>원본 행</h3>
          <DataTable caption="원본 행" rows={d.data.lines} rowKey={(l) => String(l.line_no)} empty={<Empty title="행이 없습니다." />}
            columns={[
              { key: "n", header: "#", num: true, render: (l) => l.line_no },
              { key: "c", header: "외부 SKU 코드", render: (l) => <span className="mono">{l.external_sku_code}</span> },
              { key: "m", header: "연결 SKU", render: (l) => l.sku_code ? <span className="mono">{l.sku_code}</span> : <Chip kind="warn">{LINE_ISSUE_KO[l.issue ?? ""] ?? l.issue}</Chip> },
              { key: "lot", header: "LOT · 로케이션", render: (l) => [l.lot_code, l.location_code].filter(Boolean).join(" · ") || "—" },
              { key: "st", header: "외부 상태", render: (l) => l.external_status ?? "—" },
              { key: "sell", header: "판매가능", render: (l) => (l.sellable ? "예" : "아니오") },
              { key: "q", header: "수량", num: true, render: (l) => num(l.quantity) },
            ]} />
        </>
      )}
    </Drawer>
  );
}

function UploadDrawer({ nodes, onClose, onDone }: { nodes: Node[]; onClose: () => void; onDone: (id: string) => void }) {
  const { entities } = useSession();
  const [nodeSel, setNode] = useState("");
  const node = nodeSel || nodes[0]?.id || "";
  const n = nodes.find((x) => x.id === node);
  const owners = entities.filter((e) => n?.shippers.some((s) => s.business_entity_id === e.id));
  const [be, setBe] = useState("");
  const [asOf, setAsOf] = useState("");
  const [complete, setComplete] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [errRows, setErrRows] = useState<{ row: number; message: string }[]>([]);
  const [busy, setBusy] = useState(false);
  const owner = be || owners[0]?.id || "";
  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!file) return;
    setErr(null);
    setErrRows([]);
    setBusy(true);
    try {
      const fd = new FormData();
      fd.append("file", file);
      fd.append("node_id", node);
      fd.append("business_entity_id", owner);
      if (asOf) fd.append("as_of", new Date(asOf).toISOString());
      fd.append("complete", String(complete));
      const res = await fetch("/bff/api/inventory/external-snapshots/upload", { method: "POST", body: fd, headers: { "x-cos-csrf": "1" } });
      const j = await res.json().catch(() => null);
      if (!res.ok) {
        setErrRows(j?.error?.details?.errors ?? []);
        throw new ApiError(res.status, j?.error?.code ?? "UPLOAD_FAILED", j?.error?.message ?? "파일을 반영하지 못했습니다.");
      }
      onDone(j.data.snapshot.id);
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "파일을 반영하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <Drawer title="물류사 재고 파일 올리기" onClose={onClose} footer={<><button className="btn" type="button" onClick={onClose}>취소</button>
      <button className="btn pri" type="submit" form="ext-upload" disabled={!file || !node || !owner || busy}>{busy ? "반영 중…" : "반영"}</button></>}>
      <form id="ext-upload" onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {err && <div className="note n-err" role="alert">{err}{errRows.length > 0 && <ul>{errRows.slice(0, 10).map((r) => <li key={r.row}>{r.row}행: {r.message}</li>)}</ul>}</div>}
        <label className="fld">출고거점<select className="inp" value={node} onChange={(e) => { setNode(e.target.value); setBe(""); }}>
          {nodes.map((x) => <option key={x.id} value={x.id}>{x.name} ({x.code})</option>)}</select></label>
        {n && <div className="kv"><span>수량 의미</span><span>{SEMANTIC_KO[n.quantity_semantic]}</span></div>}
        {n?.quantity_semantic === "UNKNOWN" && <div className="note n-warn">이 거점의 수량 의미가 확인되지 않아 반영 후에도 보수적으로 판매가 제한됩니다.</div>}
        <label className="fld">소유 사업자(화주계정 연결된 사업자)<select className="inp" value={owner} onChange={(e) => setBe(e.target.value)}>
          {owners.map((x) => <option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
        <label className="fld">물류사 자료 기준시각<input className="inp" type="datetime-local" value={asOf} onChange={(e) => setAsOf(e.target.value)} /></label>
        {!asOf && <p className="ctx" style={{ margin: 0 }}>기준시각이 없으면 어떤 출고까지 반영된 수량인지 알 수 없어 판매가 제한됩니다.</p>}
        <label><input type="checkbox" checked={complete} onChange={(e) => setComplete(e.target.checked)} /> 전체 재고 파일(파일에 없는 SKU 는 0 으로 반영)</label>
        <label className="fld">파일(.xlsx·.csv)<input className="inp" type="file" accept=".xlsx,.csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>
        <p className="ctx" style={{ margin: 0 }}>필수 열: SKU 코드, 수량, 판매가능(Y/N). 선택 열: 상태, LOT, 유통기한, 로케이션. 잘못된 행이 하나라도 있으면 파일 전체를 반영하지 않습니다.</p>
      </form>
    </Drawer>
  );
}
