"use client";
import { useEffect, useState } from "react";
import { CatalogTabs } from "@/components/CatalogTabs";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Field = { key: string; label_ko: string; required: boolean; sensitive?: boolean };
type Template = { import_type: string; label_ko: string; template_version: string; required_permission: string; fields: Field[] };
type Batch = { id: string; import_type: string; template_version: string; file_name: string; file_sha256: string; sheet_name: string | null;
  column_mapping: Record<string, string>; status: string; total_rows: number; ok_rows: number; warning_rows: number; error_rows: number;
  applied_rows: number; failed_rows: number; created_by_name: string; created_at: string; error_message_ko: string | null; version: number };
type Row = { row_number: number; status: string; messages: { level: string; field: string; text: string }[]; raw: Record<string, string>;
  result: string; result_message: string | null; applied_ref: string | null };

const STATUS: Record<string, ["info" | "ok" | "warn" | "err" | "mute", string]> = {
  PREVIEWED: ["info", "미리보기"], QUEUED: ["info", "대기"], RUNNING: ["info", "실행 중"], COMPLETED: ["ok", "완료"],
  COMPLETED_WITH_ERRORS: ["warn", "일부 실패"], CANCELLED: ["mute", "취소"], FAILED: ["err", "실패"],
};
const steps = ["파일 업로드", "양식 인식", "컬럼 매핑", "미리보기", "실행 · 결과"];

export default function ImportsPage() {
  const { can } = useSession();
  const templates = useApi<Template[]>("/imports/templates");
  const recent = useApi<Batch[]>("/imports");
  const [type, setType] = useState("PRODUCT_SKU");
  useEffect(() => {
    const t = new URLSearchParams(window.location.search).get("type");
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 서버 렌더와 첫 화면을 같게 두고, 하이드레이션 후 브라우저 값(주소창·로컬 저장소)을 한 번 읽는다
    if (t) setType(t);
  }, []);
  const usable = (templates.data ?? []).filter((t) => can(t.required_permission));
  const catalog = type === "PRODUCT_SKU" || type === "CHANNEL_LISTING";
  const [batchId, setBatchId] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const tpl = (templates.data ?? []).find((t) => t.import_type === type);

  const upload = async (file: File) => {
    setErr(null);
    setBusy(true);
    try {
      const fd = new FormData();
      fd.append("file", file);
      fd.append("purpose", "GENERIC_IMPORT");
      const res = await fetch("/bff/api/files", { method: "POST", body: fd, headers: { "x-cos-csrf": "1" } });
      const j = await res.json().catch(() => null);
      if (!res.ok) throw new ApiError(res.status, j?.error?.code ?? "UPLOAD_FAILED", j?.error?.message ?? "파일을 올리지 못했습니다.");
      const b = await api<Batch>("/imports", { method: "POST", body: { file_id: j.data.id, import_type: type } });
      setBatchId(b.data.id);
      recent.reload();
    } catch (e) {
      const a = e as ApiError;
      const missing = a.details?.missing as string[] | undefined;
      setErr(missing ? `${a.message}` : a.message ?? "처리하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };

  if (!can("catalog.manage") && !can("order.update")) return <ErrorState error={new ApiError(403, "PERMISSION_DENIED", "권한 없음")} />;
  return (
    <>
      <div className="page-head"><div><h1>일괄 작업 — 엑셀·CSV 가져오기</h1>
        <p>파일을 바로 반영하지 않고, 미리보기에서 오류·경고를 확인한 뒤 실행합니다. 실행은 비동기 작업으로 처리되고 행마다 결과가 남습니다.</p></div></div>
      {catalog && <CatalogTabs active="imports" />}
      {tpl?.fields.some((f) => f.sensitive) && <div className="note n-info">이 양식에는 개인정보(수령인 이름·연락처·주소)가 들어갑니다. 미리보기에서는 가려서 보여 주고, 실행·취소 후 원본 파일은 삭제됩니다(실행하지 않으면 7일 후 자동 취소).</div>}
      <ol aria-label="진행 단계" className="row-gap" style={{ listStyle: "none", margin: 0, padding: 0 }}>
        {steps.map((s, i) => <li key={s} className="chip c-mute" aria-current={(batchId ? 3 : 0) === i ? "step" : undefined}
          style={(batchId ? i <= 3 : i === 0) ? { background: "var(--primary-soft)", color: "var(--primary-strong)" } : undefined}>{i + 1}. {s}</li>)}
      </ol>
      <section className="card pad" aria-label="파일 올리기" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        <div className="row-gap">
          <label className="fld">가져오기 종류<select className="inp" value={type} onChange={(e) => { setType(e.target.value); setBatchId(null); }}>
            {usable.map((t) => <option key={t.import_type} value={t.import_type}>{t.label_ko} ({t.template_version})</option>)}</select></label>
          <a className="btn" href={`/bff/api/imports/templates/${type}/file`}>양식 내려받기</a>
          <label className="btn" style={{ cursor: "pointer" }}>{busy ? "올리는 중…" : "파일 선택(.xlsx, .csv)"}
            <input type="file" accept=".xlsx,.csv" hidden aria-label="가져올 파일" disabled={busy} onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])} /></label>
        </div>
        {tpl && <p className="ctx" style={{ margin: 0 }}>머리글: {tpl.fields.map((f) => f.label_ko + (f.required ? "*" : "")).join(", ")}. 같은 이름의 머리글을 자동으로 찾습니다(* 필수). 파일은 10MB·5,000행까지.</p>}
        {err && <div className="note n-err" role="alert">{err}</div>}
      </section>
      {batchId && <BatchPanel id={batchId} onChanged={recent.reload} />}
      <section style={{ display: "flex", flexDirection: "column", gap: 8 }} aria-label="최근 일괄 작업">
        <h2>최근 일괄 작업</h2>
        {recent.loading ? <Loading /> : recent.error ? <ErrorState error={recent.error} onRetry={recent.reload} /> : (
          <DataTable caption="일괄 작업" rows={recent.data ?? []} rowKey={(r) => r.id} onRowClick={(r) => setBatchId(r.id)}
            empty={<Empty title="아직 일괄 작업이 없습니다." />}
            columns={[
              { key: "f", header: "파일", render: (r) => <><b>{r.file_name}</b><div className="ctx">{(templates.data ?? []).find((t) => t.import_type === r.import_type)?.label_ko} {r.template_version}</div></> },
              { key: "by", header: "요청", render: (r) => <span className="ctx">{r.created_by_name} · {formatDateTime(r.created_at)}</span> },
              { key: "n", header: "결과", render: (r) => r.status === "PREVIEWED" ? `정상 ${r.ok_rows} · 경고 ${r.warning_rows} · 오류 ${r.error_rows}` : `성공 ${r.applied_rows} · 실패 ${r.failed_rows}` },
              { key: "s", header: "상태", render: (r) => <Chip kind={STATUS[r.status]?.[0] ?? "mute"}>{STATUS[r.status]?.[1] ?? r.status}</Chip> },
            ]} />)}
      </section>
    </>
  );
}

