"use client";
import { useState } from "react";
import { Chip } from "@/components/Chip";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { CS_PRIORITY_KO, CS_SOURCE_KO, CS_STATUS, CS_TYPE_KO, CsInquiry, CsTemplate } from "@/lib/cs";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

const msg = (x: unknown, f: string) => (x instanceof ApiError ? x.message : f);
const DIR_KO: Record<string, string> = { INBOUND: "고객", OUTBOUND: "답변", NOTE: "내부 메모", SYSTEM: "시스템" };

export function CsStatus({ s }: { s: string }) {
  const v = CS_STATUS[s] ?? ["mute", s];
  return <Chip kind={v[0]}>{v[1]}</Chip>;
}

/** 문의 상세: 스레드, 주문 요약, 템플릿으로 답변 작성 → 채널 직접 등록 표시, 상태·담당. */
export function CsDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can, me } = useSession();
  const { data: q, error, loading, reload } = useApi<CsInquiry>(`/cs/inquiries/${id}`);
  const templates = useApi<CsTemplate[]>(q ? `/cs/templates${qs({ business_entity_id: q.business_entity_id })}` : null);
  const [text, setText] = useState("");
  const [tpl, setTpl] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const manage = can("order.update");
  const done = (m: string) => { setNotice(m); reload(); onChanged(); };
  const send = async (direction: string) => {
    try {
      await api(`/cs/inquiries/${id}/messages`, { method: "POST", body: { direction, body: text, template_id: tpl || undefined } });
      setText("");
      setTpl("");
      done(direction === "OUTBOUND" ? "답변을 기록했습니다. 채널 관리자 화면에 같은 내용을 등록한 뒤 '채널 등록 완료'를 눌러 주세요." : "기록했습니다.");
    } catch (x) {
      setNotice(msg(x, "기록하지 못했습니다."));
    }
  };
  const applyTemplate = async (t: string) => {
    setTpl(t);
    if (!t) return;
    try {
      const r = await api<{ body: string; missing_variables: string[] }>(`/cs/inquiries/${id}/render${qs({ template_id: t })}`);
      setText(r.data.body);
      if (r.data.missing_variables.length > 0) setNotice(`채우지 못한 변수: ${r.data.missing_variables.join(", ")} — 직접 고쳐 주세요.`);
    } catch (x) {
      setNotice(msg(x, "템플릿을 불러오지 못했습니다."));
    }
  };
  const patch = async (body: object, m: string) => {
    try {
      await api(`/cs/inquiries/${id}`, { method: "PATCH", ifMatch: q?.version, body });
      done(m);
    } catch (x) {
      setNotice(msg(x, "바꾸지 못했습니다."));
    }
  };
  const posted = async (mid: string) => {
    try {
      await api(`/cs/inquiries/${id}/messages/${mid}/channel-posted`, { method: "POST", body: {} });
      done("채널 등록 완료로 표시했습니다.");
    } catch (x) {
      setNotice(msg(x, "표시하지 못했습니다."));
    }
  };
  const s = q?.order_summary ?? {};
  return (
    <Drawer title={q ? `${q.inquiry_no} ${q.title}` : "문의"} onClose={onClose} footer={q && manage ? <>
      {q.assignee_member_id !== me.member_id && q.status !== "CLOSED" && <button className="btn" type="button" onClick={() => patch({ assignee_member_id: me.member_id }, "나에게 배정했습니다.")}>나에게 배정</button>}
      {q.status !== "CLOSED" && q.status !== "WAITING_CUSTOMER" && <button className="btn" type="button" onClick={() => patch({ status: "WAITING_CUSTOMER" }, "고객 회신 대기로 바꿨습니다.")}>고객 회신 대기</button>}
      {q.status !== "CLOSED" && <button className="btn" type="button" onClick={() => patch({ status: "CLOSED" }, "문의를 종료했습니다.")}>종료</button>}
    </> : undefined}>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : q && <>
        {notice && <div className="note n-info" role="status">{notice}</div>}
        <div className="kv"><span>상태</span><span><CsStatus s={q.status} />{q.overdue && <> <Chip kind="err">기한 초과</Chip></>}{q.customer_reply && <> <Chip kind="warn">고객 재문의</Chip></>}</span></div>
        <div className="kv"><span>유형 · 경로 · 중요도</span><span>{CS_TYPE_KO[q.inquiry_type]} · {CS_SOURCE_KO[q.source]}{q.external_inquiry_key ? ` ${q.external_inquiry_key}` : ""} · {CS_PRIORITY_KO[q.priority]}</span></div>
        <div className="kv"><span>사업자 · 판매계정</span><span>{q.business_entity_name} · {q.channel_account_name}</span></div>
        <div className="kv"><span>고객 · 담당 · 응답기한</span><span>{q.customer_name_masked ?? "—"} · {q.assignee_name ?? "미배정"} · {formatDateTime(q.due_at)}</span></div>
        {q.order_id && <div className="kv"><span>주문</span><span><span className="mono">{q.order_no}</span> {s.first_item ?? ""} · 송장 {s.carrier_code ?? ""} {s.tracking_no || "없음"} · 클레임 {s.claims ?? 0}건</span></div>}
        <h3>대화</h3>
        {q.messages.length === 0 ? <Empty title="메시지가 없습니다." /> : q.messages.map((m) => (
          <div key={m.id} className="card" style={{ padding: 10, borderLeft: m.direction === "OUTBOUND" ? "3px solid var(--primary, #3366ff)" : undefined }}>
            <div className="ctx">{DIR_KO[m.direction]} · {m.created_by_name ?? ""} · {formatDateTime(m.created_at)}
              {m.channel_status === "MANUAL_REQUIRED" && <> <Chip kind="warn">채널 등록 필요</Chip>{manage && <button className="btn" type="button" onClick={() => posted(m.id)}>채널 등록 완료</button>}</>}
              {m.channel_status === "MANUAL_DONE" && <> <Chip kind="ok">채널 등록 완료</Chip></>}</div>
            <div style={{ whiteSpace: "pre-wrap" }}>{m.body}</div>
          </div>))}
        {manage && q.status !== "CLOSED" && <>
          <h3>작성</h3>
          <label className="fld">답변 템플릿<select className="inp" value={tpl} onChange={(e) => applyTemplate(e.target.value)} aria-label="답변 템플릿"><option value="">선택 안 함</option>
            {(templates.data ?? []).filter((t) => t.status === "ACTIVE").map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label>
          <label className="fld">내용<textarea className="inp" rows={5} value={text} onChange={(e) => setText(e.target.value)} aria-label="문의 메시지 내용" maxLength={5000} /></label>
          <div className="row-gap">
            <button className="btn pri" type="button" disabled={!text.trim()} onClick={() => send("OUTBOUND")}>답변 기록</button>
            <button className="btn" type="button" disabled={!text.trim()} onClick={() => send("NOTE")}>내부 메모</button>
            <button className="btn" type="button" disabled={!text.trim()} onClick={() => send("INBOUND")}>고객 추가 문의 기록</button>
          </div>
          <p className="ctx" style={{ margin: 0 }}>채널 문의 연동 전이라 답변은 자동으로 보내지지 않습니다. 기록한 답변을 채널 관리자 화면에 등록한 뒤 '채널 등록 완료'를 누르세요.</p>
        </>}
        {manage && q.status === "CLOSED" && <>
          <label className="fld">고객 재문의<textarea className="inp" rows={3} value={text} onChange={(e) => setText(e.target.value)} aria-label="문의 메시지 내용" /></label>
          <button className="btn" type="button" disabled={!text.trim()} onClick={() => send("INBOUND")}>재문의 기록(다시 열기)</button>
        </>}
      </>}
    </Drawer>
  );
}

