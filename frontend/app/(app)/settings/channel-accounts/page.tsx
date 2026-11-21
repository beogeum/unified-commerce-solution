"use client";
import { useMemo, useState } from "react";
import { DataTable } from "@/components/DataTable";
import { ExportButton } from "@/components/ExportButton";
import { StatusChip } from "@/components/Chip";
import { Empty, ErrorState, Loading } from "@/components/States";
import { qs } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

type Account = { id: string; business_entity_name: string; sales_channel_code: string; sales_channel_name: string; display_name: string;
  external_account_key: string; status: string; sales_unit_count: number; updated_at: string };
type Channel = { code: string; name_ko: string };

export default function ChannelAccountsPage() {
  const { can, selected } = useSession();
  const [channel, setChannel] = useState("");
  const [q, setQ] = useState("");
  const channels = useApi<Channel[]>("/sales-channels");
  const path = useMemo(() => `/channel-accounts${qs({ sales_channel_code: channel, q, business_entity_id: selected, limit: "100" })}`, [channel, q, selected]);
  const { data, error, loading, reload } = useApi<Account[]>(path);
  return (
    <>
      <div className="page-head">
        <div><h1>판매계정</h1><p>같은 채널이라도 사업자·계정마다 따로 등록합니다. 연동 지원 기능은 계정별로 확인된 것만 켜집니다.</p></div>
        {can("export.create") && <ExportButton exportType="CHANNEL_ACCOUNTS" businessEntityIds={selected} />}
      </div>
      <div className="row-gap" role="search">
        <label className="fld" style={{ minWidth: 240 }}>검색<input className="inp" value={q} onChange={(e) => setQ(e.target.value)} placeholder="계정 이름, 외부 계정키" /></label>
        <label className="fld">판매채널<select className="inp" value={channel} onChange={(e) => setChannel(e.target.value)}>
          <option value="">전체</option>{(channels.data ?? []).map((c) => <option key={c.code} value={c.code}>{c.name_ko}</option>)}</select></label>
      </div>
      {loading ? <Loading /> : error ? <ErrorState error={error} onRetry={reload} /> : (
        <DataTable caption="판매계정 목록" rows={data ?? []} rowKey={(r) => r.id}
          empty={<Empty title="조건에 맞는 판매계정이 없습니다." hint="사업자 필터나 판매채널을 바꿔 보세요." />}
          columns={[
            { key: "name", header: "판매계정", render: (r) => <><b>{r.display_name}</b><div className="ctx mono">{r.external_account_key}</div></> },
            { key: "be", header: "사업자", render: (r) => r.business_entity_name },
            { key: "ch", header: "판매채널", render: (r) => r.sales_channel_name ?? r.sales_channel_code },
            { key: "units", header: "판매단위", num: true, render: (r) => r.sales_unit_count },
            { key: "status", header: "상태", render: (r) => <StatusChip status={r.status} /> },
            { key: "updated", header: "수정", render: (r) => <span className="ctx">{formatDateTime(r.updated_at)}</span> },
          ]} />
      )}
    </>
  );
}
