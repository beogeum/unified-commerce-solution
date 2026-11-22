"use client";
import { FormEvent, useState } from "react";
import { CatalogTabs } from "@/components/CatalogTabs";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { SkuPicker } from "@/components/SkuPicker";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { Sku } from "@/lib/catalog";
import { formatDateTime, formatWon } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type SkuRef = { sku_id: string; sku_code: string; quantity: number; priority: number };
type Campaign = { id: string; business_entity_id: string; business_entity_name: string; name: string; starts_at: string; ends_at: string; status: string;
  priority: number; stack_group: string | null; stack_policy: string; min_order_amount: string | null; condition_mode: string; max_per_order: number;
  total_limit: number | null; out_of_stock_policy: string; return_policy: string; channel_overlap_policy: string; channel_campaign_ref: string | null;
  channel_account_ids: string[]; required_skus: SkuRef[]; excluded_skus: SkuRef[]; rewards: SkuRef[]; version: number };
type Account = { id: string; display_name: string; business_entity_id: string };
type PreviewResult = { awards: { campaign_name: string; gift_sku_id: string; quantity: number; substitute: boolean }[];
  skipped: { campaign_name: string; reason: string }[]; hold_order: boolean };

const STATUS: Record<string, ["info" | "ok" | "warn" | "mute", string]> = { DRAFT: ["info", "초안"], ACTIVE: ["ok", "진행"], PAUSED: ["warn", "일시중지"], ENDED: ["mute", "종료"] };
const STACK_KO: Record<string, string> = { CUMULATIVE: "누적 가능", ONE_PER_GROUP: "그룹당 하나(우선순위 높은 것)", HIGHEST_PRIORITY_ONLY: "최고 우선순위 하나만" };
const OOS_KO: Record<string, string> = { SUBSTITUTE: "대체 사은품", PROCEED_WITHOUT: "사은품 없이 진행", HOLD_ORDER: "주문 보류", STOP: "지급 중지" };
const REASON_KO: Record<string, string> = {
  CHANNEL_GIFT_DUPLICATE: "채널이 이미 같은 사은품을 지급(중복 금지)", STACK_GROUP: "같은 중복 그룹의 우선 캠페인이 지급", STACK_POLICY: "최고 우선순위 캠페인만 지급",
  CONDITION_NOT_MET: "조건 미충족", OUT_OF_PERIOD: "기간 밖", CHANNEL_ACCOUNT_NOT_TARGETED: "대상 판매계정 아님", NOT_ACTIVE: "진행 중 아님",
  LIMIT_EXHAUSTED: "선착순 소진", GIFT_OUT_OF_STOCK: "사은품 재고 없음", GIFT_OUT_OF_STOCK_HOLD: "사은품 재고 없음 → 주문 보류",
  GIFT_OUT_OF_STOCK_STOPPED: "사은품 재고 없음 → 지급 중지", OTHER_BUSINESS: "다른 사업자", NO_REWARD: "사은품 없음",
};

