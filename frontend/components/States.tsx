"use client";
import { ApiError } from "@/lib/api";

export function Loading({ label = "불러오는 중입니다…" }: { label?: string }) {
  return (
    <div className="state" role="status" aria-live="polite">
      <span className="ctx">{label}</span>
      <div style={{ width: "100%", display: "flex", flexDirection: "column", gap: 8 }}>
        <div className="skeleton" /><div className="skeleton" /><div className="skeleton" />
      </div>
    </div>
  );
}

export function Empty({ title, hint, action }: { title: string; hint?: string; action?: React.ReactNode }) {
  return (
    <div className="state">
      <p style={{ margin: 0, fontSize: 14 }}>{title}</p>
      {hint && <p className="ctx" style={{ margin: 0 }}>{hint}</p>}
      {action}
    </div>
  );
}

/** 권한·범위 오류는 '권한 없음', 그 외는 오류 안내 + 다시 시도. 기술 상세는 노출하지 않고 요청번호만 보여 준다. */
export function ErrorState({ error, onRetry }: { error: ApiError; onRetry?: () => void }) {
  if (error.status === 403 && (error.code === "PERMISSION_DENIED" || error.code === "ACCESS_SCOPE_DENIED")) {
    return <Forbidden />;
  }
  return (
    <div className="state" role="alert">
      <div className="note n-err" style={{ maxWidth: 560 }}>
        <b>{error.message}</b>
        {error.requestId && <div className="ctx">문제가 계속되면 요청번호 {error.requestId}를 알려 주세요.</div>}
      </div>
      {onRetry && <button className="btn" type="button" onClick={onRetry}>다시 시도</button>}
    </div>
  );
}

export function Forbidden() {
  return (
    <div className="state" role="alert">
      <p style={{ margin: 0, fontSize: 14 }}>이 화면을 볼 권한이 없습니다.</p>
      <p className="ctx" style={{ margin: 0 }}>필요한 권한이나 사업자 접근 범위를 운영관리자에게 요청하세요.</p>
    </div>
  );
}
