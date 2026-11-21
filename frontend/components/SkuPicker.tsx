"use client";
import { useState } from "react";
import { qs } from "@/lib/api";
import { SKU_TYPE, Sku } from "@/lib/catalog";
import { useApi } from "@/lib/useApi";

export function SkuPicker({ label, typeFilter, onPick }: { label: string; typeFilter?: string; onPick: (s: Sku) => void }) {
  const [q, setQ] = useState("");
  const res = useApi<Sku[]>(q.trim().length >= 2 ? `/skus${qs({ q, status: "ACTIVE", sku_type: typeFilter, limit: "8" })}` : null);
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
      <label className="fld">{label}<input className="inp" value={q} onChange={(e) => setQ(e.target.value)} placeholder="SKU 코드·상품명 2자 이상" /></label>
      {(res.data ?? []).map((s) => <button key={s.id} className="btn" type="button" onClick={() => { onPick(s); setQ(""); }} style={{ justifyContent: "flex-start" }}>
        <span className="mono">{s.sku_code}</span>&nbsp;<span className="ctx">{SKU_TYPE[s.sku_type]} · {s.product_name}</span></button>)}
    </div>
  );
}
