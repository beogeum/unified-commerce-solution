"use client";
import { useState } from "react";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Hours = { weekday: number; start: string; end: string };
type Holiday = { id: string; day: string; name: string };
type Cal = { hours: Hours[]; default_hours: boolean; holidays: Holiday[]; cs_sla: { mode: string; base_hours: number; configured: boolean; version: number } };
type Preview = { from: string; priority: string; due: string; mode: string; hours: number };
const DAYS = ["", "월", "화", "수", "목", "금", "토", "일"];
const hm = (t: string) => t.slice(0, 5);

export default function BusinessCalendarPage() {
  const { can } = useSession();
  const manage = can("calendar.manage");
  const cal = useApi<Cal>("/settings/business-calendar");
  const [mode, setMode] = useState<null | "hours" | "holiday" | "sla">(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [rows, setRows] = useState<Record<number, { on: boolean; start: string; end: string }>>({});
  const [hol, setHol] = useState({ day: "", name: "" });
  const [sla, setSla] = useState({ mode: "BUSINESS_HOURS", base_hours: 9 });
  const [prio, setPrio] = useState("NORMAL");
  const preview = useApi<Preview>(`/settings/business-calendar/cs-sla/preview${qs({ priority: prio })}`);
  const run = async (p: Promise<unknown>, done: string) => {
    try {
      await p;
      setNotice(done);
      setMode(null);
      cal.reload();
      preview.reload();
    } catch (x) {
      setMode(null);
      setNotice(x instanceof ApiError ? x.message : "저장하지 못했습니다.");
    }
  };
  const openHours = () => {
    const m: Record<number, { on: boolean; start: string; end: string }> = {};
    for (let d = 1; d <= 7; d++) {
      const h = cal.data?.hours.find((x) => x.weekday === d);
      m[d] = { on: !!h, start: h ? hm(h.start) : "09:00", end: h ? hm(h.end) : "18:00" };
    }
    setRows(m);
    setMode("hours");
  };
  const c = cal.data;
  return (
    <>
      <div className="page-head"><div><h1>영업일·CS 응답기한</h1><p>운영그룹 공통 영업시간(한국 시간)과 휴일을 정하고, CS 문의 응답기한을 달력 시간 또는 영업시간으로 계산할지 정합니다.
        공휴일은 시스템이 추정하지 않으니 직접 등록하세요. 바꾼 정책은 이후 접수·재문의부터 적용됩니다.</p></div></div>
      {notice && <div className="note n-info" role="status">{notice}</div>}
      {cal.loading ? <Loading /> : cal.error ? <ErrorState error={cal.error} onRetry={cal.reload} /> : c && <>
        <section className="card pad" aria-label="CS 응답기한 정책">
          <h3>CS 응답기한</h3>
          <div className="kv"><span>기준</span><span>{c.cs_sla.mode === "BUSINESS_HOURS" ? "영업시간" : "달력 시간(24시간 연속)"} · 기본 {c.cs_sla.base_hours}시간{c.cs_sla.configured ? "" : " (기본값)"}</span></div>
          <div className="kv"><span>중요도 배수</span><span>긴급 ÷6 · 높음 ÷2 · 보통 ×1 · 낮음 ×2</span></div>
          <div className="row-gap">
            <label className="fld">미리보기 중요도<select className="inp" value={prio} onChange={(e) => setPrio(e.target.value)} aria-label="미리보기 중요도">
              <option value="URGENT">긴급</option><option value="HIGH">높음</option><option value="NORMAL">보통</option><option value="LOW">낮음</option></select></label>
            {preview.data && <span className="ctx" role="note">지금 접수하면 {preview.data.hours}시간 → 기한 {formatDateTime(preview.data.due)}</span>}
          </div>
          {manage && <button className="btn" type="button" onClick={() => { setSla({ mode: c.cs_sla.mode, base_hours: c.cs_sla.base_hours }); setMode("sla"); }}>응답기한 정책 변경</button>}
        </section>
        <section className="card pad" aria-label="영업시간">
          <h3>영업시간{c.default_hours ? " (기본값)" : ""}</h3>
          <DataTable caption="영업시간" rows={c.hours} rowKey={(h) => String(h.weekday)} empty={<Empty title="영업일이 없습니다." />} columns={[
            { key: "d", header: "요일", render: (h) => DAYS[h.weekday] },
            { key: "t", header: "시간", render: (h) => `${hm(h.start)} ~ ${hm(h.end)}` },
          ]} />
          {manage && <button className="btn" type="button" onClick={openHours}>영업시간 변경</button>}
        </section>
        <section className="card pad" aria-label="휴일">
          <h3>휴일</h3>
          <DataTable caption="휴일" rows={c.holidays} rowKey={(h) => h.id} empty={<Empty title="등록된 휴일이 없습니다." hint="공휴일·임시 휴무를 등록하면 영업시간 기한에서 빠집니다." />} columns={[
            { key: "d", header: "날짜", render: (h) => h.day },
            { key: "n", header: "이름", render: (h) => h.name },
            { key: "x", header: "", render: (h) => (manage ? <button className="btn" type="button" onClick={() => run(api(`/settings/business-calendar/holidays/${h.id}`, { method: "DELETE" }), "휴일을 지웠습니다.")}>삭제</button> : null) },
          ]} />
          {manage && <button className="btn" type="button" onClick={() => { setHol({ day: "", name: "" }); setMode("holiday"); }}>휴일 등록</button>}
        </section>
      </>}
      {mode === "sla" && <ConfirmModal title="CS 응답기한 정책" confirmLabel="저장" onClose={() => setMode(null)} confirmDisabled={sla.base_hours < 1 || sla.base_hours > 240}
        onConfirm={() => run(api("/settings/business-calendar/cs-sla", { method: "PUT", ifMatch: c?.cs_sla.version, body: sla }), "응답기한 정책을 저장했습니다.")}
        impact={[["적용", "이후 접수·재문의부터(이미 정해진 기한은 그대로)"], ["영업시간 기준", "휴무일·휴일·영업시간 밖은 세지 않습니다"]]}>
        <label className="fld">기준<select className="inp" value={sla.mode} onChange={(e) => setSla({ ...sla, mode: e.target.value })} aria-label="응답기한 기준">
          <option value="BUSINESS_HOURS">영업시간</option><option value="CALENDAR_HOURS">달력 시간</option></select></label>
        <label className="fld">기본 응답 시간(보통 중요도)<input className="inp" type="number" min={1} max={240} value={sla.base_hours}
          onChange={(e) => setSla({ ...sla, base_hours: Math.max(0, Math.round(Number(e.target.value) || 0)) })} aria-label="기본 응답 시간" /></label>
      </ConfirmModal>}
      {mode === "hours" && <ConfirmModal title="영업시간 변경" confirmLabel="저장" onClose={() => setMode(null)}
        confirmDisabled={!Object.values(rows).some((r) => r.on) || Object.values(rows).some((r) => r.on && r.end <= r.start)}
        onConfirm={() => run(api("/settings/business-calendar/hours", { method: "PUT", body: { hours: Object.entries(rows).filter(([, r]) => r.on)
          .map(([d, r]) => ({ weekday: Number(d), start: r.start, end: r.end })) } }), "영업시간을 저장했습니다.")}
        impact={[["적용", "이후 계산하는 영업시간 기한부터"]]}>
        {Object.entries(rows).map(([d, r]) => (
          <div key={d} className="row-gap">
            <label className="fld"><span><input type="checkbox" checked={r.on} aria-label={`${DAYS[Number(d)]}요일 영업`} onChange={(e) => setRows({ ...rows, [d]: { ...r, on: e.target.checked } })} /> {DAYS[Number(d)]}</span></label>
            <input className="inp" type="time" value={r.start} disabled={!r.on} aria-label={`${DAYS[Number(d)]} 시작`} onChange={(e) => setRows({ ...rows, [d]: { ...r, start: e.target.value } })} />
            <input className="inp" type="time" value={r.end} disabled={!r.on} aria-label={`${DAYS[Number(d)]} 마감`} onChange={(e) => setRows({ ...rows, [d]: { ...r, end: e.target.value } })} />
          </div>))}
      </ConfirmModal>}
      {mode === "holiday" && <ConfirmModal title="휴일 등록" confirmLabel="등록" onClose={() => setMode(null)} confirmDisabled={!hol.day || !hol.name.trim()}
        onConfirm={() => run(api("/settings/business-calendar/holidays", { method: "POST", body: { holidays: [hol] } }), "휴일을 등록했습니다.")}
        impact={[["효과", "영업시간 기준 기한에서 이 날을 건너뜁니다"]]}>
        <label className="fld">날짜<input className="inp" type="date" value={hol.day} onChange={(e) => setHol({ ...hol, day: e.target.value })} aria-label="휴일 날짜" /></label>
        <label className="fld">이름<input className="inp" value={hol.name} onChange={(e) => setHol({ ...hol, name: e.target.value })} maxLength={50} aria-label="휴일 이름" /></label>
      </ConfirmModal>}
    </>
  );
}
