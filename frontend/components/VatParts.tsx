"use client";
import { FormEvent, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError } from "@/lib/api";
import { VAT_CLASS_KO, VAT_ISSUE_KO, VatBatch, VatClassTotal, VatRow, won } from "@/lib/finance";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

const msg = (x: unknown, f: string) => (x instanceof ApiError ? x.message : f);

export function VatTotals({ totals }: { totals: VatClassTotal[] }) {
  return (
    <DataTable caption="분류별 합계" rows={totals} rowKey={(t) => t.classification} empty={<Empty title="자료가 없습니다." />} columns={[
      { key: "c", header: "분류", render: (t) => (t.classification === "REVIEW" ? <Chip kind="warn">{t.label}</Chip> : t.classification === "EXCLUDED" ? <Chip>{t.label}</Chip> : t.label) },
      { key: "n", header: "건수", num: true, render: (t) => t.rows },
      { key: "s", header: "공급가", num: true, render: (t) => won(t.supply) },
      { key: "v", header: "세액", num: true, render: (t) => won(t.vat) },
      { key: "t", header: "합계", num: true, render: (t) => won(t.total) },
    ]} />
  );
}

/** 신고자료 묶음 상세: 파일 올리기·분류 합계·대사·문제 행 보정·확정. */
export function VatBatchDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can, me } = useSession();
  const { data: b, error, loading, reload } = useApi<VatBatch>(`/vat/batches/${id}`);
  const [file, setFile] = useState<File | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [fixing, setFixing] = useState<VatRow | null>(null);
  const [cls, setCls] = useState("CARD");
  const [reason, setReason] = useState("");
  const [confirming, setConfirming] = useState(false);
  const manage = can("vat.manage") && b?.status === "DRAFT";
  const done = (m: string) => { setNotice(m); reload(); onChanged(); };
  const upload = async (e: FormEvent) => {
    e.preventDefault();
    if (!file) return;
    setBusy(true);
    setNotice(null);
    try {
      const fd = new FormData();
      fd.append("file", file);
      const res = await fetch(`/bff/api/vat/batches/${id}/files`, { method: "POST", body: fd, headers: { "x-cos-csrf": "1" } });
      const j = await res.json().catch(() => null);
      if (!res.ok) throw new ApiError(res.status, j?.error?.code ?? "UPLOAD_FAILED", j?.error?.message ?? "파일을 반영하지 못했습니다.");
      setFile(null);
      done(`${j.data.row_count}행 중 새 행 ${j.data.new_rows} · 중복 ${j.data.duplicate_rows} · 보정 필요 ${j.data.blocking_rows} · 경고 ${j.data.warning_rows}`);
    } catch (x) {
      setNotice(msg(x, "파일을 반영하지 못했습니다."));
    } finally {
      setBusy(false);
    }
  };
  const override = async () => {
    if (!fixing) return;
    try {
      await api(`/vat/rows/${fixing.id}/override`, { method: "POST", body: { classification: cls, reason } });
      setFixing(null);
      setReason("");
      done("분류를 보정했습니다. 원본 행은 그대로 남습니다.");
    } catch (x) {
      setNotice(msg(x, "보정하지 못했습니다."));
    }
  };
  const confirm = async () => {
    try {
      await api(`/vat/batches/${id}/confirm`, { method: "POST", body: {} });
      setConfirming(false);
      done("확정했습니다. 이후에는 자료를 바꿀 수 없습니다.");
    } catch (x) {
      setConfirming(false);
      setNotice(msg(x, "확정하지 못했습니다."));
    }
  };
  const uploadedByMe = b?.files.some((f) => f.imported_by_name === me.display_name) ?? false;
  return (
    <Drawer title={b ? `${b.channel_account_name} ${b.period_from} ~ ${b.period_to}` : "신고자료"} onClose={onClose}
      footer={manage ? <button className="btn pri" type="button" onClick={() => setConfirming(true)} disabled={b.files.length === 0}>검토 후 확정</button> : undefined}>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : b && <>
        {notice && <div className="note n-info" role="status">{notice}</div>}
        <div className="kv"><span>사업자</span><span>{b.business_entity_name} · {b.business_registration_no}</span></div>
        <div className="kv"><span>상태</span><span>{b.status === "CONFIRMED" ? <Chip kind="ok">확정 {b.confirmed_by_name ?? ""} {b.confirmed_at ? formatDateTime(b.confirmed_at) : ""}</Chip>
          : <Chip kind="warn">작성 중</Chip>} <span className="ctx">분류 규칙 {b.rule_version}(세무 담당 확정 전 임시)</span></span></div>
        <div className="kv"><span>행 · 보정 필요 · 경고</span><span>{b.rows} · {b.blocking_rows} · {b.warning_rows}</span></div>
        {manage && <form className="row-gap" onSubmit={upload}>
          <label className="fld">신고자료 파일(.xlsx·.csv)<input className="inp" type="file" accept=".xlsx,.csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>
          <button className="btn" type="submit" disabled={!file || busy}>{busy ? "반영 중…" : "올리기"}</button>
        </form>}
        {manage && <p className="ctx" style={{ margin: 0 }}>표준 양식 필수 열: 거래일, 매출유형(판매·환불·조정), 세금유형(과세·면세·영세율·기타), 결제수단(카드·현금영수증·휴대폰·기타), 증빙유형(세금계산서·전자세금계산서·계산서·전자계산서·없음), 공급가, 세액.
          선택 열: 주문번호, 합계, 사업자등록번호(있으면 이 사업자와 일치해야 함). 채널은 파일명으로 추정하지 않고 이 묶음의 판매계정으로 정합니다. 채널별 원본 양식 자동 변환은 실계정 확인 후 지원합니다.</p>}
        <h3>분류별 합계</h3>
        <VatTotals totals={b.totals} />
        <h3>대사(부가세 포함)</h3>
        <div className="kv"><span>신고자료 판매 · 환불</span><span>{won(b.recon.vat_sales)} · {won(b.recon.vat_refunds)}</span></div>
        <div className="kv"><span>원장(주문 기준) 매출 · 반품</span><span>{won(b.recon.ledger_sales)} · {won(b.recon.ledger_refunds)}</span></div>
        <div className="kv"><span>정산 매출</span><span>{b.recon.settlement_imported ? won(b.recon.settlement_total) : "미확인(정산 미가져옴)"}</span></div>
        <div className="kv"><span>차이(신고자료 − 원장 · − 정산)</span><span><b>{won(b.recon.diff_vs_ledger)}</b> · {b.recon.diff_vs_settlement === null ? "—" : won(b.recon.diff_vs_settlement)}</span></div>
        <p className="ctx">{b.recon.note}</p>
        <h3>파일</h3>
        <DataTable caption="원본 파일" rows={b.files} rowKey={(f) => f.id} empty={<Empty title="올린 파일이 없습니다." />} columns={[
          { key: "n", header: "파일", render: (f) => <>{f.filename}<div className="ctx mono">SHA-256 {f.sha256.slice(0, 12)}… · {f.parser_version}</div></> },
          { key: "r", header: "행 · 새 행 · 중복", num: true, render: (f) => `${f.row_count} · ${f.new_rows} · ${f.duplicate_rows}` },
          { key: "b", header: "올린 사람", render: (f) => <span className="ctx">{f.imported_by_name ?? ""} {formatDateTime(f.imported_at)}</span> },
        ]} />
        <h3>문제·보정 행</h3>
        <DataTable caption="문제·보정 행" rows={b.issue_rows} rowKey={(r) => r.id} empty={<Empty title="문제 행이 없습니다." />} columns={[
          { key: "a", header: "보정", render: (r) => (manage ? <button className="btn" type="button" onClick={() => { setFixing(r); setCls(r.blocking ? "EXCLUDED" : r.effective_classification); }}>
            {r.blocking ? "보정 필요" : "분류 보정"}</button> : null) },
          { key: "r", header: "파일 · 행", render: (r) => <span className="ctx">{r.file_name} #{r.row_no}</span> },
          { key: "d", header: "거래일 · 주문", render: (r) => <span className="mono">{r.txn_date} {r.source_key ?? ""}</span> },
          { key: "t", header: "합계", num: true, render: (r) => won(r.total) },
          { key: "i", header: "문제", render: (r) => <span className="ctx">{r.issues.map((x) => VAT_ISSUE_KO[x] ?? x).join(", ")}</span> },
          { key: "c", header: "분류", render: (r) => <>{VAT_CLASS_KO[r.effective_classification]}{r.override_reason ? <div className="ctx">보정: {r.override_reason}</div> : null}</> },
        ]} />
      </>}
      {fixing && <ConfirmModal title="분류 보정" confirmLabel="보정" confirmDisabled={!reason.trim()} onClose={() => setFixing(null)} onConfirm={override}
        impact={[["행", `${fixing.file_name} #${fixing.row_no} · ${won(fixing.total)}원`], ["원본", "원본 행은 바꾸지 않고 보정 기록을 남깁니다"]]}>
        <label className="fld">분류<select className="inp" value={cls} onChange={(e) => setCls(e.target.value)} aria-label="보정 분류">
          {Object.entries(VAT_CLASS_KO).filter(([k]) => k !== "REVIEW").map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
        <label className="fld">사유<input className="inp" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
      </ConfirmModal>}
      {confirming && b && <ConfirmModal title="신고자료를 확정할까요?" confirmLabel="확정" onClose={() => setConfirming(false)} onConfirm={confirm}
        impact={[["확정자", uploadedByMe ? "자료를 올린 담당자는 확정할 수 없습니다" : "자료를 올리지 않은 회계 담당자"], ["조건", `보정 필요 행 ${b.blocking_rows}건이 0 이어야 합니다`],
          ["효과", "확정 후에는 파일 추가·보정을 할 수 없습니다"]]} />}
    </Drawer>
  );
}

