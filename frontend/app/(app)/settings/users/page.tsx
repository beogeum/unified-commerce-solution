"use client";
import { useState } from "react";
import { Chip, StatusChip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { ConfirmModal, Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Member = { user_id: string; member_id: string; email: string; display_name: string; status: string; role_codes: string[]; created_at: string };
type ScopeEntry = { target_id: string; effect: "ALLOW" | "DENY" };
type Scope = { dimension: string; access_kind: string; mode: "ALL" | "SELECTED"; auto_include_new: boolean | null; entries: ScopeEntry[] };
type Policy = { access_policy_version: number; permission_overrides: unknown[]; scopes: Scope[] };
const KINDS: [string, string][] = [["READ", "조회"], ["WRITE", "수정"], ["EXPORT", "내보내기"]];

export default function UsersPage() {
  const { can } = useSession();
  const { data, error, loading, reload } = useApi<Member[]>("/users?limit=100");
  const [detail, setDetail] = useState<Member | null>(null);
  return (
    <>
      <div className="page-head"><div><h1>사용자·권한</h1><p>역할은 권한 묶음이고, 사업자 접근 범위는 사용자마다 따로 정합니다. 범위 밖 데이터는 서버가 거부합니다.</p></div></div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="사용자 목록" rows={data ?? []} rowKey={(r) => r.user_id} onRowClick={setDetail}
          empty={<Empty title="사용자가 없습니다." />}
          columns={[
            { key: "name", header: "사용자", render: (r) => <><b>{r.display_name}</b><div className="ctx">{r.email}</div></> },
            { key: "roles", header: "역할", render: (r) => r.role_codes.join(", ") || "—" },
            { key: "status", header: "상태", render: (r) => <StatusChip status={r.status} /> },
            { key: "created", header: "등록", render: (r) => <span className="ctx">{formatDateTime(r.created_at)}</span> },
          ]} />
      )}
      {detail && <MemberDrawer member={detail} canManage={can("access.manage")} onClose={() => setDetail(null)} />}
    </>
  );
}

