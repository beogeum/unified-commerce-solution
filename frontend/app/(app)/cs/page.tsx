"use client";
import { useMemo, useState } from "react";
import { Chip } from "@/components/Chip";
import { CsDrawer, CsStatus, NewInquiryModal, TemplateModal } from "@/components/CsParts";
import { DataTable } from "@/components/DataTable";
import { Empty, ErrorState, Forbidden, Loading } from "@/components/States";
import { qs } from "@/lib/api";
import { CS_PRIORITY_KO, CS_STATUS, CS_TYPE_KO, CsInquiry, CsKpi, CsTemplate } from "@/lib/cs";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

export default function CsPage() {
  const { can, selected } = useSession();
  const allowed = can("order.read");
  const [tab, setTab] = useState<"inbox" | "templates">("inbox");
  const [status, setStatus] = useState("ACTIVE");
  const [type, setType] = useState("");
  const [mine, setMine] = useState(false);
  const [overdue, setOverdue] = useState(false);
  const [q, setQ] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const [modal, setModal] = useState<null | "inquiry" | { tpl?: CsTemplate }>(null);
  const path = useMemo(() => (allowed && tab === "inbox" ? `/cs/inquiries${qs({ business_entity_id: selected, status: status || undefined, inquiry_type: type || undefined,
    mine: mine ? "true" : undefined, overdue: overdue ? "true" : undefined, q: q || undefined })}` : null), [allowed, tab, selected, status, type, mine, overdue, q]);
  const list = useApi<CsInquiry[]>(path);
  const kpi = useApi<CsKpi>(allowed ? `/cs/kpi${qs({ business_entity_id: selected })}` : null);
  const templates = useApi<CsTemplate[]>(allowed && tab === "templates" ? "/cs/templates" : null);
  if (!allowed) return <Forbidden />;
  const k = kpi.data;
  const refresh = () => { list.reload(); kpi.reload(); };
  return (
    <>
      <div className="page-head"><div><h1>CS 통합함</h1><p>채널·전화·메일 문의를 한곳에서 기록하고 응답기한 안에 처리합니다. 채널 문의 연동 전이라 채널 문의는 문의번호와 함께 옮겨 적고,
        답변은 기록한 뒤 채널에 직접 등록하고 &apos;채널 등록 완료&apos;로 표시합니다. 자동으로 고객에게 보내는 기능은 없습니다.</p></div>
        {can("order.update") && <span className="row-gap">
          {tab === "templates" ? <button className="btn pri" type="button" onClick={() => setModal({})}>템플릿 추가</button>
            : <button className="btn pri" type="button" onClick={() => setModal("inquiry")}>문의 접수</button>}</span>}</div>
      {k && <div className="row-gap" role="group" aria-label="CS 현황">
        <Chip kind="warn">미답변 {k.open}</Chip><Chip kind="info">오늘 마감 {k.due_today}</Chip><Chip kind="err">기한 초과 {k.overdue}</Chip>
        <Chip kind="info">처리 중 {k.in_progress}</Chip><Chip kind="warn">고객 재문의 {k.customer_replied}</Chip><Chip kind="mute">채널 등록 대기 {k.waiting_channel_post}</Chip></div>}
      <nav className="row-gap" aria-label="CS 메뉴">
        <button className="btn" type="button" aria-current={tab === "inbox" ? "page" : undefined} onClick={() => setTab("inbox")}>통합함</button>
        <button className="btn" type="button" aria-current={tab === "templates" ? "page" : undefined} onClick={() => setTab("templates")}>답변 템플릿</button>
      </nav>
      {tab === "inbox" ? <>
        <div className="row-gap">
          <label className="fld">검색<input className="inp" value={q} onChange={(e) => setQ(e.target.value)} placeholder="문의번호·제목·주문번호" /></label>
          <label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}><option value="ACTIVE">진행 중 전체</option><option value="">전체</option>
            {Object.entries(CS_STATUS).map(([s, v]) => <option key={s} value={s}>{v[1]}</option>)}</select></label>
          <label className="fld">유형<select className="inp" value={type} onChange={(e) => setType(e.target.value)}><option value="">전체</option>
            {Object.entries(CS_TYPE_KO).map(([s, v]) => <option key={s} value={s}>{v}</option>)}</select></label>
          <label><input type="checkbox" checked={mine} onChange={(e) => setMine(e.target.checked)} /> 내 담당</label>
          <label><input type="checkbox" checked={overdue} onChange={(e) => setOverdue(e.target.checked)} /> 기한 초과만</label>
        </div>
        {list.loading ? <Loading /> : list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : (
          <DataTable caption="문의" rows={list.data ?? []} rowKey={(x) => x.id} onRowClick={(x) => setOpen(x.id)} empty={<Empty title="문의가 없습니다." hint="'문의 접수'로 채널·전화 문의를 기록하세요." />} columns={[
            { key: "n", header: "문의", render: (x) => <><b>{x.title}</b><div className="ctx mono">{x.inquiry_no}{x.external_inquiry_key ? ` · ${x.external_inquiry_key}` : ""}</div></> },
            { key: "b", header: "사업자 · 계정", render: (x) => <span className="ctx">{x.business_entity_name} · {x.channel_account_name}</span> },
            { key: "o", header: "주문 · 고객", render: (x) => <span className="ctx">{x.order_no ?? "주문 없음"} · {x.customer_name_masked ?? "—"}</span> },
            { key: "t", header: "유형", render: (x) => CS_TYPE_KO[x.inquiry_type] },
            { key: "l", header: "최근 메시지", render: (x) => <span className="ctx">{x.last_message ?? ""}</span> },
            { key: "a", header: "담당", render: (x) => x.assignee_name ?? "미배정" },
            { key: "d", header: "응답기한", render: (x) => <span className={x.overdue ? "fld-err" : "ctx"}>{formatDateTime(x.due_at)}</span> },
            { key: "s", header: "상태", render: (x) => <><CsStatus s={x.status} />{x.priority !== "NORMAL" && <> <Chip kind={x.priority === "LOW" ? "mute" : "err"}>{CS_PRIORITY_KO[x.priority]}</Chip></>}
              {x.pending_channel_answers > 0 && <> <Chip kind="warn">채널 등록 필요</Chip></>}{x.customer_reply && <> <Chip kind="warn">재문의</Chip></>}</> },
          ]} />)}
      </> : (templates.loading ? <Loading /> : templates.error ? <ErrorState error={templates.error} onRetry={templates.reload} /> : (
        <DataTable caption="답변 템플릿" rows={templates.data ?? []} rowKey={(t) => t.id} onRowClick={(t) => can("order.update") && setModal({ tpl: t })}
          empty={<Empty title="템플릿이 없습니다." hint="{고객명} {주문번호} {상품} {송장번호} 변수를 쓸 수 있습니다." />} columns={[
            { key: "n", header: "이름", render: (t) => t.name },
            { key: "t", header: "유형", render: (t) => (t.inquiry_type ? CS_TYPE_KO[t.inquiry_type] : "전체") },
            { key: "b", header: "내용", render: (t) => <span className="ctx">{t.body.length > 80 ? `${t.body.slice(0, 80)}…` : t.body}</span> },
            { key: "s", header: "상태", render: (t) => (t.status === "ACTIVE" ? "사용" : "중지") },
          ]} />))}
      {open && <CsDrawer id={open} onClose={() => setOpen(null)} onChanged={refresh} />}
      {modal === "inquiry" && <NewInquiryModal selected={selected} onClose={() => setModal(null)} onDone={(id) => { setModal(null); refresh(); setOpen(id); }} />}
      {modal && modal !== "inquiry" && <TemplateModal template={modal.tpl} onClose={() => setModal(null)} onDone={() => { setModal(null); templates.reload(); }} />}
    </>
  );
}