function BatchPanel({ id, onChanged }: { id: string; onChanged: () => void }) {
  const { data, error, loading, reload } = useApi<Batch>(`/imports/${id}`);
  const [filter, setFilter] = useState("problems");
  const rows = useApi<Row[]>(`/imports/${id}/rows${qs({ status: filter === "problems" ? undefined : filter, limit: "200" })}`);
  const [confirm, setConfirm] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const running = data?.status === "QUEUED" || data?.status === "RUNNING";
  useEffect(() => {
    if (!running) return;
    const t = setInterval(() => { reload(); rows.reload(); }, 1500);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [running]);
  useEffect(() => { if (data && !running) onChanged(); /* 상태가 끝나면 목록 갱신 */ // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data?.status]);
  if (loading && !data) return <Loading />;
  if (error) return <ErrorState error={error} onRetry={reload} />;
  if (!data) return null;
  const b = data;
  const executable = b.ok_rows + b.warning_rows;
  const shown = (rows.data ?? []).filter((r) => filter !== "problems" || r.status !== "OK" || r.result === "FAILED");
  const headers = Object.keys(b.column_mapping);
  const execute = async () => {
    setConfirm(false);
    setMsg(null);
    try {
      await api(`/imports/${id}/execute`, { method: "POST", ifMatch: b.version, body: { confirm_rows: executable } });
      reload();
    } catch (e) {
      setMsg(e instanceof ApiError ? e.message : "실행하지 못했습니다.");
    }
  };
  return (
    <section className="card" aria-labelledby="pv-h" style={{ minWidth: 0 }}>
      <div className="page-head" style={{ padding: "16px 16px 8px" }}>
        <h2 id="pv-h">미리보기 — {b.file_name}{b.sheet_name ? ` · 시트 '${b.sheet_name}'` : ""} · 양식 {b.template_version}</h2>
        <span className="ctx">SHA-256 {b.file_sha256.slice(0, 8)}… · 업로드 {b.created_by_name} {formatDateTime(b.created_at)}</span>
      </div>
      <div className="row-gap" style={{ padding: "0 16px 8px" }}>
        <Chip kind="ok">정상 {b.ok_rows}행</Chip><Chip kind="warn">경고 {b.warning_rows}행</Chip><Chip kind="err">오류 {b.error_rows}행</Chip><Chip>전체 {b.total_rows}행</Chip>
        <Chip kind={STATUS[b.status][0]}>{STATUS[b.status][1]}</Chip>
        {b.status !== "PREVIEWED" && b.status !== "CANCELLED" && <span className="ctx" role="status">성공 {b.applied_rows} · 실패 {b.failed_rows}</span>}
      </div>
      <p className="ctx" style={{ margin: "0 16px 8px" }}>컬럼 매핑: {headers.map((h) => `${h} → ${b.column_mapping[h]}`).join(", ")}</p>
      {msg && <div className="note n-err" role="alert" style={{ margin: "0 16px" }}>{msg}</div>}
      {b.error_message_ko && <div className="note n-err" role="alert" style={{ margin: "0 16px" }}>{b.error_message_ko}</div>}
      <div role="tablist" aria-label="행 보기" className="row-gap" style={{ padding: "0 16px" }}>
        {[["problems", "문제 행"], ["ERROR", "오류"], ["WARNING", "경고"], ["FAILED", "실행 실패"], ["", "전체"]].map(([k, l]) =>
          <button key={k} role="tab" aria-selected={filter === k} className="btn" type="button" onClick={() => setFilter(k)}
            style={filter === k ? { borderColor: "var(--primary)", color: "var(--primary-strong)" } : undefined}>{l}</button>)}
      </div>
      <div className="tbl-wrap">
        {rows.loading && !rows.data ? <Loading /> : shown.length === 0 ? <Empty title="해당하는 행이 없습니다." /> : (
          <table>
            <thead><tr><th scope="col" className="num">행</th>{headers.slice(0, 4).map((h) => <th key={h} scope="col">{h}</th>)}<th scope="col">결과</th><th scope="col">사유</th></tr></thead>
            <tbody>{shown.map((r) => <tr key={r.row_number}>
              <td className="num">{r.row_number}</td>{headers.slice(0, 4).map((h) => <td key={h}>{r.raw[h]}</td>)}
              <td>{r.result === "APPLIED" ? <Chip kind="ok">반영</Chip> : r.result === "FAILED" ? <Chip kind="err">실행 실패</Chip>
                : <Chip kind={r.status === "ERROR" ? "err" : r.status === "WARNING" ? "warn" : "ok"}>{r.status === "ERROR" ? "오류" : r.status === "WARNING" ? "경고" : "정상"}</Chip>}</td>
              <td>{[...r.messages.map((m) => m.text), r.result_message].filter(Boolean).join(" / ") || "—"}</td></tr>)}</tbody>
          </table>)}
      </div>
      <div className="row-gap" style={{ padding: "12px 16px", alignItems: "center" }}>
        <a className="btn" href={`/bff/api/imports/${id}/problem-rows`}>문제 행 내려받기</a>
        {b.status === "PREVIEWED" && <>
          <button className="btn" type="button" onClick={async () => { await api(`/imports/${id}/cancel`, { method: "POST", ifMatch: b.version, body: {} }); reload(); onChanged(); }}>취소</button>
          <button className="btn pri" type="button" disabled={executable === 0} onClick={() => setConfirm(true)}>정상·경고 {executable}행 실행</button>
          {b.error_rows > 0 && <span className="ctx">오류 {b.error_rows}행은 실행에서 빠집니다.</span>}</>}
      </div>
      {confirm && <ConfirmModal title={`${executable}행을 실행할까요?`} confirmLabel={`${executable}행 실행`}
        impact={[["정상", `${b.ok_rows}행`], ["경고(그대로 반영)", `${b.warning_rows}행`], ["오류(제외)", `${b.error_rows}행`], ["처리", "비동기 — 행마다 성공·실패 기록"]]}
        onConfirm={execute} onClose={() => setConfirm(false)} />}
    </section>
  );
}