export default function GwpPage() {
  const { can, selected } = useSession();
  const [status, setStatus] = useState("");
  const { data, error, loading, reload } = useApi<Campaign[]>(`/gwp-campaigns${qs({ status, business_entity_id: selected })}`);
  const [edit, setEdit] = useState<Campaign | "new" | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [previewing, setPreviewing] = useState(false);
  return (
    <>
      <div className="page-head">
        <div><h1>GWP·증정</h1><p>조건에 맞는 주문에 사은품을 붙입니다. 채널이 이미 같은 사은품을 준 주문에는 기본적으로 내부 사은품을 더 주지 않습니다.</p></div>
        <div className="row-gap"><button className="btn" type="button" onClick={() => setPreviewing(true)}>지급 미리보기</button>
          {can("catalog.manage") && <button className="btn pri" type="button" onClick={() => setEdit("new")}>캠페인 만들기</button>}</div>
      </div>
      <CatalogTabs active="gwp" />
      <div className="row-gap"><label className="fld">상태<select className="inp" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">전체</option>
        {Object.entries(STATUS).map(([k, v]) => <option key={k} value={k}>{v[1]}</option>)}</select></label></div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="GWP 캠페인" rows={data ?? []} rowKey={(r) => r.id} onRowClick={(r) => setOpen(r.id)}
          empty={<Empty title="캠페인이 없습니다." />}
          columns={[
            { key: "n", header: "캠페인", render: (r) => <><b>{r.name}</b><div className="ctx">{r.business_entity_name}</div></> },
            { key: "p", header: "기간", render: (r) => <span className="ctx">{formatDateTime(r.starts_at)} ~ {formatDateTime(r.ends_at)}</span> },
            { key: "c", header: "조건", render: (r) => [r.min_order_amount ? `${formatWon(r.min_order_amount)}원 이상` : null,
              ...r.required_skus.map((s) => `${s.sku_code} ${s.quantity}개 이상`)].filter(Boolean).join(r.condition_mode === "ALL" ? " 그리고 " : " 또는 ") || "모든 주문" },
            { key: "g", header: "사은품", render: (r) => r.rewards.map((g) => `${g.sku_code}×${g.quantity}`).join(" → ") },
            { key: "l", header: "선착순", num: true, render: (r) => r.total_limit ?? "—" },
            { key: "s", header: "상태", render: (r) => <Chip kind={STATUS[r.status][0]}>{STATUS[r.status][1]}</Chip> },
          ]} />
      )}
      {open && <CampaignDrawer id={open} onClose={() => setOpen(null)} onChanged={reload} onEdit={(c) => { setOpen(null); setEdit(c); }} />}
      {edit && <CampaignForm initial={edit === "new" ? null : edit} onClose={() => setEdit(null)} onSaved={(id) => { setEdit(null); reload(); setOpen(id); }} />}
      {previewing && <PreviewDrawer onClose={() => setPreviewing(false)} />}
    </>
  );
}

