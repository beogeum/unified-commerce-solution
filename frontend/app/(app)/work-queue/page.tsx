"use client";
import { useMemo, useState } from "react";
import { Chip, StatusChip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Item = { id: string; item_type: string; severity: string; status: string; title_ko: string; cause_ko: string;
  recommended_action_ko: string; occurrence_count: number; last_occurred_at: string; version: number };
const SEV: Record<string, ["err" | "warn" | "mute", string]> = { HIGH: ["err", "긴급"], MEDIUM: ["warn", "보통"], LOW: ["mute", "낮음"] };

export default function WorkQueuePage() {
  const { selected, can } = useSession();
  const [status, setStatus] = useState("OPEN");
  const path = useMemo(() => `/work-items${qs({ status, business_entity_id: selected, limit: "100" })}`, [status, selected]);
  const { data, error, loading, reload } = useApi<Item[]>(path);
  const [detail, setDetail] = useState<Item | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const update = async (it: Item, next: string) => {
    setErr(null);
    try {
      await api(`/work-items/${it.id}`, { method: "PATCH", ifMatch: it.version, body: { status: next } });
      setDetail(null);
      reload();
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : "변경하지 못했습니다.");
    }
  };
  return (
    <>
      <div className="page-head"><div><h1>업무함</h1><p>자동 처리할 수 없는 예외가 모입니다. 같은 원인은 한 건으로 묶이고 발생 횟수가 늘어납니다.</p></div></div>
      <div className="row-gap"><label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}>
        <option value="OPEN">열림</option><option value="IN_PROGRESS">처리 중</option><option value="ON_HOLD">보류</option><option value="DONE">완료</option><option value="">전체</option></select></label></div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="업무" rows={data ?? []} rowKey={(r) => r.id} onRowClick={setDetail}
          empty={<Empty title="처리할 업무가 없습니다." />}
          columns={[
            { key: "sev", header: "중요도", render: (r) => <Chip kind={(SEV[r.severity] ?? ["mute"])[0]}>{(SEV[r.severity] ?? ["mute", r.severity])[1]}</Chip> },
            { key: "title", header: "업무", render: (r) => <><b>{r.title_ko}</b><div className="ctx">{r.cause_ko}</div></> },
            { key: "n", header: "발생", num: true, render: (r) => `${r.occurrence_count}회` },
            { key: "last", header: "최근 발생", render: (r) => <span className="ctx">{formatDateTime(r.last_occurred_at)}</span> },
            { key: "status", header: "상태", render: (r) => <StatusChip status={r.status} /> },
          ]} />
      )}
      {detail && (
        <Drawer title={detail.title_ko} onClose={() => setDetail(null)} footer={can("work_item.update") && detail.status !== "DONE" ? <>
          <button className="btn" type="button" onClick={() => update(detail, "IN_PROGRESS")}>처리 시작</button>
          <button className="btn pri" type="button" onClick={() => update(detail, "DONE")}>완료</button></> : undefined}>
          {err && <div className="note n-err" role="alert">{err}</div>}
          <div className="note n-warn"><b>원인</b><div>{detail.cause_ko}</div></div>
          <div className="note n-info"><b>권장 조치</b><div>{detail.recommended_action_ko}</div></div>
          <div className="kv"><span>발생 횟수</span><span>{detail.occurrence_count}회</span></div>
        </Drawer>
      )}
    </>
  );
}