type Account = { id: string; display_name: string; business_entity_name: string };

export function NewInquiryModal({ selected, onClose, onDone }: { selected: string[]; onClose: () => void; onDone: (id: string) => void }) {
  const accounts = useApi<Account[]>(`/channel-accounts${qs({ business_entity_id: selected, limit: "200" })}`);
  const [accSel, setAcc] = useState("");
  const acc = accSel || accounts.data?.[0]?.id || "";
  const [f, setF] = useState({ order_no: "", source: "CHANNEL", external_inquiry_key: "", inquiry_type: "DELIVERY", title: "", message: "", customer_name: "", customer_phone: "", priority: "NORMAL" });
  const [err, setErr] = useState<string | null>(null);
  const submit = async () => {
    setErr(null);
    try {
      let orderId: string | undefined;
      if (f.order_no.trim()) {
        const found = await api<{ id: string; order_no: string; external_order_key: string }[]>(`/orders${qs({ q: f.order_no.trim(), limit: "5" })}`);
        const hit = found.data.find((o) => o.order_no === f.order_no.trim() || o.external_order_key === f.order_no.trim());
        if (!hit) throw new ApiError(404, "ORDER_NOT_FOUND", "주문번호로 주문을 찾지 못했습니다.");
        orderId = hit.id;
      }
      const r = await api<CsInquiry>("/cs/inquiries", { method: "POST", body: { channel_account_id: orderId ? undefined : acc, order_id: orderId, source: f.source,
        external_inquiry_key: f.external_inquiry_key || undefined, inquiry_type: f.inquiry_type, title: f.title, message: f.message, priority: f.priority,
        customer_name: orderId ? undefined : f.customer_name || undefined, customer_phone: orderId ? undefined : f.customer_phone || undefined } });
      onDone(r.data.id);
    } catch (x) {
      setErr(msg(x, "접수하지 못했습니다."));
    }
  };
  return (
    <ConfirmModal title="문의 접수" confirmLabel="접수" confirmDisabled={!f.title.trim() || !f.message.trim() || (!acc && !f.order_no.trim())} onClose={onClose} onConfirm={submit}
      impact={[["응답기한", "중요도에 따라 자동 설정(보통 24시간)"], ["개인정보", "주문 없는 문의의 고객 정보는 암호화되어 마스킹으로 표시됩니다"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">주문번호(있으면)<input className="inp" value={f.order_no} onChange={(e) => setF({ ...f, order_no: e.target.value })} placeholder="내부 주문번호 또는 채널 주문번호" /></label>
      {!f.order_no.trim() && <label className="fld">판매계정<select className="inp" value={acc} onChange={(e) => setAcc(e.target.value)} aria-label="문의 판매계정">
        {(accounts.data ?? []).map((a) => <option key={a.id} value={a.id}>{a.business_entity_name} · {a.display_name}</option>)}</select></label>}
      <label className="fld">접수 경로<select className="inp" value={f.source} onChange={(e) => setF({ ...f, source: e.target.value })}>
        {Object.entries(CS_SOURCE_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      {f.source === "CHANNEL" && <label className="fld">채널 문의번호(중복 방지)<input className="inp" value={f.external_inquiry_key} onChange={(e) => setF({ ...f, external_inquiry_key: e.target.value })} /></label>}
      <label className="fld">문의 유형<select className="inp" value={f.inquiry_type} onChange={(e) => setF({ ...f, inquiry_type: e.target.value })}>
        {Object.entries(CS_TYPE_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      <label className="fld">중요도<select className="inp" value={f.priority} onChange={(e) => setF({ ...f, priority: e.target.value })}>
        {Object.entries(CS_PRIORITY_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      <label className="fld">제목<input className="inp" value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} maxLength={200} /></label>
      <label className="fld">문의 내용<textarea className="inp" rows={4} value={f.message} onChange={(e) => setF({ ...f, message: e.target.value })} maxLength={5000} /></label>
      {!f.order_no.trim() && <>
        <label className="fld">고객 이름<input className="inp" value={f.customer_name} onChange={(e) => setF({ ...f, customer_name: e.target.value })} /></label>
        <label className="fld">고객 연락처<input className="inp" value={f.customer_phone} onChange={(e) => setF({ ...f, customer_phone: e.target.value })} /></label>
      </>}
    </ConfirmModal>
  );
}

export function TemplateModal({ template, onClose, onDone }: { template?: CsTemplate; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({ name: template?.name ?? "", inquiry_type: template?.inquiry_type ?? "", body: template?.body ?? "", status: template?.status ?? "ACTIVE" });
  const [err, setErr] = useState<string | null>(null);
  const submit = async () => {
    try {
      const body = { ...f, inquiry_type: f.inquiry_type || undefined };
      if (template) await api(`/cs/templates/${template.id}`, { method: "PUT", body });
      else await api("/cs/templates", { method: "POST", body });
      onDone();
    } catch (x) {
      setErr(msg(x, "저장하지 못했습니다."));
    }
  };
  return (
    <ConfirmModal title={template ? "답변 템플릿 수정" : "답변 템플릿 추가"} confirmLabel="저장" confirmDisabled={!f.name.trim() || !f.body.trim()} onClose={onClose} onConfirm={submit}
      impact={[["변수", "{고객명} {주문번호} {상품} {송장번호} — 고객명은 마스킹으로 채워집니다"]]}>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">이름<input className="inp" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} maxLength={100} /></label>
      <label className="fld">문의 유형<select className="inp" value={f.inquiry_type} onChange={(e) => setF({ ...f, inquiry_type: e.target.value })}><option value="">전체</option>
        {Object.entries(CS_TYPE_KO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      <label className="fld">내용<textarea className="inp" rows={5} value={f.body} onChange={(e) => setF({ ...f, body: e.target.value })} maxLength={5000} /></label>
      <label className="fld">상태<select className="inp" value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })}><option value="ACTIVE">사용</option><option value="INACTIVE">중지</option></select></label>
    </ConfirmModal>
  );
}