function MemberDrawer({ member, canManage, onClose }: { member: Member; canManage: boolean; onClose: () => void }) {
  const { entities, me } = useSession();
  const policy = useApi<Policy>(`/users/${member.user_id}/access-policy`);
  const [draft, setDraft] = useState<Record<string, Scope> | null>(null);
  const [msg, setMsg] = useState<{ kind: "ok" | "err"; text: string } | null>(null);
  const [confirmReset, setConfirmReset] = useState(false);
  const [busy, setBusy] = useState(false);

  const scopesByKind = (p: Policy): Record<string, Scope> => Object.fromEntries(KINDS.map(([k]) => [k,
    p.scopes.find((s) => s.dimension === "BUSINESS_ENTITY" && s.access_kind === k) ??
      { dimension: "BUSINESS_ENTITY", access_kind: k, mode: "ALL", auto_include_new: null, entries: [] }]));
  const current = draft ?? (policy.data ? scopesByKind(policy.data) : null);
  const nameOf = (id: string) => entities.find((e) => e.id === id)?.display_name ?? "(범위 밖 사업자)";

  const toggle = (kind: string, id: string) => {
    if (!current) return;
    const s = current[kind];
    const has = s.entries.some((e) => e.target_id === id && e.effect === "ALLOW");
    const entries = has ? s.entries.filter((e) => e.target_id !== id) : [...s.entries.filter((e) => e.target_id !== id), { target_id: id, effect: "ALLOW" as const }];
    setDraft({ ...current, [kind]: { ...s, entries } });
  };
  const setMode = (kind: string, mode: "ALL" | "SELECTED") => current && setDraft({ ...current, [kind]: { ...current[kind], mode, auto_include_new: mode === "SELECTED" ? false : null } });

  const save = async () => {
    if (!policy.data || !current) return;
    setBusy(true);
    setMsg(null);
    try {
      const others = policy.data.scopes.filter((s) => s.dimension !== "BUSINESS_ENTITY");
      await api(`/users/${member.user_id}/access-policy`, { method: "PUT", ifMatch: policy.data.access_policy_version,
        body: { permission_overrides: policy.data.permission_overrides, scopes: [...others, ...Object.values(current)] } });
      setDraft(null);
      policy.reload();
      setMsg({ kind: "ok", text: "접근 범위를 저장했습니다. 다음 요청부터 바로 적용됩니다." });
    } catch (e) {
      setMsg({ kind: "err", text: e instanceof ApiError ? e.message : "저장하지 못했습니다." });
    } finally {
      setBusy(false);
    }
  };

  const resetMfa = async () => {
    setBusy(true);
    try {
      await api(`/users/${member.user_id}/mfa-reset`, { method: "POST", body: {} });
      setMsg({ kind: "ok", text: "2단계 인증을 초기화했습니다. 이 사용자는 다시 로그인해 새로 등록해야 합니다." });
    } catch (e) {
      setMsg({ kind: "err", text: e instanceof ApiError ? e.message : "초기화하지 못했습니다." });
    } finally {
      setBusy(false);
      setConfirmReset(false);
    }
  };

  return (
    <Drawer title={member.display_name} onClose={onClose} footer={canManage ? <>
      {member.user_id !== me.user_id && <button className="btn dan" type="button" onClick={() => setConfirmReset(true)}>2단계 인증 초기화</button>}
      <button className="btn pri" type="button" onClick={save} disabled={!draft || busy}>접근 범위 저장</button></> : undefined}>
      {msg && <div className={`note ${msg.kind === "ok" ? "n-info" : "n-err"}`} role={msg.kind === "err" ? "alert" : "status"}>{msg.text}</div>}
      <div className="kv"><span>이메일</span><span>{member.email}</span></div>
      <div className="kv"><span>역할</span><span>{member.role_codes.join(", ") || "—"}</span></div>
      <div className="kv"><span>상태</span><StatusChip status={member.status} /></div>
      <h3 style={{ margin: "8px 0 0", fontSize: 14 }}>사업자 접근 범위</h3>
      {policy.loading ? <Loading /> : policy.error ? <ErrorState error={policy.error} onRetry={policy.reload} /> : current && KINDS.map(([k, label]) => (
        <fieldset key={k} className="card pad" style={{ display: "flex", flexDirection: "column", gap: 6 }} disabled={!canManage}>
          <legend className="ctx">{label}</legend>
          <div className="row-gap">
            <label><input type="radio" name={`mode-${k}`} checked={current[k].mode === "ALL"} onChange={() => setMode(k, "ALL")} /> 전체 사업자(신규 포함)</label>
            <label><input type="radio" name={`mode-${k}`} checked={current[k].mode === "SELECTED"} onChange={() => setMode(k, "SELECTED")} /> 선택한 사업자만</label>
          </div>
          {current[k].mode === "SELECTED" && entities.map((e) => (
            <label key={e.id}><input type="checkbox" checked={current[k].entries.some((x) => x.target_id === e.id && x.effect === "ALLOW")}
              onChange={() => toggle(k, e.id)} /> {e.display_name}</label>))}
          {current[k].entries.filter((x) => x.effect === "DENY").map((x) => <Chip key={x.target_id} kind="err">거부: {nameOf(x.target_id)}</Chip>)}
        </fieldset>
      ))}
      <p className="ctx" style={{ margin: 0 }}>수정·내보내기 범위는 조회 범위 안에서만 의미가 있습니다. 명시적 거부가 허용보다 우선합니다.</p>
      {confirmReset && <ConfirmModal title={`${member.display_name}님의 2단계 인증을 초기화할까요?`} danger busy={busy} confirmLabel="초기화"
        impact={[["로그인 세션", "모든 세션 즉시 종료"], ["다음 로그인", "OTP 앱 새로 등록 필요"], ["기록", "변경이력에 남음"]]}
        onConfirm={resetMfa} onClose={() => setConfirmReset(false)} />}
    </Drawer>
  );
}