type Account = { id: string; display_name: string; business_entity_name: string };

export function NewVatBatchModal({ accounts, onClose, onDone }: { accounts: Account[]; onClose: () => void; onDone: (id: string) => void }) {
  const y = new Date(Date.now() + 9 * 3600_000).getUTCFullYear();
  const [accSel, setAcc] = useState("");
  const acc = accSel || accounts[0]?.id || "";
  const [from, setFrom] = useState(`${y}-07-01`);
  const [to, setTo] = useState(`${y}-12-31`);
  const [err, setErr] = useState<string | null>(null);
  const submit = async () => {
    try {
      const r = await api<VatBatch>("/vat/batches", { method: "POST", body: { channel_account_id: acc, period_from: from, period_to: to } });
      onDone(r.data.id);
    } catch (x) {
      setErr(msg(x, "만들지 못했습니다."));
    }
  };
  return (
    <ConfirmModal title="신고자료 묶음 만들기" confirmLabel="만들기" confirmDisabled={!acc || !from || !to} onClose={onClose} onConfirm={submit}
      impact={[["단위", "판매계정 × 신고기간. 사업자는 판매계정의 사업자이며 다른 사업자 자료와 섞지 않습니다"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">판매계정<select className="inp" value={acc} onChange={(e) => setAcc(e.target.value)} aria-label="신고자료 판매계정">
        {accounts.map((a) => <option key={a.id} value={a.id}>{a.business_entity_name} · {a.display_name}</option>)}</select></label>
      <div className="row-gap">
        <button className="btn" type="button" onClick={() => { setFrom(`${y}-01-01`); setTo(`${y}-06-30`); }}>{y}년 1기</button>
        <button className="btn" type="button" onClick={() => { setFrom(`${y}-07-01`); setTo(`${y}-12-31`); }}>{y}년 2기</button>
      </div>
      <label className="fld">시작일<input className="inp" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
      <label className="fld">종료일<input className="inp" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
    </ConfirmModal>
  );
}

type Job = { id: string; status: string; result?: { file_id?: string; row_count?: number } | null; error_message_ko?: string | null };

/** 사업자 1곳 단위 내보내기(비동기, 실행 시점 범위 재평가). */
export function VatExportButton({ businessEntityId, from, to }: { businessEntityId: string; from: string; to: string }) {
  const [state, setState] = useState<{ status: "idle" | "running" | "done" | "error"; message?: string; fileId?: string }>({ status: "idle" });
  const start = async () => {
    setState({ status: "running", message: "신고자료 파일을 만드는 중입니다…" });
    try {
      const r = await api<{ job_id: string }>("/vat/exports", { method: "POST", body: { business_entity_id: businessEntityId, period_from: from, period_to: to } });
      for (let i = 0; i < 30; i++) {
        await new Promise((res) => setTimeout(res, 700));
        const j = (await api<Job>(`/jobs/${r.data.job_id}`)).data;
        if (j.status === "SUCCEEDED" && j.result?.file_id) {
          setState({ status: "done", fileId: j.result.file_id });
          return;
        }
        if (j.status === "FAILED" || j.status === "REVIEW_REQUIRED") {
          setState({ status: "error", message: j.error_message_ko ?? "내보내기에 실패했습니다." });
          return;
        }
      }
      setState({ status: "error", message: "내보내기가 오래 걸리고 있습니다. 잠시 후 다시 확인하세요." });
    } catch (e) {
      setState({ status: "error", message: msg(e, "내보내기에 실패했습니다.") });
    }
  };
  return (
    <span className="row-gap">
      <button className="btn" type="button" onClick={start} disabled={state.status === "running"}>이 사업자 신고자료 내보내기</button>
      {state.status === "running" && <span className="ctx" role="status">{state.message}</span>}
      {state.status === "done" && <a className="btn" href={`/bff/api/files/${state.fileId}/content`} data-testid="vat-export-download">파일 받기</a>}
      {state.status === "error" && <span className="fld-err" role="alert">{state.message}</span>}
    </span>
  );
}
