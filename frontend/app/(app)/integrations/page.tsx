"use client";
import { useMemo, useState } from "react";
import { Chip, StatusChip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey, qs } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Connection = { id: string; display_name: string; connector_code: string; is_mock: boolean; target_type: string; status: string;
  last_success_at: string | null; last_error_code: string | null; credential_expires_at: string | null; has_active_credential: boolean };
type Capability = { feature_code: string; feature_name_ko: string; operation: string; support_level: string; account_verification: string;
  executable: boolean; blocked_reasons: string[] };

export default function IntegrationsPage() {
  const { selected } = useSession();
  const path = useMemo(() => `/integrations/connections${qs({ business_entity_id: selected, limit: "100" })}`, [selected]);
  const { data, error, loading, reload } = useApi<Connection[]>(path);
  const [detail, setDetail] = useState<Connection | null>(null);
  return (
    <>
      <div className="page-head"><div><h1>연동 관리</h1><p>실제 판매채널·3PL API 권한이 확인되기 전까지 모의 연동으로만 동작합니다. 확인되지 않은 기능은 실행되지 않습니다.</p></div></div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="연동 연결" rows={data ?? []} rowKey={(r) => r.id} onRowClick={setDetail}
          empty={<Empty title="등록된 연동이 없습니다." hint="판매계정이나 화주계정을 먼저 등록한 뒤 연동을 추가하세요." />}
          columns={[
            { key: "name", header: "연결", render: (r) => <><b>{r.display_name}</b><div className="ctx">{r.target_type === "CHANNEL_ACCOUNT" ? "판매계정" : "화주계정"}</div></> },
            { key: "conn", header: "방식", render: (r) => r.is_mock ? <Chip kind="info">모의 연동</Chip> : r.connector_code },
            { key: "status", header: "상태", render: (r) => <StatusChip status={r.status} /> },
            { key: "ok", header: "최근 성공", render: (r) => <span className="ctx">{formatDateTime(r.last_success_at)}</span> },
            { key: "exp", header: "인증정보 만료", render: (r) => <span className="ctx">{r.has_active_credential ? formatDateTime(r.credential_expires_at) : "없음"}</span> },
          ]} />
      )}
      {detail && <ConnectionDrawer conn={detail} onClose={() => setDetail(null)} onChanged={reload} />}
    </>
  );
}

function ConnectionDrawer({ conn, onClose, onChanged }: { conn: Connection; onClose: () => void; onChanged: () => void }) {
  const { can } = useSession();
  const caps = useApi<Capability[]>(`/integrations/capabilities?connection_id=${conn.id}`);
  const [msg, setMsg] = useState<string | null>(null);
  const check = async () => {
    try {
      await api(`/integrations/${conn.id}/sync-jobs`, { method: "POST", body: {}, idempotencyKey: newIdempotencyKey() });
      setMsg("상태 점검을 요청했습니다. 결과는 잠시 뒤 상태와 업무함에 반영됩니다.");
      onChanged();
    } catch (e) {
      setMsg(e instanceof ApiError ? e.message : "요청하지 못했습니다.");
    }
  };
  return (
    <Drawer title={conn.display_name} onClose={onClose}
      footer={can("integration.sync") ? <button className="btn" type="button" onClick={check}>상태 점검</button> : undefined}>
      {msg && <div className="note n-info" role="status">{msg}</div>}
      <div className="kv"><span>상태</span><StatusChip status={conn.status} /></div>
      <div className="kv"><span>최근 오류</span><span>{conn.last_error_code ?? "—"}</span></div>
      <h3 style={{ margin: "8px 0 0", fontSize: 14 }}>지원 기능</h3>
      {caps.loading ? <Loading /> : caps.error ? <ErrorState error={caps.error} onRetry={caps.reload} /> :
        (caps.data ?? []).length === 0 ? <Empty title="확인된 지원 기능이 없습니다." /> :
          (caps.data ?? []).map((c) => (
            <div className="kv" key={`${c.feature_code}-${c.operation}`}>
              <span>{c.feature_name_ko} · {c.operation}</span>
              <span>{c.executable ? <Chip kind="ok">실행 가능</Chip> : <Chip kind="mute">미확인</Chip>}</span>
            </div>))}
      <p className="ctx" style={{ margin: 0 }}>실행 조건: 커넥터 구현 + 공식 지원 확인 + 이 계정에서 검증 완료 + 연결 운영 중.</p>
    </Drawer>
  );
}
