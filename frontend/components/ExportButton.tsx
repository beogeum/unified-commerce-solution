"use client";
import { useState } from "react";
import { api, ApiError, newIdempotencyKey } from "@/lib/api";

type Job = { id: string; status: string; result?: { file_id?: string; row_count?: number } | null; error_message_ko?: string | null };

/** 내보내기: 비동기 작업 → 완료 시 다운로드 링크. 범위는 실행 시점에 서버가 다시 평가한다(DEC-F14). */
export function ExportButton({ exportType, businessEntityIds }: { exportType: string; businessEntityIds: string[] }) {
  const [state, setState] = useState<{ status: "idle" | "running" | "done" | "error"; message?: string; fileId?: string; rows?: number }>({ status: "idle" });
  const start = async () => {
    setState({ status: "running", message: "내보내기 파일을 만드는 중입니다…" });
    try {
      const r = await api<{ job_id: string }>("/exports", { method: "POST", idempotencyKey: newIdempotencyKey(),
        body: { export_type: exportType, business_entity_ids: businessEntityIds } });
      for (let i = 0; i < 30; i++) {
        await new Promise((res) => setTimeout(res, 700));
        const j = (await api<Job>(`/jobs/${r.data.job_id}`)).data;
        if (j.status === "SUCCEEDED" && j.result?.file_id) {
          setState({ status: "done", fileId: j.result.file_id, rows: j.result.row_count });
          return;
        }
        if (j.status === "FAILED" || j.status === "REVIEW_REQUIRED") {
          setState({ status: "error", message: j.error_message_ko ?? "내보내기에 실패했습니다." });
          return;
        }
      }
      setState({ status: "error", message: "내보내기가 오래 걸리고 있습니다. 잠시 후 다시 확인하세요." });
    } catch (e) {
      setState({ status: "error", message: e instanceof ApiError ? e.message : "내보내기에 실패했습니다." });
    }
  };
  return (
    <span style={{ display: "inline-flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
      <button className="btn" type="button" onClick={start} disabled={state.status === "running"}>내보내기</button>
      {state.status === "running" && <span className="ctx" role="status">{state.message}</span>}
      {state.status === "done" && <a className="btn" href={`/bff/api/files/${state.fileId}/content`} data-testid="export-download">파일 받기({state.rows}행)</a>}
      {state.status === "error" && <span className="fld-err" role="alert">{state.message}</span>}
    </span>
  );
}