function CampaignForm({ initial, onClose, onSaved }: { initial: Campaign | null; onClose: () => void; onSaved: (id: string) => void }) {
  const { entities } = useSession();
  const [beSel, setBe] = useState(initial?.business_entity_id ?? "");
  const be = beSel || entities[0]?.id || "";
  const accounts = useApi<Account[]>(be ? `/channel-accounts${qs({ business_entity_id: be })}` : null);
  const [accountIds, setAccountIds] = useState<string[]>(initial?.channel_account_ids ?? []);
  const [required, setRequired] = useState<{ id: string; code: string; qty: number }[]>(initial?.required_skus.map((s) => ({ id: s.sku_id, code: s.sku_code, qty: s.quantity })) ?? []);
  const [rewards, setRewards] = useState<{ id: string; code: string; qty: number }[]>(initial?.rewards.map((s) => ({ id: s.sku_id, code: s.sku_code, qty: s.quantity })) ?? []);
  const [err, setErr] = useState<string | null>(null);
  const [key] = useState(newIdempotencyKey);
  const local = (iso?: string) => (iso ? new Date(new Date(iso).getTime() + 9 * 3600_000).toISOString().slice(0, 16) : "");
  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const v = (k: string) => (String(f.get(k) ?? "").trim() || undefined);
    const toIso = (k: string) => (v(k) ? new Date(`${v(k)}:00+09:00`).toISOString() : undefined);
    const body = { business_entity_id: be, name: v("name"), starts_at: toIso("starts_at"), ends_at: toIso("ends_at"), priority: v("priority") ? Number(v("priority")) : undefined,
      stack_group: v("stack_group"), stack_policy: v("stack_policy"), min_order_amount: v("min")?.replace(/,/g, ""), condition_mode: v("mode"),
      max_per_order: v("max") ? Number(v("max")) : undefined, total_limit: v("limit") ? Number(v("limit")) : undefined, out_of_stock_policy: v("oos"),
      channel_overlap_policy: v("overlap"), channel_campaign_ref: v("ref"), channel_account_ids: accountIds,
      required_skus: required.map((r) => ({ sku_id: r.id, min_quantity: r.qty })), rewards: rewards.map((r) => ({ gift_sku_id: r.id, quantity: r.qty })) };
    setErr(null);
    try {
      const r = initial ? await api<Campaign>(`/gwp-campaigns/${initial.id}`, { method: "PUT", ifMatch: initial.version, body })
        : await api<Campaign>("/gwp-campaigns", { method: "POST", idempotencyKey: key, body });
      onSaved(r.data.id);
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : "저장하지 못했습니다.");
    }
  };
  const addTo = (list: { id: string; code: string; qty: number }[], set: (l: { id: string; code: string; qty: number }[]) => void) => (s: Sku) =>
    !list.some((x) => x.id === s.id) && set([...list, { id: s.id, code: s.sku_code, qty: 1 }]);
  const listEditor = (label: string, list: { id: string; code: string; qty: number }[], set: (l: { id: string; code: string; qty: number }[]) => void) => (
    <>{list.map((r, i) => <div className="row-gap" key={r.id} style={{ alignItems: "center" }}>
      <span className="mono" style={{ flex: 1 }}>{i === 0 && label === "사은품" ? "" : label === "사은품" ? `대체 ${i}: ` : ""}{r.code}</span>
      <input className="inp" type="number" min={1} aria-label={`${r.code} ${label} 수량`} value={r.qty} style={{ width: 80 }} onChange={(e) => set(list.map((x, j) => (j === i ? { ...x, qty: Number(e.target.value) || 1 } : x)))} />
      <button className="btn" type="button" onClick={() => set(list.filter((_, j) => j !== i))}>빼기</button></div>)}</>
  );
  return (
    <Drawer title={initial ? "캠페인 수정(초안)" : "캠페인 만들기"} onClose={onClose} footer={<><button className="btn" type="button" onClick={onClose}>취소</button>
      <button className="btn pri" type="submit" form="gwp-form">초안 저장</button></>}>
      <form id="gwp-form" onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        {err && <div className="note n-err" role="alert">{err}</div>}
        <label className="fld">행사 사업자<select className="inp" value={be} disabled={!!initial} onChange={(e) => { setBe(e.target.value); setAccountIds([]); }}>
          {entities.map((e) => <option key={e.id} value={e.id}>{e.display_name}</option>)}</select></label>
        <label className="fld">캠페인 이름<input className="inp" name="name" required maxLength={100} defaultValue={initial?.name} /></label>
        <div className="row-gap">
          <label className="fld">시작(한국 시간)<input className="inp" type="datetime-local" name="starts_at" required defaultValue={local(initial?.starts_at)} /></label>
          <label className="fld">종료(한국 시간)<input className="inp" type="datetime-local" name="ends_at" required defaultValue={local(initial?.ends_at)} /></label>
        </div>
        <fieldset className="card pad"><legend className="ctx">대상 판매계정(선택 안 하면 사업자의 모든 계정)</legend>
          {(accounts.data ?? []).map((a) => <label key={a.id} style={{ display: "block" }}><input type="checkbox" checked={accountIds.includes(a.id)}
            onChange={(e) => setAccountIds(e.target.checked ? [...accountIds, a.id] : accountIds.filter((x) => x !== a.id))} /> {a.display_name}</label>)}</fieldset>
        <fieldset className="card pad" style={{ display: "flex", flexDirection: "column", gap: 6 }}><legend className="ctx">조건</legend>
          <label className="fld">주문금액 이상(부가세 포함, 원)<input className="inp" name="min" inputMode="numeric" defaultValue={initial?.min_order_amount ?? ""} /></label>
          {listEditor("조건", required, setRequired)}
          <SkuPicker label="조건 SKU 추가(최소 수량)" onPick={addTo(required, setRequired)} />
          <label className="fld">조건 결합<select className="inp" name="mode" defaultValue={initial?.condition_mode ?? "ALL"}><option value="ALL">모두 충족(AND)</option><option value="ANY">하나만 충족(OR)</option></select></label>
        </fieldset>
        <fieldset className="card pad" style={{ display: "flex", flexDirection: "column", gap: 6 }}><legend className="ctx">사은품(첫 번째가 기본, 다음은 대체 순서)</legend>
          {listEditor("사은품", rewards, setRewards)}
          <SkuPicker label="사은품 추가" onPick={addTo(rewards, setRewards)} />
          <div className="row-gap">
            <label className="fld">주문당 지급<input className="inp" name="max" type="number" min={1} defaultValue={initial?.max_per_order ?? 1} /></label>
            <label className="fld">선착순 총수량<input className="inp" name="limit" type="number" min={1} defaultValue={initial?.total_limit ?? ""} /></label>
          </div>
          <label className="fld">품절 시<select className="inp" name="oos" defaultValue={initial?.out_of_stock_policy ?? "SUBSTITUTE"}>{Object.entries(OOS_KO).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></label>
        </fieldset>
        <fieldset className="card pad" style={{ display: "flex", flexDirection: "column", gap: 6 }}><legend className="ctx">중복 지급</legend>
          <div className="row-gap">
            <label className="fld">우선순위(클수록 먼저)<input className="inp" name="priority" type="number" min={0} max={10000} defaultValue={initial?.priority ?? 100} /></label>
            <label className="fld">중복 그룹<input className="inp mono" name="stack_group" defaultValue={initial?.stack_group ?? ""} placeholder="WINTER" /></label>
          </div>
          <label className="fld">중복 정책<select className="inp" name="stack_policy" defaultValue={initial?.stack_policy ?? "ONE_PER_GROUP"}>{Object.entries(STACK_KO).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></label>
          <label className="fld">채널 자체 사은품과 겹칠 때<select className="inp" name="overlap" defaultValue={initial?.channel_overlap_policy ?? "SKIP_INTERNAL"}>
            <option value="SKIP_INTERNAL">내부 사은품 생략(기본, 중복 지급 금지)</option><option value="ALLOW_BOTH">함께 지급(명시적 허용)</option></select></label>
          <label className="fld">채널 행사 번호(같은 행사 판별)<input className="inp mono" name="ref" defaultValue={initial?.channel_campaign_ref ?? ""} /></label>
        </fieldset>
        <p className="ctx" style={{ margin: 0 }}>진행을 시작하면 조건·사은품은 바꿀 수 없고 종료일 연장·선착순 수량 증가만 됩니다. 사은품 원가는 출고 시 판촉비로 잡힙니다.</p>
      </form>
    </Drawer>
  );
}

function CampaignDrawer({ id, onClose, onChanged, onEdit }: { id: string; onClose: () => void; onChanged: () => void; onEdit: (c: Campaign) => void }) {
  const { can } = useSession();
  const { data, error, loading, reload } = useApi<Campaign>(`/gwp-campaigns/${id}`);
  const [msg, setMsg] = useState<{ kind: "ok" | "err"; text: string } | null>(null);
  const [confirm, setConfirm] = useState<string | null>(null);
  if (loading && !data) return <Drawer title="캠페인" onClose={onClose}><Loading /></Drawer>;
  if (error) return <Drawer title="캠페인" onClose={onClose}><ErrorState error={error} onRetry={reload} /></Drawer>;
  if (!data) return null;
  const c = data;
  const go = async (status: string) => {
    setConfirm(null);
    try {
      await api(`/gwp-campaigns/${id}`, { method: "PATCH", ifMatch: c.version, body: { status } });
      setMsg({ kind: "ok", text: "상태를 바꿨습니다." });
      reload();
      onChanged();
    } catch (e) {
      setMsg({ kind: "err", text: e instanceof ApiError ? e.message : "바꾸지 못했습니다." });
    }
  };
  const actions: Record<string, [string, string][]> = { DRAFT: [["ACTIVE", "진행 시작"], ["ENDED", "취소(종료)"]], ACTIVE: [["PAUSED", "일시중지"], ["ENDED", "종료"]],
    PAUSED: [["ACTIVE", "재개"], ["ENDED", "종료"]], ENDED: [] };
  return (
    <Drawer title={c.name} onClose={onClose} footer={can("catalog.manage") ? <>
      {c.status === "DRAFT" && <button className="btn" type="button" onClick={() => onEdit(c)}>수정</button>}
      {actions[c.status].map(([s, l]) => <button key={s} className={`btn ${s === "ACTIVE" ? "pri" : s === "ENDED" ? "dan" : ""}`} type="button" onClick={() => setConfirm(s)}>{l}</button>)}</> : undefined}>
      {msg && <div className={`note ${msg.kind === "ok" ? "n-info" : "n-err"}`} role={msg.kind === "err" ? "alert" : "status"}>{msg.text}</div>}
      <div className="kv"><span>상태</span><Chip kind={STATUS[c.status][0]}>{STATUS[c.status][1]}</Chip></div>
      <div className="kv"><span>사업자</span><span>{c.business_entity_name}</span></div>
      <div className="kv"><span>기간</span><span>{formatDateTime(c.starts_at)} ~ {formatDateTime(c.ends_at)}</span></div>
      <div className="kv"><span>대상 계정</span><span>{c.channel_account_ids.length ? `${c.channel_account_ids.length}개` : "사업자 전체"}</span></div>
      <div className="kv"><span>조건</span><span>{[c.min_order_amount ? `${formatWon(c.min_order_amount)}원 이상` : null, ...c.required_skus.map((s) => `${s.sku_code} ${s.quantity}개 이상`)].filter(Boolean).join(c.condition_mode === "ALL" ? " 그리고 " : " 또는 ") || "모든 주문"}</span></div>
      <div className="kv"><span>사은품</span><span>{c.rewards.map((g, i) => `${i ? "대체 " : ""}${g.sku_code}×${g.quantity}`).join(" → ")}</span></div>
      <div className="kv"><span>주문당 · 선착순</span><span>{c.max_per_order}회 · {c.total_limit ?? "제한 없음"}</span></div>
      <div className="kv"><span>중복</span><span>{c.stack_group ? `${c.stack_group} · ` : ""}{STACK_KO[c.stack_policy]}</span></div>
      <div className="kv"><span>채널 사은품과 겹칠 때</span><span>{c.channel_overlap_policy === "SKIP_INTERNAL" ? "내부 사은품 생략" : "함께 지급"}{c.channel_campaign_ref ? ` (행사 ${c.channel_campaign_ref})` : ""}</span></div>
      <div className="kv"><span>품절 시</span><span>{OOS_KO[c.out_of_stock_policy]}</span></div>
      {confirm && <ConfirmModal title={`캠페인을 '${actions[c.status].find(([s]) => s === confirm)?.[1]}' 할까요?`} danger={confirm === "ENDED"}
        confirmLabel="확인" impact={confirm === "ACTIVE" ? [["이후 변경", "조건·사은품 고정(종료일 연장·선착순 증가만)"], ["적용", "기간 안 주문부터"]]
          : confirm === "ENDED" ? [["되돌리기", "불가"], ["이미 지급", "그대로 유지"]] : [["적용", "새 주문부터 지급 멈춤"]]}
        onConfirm={() => go(confirm)} onClose={() => setConfirm(null)} />}
    </Drawer>
  );
}

function PreviewDrawer({ onClose }: { onClose: () => void }) {
  const { entities } = useSession();
  const [beSel, setBe] = useState("");
  const be = beSel || entities[0]?.id || "";
  const accounts = useApi<Account[]>(be ? `/channel-accounts${qs({ business_entity_id: be })}` : null);
  const [account, setAccount] = useState("");
  const [lines, setLines] = useState<{ sku: Sku; qty: number; amount: string }[]>([]);
  const [channelRef, setChannelRef] = useState("");
  const [result, setResult] = useState<PreviewResult | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const run = async () => {
    setErr(null);
    try {
      const r = await api<PreviewResult>("/gwp-campaigns/preview", { method: "POST", body: { business_entity_id: be, channel_account_id: account || accounts.data?.[0]?.id,
        lines: lines.map((l) => ({ sku_id: l.sku.id, quantity: l.qty, amount: l.amount.replace(/,/g, "") || "0" })),
        channel_gifts: channelRef ? [{ channel_campaign_ref: channelRef }] : [] } });
      setResult(r.data);
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : "판정하지 못했습니다.");
    }
  };
  return (
    <Drawer title="지급 미리보기" onClose={onClose} footer={<button className="btn pri" type="button" onClick={run}>판정</button>}>
      <p className="ctx" style={{ margin: 0 }}>가상의 주문으로 어떤 사은품이 붙는지 확인합니다. 실제 주문·재고는 바뀌지 않습니다.</p>
      {err && <div className="note n-err" role="alert">{err}</div>}
      <label className="fld">사업자<select className="inp" value={be} onChange={(e) => setBe(e.target.value)}>{entities.map((e) => <option key={e.id} value={e.id}>{e.display_name}</option>)}</select></label>
      <label className="fld">판매계정<select className="inp" value={account} onChange={(e) => setAccount(e.target.value)}>{(accounts.data ?? []).map((a) => <option key={a.id} value={a.id}>{a.display_name}</option>)}</select></label>
      {lines.map((l, i) => <div className="row-gap" key={l.sku.id}><span className="mono" style={{ flex: 1 }}>{l.sku.sku_code}</span>
        <input className="inp" type="number" min={1} aria-label={`${l.sku.sku_code} 주문 수량`} value={l.qty} style={{ width: 70 }} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, qty: Number(e.target.value) || 1 } : x)))} />
        <input className="inp" aria-label={`${l.sku.sku_code} 결제금액`} value={l.amount} style={{ width: 110 }} placeholder="금액(원)" onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, amount: e.target.value } : x)))} /></div>)}
      <SkuPicker label="주문 상품 추가" onPick={(s) => !lines.some((l) => l.sku.id === s.id) && setLines([...lines, { sku: s, qty: 1, amount: "" }])} />
      <label className="fld">채널이 이미 준 사은품의 행사 번호(있으면)<input className="inp mono" value={channelRef} onChange={(e) => setChannelRef(e.target.value)} /></label>
      {result && <section aria-label="판정 결과" style={{ display: "flex", flexDirection: "column", gap: 4 }}>
        <h3 style={{ margin: 0, fontSize: 14 }}>지급</h3>
        {result.awards.length === 0 ? <p className="ctx" style={{ margin: 0 }}>지급할 사은품이 없습니다.</p> : result.awards.map((a) => <div className="kv" key={a.campaign_name}><span>{a.campaign_name}</span><span>{a.quantity}개{a.substitute ? " (대체 사은품)" : ""}</span></div>)}
        <h3 style={{ margin: "6px 0 0", fontSize: 14 }}>지급 안 함</h3>
        {result.skipped.map((s) => <div className="kv" key={s.campaign_name}><span>{s.campaign_name}</span><span>{REASON_KO[s.reason] ?? s.reason}</span></div>)}
        {result.hold_order && <div className="note n-warn">사은품 재고가 없어 이 주문은 보류됩니다.</div>}
      </section>}
    </Drawer>
  );
}
