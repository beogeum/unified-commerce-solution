"use client";
import { useEffect, useState } from "react";
import { DataTable } from "@/components/DataTable";
import { Drawer } from "@/components/Overlay";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, qs } from "@/lib/api";
import { BASIS_KO, CATEGORY_KO, Entry, won } from "@/lib/finance";

export function EntriesDrawer({ title, filter, from, to, onClose }: { title: string; filter: Record<string, string | string[] | undefined>; from?: string; to?: string;
  onClose: () => void }) {
  const [rows, setRows] = useState<Entry[] | null>(null);
  const [err, setErr] = useState<ApiError | null>(null);
  useEffect(() => {
    api<Entry[]>(`/finance/entries${qs({ ...filter, from, to })}`).then((r) => setRows(r.data)).catch((e) => setErr(e instanceof ApiError ? e : new ApiError(0, "UNKNOWN", "불러오지 못했습니다.")));
  }, [filter, from, to]);
  return (
    <Drawer title={title} onClose={onClose}>
      {err ? <ErrorState error={err} /> : rows === null ? <Loading /> : <DataTable caption="원장" rows={rows} rowKey={(e) => e.id} empty={<Empty title="원장이 없습니다." />} columns={[
        { key: "d", header: "귀속일", render: (e) => <span className="ctx">{e.period_date}</span> },
        { key: "c", header: "항목", render: (e) => <>{CATEGORY_KO[e.category] ?? e.category} <span className="ctx">{BASIS_KO[e.basis]}{e.reverses_id ? " · 상계" : ""}</span></> },
        { key: "r", header: "근거", render: (e) => <span className="ctx mono">{e.order_no ?? ""} {e.sku_code ?? ""} {e.source_ref ?? ""}</span> },
        { key: "a", header: "공급가", num: true, render: (e) => won(e.supply_amount) },
        { key: "v", header: "부가세", num: true, render: (e) => won(e.vat_amount) },
        { key: "m", header: "메모", render: (e) => <span className="ctx">{e.memo ?? ""}</span> },
      ]} />}
    </Drawer>
  );
}
